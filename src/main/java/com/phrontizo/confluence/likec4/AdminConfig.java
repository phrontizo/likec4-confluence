package com.phrontizo.confluence.likec4;

import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.websudo.WebSudoManager;
import com.phrontizo.likec4.source.BaseUrlValidator;
import com.phrontizo.likec4.source.FileTokenKeyStore;
import com.phrontizo.likec4.source.InputValidation;
import com.phrontizo.likec4.source.TokenCipher;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import jakarta.inject.Inject;
import org.springframework.beans.factory.DisposableBean;

/**
 * Admin-managed config: GitLab base URL, AES-encrypted service token, allowlist, TTLs.
 *
 * <p>Declared as a {@code <component>} in {@code atlassian-plugin.xml} so that Confluence's
 * plugin Spring context creates this bean eagerly at plugin enable time. SAL services are
 * constructor-injected (available via the matching {@code <component-import>} declarations).
 * The static {@link #INSTANCE} field is set in the constructor so plain-Jersey REST resources
 * can call {@link #getInstance()} without needing Spring injection themselves.
 */
public class AdminConfig implements DisposableBean {
  private static final System.Logger LOG = System.getLogger(AdminConfig.class.getName());
  private static final String NS = "com.phrontizo.confluence.likec4:";
  /**
   * Legacy PluginSettings key that USED to hold the raw base64 AES key, right next to the
   * ciphertext. It is migrated to the home-filesystem key file on first {@link #cipher()} call and
   * then removed (see {@link #cipher()}). Kept as a constant only so migration can find + delete it.
   */
  private static final String LEGACY_CIPHER_KEY = NS + "cipherKey";

  private static volatile AdminConfig INSTANCE;

  private final PluginSettingsFactory settingsFactory;
  private final UserManager userManager;
  private final ApplicationProperties applicationProperties;
  private final WebSudoManager webSudoManager;
  /** Memoised cipher: the key file is read/bootstrapped exactly once (under {@code this}) instead of
   *  on every getToken/setToken — which also makes FileTokenKeyStore's per-instance lock effective. */
  private volatile TokenCipher cipher;

  @Inject
  public AdminConfig(PluginSettingsFactory settingsFactory, UserManager userManager,
                     ApplicationProperties applicationProperties, WebSudoManager webSudoManager) {
    this.settingsFactory = settingsFactory;
    this.userManager = userManager;
    this.applicationProperties = applicationProperties;
    this.webSudoManager = webSudoManager;
    INSTANCE = this;
  }

  /** Returns the singleton set by the plugin Spring context at enable time. */
  public static AdminConfig getInstance() {
    return INSTANCE;
  }

  /** Spring lifecycle: clear the static holder on plugin disable so a disposed config doesn't linger
   *  (mirrors {@link SourceServiceProvider#destroy()}; guarded so a newer instance is never nulled). */
  @Override
  public void destroy() {
    if (INSTANCE == this) INSTANCE = null;
  }

  /** Returns the SAL {@link UserManager} for caller authentication checks. */
  public UserManager getUserManager() { return userManager; }

  /** Returns the SAL {@link WebSudoManager} for Secure-Administrator-Session enforcement. */
  public WebSudoManager getWebSudoManager() { return webSudoManager; }

  private PluginSettings settings() { return settingsFactory.createGlobalSettings(); }

  public String getBaseUrl() { return (String) settings().get(NS + "baseUrl"); }
  /** Validates + normalizes before persisting (no embedded credentials; https required for a public
   *  host, but plain http is permitted for internal/private hosts — see {@link BaseUrlValidator}), so a
   *  bad/insecure base URL is rejected at the admin boundary rather than shipping the token cleartext. */
  public void setBaseUrl(String v) { settings().put(NS + "baseUrl", BaseUrlValidator.validate(v)); }

  public List<String> getAllowlist() { return parseAllowlist((String) settings().get(NS + "allowlist")); }
  /** Validates + normalises each entry before persisting (see {@link #validatedAllowlist}), so a
   *  malformed entry that could never match a real project is rejected at the admin boundary rather than
   *  silently stored as inert dead weight — mirroring {@link #setBaseUrl}'s validate-on-write. */
  public void setAllowlist(List<String> entries) { settings().put(NS + "allowlist", formatAllowlist(validatedAllowlist(entries))); }

