package com.phrontizo.confluence.likec4;

import com.phrontizo.likec4.source.BaseUrlValidator;
import com.phrontizo.likec4.source.CircuitBreaker;
import com.phrontizo.likec4.source.GitLabSourceClient;
import com.phrontizo.likec4.source.ProjectAllowlist;
import com.phrontizo.likec4.source.SourceService;
import com.phrontizo.likec4.source.cache.Clock;
import com.phrontizo.likec4.source.cache.RefShaCache;
import com.phrontizo.likec4.source.cache.SourceBundleCache;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.inject.Inject;
import org.springframework.beans.factory.DisposableBean;

/**
 * Assembles a {@link SourceService} from the current {@link AdminConfig}; caches are
 * process-singletons tied to this OSGi bundle's class loader lifetime.
 *
 * <p>Declared as a {@code <component>} in {@code atlassian-plugin.xml} — Confluence's plugin
 * Spring context creates this bean after {@link AdminConfig} (which is also a {@code <component>}).
 * The static {@link #INSTANCE} field is set in the constructor so plain-Jersey REST resources
 * can call {@link #getInstance()} without needing Spring injection themselves.
 */
public class SourceServiceProvider implements DisposableBean {
  private static final java.lang.System.Logger LOG =
      java.lang.System.getLogger(SourceServiceProvider.class.getName());
  private static volatile SourceServiceProvider INSTANCE;

  private final AdminConfig config;
  // connectTimeout bounds TCP connect; per-request read timeouts live in GitLabSourceClient so a
  // hung/slow GitLab cannot tie up a Confluence request thread (or wedge the circuit breaker) forever.
  // followRedirects(NEVER) is explicit (it is also the JDK default): a 3xx from the GitLab host is
  // surfaced as a non-200 error rather than transparently followed, so the PRIVATE-TOKEN header can
  // never be replayed to an attacker-chosen redirect target (SSRF/token-exfil defence-in-depth).
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
  private final RefShaCache refCache;
  private final SourceBundleCache bundleCache;
  private final CircuitBreaker breaker;
  // One-shot guard so destroy() closes the HttpClient at most once, even if Spring calls it twice or an
  // enable→disable→enable churn overlaps two providers.
  private final AtomicBoolean closed = new AtomicBoolean(false);

  @Inject
  public SourceServiceProvider(AdminConfig config) throws Exception {
    this.config = config;
    try {
      // refTtlMillis is read ONCE here and baked into the process-singleton RefShaCache. Unlike the
      // base URL / token / allowlist (re-read per request in get() for live reconfig), a TTL change
      // takes effect only after a plugin disable/enable. There is deliberately no admin-UI field for
      // it, so in practice it is a fixed build-time constant; this is the one config value exempt from
      // the live-reconfig contract documented on get().
      this.refCache = new RefShaCache(Clock.SYSTEM, config.getRefTtlMillis());
      this.bundleCache = new SourceBundleCache(ownerOnlyCacheDir(), 200);
      // Trip after 5 consecutive GitLab OUTAGES; stay open 30s before a half-open trial. Only a genuine
      // server-down/overloaded/slow signal counts (SourceService.COUNTS_AS_OUTAGE): a 4xx client error
      // from a typo'd/deleted ref must NOT trip this process-shared breaker, or one misconfigured macro
      // would fail EVERY LikeC4 diagram on the instance for the open window.
      this.breaker = new CircuitBreaker(Clock.SYSTEM, 5, 30_000, SourceService.COUNTS_AS_OUTAGE);
    } catch (Exception e) {
      // A constructor failure (e.g. an unwritable java.io.tmpdir for the owner-only bundle-cache dir)
      // leaves INSTANCE unset, so the REST resources keep returning 503 "plugin initialising"
      // indefinitely — which reads as transient. Log an actionable message so the operator can see the
      // real, PERMANENT cause rather than chasing a phantom slow startup.
      LOG.log(java.lang.System.Logger.Level.ERROR,
          "LikeC4 SourceServiceProvider failed to initialise; /resolve and /source will stay 503", e);
      throw e;
    }
    INSTANCE = this;
  }

  /** The on-disk bundle-cache dir, restricted to owner-only (0700) on POSIX so other local OS users
   *  cannot read the fetched (internal) architecture sources from a shared temp directory. */
  private static Path ownerOnlyCacheDir() throws IOException {
    return createOwnerOnlyCacheDir(
        Path.of(System.getProperty("java.io.tmpdir")), System.getProperty("user.name"));
  }

