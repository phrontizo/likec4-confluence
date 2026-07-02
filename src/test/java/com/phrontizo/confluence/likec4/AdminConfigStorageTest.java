package com.phrontizo.confluence.likec4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.websudo.WebSudoManager;
import com.phrontizo.likec4.source.FileTokenKeyStore;
import com.phrontizo.likec4.source.TokenCipher;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Storage-level tests for {@link AdminConfig} against an in-memory {@code PluginSettings}: the
 * corrupt-refTtl fallback (read during {@code SourceServiceProvider} construction, so it must never
 * throw) and the one-time legacy-key cipher migration (key moves off PluginSettings to the home file
 * and already-encrypted tokens stay decryptable).
 */
class AdminConfigStorageTest {
  private static final String NS = "com.phrontizo.confluence.likec4:";

  @AfterEach
  void clearHolder() throws Exception {
    Field f = AdminConfig.class.getDeclaredField("INSTANCE");
    f.setAccessible(true);
    f.set(null, null);
  }

  /** An AdminConfig backed by the given mutable map (as PluginSettings) and home directory. */
  private static AdminConfig configWith(Map<String, Object> store, Path home) {
    PluginSettings settings = mock(PluginSettings.class);
    when(settings.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
    when(settings.put(anyString(), any())).thenAnswer(i -> store.put(i.getArgument(0), i.getArgument(1)));
    when(settings.remove(anyString())).thenAnswer(i -> store.remove(i.<String>getArgument(0)));
    PluginSettingsFactory factory = mock(PluginSettingsFactory.class);
    when(factory.createGlobalSettings()).thenReturn(settings);
    ApplicationProperties appProps = mock(ApplicationProperties.class);
    when(appProps.getLocalHomeDirectory()).thenReturn(Optional.of(home));
    return new AdminConfig(factory, mock(UserManager.class), appProps, mock(WebSudoManager.class));
  }

  @Test
  void getRefTtlMillis_falls_back_to_the_default_on_unset_or_corrupt_values(@TempDir Path home) {
    Map<String, Object> store = new HashMap<>();
    AdminConfig cfg = configWith(store, home);
    assertEquals(60_000L, cfg.getRefTtlMillis(), "unset -> default");
    store.put(NS + "refTtlMillis", "not-a-number");
    assertEquals(60_000L, cfg.getRefTtlMillis(), "corrupt -> default (must not throw during plugin init)");
    store.put(NS + "refTtlMillis", "   ");
    assertEquals(60_000L, cfg.getRefTtlMillis(), "blank -> default");
    store.put(NS + "refTtlMillis", "120000");
    assertEquals(120_000L, cfg.getRefTtlMillis(), "valid -> parsed");
  }

  @Test
  void getRefTtlMillis_clamps_out_of_range_values_so_they_cannot_overflow_or_disable_the_cache(@TempDir Path home) {
    Map<String, Object> store = new HashMap<>();
    AdminConfig cfg = configWith(store, home);
    // A near-MAX_VALUE stored value would overflow `now + ttlMillis` to a NEGATIVE expiry inside
    // RefShaCache, making every entry instantly stale and silently defeating the ref cache. Clamp it.
    store.put(NS + "refTtlMillis", String.valueOf(Long.MAX_VALUE));
    assertEquals(AdminConfig.MAX_REF_TTL_MILLIS, cfg.getRefTtlMillis(),
        "an absurdly large value is clamped to the 24h ceiling (no overflow)");
    // A negative value likewise makes every entry instantly stale; clamp up to 0.
    store.put(NS + "refTtlMillis", "-5");
    assertEquals(0L, cfg.getRefTtlMillis(), "a negative value is clamped to 0");
    // An in-range value passes through unchanged.
    store.put(NS + "refTtlMillis", "300000");
    assertEquals(300_000L, cfg.getRefTtlMillis(), "an in-range value is returned as-is");
  }

  @Test
  void cipher_migrates_a_legacy_plugin_settings_key_to_the_home_file_and_keeps_old_ciphertext_decryptable(
      @TempDir Path home) {
    Map<String, Object> store = new HashMap<>();
    byte[] legacyKey = TokenCipher.newKey();
    // A token encrypted under the legacy key, as if stored before the key moved off PluginSettings.
    String ciphertext = new TokenCipher(legacyKey).encrypt("s3cr3t");
    store.put(NS + "cipherKey", Base64.getEncoder().encodeToString(legacyKey));
    store.put(NS + "token", ciphertext);

    AdminConfig cfg = configWith(store, home);
    // getToken() -> cipher() -> buildCipher() performs the one-time migration, then decrypts.
    assertEquals("s3cr3t", cfg.getToken(), "the already-encrypted token must stay decryptable post-migration");
    assertFalse(store.containsKey(NS + "cipherKey"),
        "the legacy key must be removed from PluginSettings so it no longer co-resides with the ciphertext");
    assertTrue(Files.exists(home.resolve(FileTokenKeyStore.KEY_FILENAME)),
        "the migrated key must be written to the home filesystem");

    // A fresh config over the same home (legacy key now gone) loads the migrated key from the file, so
    // the existing ciphertext remains decryptable across a restart.
    AdminConfig fresh = configWith(store, home);
    assertEquals("s3cr3t", fresh.getToken());
  }

  @Test
  void a_lingering_legacy_key_is_removed_even_when_a_home_key_file_already_exists(@TempDir Path home) {
    // Steady state after the one-time migration is: key on the home file, NO cipherKey in PluginSettings.
    // But a backup/restore (or a partial migration) can reintroduce a legacy cipherKey ALONGSIDE an
    // already-present key file. The exists()-true branch loads the FILE key (authoritative) but historically
    // never cleaned up the stale DB copy, so the key silently co-resided with the ciphertext again — the
    // very key-at-rest separation this class advertises. buildCipher must opportunistically remove the
    // legacy key on the exists()-true path too, so it never lingers regardless of how it got there.
    Map<String, Object> store = new HashMap<>();
    byte[] fileKey = TokenCipher.newKey();
    new FileTokenKeyStore(home).storeKey(fileKey);              // an already-migrated key file exists
    String ciphertext = new TokenCipher(fileKey).encrypt("s3cr3t");
    store.put(NS + "token", ciphertext);
    // A DIFFERENT, stale legacy key re-appears in PluginSettings (e.g. a restored older DB row).
    store.put(NS + "cipherKey", Base64.getEncoder().encodeToString(TokenCipher.newKey()));

    AdminConfig cfg = configWith(store, home);
    // The FILE key is authoritative, so the token still decrypts (the stale DB key is a red herring)...
    assertEquals("s3cr3t", cfg.getToken(), "the home-file key must win; the stale legacy key is irrelevant");
    // ...and the lingering legacy key must have been swept out so it no longer co-resides with the ciphertext.
    assertFalse(store.containsKey(NS + "cipherKey"),
        "a legacy key lingering next to an existing key file must be removed, not left to co-reside");
  }

  @Test
  void a_corrupt_base64_legacy_key_is_a_config_fault_not_an_outage_and_is_not_destroyed(@TempDir Path home) {
    Map<String, Object> store = new HashMap<>();
    // A legacy value that is not valid base64 at all (e.g. DB corruption / a botched settings migration).
    store.put(NS + "cipherKey", "!!! not base64 !!!");
    store.put(NS + "token", "irrelevant-ciphertext");
    AdminConfig cfg = configWith(store, home);

    // Must surface as DecryptException (→ REST 503 "repository misconfigured"), NOT a bare
    // IllegalArgumentException (→ misleading 400) or a generic exception (→ misleading 502 outage).
    assertThrows(TokenCipher.DecryptException.class, cfg::getToken);
    // And the migration must NOT have destroyed recoverability: the legacy key stays in PluginSettings
    // and no (corrupt) key file was written, so an operator can still fix the stored value.
    assertTrue(store.containsKey(NS + "cipherKey"),
        "a corrupt legacy key must not be removed from PluginSettings");
    assertFalse(Files.exists(home.resolve(FileTokenKeyStore.KEY_FILENAME)),
        "a corrupt legacy key must not leave a key file on disk");
  }

  @Test
  void a_missing_home_directory_is_a_config_fault_not_an_outage() throws Exception {
    // If neither the local nor shared Confluence home is available, the token key file cannot be located
    // — a LOCAL config fault, not an upstream GitLab outage. getToken() must surface it as a
    // DecryptException (→ REST 503 "repository misconfigured", pointing the operator at the JVM/home
    // config) rather than a bare IllegalStateException that the REST catch mislabels as 502 "cannot
    // reach repository". Mirrors the legacy-key config-fault handling above.
    Map<String, Object> store = new HashMap<>();
    store.put(NS + "token", "irrelevant-ciphertext");
    PluginSettings settings = mock(PluginSettings.class);
    when(settings.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
    PluginSettingsFactory factory = mock(PluginSettingsFactory.class);
    when(factory.createGlobalSettings()).thenReturn(settings);
    ApplicationProperties appProps = mock(ApplicationProperties.class);
    when(appProps.getLocalHomeDirectory()).thenReturn(Optional.empty());
    when(appProps.getSharedHomeDirectory()).thenReturn(Optional.empty());
    AdminConfig cfg = new AdminConfig(factory, mock(UserManager.class), appProps, mock(WebSudoManager.class));
    assertThrows(TokenCipher.DecryptException.class, cfg::getToken);
  }

  @Test
  void a_wrong_length_legacy_key_is_a_config_fault_not_an_outage_and_is_not_destroyed(@TempDir Path home) {
    Map<String, Object> store = new HashMap<>();
    // Valid base64, but not a 128/192/256-bit AES key (10 bytes) — a truncated/foreign legacy value.
    store.put(NS + "cipherKey", Base64.getEncoder().encodeToString(new byte[10]));
    store.put(NS + "token", "irrelevant-ciphertext");
    AdminConfig cfg = configWith(store, home);

    assertThrows(TokenCipher.DecryptException.class, cfg::getToken);
    assertTrue(store.containsKey(NS + "cipherKey"),
        "a wrong-length legacy key must not be removed from PluginSettings");
    assertFalse(Files.exists(home.resolve(FileTokenKeyStore.KEY_FILENAME)),
        "a wrong-length legacy key must not leave a key file on disk");
  }

  @Test
  void a_truncated_key_file_on_disk_is_a_config_fault_not_an_outage(@TempDir Path home) throws Exception {
    // A key FILE that exists but is not a valid AES key length (10 bytes) — e.g. a truncated file from a
    // botched backup/restore, or a foreign file this class did not write. FileTokenKeyStore.validatedKey
    // rejects it with a plain IllegalStateException; without buildCipher re-classifying that as a config
    // fault, getToken() (which decrypts LAZILY at request time) falls through the REST layer's generic
    // catch to a misleading 502 "cannot reach repository" instead of 503 "repository misconfigured". This
    // is the on-disk sibling of the legacy-key / missing-home config-fault cases above.
    Map<String, Object> store = new HashMap<>();
    store.put(NS + "token", "irrelevant-ciphertext");
    Files.write(home.resolve(FileTokenKeyStore.KEY_FILENAME), new byte[10]);
    AdminConfig cfg = configWith(store, home);
    assertThrows(TokenCipher.DecryptException.class, cfg::getToken);
  }

  @Test
  void set_then_get_token_round_trips_through_a_freshly_generated_key(@TempDir Path home) {
    Map<String, Object> store = new HashMap<>();
    AdminConfig cfg = configWith(store, home);
    // No legacy key present: cipher() generates a fresh key straight into the home file. The ordinary
    // setToken -> getToken round-trip (the plugin's core secret-storage guarantee) was otherwise only
    // ever exercised via a pre-seeded LEGACY key — never the fresh-key path taken on a clean install.
    assertFalse(cfg.hasToken(), "no token stored initially");
    cfg.setToken("s3cr3t");
    assertTrue(cfg.hasToken(), "hasToken() sees the stored ciphertext without decrypting it");
    assertEquals("s3cr3t", cfg.getToken(), "the token decrypts back to the original plaintext");
    assertNotEquals("s3cr3t", store.get(NS + "token"),
        "the token must be stored ENCRYPTED, never in cleartext in PluginSettings");
    assertTrue(Files.exists(home.resolve(FileTokenKeyStore.KEY_FILENAME)),
        "the fresh key is written to the home filesystem, separate from the ciphertext");
    assertFalse(store.containsKey(NS + "cipherKey"),
        "the fresh-key path never writes a key into PluginSettings");

    // A fresh config over the same home + store round-trips too (key loaded from the file, ciphertext
    // from the store) — proving the token survives a plugin/JVM restart.
    AdminConfig restarted = configWith(store, home);
    assertEquals("s3cr3t", restarted.getToken());
  }

  @Test
  void clear_token_removes_the_stored_ciphertext_so_hasToken_reports_false(@TempDir Path home) {
    Map<String, Object> store = new HashMap<>();
    AdminConfig cfg = configWith(store, home);
    cfg.setToken("s3cr3t");
    assertTrue(cfg.hasToken(), "a token is stored");
    // An explicit REVOKE removes the ciphertext entirely (not a blank-token no-op). Crucially clearToken
    // does NO crypto — a plain PluginSettings remove — so an operator can revoke a compromised token even
    // when the home key file is gone. After it, hasToken() is false and getToken() returns null, so the
    // /source gate treats the repository as unconfigured.
    cfg.clearToken();
    assertFalse(cfg.hasToken(), "clearToken must remove the stored ciphertext");
    assertFalse(store.containsKey(NS + "token"), "the token key must be removed from PluginSettings");
    assertNull(cfg.getToken(), "getToken() returns null after a revoke");
  }

  @Test
  void set_base_url_validates_on_write_through_the_real_BaseUrlValidator(@TempDir Path home) {
    Map<String, Object> store = new HashMap<>();
    AdminConfig cfg = configWith(store, home);
    // setBaseUrl runs the value through the REAL BaseUrlValidator (validate-on-write), so a bad/insecure
    // URL is rejected at the admin boundary rather than shipping the token cleartext. Only the resource
    // tests exercise setBaseUrl (and they mock AdminConfig), so this real storage-level wiring was untested.
    assertThrows(IllegalArgumentException.class, () -> cfg.setBaseUrl("http://gitlab.example.com"),
        "plain http to a public FQDN must be rejected (it would ship the token in cleartext)");
    assertFalse(store.containsKey(NS + "baseUrl"), "a rejected base URL must not be persisted");
    // A valid URL is normalised (trailing slash stripped) on write and read back unchanged.
    cfg.setBaseUrl("https://gitlab.example.com/");
    assertEquals("https://gitlab.example.com", store.get(NS + "baseUrl"));
    assertEquals("https://gitlab.example.com", cfg.getBaseUrl());
  }

  @Test
  void set_allowlist_validates_each_entry_on_write_and_persists_the_normalised_form(@TempDir Path home) {
    Map<String, Object> store = new HashMap<>();
    AdminConfig cfg = configWith(store, home);
    // setAllowlist validates each entry (validate-on-write, like setBaseUrl): a malformed entry that could
    // never match a real project is rejected at the admin boundary rather than silently stored as inert
    // dead weight. Only the resource tests exercise setAllowlist (against a mocked AdminConfig), so this
    // real storage-level validation wiring was untested.
    assertThrows(IllegalArgumentException.class,
        () -> cfg.setAllowlist(java.util.List.of("platform", "../secret")),
        "a traversal entry must be rejected");
    assertFalse(store.containsKey(NS + "allowlist"), "a rejected allowlist must not be persisted");
    // Valid entries are normalised (surrounding slashes/whitespace folded) and read back cleanly; blanks drop.
    cfg.setAllowlist(java.util.Arrays.asList(" /platform/ ", "", "grp/sub", null));
    assertEquals("platform,grp/sub", store.get(NS + "allowlist"));
    assertEquals(java.util.List.of("platform", "grp/sub"), cfg.getAllowlist());
  }
}
