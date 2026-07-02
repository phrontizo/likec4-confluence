package com.phrontizo.likec4.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Fetches LikeC4 source from a self-managed GitLab via the REST + archive endpoints. */
public final class GitLabSourceClient {
  /** Default per-request timeout applied to every GitLab call. */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Overall wall-clock budget for consuming a streamed archive body (see {@link DeadlineInputStream}).
   * The request timeout only governs the response-headers phase, so this bounds a slow-trickle body.
   * Generous (well above the request timeout) so a legitimately large/slow download is unaffected.
   */
  public static final Duration DEFAULT_MAX_DOWNLOAD_DURATION = Duration.ofSeconds(120);

  // A GitLab commit `id` is always the FULL 40-hex SHA-1. Requiring exactly 40 hex (rather than a
  // loose 7..40) rejects a truncated/odd id from a misbehaving proxy and, together with the
  // lower-casing in resolveSha, guarantees every downstream consumer (RefShaCache, the bundle cache
  // key, the archive `sha=` param) sees one canonical form — no case/length cache-key fragmentation.
  private static final Pattern HEX_SHA = Pattern.compile("[0-9a-fA-F]{40}");

  /**
   * Upper bound on the commit-resolve response body. A real GitLab commit JSON is ~1 KiB; 1 MiB is an
   * enormous margin while bounding a compromised/MITM'd endpoint that returns a multi-megabyte body.
   * The archive path is streamed and capped by {@link GitLabArchiveExtractor}; this commit path was the
   * one place a full response was buffered whole into a {@code byte[]} before parsing.
   */
  private static final int MAX_COMMIT_RESPONSE_BYTES = 1024 * 1024;

  // Shared, thread-safe after configuration, and expensive to build (it constructs the full
  // serialization factory). SourceServiceProvider rebuilds a GitLabSourceClient on EVERY request (for
  // live reconfig), so a per-instance ObjectMapper would pay that construction cost on the hot path; a
  // static one is used read-only (readTree) and is safe to share across instances and threads.
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http;
  private final String baseUrl;
  private final Supplier<String> token;
  private final Duration requestTimeout;
  private final Duration maxDownloadDuration;

  public GitLabSourceClient(HttpClient http, String baseUrl, Supplier<String> token) {
    this(http, baseUrl, token, DEFAULT_REQUEST_TIMEOUT);
  }

  public GitLabSourceClient(
      HttpClient http, String baseUrl, Supplier<String> token, Duration requestTimeout) {
    this(http, baseUrl, token, requestTimeout, DEFAULT_MAX_DOWNLOAD_DURATION);
  }

  public GitLabSourceClient(
      HttpClient http, String baseUrl, Supplier<String> token, Duration requestTimeout,
      Duration maxDownloadDuration) {
    // Fail fast on a null dependency (named-argument NPE), mirroring RefShaCache/TokenCipher/
    // ProjectAllowlist/SourceBundle. A null baseUrl already NPEs eagerly below, but a null http or
    // token supplier would otherwise defer to the first send()/token.get() where it is far harder to
    // diagnose; check all five up front so the failure names the offending argument at construction.
    Objects.requireNonNull(http, "http");
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    Objects.requireNonNull(maxDownloadDuration, "maxDownloadDuration");
    // The token-replay defence in send() depends on a 3xx NOT being followed (a followed redirect would
    // replay the PRIVATE-TOKEN header to the redirect target). That invariant otherwise lives outside
    // this class — enforced only by every caller remembering to build the HttpClient with Redirect.NEVER
    // (the JDK default). Assert it here so a future caller that passes a Redirect.NORMAL/ALWAYS client is
    // rejected at construction rather than silently leaking the token on the first redirecting response.
    if (http.followRedirects() != HttpClient.Redirect.NEVER) {
      throw new IllegalArgumentException(
          "GitLabSourceClient requires an HttpClient built with Redirect.NEVER so the PRIVATE-TOKEN is "
              + "never replayed to a redirect target; got followRedirects()=" + http.followRedirects());
    }
    this.http = http;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.token = token;
    this.requestTimeout = requestTimeout;
    this.maxDownloadDuration = maxDownloadDuration;
  }