  /** Package-private for tests: create the per-user bundle-cache dir under {@code tmpRoot} and restrict
   *  it to owner-only (0700) on POSIX. Separated from {@link #ownerOnlyCacheDir()} so the security-load-
   *  bearing 0700 restriction can be asserted without depending on the real {@code java.io.tmpdir}. */
  static Path createOwnerOnlyCacheDir(Path tmpRoot, String userName) throws IOException {
    Path dir = bundleCacheDirFor(tmpRoot, userName);
    Files.createDirectories(dir);
    if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(dir, EnumSet.of(
          PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }
    return dir;
  }

  /**
   * The per-OS-user bundle-cache dir name under {@code tmpRoot}. Namespacing by the (sanitised) OS user
   * avoids a permanent, opaque 503 when two Confluence JVMs run as DIFFERENT OS users sharing one
   * {@code java.io.tmpdir}: a fixed name 0700-owned by the first user leaves the second unable to create
   * or secure it. The name is STABLE per user (not random, unlike {@code createTempDirectory}) so the
   * disk tier stays warm across restarts and we don't leak a fresh dir per enable/restart. The user name
   * is sanitised to {@code [A-Za-z0-9_-]} (others → {@code _}) so it can never inject a path separator or
   * a {@code ..} segment, and a null/blank name falls back to {@code anon}.
   */
  static Path bundleCacheDirFor(Path tmpRoot, String userName) {
    String safeUser = (userName == null || userName.isBlank())
        ? "anon" : userName.replaceAll("[^A-Za-z0-9_-]", "_");
    return tmpRoot.resolve("likec4-bundle-cache-" + safeUser);
  }

  /** Returns the singleton set by the plugin Spring context at enable time. */
  public static SourceServiceProvider getInstance() {
    return INSTANCE;
  }

  /** Spring lifecycle: close the HttpClient (and its selector/executor threads) on plugin disable so
   *  the bundle classloader isn't pinned across enable/disable cycles. Idempotent via {@link #closed}:
   *  HttpClient.close() blocks until in-flight requests finish (Java 21), so it must run at most once
   *  even under a double dispose or an overlapping enable→disable→enable. */
  @Override
  public void destroy() {
    if (closed.compareAndSet(false, true)) {
      http.close();
    }
    if (INSTANCE == this) INSTANCE = null;
  }

  public SourceService get() {
    // A fresh SourceService (+ GitLabSourceClient) is assembled per call ON PURPOSE: it re-reads the
    // current base URL / token / allowlist from AdminConfig so an admin change (token rotation, a new
    // allowlist entry, a re-pointed base URL) takes effect on the very next request without a plugin
    // restart. The expensive, stateful pieces (HttpClient, the ref/bundle caches, the breaker) are
    // process-singletons shared across these per-call instances, so the per-request cost is small.
    // (Exception: refTtlMillis is baked into refCache at construction — see the constructor — so a
    // TTL change is NOT picked up live; everything else here is.)
    // Re-validate the stored base URL before using it for a token-bearing outbound request. setBaseUrl
    // validates on write, so this only bites a value that bypassed it (a legacy pre-validation entry or
    // direct settings tampering) — but it ensures the PRIVATE-TOKEN is never shipped to an
    // unvalidated/cleartext-public host even then. A normalised, valid URL re-validates to itself.
    // Coupling note: REST callers reach get() only AFTER SourceRestResource.gate() has already turned a
    // blank/invalid stored URL into a 503, so on the production path this validate() never actually
    // rejects. It is defence-in-depth for a direct (un-gated) caller — for whom it throws
    // IllegalArgumentException rather than silently building a client against a bad URL.
    // NOTE: the three config reads (base URL here, token + allowlist below) are individually safe but
    // NOT atomic as a group — an admin save concurrent with this call could mix the old base URL with a
    // new allowlist for one request. Benign and arguably intended (live reconfig takes effect next
    // request); the next request is fully consistent.
    String baseUrl = BaseUrlValidator.validate(config.getBaseUrl());
    GitLabSourceClient client = new GitLabSourceClient(http, baseUrl, config::getToken);
    return new SourceService(new ProjectAllowlist(config.getAllowlist()), refCache, bundleCache,
        client::resolveSha, client::fetchSubtree, breaker);
  }
}
