package com.phrontizo.confluence.likec4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.phrontizo.likec4.source.SourceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for the on-disk bundle-cache dir naming + owner-only (0700) creation (the pure /
 *  filesystem seams of the otherwise Spring/HttpClient-bound provider). */
class SourceServiceProviderTest {
  private static final Path TMP = Path.of("/tmp");

  @Test
  void cache_dir_is_namespaced_by_os_user_so_different_users_do_not_collide() {
    // A fixed dir name 0700-owned by the first OS user makes a co-located second user's
    // createDirectories/setPosixFilePermissions fail -> a permanent, opaque 503. Namespacing by user
    // gives each its own dir. The name is STABLE per user (not random) so the disk tier stays warm
    // across restarts and we don't leak a fresh dir per enable/restart.
    Path a = SourceServiceProvider.bundleCacheDirFor(TMP, "alice");
    Path b = SourceServiceProvider.bundleCacheDirFor(TMP, "bob");
    assertNotEquals(a, b, "different OS users must get different cache dirs (no shared-/tmp 503)");
    assertTrue(a.getFileName().toString().contains("alice"));
    assertEquals(a, SourceServiceProvider.bundleCacheDirFor(TMP, "alice"), "stable per user");
    assertEquals(TMP, a.getParent());
  }

  @Test
  void cache_dir_name_sanitises_path_unsafe_user_names() {
    Path p = SourceServiceProvider.bundleCacheDirFor(TMP, "../../evil/user");
    String name = p.getFileName().toString();
    // The user name must never introduce a path separator or a ".." segment into the dir name.
    assertFalse(name.contains("/"), "no path separator in the cache dir name");
    assertFalse(name.contains(".."), "no traversal segment in the cache dir name");
    assertEquals(TMP, p.getParent(), "the cache dir must stay directly under tmp, not escape it");
  }

  @Test
  void cache_dir_falls_back_for_a_null_or_blank_user_name() {
    Path fallback = SourceServiceProvider.bundleCacheDirFor(TMP, "anon");
    assertEquals(fallback, SourceServiceProvider.bundleCacheDirFor(TMP, null));
    assertEquals(fallback, SourceServiceProvider.bundleCacheDirFor(TMP, "   "));
  }

  @Test
  void get_revalidates_a_tampered_base_url_before_building_a_token_bearing_client() throws Exception {
    // get() re-validates config.getBaseUrl() before assembling the GitLabSourceClient — a belt-and-braces
    // SSRF/token-exfil defence so the PRIVATE-TOKEN is never shipped to an unvalidated/cleartext-public
    // host even for a value that bypassed setBaseUrl (a legacy pre-validation entry or settings tampering).
    // setProvider mocks the provider in the REST tests, so this real get() re-validation had no direct test.
    AdminConfig config = mock(AdminConfig.class);
    when(config.getRefTtlMillis()).thenReturn(60_000L); // used once in the ctor
    // A public FQDN over plain http: BaseUrlValidator rejects it (would ship the token in cleartext).
    when(config.getBaseUrl()).thenReturn("http://gitlab.example.com");
    SourceServiceProvider provider = new SourceServiceProvider(config);
    try {
      assertThrows(IllegalArgumentException.class, provider::get,
          "get() must re-validate the stored base URL and refuse to build a token-bearing client for it");
    } finally {
      provider.destroy(); // close this instance's HttpClient and clear the static INSTANCE it set
    }
  }

  @Test
  void get_reassembles_the_service_per_call_so_an_allowlist_change_takes_effect_live() throws Exception {
    // The headline contract of get(): a FRESH SourceService is assembled per call, re-reading the current
    // base URL / token / allowlist from AdminConfig, so an admin change (here: emptying the allowlist)
    // takes effect on the very next request without a plugin restart. Previously only the failure path
    // (a tampered base URL) was tested; a regression that cached the SourceService or wired an empty
    // allowlist would pass every other test. Drive a REAL allowlist decision — it is checked before any
    // network I/O — and use a loopback port-1 base URL so the ALLOWED path fails fast (connection refused)
    // with a NON-NotAllowed error rather than hanging on DNS/connect.
    AdminConfig config = mock(AdminConfig.class);
    when(config.getRefTtlMillis()).thenReturn(60_000L); // used once in the ctor
    when(config.getBaseUrl()).thenReturn("https://127.0.0.1:1");
    when(config.getAllowlist()).thenReturn(List.of("group"));
    SourceServiceProvider provider = new SourceServiceProvider(config);
    try {
      SourceService s1 = provider.get();
      // On the current allowlist -> passes the gate, then fails at the (refused) network. NOT NotAllowed.
      Exception onAllowlist = assertThrows(Exception.class, () -> s1.resolve("group/proj", "main"));
      assertFalse(onAllowlist instanceof SourceService.NotAllowedException,
          "a project on the current allowlist must pass the gate, failing later at the network");

      // Live-reconfigure: empty the allowlist. The NEXT get() must reflect it.
      when(config.getAllowlist()).thenReturn(List.of());
      SourceService s2 = provider.get();
      assertNotEquals(s1, s2, "get() must assemble a FRESH SourceService per call, never cache one");
      assertThrows(SourceService.NotAllowedException.class, () -> s2.resolve("group/proj", "main"),
          "after the allowlist is emptied, the next get() denies the same project — proving live reconfig");
    } finally {
      provider.destroy(); // close this instance's HttpClient and clear the static INSTANCE it set
    }
  }