  /**
   * Trim, drop blanks, and VALIDATE each remaining entry as a GitLab group/project PREFIX path,
   * returning the normalised (surrounding-slash-stripped) entries to persist. A malformed entry throws
   * {@link IllegalArgumentException} (which {@link AdminConfigResource} maps to a 400). Idempotent, so
   * the resource can pre-validate with it BEFORE any persist to keep the no-half-apply invariant, and
   * {@link #setAllowlist} then re-runs it as defence-in-depth. Blanks are the caller's to drop (a stray
   * {@code ","} must not become an "entry is required" error), so they are filtered before validation.
   */
  static List<String> validatedAllowlist(List<String> entries) {
    return entries.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty())
        .map(AdminConfig::validatedEntry).toList();
  }

  /** Validate one entry, re-throwing any rejection with a FIXED, value-free message. The admin resource
   *  echoes {@code e.getMessage()} to the client, and (mirroring the base-URL no-echo invariant pinned in
   *  {@code AdminConfigResourceTest}) the caller-supplied value must not be reflected back — whereas
   *  {@link InputValidation#sanitizeAllowlistEntry}'s own message embeds the (neutralised) value. */
  private static String validatedEntry(String entry) {
    try {
      return InputValidation.sanitizeAllowlistEntry(entry);
    } catch (IllegalArgumentException bad) {
      throw new IllegalArgumentException(
          "an allowlist entry is not a valid group/project path (letters, digits, '.', '_', '-', '/')");
    }
  }

  /** Parse the stored comma-separated allowlist: trim each entry and DROP blanks, so a stray
   *  {@code ","} / {@code " , "} never yields an empty entry server-side (the admin page JS filters
   *  blanks too, but {@code setAllowlist} via REST must not depend on the client to do it). Returns an
   *  immutable list — all callers only read it. */
  static List<String> parseAllowlist(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  /** Serialise an allowlist back to the stored form, trimming + dropping blanks symmetrically with
   *  {@link #parseAllowlist} so a round-trip is stable and no empty entry is ever persisted. */
  static String formatAllowlist(List<String> entries) {
    return entries.stream().filter(Objects::nonNull).map(String::trim)
        .filter(s -> !s.isEmpty()).collect(Collectors.joining(","));
  }

  /** Upper bound (24h) on the ref→sha cache TTL. Clamps a corrupt/legacy stored value so an absurdly
   *  large number can't overflow {@code now + ttlMillis} to a NEGATIVE expiry inside
   *  {@link com.phrontizo.likec4.source.cache.RefShaCache} (which would make every entry instantly
   *  stale and silently defeat the ref cache). There is no
   *  admin-UI field for the TTL, so in practice this is always the default. */
  static final long MAX_REF_TTL_MILLIS = 24L * 60 * 60 * 1000;

  public long getRefTtlMillis() {
    String raw = (String) settings().get(NS + "refTtlMillis");
    if (raw == null || raw.isBlank()) return 60_000L;
    long parsed;
    try {
      parsed = Long.parseLong(raw.trim());
    } catch (NumberFormatException e) {
      // A corrupt/legacy PluginSettings value must not blow up plugin initialisation (this is read in
      // SourceServiceProvider's constructor) — fall back to the default. Log at DEBUG so the silent
      // mask leaves an operator-diagnosable trace without spamming a corrupt value on every read.
      LOG.log(System.Logger.Level.DEBUG,
          () -> "corrupt refTtlMillis '" + raw + "'; using default 60000ms");
      return 60_000L;
    }
    // Clamp into [0, MAX_REF_TTL_MILLIS]: a negative value (or a near-MAX_VALUE one that would overflow
    // RefShaCache's expiry arithmetic) both silently defeat the cache. Bound it to a sane range instead.
    long clamped = Math.max(0L, Math.min(parsed, MAX_REF_TTL_MILLIS));
    if (clamped != parsed) {
      LOG.log(System.Logger.Level.DEBUG,
          () -> "refTtlMillis '" + raw + "' out of range; clamped to " + clamped + "ms");
    }
    return clamped;
  }

  /**
   * Returns the AES-256-GCM cipher whose KEY lives on the Confluence-home filesystem (0600),
   * deliberately SEPARATE from the ciphertext that this class stores in PluginSettings (the DB).
   *
   * <p><b>Threat model:</b> previously the AES key was written into PluginSettings right next to the
   * encrypted token, so a single DB/PluginSettings dump yielded both key and ciphertext → trivially
   * decryptable. The key now lives at {@code <confluence-home>/likec4-token.key} with owner-only
   * permissions (see {@link FileTokenKeyStore}); the ciphertext stays in PluginSettings. A DB dump
   * ALONE therefore no longer decrypts tokens — read access to the Confluence home filesystem is
   * additionally required. Residual risk (honest): an attacker with BOTH the DB and the home
   * filesystem can still decrypt; a KMS/HSM would raise the bar further and is out of scope for v1.
   *
   * <p><b>Migration:</b> if a legacy key is still present in PluginSettings (from before this
   * change), it is read once, written to the home key file, and removed from PluginSettings — so
   * already-encrypted tokens stay decryptable under the same key. Otherwise a fresh key is generated
   * straight into the file on first use. As defence-in-depth, if a legacy key ever reappears in
   * PluginSettings ALONGSIDE an existing key file (a backup/restore, a partial migration), it is also
   * swept out on the steady-state path — the file key is authoritative and the stale DB copy must never
   * be left to co-reside with the ciphertext.
   */
  public TokenCipher cipher() {
    TokenCipher c = cipher;
    if (c != null) return c;
    synchronized (this) {
      if (cipher == null) cipher = buildCipher();
      return cipher;
    }
  }

  private TokenCipher buildCipher() {
    try {
      FileTokenKeyStore keyStore = new FileTokenKeyStore(homeDir());
      byte[] key;
      if (keyStore.exists()) {
        key = keyStore.loadOrCreateKey();
        // Defence-in-depth: if a legacy key ALSO lingers in PluginSettings (a backup/restore or a partial
        // migration can reintroduce it alongside an already-present key file), sweep it out here too. The
        // file key is authoritative — the stale DB copy is only a decryption red herring AND a silent
        // weakening of the key-at-rest separation this class advertises (a DB dump would again yield a key).
        // The one-time-migration else-branch below already removes it; this closes the co-residence window
        // on the steady-state path so the legacy key can never persist next to the ciphertext, however it
        // got there. A no-op when absent (the normal case).
        if (settings().get(LEGACY_CIPHER_KEY) != null) {
          settings().remove(LEGACY_CIPHER_KEY);
        }
      } else {
        String legacy = (String) settings().get(LEGACY_CIPHER_KEY);
        if (legacy != null) {
          key = migrateLegacyKey(keyStore, legacy);
        } else {
          key = keyStore.loadOrCreateKey(); // fresh key generated + persisted (0600) into the file
        }
      }
      return new TokenCipher(key);
    } catch (TokenCipher.DecryptException alreadyClassified) {
      // homeDir() and migrateLegacyKey() already surface their config faults as DecryptException — keep
      // that classification (DecryptException IS-A IllegalStateException, so the broader catch below would
      // otherwise re-wrap it into a redundant layer).
      throw alreadyClassified;
    } catch (IllegalStateException | java.io.UncheckedIOException keyStoreFault) {
      // A key FILE that exists but is a truncated/foreign value (IllegalStateException from
      // FileTokenKeyStore.validatedKey) or an IO error reading/creating it (UncheckedIOException) is a
      // LOCAL server-config fault, exactly like the missing-home / corrupt-legacy-key cases above — NOT an
      // upstream GitLab outage. Surface it as DecryptException so the REST layer maps it to 503 "repository
      // misconfigured" (pointing the operator at the key store) rather than the misleading 502 "cannot
      // reach repository" its generic catch(Exception) would produce (getToken() decrypts lazily at request
      // time, so the fault surfaces there, not on the admin page — which uses hasToken()).
      throw new TokenCipher.DecryptException("token key file unavailable or invalid", keyStoreFault);
    }
  }

  /**
   * One-time migration: decode the legacy base64 key from PluginSettings, move it onto the home
   * filesystem, then delete it from PluginSettings so the key no longer co-resides with the ciphertext.
   *
   * <p>A corrupt legacy value (not base64, or not a valid AES key length) is a LOCAL config fault, not
   * an upstream outage: surface it as {@link TokenCipher.DecryptException} so the REST layer maps it to
   * 503 "repository misconfigured" (pointing the operator at the stored config) rather than a misleading
   * 400 "invalid request parameter" (bare {@code IllegalArgumentException} from {@code Base64.decode} /
   * {@code new TokenCipher}) or 502 "cannot reach repository". Crucially the value is fully validated
   * BEFORE {@code storeKey}/{@code remove}, so a botched legacy value neither writes a corrupt key file
   * nor deletes the only remaining copy of the key — either would wedge decryption permanently.
   */
  private byte[] migrateLegacyKey(FileTokenKeyStore keyStore, String legacy) {
    byte[] key;
    try {
      key = Base64.getDecoder().decode(legacy);
    } catch (IllegalArgumentException notBase64) {
      throw new TokenCipher.DecryptException(
          "legacy token key in PluginSettings is not valid base64", notBase64);
    }
    if (key.length != 16 && key.length != 24 && key.length != 32) {
      throw new TokenCipher.DecryptException(
          "legacy token key in PluginSettings is not a valid AES key (" + key.length + " bytes)");
    }
    keyStore.storeKey(key); // validated above; storeKey re-checks the length as defence-in-depth
    settings().remove(LEGACY_CIPHER_KEY);
    return key;
  }

  /**
   * The Confluence (local) home directory, where the token-encryption key file is kept.
   *
   * <p>Prefers the non-deprecated {@code getLocalHomeDirectory()} (the old {@code getHomeDirectory()}
   * returned this same per-node home), falling back to {@code getSharedHomeDirectory()} only if the
   * local home is unavailable. NOTE for multi-node Data Center: the key therefore normally lives on
   * each node's LOCAL home; a clustered deployment that must share one token across nodes would key
   * deliberately off the shared home — a separately-tested change, kept local-first here to preserve
   * the proven single-node behaviour.
   */
  private Path homeDir() {
    // An unavailable home directory is a LOCAL server-config fault (the key file cannot be located), not
    // an upstream GitLab outage. Surface it as TokenCipher.DecryptException — like migrateLegacyKey's
    // config-fault handling — so the /source and /resolve endpoints map it to 503 "repository
    // misconfigured" (pointing the operator at the JVM/home config) rather than the misleading 502
    // "cannot reach repository" a bare IllegalStateException would fall through to.
    return applicationProperties.getLocalHomeDirectory()
        .or(applicationProperties::getSharedHomeDirectory)
        .orElseThrow(() -> new TokenCipher.DecryptException("no Confluence home directory available"));
  }

  public void setToken(String plaintext) { settings().put(NS + "token", cipher().encrypt(plaintext)); }

  /** Removes any stored token ciphertext entirely — an explicit REVOKE, distinct from {@link #setToken}
   *  which REPLACES it. After this {@link #hasToken()} reports false and {@link #getToken()} returns null,
   *  so the {@code /source} gate treats the repository as not configured. Unlike {@code setToken} this does
   *  NO crypto (a plain PluginSettings remove), so an operator can always revoke a compromised token even
   *  when the home key file has been lost/rotated (a state in which {@code setToken} would fail). */
  public void clearToken() { settings().remove(NS + "token"); }

  public String getToken() {
    String enc = (String) settings().get(NS + "token");
    return enc == null ? null : cipher().decrypt(enc);
  }

  /** Whether a token ciphertext is stored, WITHOUT decrypting it. The admin page only needs to know
   *  if a token is set; decrypting just to null-check would make a corrupt/rotated key (which makes
   *  {@link #getToken()} throw) 500 the whole config page. This read cannot fail on crypto. */
  public boolean hasToken() { return settings().get(NS + "token") != null; }
}