  /** Resolve a branch/tag/sha ref to a full commit sha. */
  public String resolveSha(String project, String ref) throws IOException, InterruptedException {
    // Defence-in-depth: re-sanitise at the HTTP boundary. SourceService already sanitises every input,
    // so for the production path this is idempotent; it ensures a direct caller (a tool/test that
    // bypasses SourceService) cannot push unsanitised project/ref into the GitLab request URL.
    project = InputValidation.sanitizeProject(project);
    ref = InputValidation.sanitizeRef(ref);
    String enc = URLEncoder.encode(project, StandardCharsets.UTF_8);
    String refEnc = URLEncoder.encode(ref, StandardCharsets.UTF_8);
    URI uri = URI.create(baseUrl + "/api/v4/projects/" + enc + "/repository/commits/" + refEnc);
    // Stream the (small) commit JSON through the same wall-clock deadline as the archive body and a
    // hard size cap, so a hostile endpoint can neither OOM the JVM by returning a giant body nor pin the
    // request thread by trickling it. ofByteArray would have buffered the whole body before any check.
    HttpResponse<InputStream> res = send(uri, HttpResponse.BodyHandlers.ofInputStream());
    long deadline = System.nanoTime() + maxDownloadDuration.toNanos();
    byte[] body;
    // Check status BEFORE wrapping in the deadline guard: on a non-200 we discard the body, so spawning
    // the watchdog thread just to tear it down is pure overhead on every error response (404s, breaker
    // probes). The outer try-with-resources still closes the raw body to release the connection.
    try (InputStream raw = res.body()) {
      if (res.statusCode() != 200) throw new GitLabException(res.statusCode(), "resolve ref " + ref);
      try (InputStream in = DeadlineInputStream.start(raw, deadline)) {
        body = readBounded(in, MAX_COMMIT_RESPONSE_BYTES, "commit response");
      }
    }
    JsonNode node = JSON.readTree(body);
    String sha = node.path("id").asText("");
    if (sha.isEmpty()) throw new GitLabException(200, "commit response had no id");
    if (!HEX_SHA.matcher(sha).matches()) throw new GitLabException(200, "commit response had invalid id");
    return sha.toLowerCase(Locale.ROOT); // canonicalise so cache keys never fragment on letter-case
  }

  /**
   * Fetch the LikeC4 subtree at a sha, filtered to LikeC4 source + snapshots.
   *
   * <p>The archive body is <em>streamed</em> ({@link HttpResponse.BodyHandlers#ofInputStream}) straight
   * into {@link GitLabArchiveExtractor}, never buffered whole into a {@code byte[]} first. The
   * extractor's per-entry / total uncompressed caps therefore bound memory <em>during</em> the
   * download, so a malicious or oversized GitLab response cannot OOM the JVM before extraction limits
   * apply.
   */
  public SourceBundle fetchSubtree(String project, String sha, String path)
      throws IOException, InterruptedException {
    // Defence-in-depth (see resolveSha): re-sanitise project/path and require a canonical 40-hex sha
    // before it reaches the archive `sha=` URL param, so a direct caller cannot bypass the guards.
    project = InputValidation.sanitizeProject(project);
    if (sha == null || !HEX_SHA.matcher(sha).matches()) {
      throw new IllegalArgumentException("invalid sha: " + sha);
    }
    // Canonicalise to lower-case exactly as resolveSha does. The production path always arrives here
    // with a sha already lower-cased by resolveSha, but a direct caller passing a valid UPPER/mixed-case
    // 40-hex sha would otherwise send `sha=ABC…` to GitLab and key the bundle cache on the mixed-case
    // string — fragmenting the cache (a duplicate fetch) relative to the lower-case production path.
    sha = sha.toLowerCase(Locale.ROOT);
    path = InputValidation.sanitizePath(path);
    String enc = URLEncoder.encode(project, StandardCharsets.UTF_8);
    StringBuilder u = new StringBuilder(baseUrl)
        .append("/api/v4/projects/").append(enc)
        .append("/repository/archive.tar.gz?sha=").append(URLEncoder.encode(sha, StandardCharsets.UTF_8));
    if (path != null && !path.isEmpty()) {
      u.append("&path=").append(URLEncoder.encode(path, StandardCharsets.UTF_8));
    }
    HttpResponse<InputStream> res = send(URI.create(u.toString()), HttpResponse.BodyHandlers.ofInputStream());
    // Bound the wall-clock spent consuming the streamed body, independent of the response-headers
    // request timeout, so a trickling body cannot pin the request thread / single-flight lock / breaker
    // permit under the size caps.
    long deadline = System.nanoTime() + maxDownloadDuration.toNanos();
    // Check status BEFORE wrapping (see resolveSha): skip the watchdog thread on a non-200 we discard.
    try (InputStream raw = res.body()) {
      if (res.statusCode() != 200) throw new GitLabException(res.statusCode(), "archive at " + sha);
      try (InputStream body = DeadlineInputStream.start(raw, deadline)) {
        Map<String, String> files = GitLabArchiveExtractor.extract(body, path);
        return new SourceBundle(sha, files);
      }
    }
  }