  @Test
  void destroy_is_idempotent_and_only_clears_its_own_static_holder() throws Exception {
    // The guarded-holder + one-shot-close logic exists for OSGi enable->disable->enable churn. Assert the
    // off-by-one conditions that would rot silently: an OLDER provider's destroy() must not null a NEWER
    // provider's static holder, and a double dispose (Spring can call destroy() twice) must not throw.
    AdminConfig config = mock(AdminConfig.class);
    when(config.getRefTtlMillis()).thenReturn(60_000L);
    SourceServiceProvider p1 = new SourceServiceProvider(config);
    assertSame(p1, SourceServiceProvider.getInstance(), "the ctor publishes the instance");
    SourceServiceProvider p2 = new SourceServiceProvider(config); // supersedes the holder
    assertSame(p2, SourceServiceProvider.getInstance());
    p1.destroy(); // older provider: closes ITS HttpClient but must NOT clear p2's holder
    assertSame(p2, SourceServiceProvider.getInstance(),
        "an older provider's destroy() must not clear a newer provider's static holder");
    p2.destroy();
    p2.destroy(); // idempotent: the one-shot `closed` guard means a second dispose must not throw
    assertNull(SourceServiceProvider.getInstance(), "the current provider's destroy() clears the holder");
  }

  @Test
  void a_failed_construction_leaves_a_live_instance_untouched(@TempDir Path tmp) throws Exception {
    // `INSTANCE = this` is the LAST statement in the ctor, unreachable on throw — so a provider whose
    // construction fails (here: an unwritable bundle-cache root) must NOT clobber a previously-published
    // live provider's static holder. Otherwise an enable-churn that transiently hit a bad java.io.tmpdir
    // would null getInstance() and leave /resolve + /source 503 even though a working provider still
    // exists. This is the init-order invariant the reviewer asked to pin (the success path is covered by
    // destroy_is_idempotent_and_only_clears_its_own_static_holder).
    AdminConfig config = mock(AdminConfig.class);
    when(config.getRefTtlMillis()).thenReturn(60_000L);
    SourceServiceProvider live = new SourceServiceProvider(config); // publishes INSTANCE
    assertSame(live, SourceServiceProvider.getInstance());
    String savedTmp = System.getProperty("java.io.tmpdir");
    Path notADir = Files.createFile(tmp.resolve("not-a-dir")); // a regular FILE, not a directory
    try {
      // Point java.io.tmpdir at a regular file: ownerOnlyCacheDir() -> Files.createDirectories under a
      // non-directory ancestor throws, so THIS construction fails after `live` already succeeded.
      System.setProperty("java.io.tmpdir", notADir.toString());
      assertThrows(Exception.class, () -> new SourceServiceProvider(config),
          "construction under an unwritable tmp root must throw");
      assertSame(live, SourceServiceProvider.getInstance(),
          "a failed construction must not clobber the live provider's static holder");
    } finally {
      System.setProperty("java.io.tmpdir", savedTmp);
      live.destroy(); // close its HttpClient and clear the holder
    }
  }

  @Test
  void cache_dir_is_created_owner_only_0700_so_other_local_users_cannot_read_fetched_sources(@TempDir Path tmp)
      throws Exception {
    // The disk tier holds fetched (internal/private) architecture sources. The dir must be created
    // 0700 on POSIX so a co-located OS user sharing java.io.tmpdir cannot read them — a token-exfil /
    // source-disclosure defence that was previously only documented, never asserted.
    Path dir = SourceServiceProvider.createOwnerOnlyCacheDir(tmp, "alice");
    assertTrue(Files.isDirectory(dir), "the bundle-cache dir must be created");
    assertEquals(tmp, dir.getParent(), "the cache dir stays directly under the given tmp root");
    if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals(
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
          Files.getPosixFilePermissions(dir),
          "the bundle-cache dir must be 0700 (owner-only) on POSIX");
    }
  }
}
