package com.phrontizo.likec4.source;

import com.phrontizo.likec4.source.cache.RefShaCache;
import com.phrontizo.likec4.source.cache.SourceBundleCache;

/** Server-side orchestration: allowlist + validation + cached ref→sha + cached subtree fetch. */
public final class SourceService {
  public static final class NotAllowedException extends RuntimeException {
    public NotAllowedException(String project) {
      super("project not in allowlist: " + project);
    }
  }

  /**
   * Which GitLab failures the shared {@link CircuitBreaker} should treat as an outage. Only a
   * server-down / overloaded / slow signal counts: a connection refused / timeout {@code IOException},
   * a {@code 5xx}, or a {@code 429}/{@code 408}. A plain {@code 4xx} client error — a missing ref
   * (404), an auth failure (401/403), a bad request (400) — and the malformed-body cases surfaced as
   * status {@code 200} all mean GitLab is UP and answering, so they must NOT count: otherwise a single
   * macro pointing at a typo'd/deleted ref would, after {@code failureThreshold} renders, trip the
   * process-shared breaker and fail EVERY LikeC4 diagram on the instance for the open window even
   * though GitLab is perfectly healthy. A {@link GitLabConfigException} (a local misconfiguration such
   * as a blank/unconfigured token) is likewise NOT an outage — GitLab is up; the fault is our config —
   * so it too must not trip the breaker, or a token cleared during rotation would fail every diagram
   * behind a masking {@code circuit breaker is open} instead of the actionable config error. Wired into
   * the production breaker by {@code SourceServiceProvider}; the underlying error still propagates to
   * the caller unchanged.
   */
  public static final java.util.function.Predicate<Exception> COUNTS_AS_OUTAGE = ex -> {
    if (ex instanceof GitLabConfigException) return false; // local misconfiguration, not a GitLab outage
    if (ex instanceof GitLabException g) return g.status() >= 500 || g.status() == 429 || g.status() == 408;
    return true; // a network IOException / connect-refused / read-timeout: GitLab is unreachable → outage
  };

  private final ProjectAllowlist allowlist;
  private final RefShaCache refCache;
  private final SourceBundleCache bundleCache;
  private final ShaResolver resolver;
  private final SubtreeFetcher fetcher;
  private final CircuitBreaker breaker;

  public SourceService(ProjectAllowlist allowlist, RefShaCache refCache, SourceBundleCache bundleCache,
                       ShaResolver resolver, SubtreeFetcher fetcher, CircuitBreaker breaker) {
    this.allowlist = allowlist;
    this.refCache = refCache;
    this.bundleCache = bundleCache;
    this.resolver = resolver;
    this.fetcher = fetcher;
    this.breaker = breaker;
  }

  public String resolve(String project, String ref) throws Exception {
    String safeProject = InputValidation.sanitizeProject(project);
    requireAllowed(safeProject);
    String safeRef = InputValidation.sanitizeRef(ref);
    return resolveSha(safeProject, safeRef);
  }

  public SourceBundle source(String project, String ref, String path) throws Exception {
    String safeProject = InputValidation.sanitizeProject(project);
    requireAllowed(safeProject);
    String safeRef = InputValidation.sanitizeRef(ref);
    String safePath = InputValidation.sanitizePath(path);
    // resolveSha runs first (and itself goes through the breaker). While the ref→sha entry is fresh
    // (within RefShaCache's TTL), an outage trips the breaker, the fetch fails fast, and
    // SourceBundleCache serves last-good by (project, path) — stale-while-revalidate. NOTE (by design):
    // if an outage outlasts the ref TTL, resolveSha itself fails fast with CircuitOpenException and we
    // never reach the bundle SWR. The bundle-level SWR therefore covers in-TTL outages; surviving a
    // longer outage would need a last-good-sha that outlives the TTL (a deliberate future enhancement).
    String sha = resolveSha(safeProject, safeRef);
    return bundleCache.get(safeProject, sha, safePath,
        (p, s, pa) -> breaker.call(() -> fetcher.fetchSubtree(p, s, pa)));
  }

  private String resolveSha(String project, String safeRef) throws Exception {
    return refCache.get(project, safeRef, (p, r) -> breaker.call(() -> resolver.resolveSha(p, r)));
  }

  private void requireAllowed(String project) {
    if (!allowlist.isAllowed(project)) throw new NotAllowedException(project);
  }
}