  /** Read {@code in} fully into a byte[], aborting with an {@link IOException} once {@code limit} bytes
   *  are exceeded — so a body is bounded BEFORE it is buffered whole, mirroring the archive caps. */
  private static byte[] readBounded(InputStream in, int limit, String what) throws IOException {
    // Pre-size to one read buffer (8 KiB): a real commit JSON is ~1 KiB, so the common case needs zero
    // reallocations rather than the eight doublings the default 32-byte ByteArrayOutputStream would take
    // just to reach 8 KiB. A near-cap (1 MiB) adversarial body still grows-and-reallocates from here, but
    // the size cap below bounds it either way — the pre-size is only to spare the common commit response.
    ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
    byte[] tmp = new byte[8192];
    long total = 0;
    int n;
    // A legal 0-length read (no progress yet) simply re-loops; this cannot busy-spin unbounded because
    // both callers wrap {@code in} in a DeadlineInputStream, whose between-reads deadline check throws
    // once the wall-clock budget is spent (and the JDK HttpClient body never returns 0 in the first place).
    while ((n = in.read(tmp)) != -1) {
      total += n;
      if (total > limit) throw new IOException(what + " exceeds the " + limit + "-byte limit");
      buf.write(tmp, 0, n);
    }
    return buf.toByteArray();
  }

  private <T> HttpResponse<T> send(URI uri, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    String tok = token.get();
    if (tok == null || tok.isBlank()) {
      // A GitLabConfigException (not a plain IllegalStateException) so the shared CircuitBreaker does
      // NOT count this local misconfiguration as a GitLab outage — otherwise a blank/rotated-away token
      // would trip the process-shared breaker and fail every diagram (see SourceService.COUNTS_AS_OUTAGE).
      throw new GitLabConfigException("GitLab token is not configured");
    }
    HttpRequest req = HttpRequest.newBuilder(uri)
        .timeout(requestTimeout)
        .header("PRIVATE-TOKEN", tok)
        .GET()
        .build();
    // This method sets no redirect policy of its own — it relies on the caller-supplied HttpClient
    // being built with Redirect.NEVER (as SourceServiceProvider does; it is also the JDK default), which
    // the constructor now asserts so a Redirect.NORMAL/ALWAYS client cannot get this far. A 3xx is then
    // surfaced here as a non-200 (→ GitLabException) and the PRIVATE-TOKEN header is never replayed to a
    // redirect target. The default-client behaviour is pinned by GitLabSourceClientTest
    // (does_not_follow_a_redirect_… and fetchSubtree_does_not_follow_a_redirect_…) and the construction
    // guard by constructor_rejects_a_client_that_would_follow_redirects. A non-2xx is handled by each caller.
    return http.send(req, handler);
  }
}
