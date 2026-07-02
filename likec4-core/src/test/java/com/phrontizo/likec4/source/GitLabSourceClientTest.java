package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitLabSourceClientTest {
  private HttpServer server;
  private String base;
  private volatile String lastToken;
  private volatile String lastArchiveQuery;

  private static byte[] tarGz(Map<String, String> entries) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(bos))) {
      for (Map.Entry<String, String> en : entries.entrySet()) {
        byte[] data = en.getValue().getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry te = new TarArchiveEntry(en.getKey());
        te.setSize(data.length);
        tar.putArchiveEntry(te);
        tar.write(data);
        tar.closeArchiveEntry();
      }
    }
    return bos.toByteArray();
  }

  /** A syntactically valid commit-response JSON carrying the canonical 40-hex id, padded with an
   *  ignored field to EXACTLY {@code size} bytes -- so a test can drive readBounded's inclusive size
   *  boundary (accept exactly the cap; reject one byte over). All-ASCII, so byte length == char length. */
  private static byte[] commitJsonOfExactly(int size) {
    String head = "{\"id\":\"0123456789abcdef0123456789abcdef01234567\",\"pad\":\"";
    String tail = "\"}";
    int pad = size - head.length() - tail.length();
    if (pad < 0) throw new IllegalArgumentException("size too small for a valid commit body: " + size);
    return (head + "x".repeat(pad) + tail).getBytes(StandardCharsets.US_ASCII);
  }

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      lastToken = ex.getRequestHeaders().getFirst("PRIVATE-TOKEN");
      String raw = ex.getRequestURI().getRawPath();
      String query = ex.getRequestURI().getRawQuery();
      byte[] body;
      int status = 200;
      if (raw.contains("/repository/commits/")) {
        if (raw.endsWith("/missing")) { status = 404; body = "{}".getBytes(); }
        else if (raw.endsWith("/badid")) { body = "{\"id\":\"not-a-hex-sha!!\"}".getBytes(); }
        else if (raw.endsWith("/shortid")) { body = "{\"id\":\"abc1234\"}".getBytes(); } // 7-hex, not a full sha
        else if (raw.endsWith("/mixedid")) { body = "{\"id\":\"0123456789ABCDEF0123456789ABCDEF01234567\"}".getBytes(); }
        else if (raw.endsWith("/hugeid")) { body = new byte[2 * 1024 * 1024]; java.util.Arrays.fill(body, (byte) 'x'); }
        else if (raw.endsWith("/atcap")) { body = commitJsonOfExactly(1024 * 1024); } // == MAX_COMMIT_RESPONSE_BYTES
        else if (raw.endsWith("/overcap")) { body = commitJsonOfExactly(1024 * 1024 + 1); } // one byte over the cap
        else if (raw.endsWith("/noid")) { body = "{}".getBytes(); } // 200, but the JSON carries no id field
        else if (raw.endsWith("/utf8author")) {
          // A valid commit JSON whose NON-id fields carry multibyte UTF-8 (author name + a CJK glyph),
          // to exercise the byte-level charset handling of JSON.readTree(byte[]).
          body = "{\"id\":\"0123456789abcdef0123456789abcdef01234567\",\"author_name\":\"Jörg Müller 図\"}"
              .getBytes(StandardCharsets.UTF_8);
        }
        else body = "{\"id\":\"0123456789abcdef0123456789abcdef01234567\"}".getBytes();
      } else if (raw.contains("/repository/archive.tar.gz")) {
        // Capture the query for the test thread to assert. An AssertionError thrown HERE runs on the
        // HttpServer dispatch thread, which swallows it — the test would pass even with a wrong/absent
        // path= param. Record it instead and assert in fetches_and_filters_the_subtree.
        lastArchiveQuery = query;
        if (query != null && query.contains("sha=ffffffffffffffffffffffffffffffffffffffff")) {
          // The archive endpoint can fail independently of the commit resolve (e.g. the token lacks
          // archive scope, or GitLab 5xxs); a sentinel sha drives that non-200 branch.
          status = 403; body = "{}".getBytes();
        } else {
          Map<String, String> entries = new LinkedHashMap<>();
          entries.put("myrepo-main-sha/diagrams/model.likec4", "M");
          entries.put("myrepo-main-sha/diagrams/secret.env", "X");
          try { body = tarGz(entries); } catch (IOException e) { throw new RuntimeException(e); }
        }
      } else { status = 404; body = "{}".getBytes(); }
      ex.sendResponseHeaders(status, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() { server.stop(0); }

  private GitLabSourceClient client() {
    return new GitLabSourceClient(HttpClient.newHttpClient(), base, () -> "secret-token");
  }

  @Test
  void constructor_fails_fast_on_null_dependencies() {
    HttpClient http = HttpClient.newHttpClient();
    // Mirror the fail-fast, named-argument NPE convention used across the module (RefShaCache,
    // TokenCipher, ProjectAllowlist, SourceBundle) rather than deferring a null http/token to the
    // first send() call, where the failure is far less diagnosable.
    assertEquals("http",
        assertThrows(NullPointerException.class, () -> new GitLabSourceClient(null, base, () -> "t")).getMessage());
    assertEquals("baseUrl",
        assertThrows(NullPointerException.class, () -> new GitLabSourceClient(http, null, () -> "t")).getMessage());
    assertEquals("token",
        assertThrows(NullPointerException.class, () -> new GitLabSourceClient(http, base, null)).getMessage());
    assertEquals("requestTimeout",
        assertThrows(NullPointerException.class,
            () -> new GitLabSourceClient(http, base, () -> "t", null)).getMessage());
    assertEquals("maxDownloadDuration",
        assertThrows(NullPointerException.class,
            () -> new GitLabSourceClient(http, base, () -> "t", Duration.ofSeconds(1), null)).getMessage());
  }

  @Test
  void constructor_rejects_a_client_that_would_follow_redirects() {
    // The token-replay defence relies on 3xx NOT being followed (a followed redirect replays the
    // PRIVATE-TOKEN header to the redirect target). A Redirect.NORMAL/ALWAYS client would defeat that, so
    // the constructor must reject it up front rather than trust every caller to build Redirect.NEVER.
    HttpClient follows = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> new GitLabSourceClient(follows, base, () -> "t"));
    assertTrue(ex.getMessage().contains("Redirect.NEVER"), ex.getMessage());
    // The JDK default (used everywhere in production and these tests) is NEVER, so it is accepted.
    new GitLabSourceClient(HttpClient.newHttpClient(), base, () -> "t");
  }

  @Test
  void resolves_sha_and_sends_token() throws Exception {
    String sha = client().resolveSha("grp/repo", "main");
    assertEquals("0123456789abcdef0123456789abcdef01234567", sha);
    assertEquals("secret-token", lastToken);
  }

  @Test
  void fetches_and_filters_the_subtree() throws Exception {
    SourceBundle bundle = client().fetchSubtree("grp/repo", "0123456789abcdef0123456789abcdef01234567", "diagrams");
    assertEquals(Map.of("model.likec4", "M"), bundle.files());
    assertEquals("0123456789abcdef0123456789abcdef01234567", bundle.sha());
    // Asserted on the TEST thread (the handler only records it): the subtree path must reach GitLab as
    // the archive `path=` param, so a regression dropping it (refetching the whole repo) is caught here.
    assertTrue(lastArchiveQuery != null && lastArchiveQuery.contains("path=diagrams"),
        "archive request must carry path=diagrams; was: " + lastArchiveQuery);
  }

  @Test
  void fetchSubtree_canonicalises_a_mixed_case_sha_to_lower_case() throws Exception {
    // fetchSubtree is public and documented as directly callable (defence-in-depth). A direct caller
    // passing a valid UPPER/mixed-case 40-hex sha must have it canonicalised to lower-case, both in the
    // archive `sha=` param sent to GitLab and in the returned bundle's sha() — otherwise the bundle
    // cache keys on the mixed-case string and fragments (a duplicate fetch) versus the lower-case
    // production path from resolveSha. Mirrors resolveSha's canonicalises_a_mixed_case_sha_to_lower_case.
    SourceBundle bundle = client().fetchSubtree(
        "grp/repo", "0123456789ABCDEF0123456789ABCDEF01234567", "diagrams");
    assertEquals("0123456789abcdef0123456789abcdef01234567", bundle.sha());
    assertTrue(lastArchiveQuery != null
            && lastArchiveQuery.contains("sha=0123456789abcdef0123456789abcdef01234567"),
        "archive request must carry the lower-cased sha; was: " + lastArchiveQuery);
  }

  @Test
  void maps_non_2xx_to_GitLabException_with_status() {
    GitLabException ex = assertThrows(GitLabException.class, () -> client().resolveSha("grp/repo", "missing"));
    assertEquals(404, ex.status());
  }

  @Test
  void rejects_a_commit_response_whose_id_is_not_a_hex_sha() {
    GitLabException ex = assertThrows(GitLabException.class, () -> client().resolveSha("grp/repo", "badid"));
    assertEquals(200, ex.status());
    assertTrue(ex.getMessage().contains("invalid id"), "was: " + ex.getMessage());
  }

  @Test
  void rejects_a_commit_response_that_omits_the_id_field() {
    // A 200 whose JSON carries no `id` at all (e.g. a proxy/caching layer returning `{}` with a success
    // status) hits the DISTINCT "no id" branch — `path("id").asText("")` yields "" — rather than the
    // "invalid id" branch. It must be rejected, not resolved to an empty sha that would poison the caches.
    GitLabException ex = assertThrows(GitLabException.class, () -> client().resolveSha("grp/repo", "noid"));
    assertEquals(200, ex.status());
    assertTrue(ex.getMessage().contains("no id"), "was: " + ex.getMessage());
  }

  @Test
  void rejects_a_truncated_short_commit_id() {
    // A real GitLab commit id is always the full 40-hex sha; a short id (e.g. from a misbehaving proxy)
    // is rejected rather than silently cached as if it were a commit sha.
    GitLabException ex = assertThrows(GitLabException.class, () -> client().resolveSha("grp/repo", "shortid"));
    assertEquals(200, ex.status());
    assertTrue(ex.getMessage().contains("invalid id"), "was: " + ex.getMessage());
  }

  @Test
  void resolves_a_commit_body_whose_non_id_fields_contain_multibyte_utf8() throws Exception {
    // resolveSha parses the RAW response bytes via Jackson (JSON.readTree(byte[])), which auto-detects the
    // JSON charset per RFC 4627 rather than assuming the platform default. Pin that a commit body carrying
    // multibyte UTF-8 in a non-id field still parses and yields the canonical sha — a regression that first
    // decoded the bytes with a fixed/platform charset (e.g. new String(body)) would be a latent corruption
    // this guards against, since every existing fixture is pure ASCII.
    assertEquals("0123456789abcdef0123456789abcdef01234567", client().resolveSha("grp/repo", "utf8author"));
  }

  @Test
  void canonicalises_a_mixed_case_sha_to_lower_case() throws Exception {
    // The resolved sha is the key for both the ref->sha cache and the source-bundle cache; canonical
    // lower-case prevents an upper/mixed-case id from fragmenting those caches (a duplicate fetch).
    assertEquals("0123456789abcdef0123456789abcdef01234567", client().resolveSha("grp/repo", "mixedid"));
  }

  @Test
  void rejects_an_oversized_commit_response_before_buffering_it_whole() {
    // A compromised/MITM'd endpoint that returns a multi-megabyte commit body must be rejected by the
    // size cap (the body is read through a bounded loop), not buffered whole into a byte[] (OOM risk).
    IOException ex = assertThrows(IOException.class, () -> client().resolveSha("grp/repo", "hugeid"));
    assertTrue(ex.getMessage().contains("exceeds"), "was: " + ex.getMessage());
  }

  @Test
  void accepts_a_commit_response_of_exactly_the_size_cap() throws Exception {
    // readBounded's size gate is INCLUSIVE (`total > limit`): a body of EXACTLY the cap must be accepted
    // and resolved, not rejected. Pins the boundary so a regression to `>=` -- which would reject a
    // legitimate exactly-1-MiB commit body -- is caught, mirroring TokenCipher's inclusive-boundary test.
    assertEquals("0123456789abcdef0123456789abcdef01234567", client().resolveSha("grp/repo", "atcap"));
  }

  @Test
  void rejects_a_commit_response_one_byte_over_the_size_cap() {
    // The complement of the exactly-at-cap case: one byte past the cap must trip the bound (total >
    // limit) before the body is buffered whole, closing the gap the far-over `hugeid` case cannot pin.
    IOException ex = assertThrows(IOException.class, () -> client().resolveSha("grp/repo", "overcap"));
    assertTrue(ex.getMessage().contains("exceeds"), "was: " + ex.getMessage());
  }

  @Test
  void does_not_follow_a_redirect_so_the_private_token_is_never_replayed_to_another_host() throws Exception {
    // A 30x from the GitLab host must NOT be transparently followed (that would replay the
    // PRIVATE-TOKEN header to the redirect target). With the JDK default Redirect.NEVER the 302 is
    // surfaced as a non-200 error and the Location target is never contacted.
    HttpServer redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    java.util.concurrent.atomic.AtomicBoolean targetHit = new java.util.concurrent.atomic.AtomicBoolean(false);
    redirector.createContext("/", ex -> {
      if (ex.getRequestURI().getRawPath().contains("/repository/commits/")) {
        ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + ex.getLocalAddress().getPort() + "/stolen");
        ex.sendResponseHeaders(302, -1);
      } else {
        targetHit.set(true); // the redirect target (would have received the token) was contacted
        byte[] b = "{\"id\":\"0123456789abcdef0123456789abcdef01234567\"}".getBytes();
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
      }
      ex.close();
    });
    redirector.start();
    String redirBase = "http://127.0.0.1:" + redirector.getAddress().getPort();
    try {
      GitLabSourceClient c = new GitLabSourceClient(HttpClient.newHttpClient(), redirBase, () -> "secret-token");
      GitLabException ex = assertThrows(GitLabException.class, () -> c.resolveSha("grp/repo", "main"));
      assertEquals(302, ex.status());
      assertTrue(!targetHit.get(), "redirect target must NOT be contacted (token would leak)");
    } finally {
      redirector.stop(0);
    }
  }

  @Test
  void fetchSubtree_does_not_follow_a_redirect_so_the_private_token_is_never_replayed() throws Exception {
    // The archive fetch routes through the SAME send() as resolveSha and carries the identical
    // PRIVATE-TOKEN, so the redirect-never token-replay guard must hold for it too. A regression that
    // let ONLY fetchSubtree follow a 30x (e.g. a per-call redirect override) would replay the token to
    // the Location target — the resolveSha test above would not catch it, so pin fetchSubtree here.
    HttpServer redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    java.util.concurrent.atomic.AtomicBoolean targetHit = new java.util.concurrent.atomic.AtomicBoolean(false);
    redirector.createContext("/", ex -> {
      if (ex.getRequestURI().getRawPath().contains("/repository/archive.tar.gz")) {
        ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + ex.getLocalAddress().getPort() + "/stolen");
        ex.sendResponseHeaders(302, -1);
      } else {
        targetHit.set(true); // the redirect target (would have received the token) was contacted
        ex.sendResponseHeaders(200, -1);
      }
      ex.close();
    });
    redirector.start();
    String redirBase = "http://127.0.0.1:" + redirector.getAddress().getPort();
    try {
      GitLabSourceClient c = new GitLabSourceClient(HttpClient.newHttpClient(), redirBase, () -> "secret-token");
      GitLabException ex = assertThrows(GitLabException.class,
          () -> c.fetchSubtree("grp/repo", "0123456789abcdef0123456789abcdef01234567", "diagrams"));
      assertEquals(302, ex.status());
      assertTrue(!targetHit.get(), "redirect target must NOT be contacted (token would leak)");
    } finally {
      redirector.stop(0);
    }
  }

  @Test
  void rejects_an_unsanitised_project_at_the_http_boundary() {
    // Defence-in-depth: the SSRF/traversal guard must not live solely in SourceService. A direct
    // caller (a future tool/test) passing an unsanitised project must be rejected with
    // IllegalArgumentException BEFORE any HTTP request, not URL-encoded and sent (which would surface
    // as a GitLabException 404 from the server, proving the request went out).
    assertThrows(IllegalArgumentException.class, () -> client().resolveSha("grp/../../etc", "main"));
    assertThrows(IllegalArgumentException.class, () -> client().resolveSha("grp/repo", "bad ref!"));
  }

  @Test
  void maps_a_non_200_archive_response_to_GitLabException_with_status() {
    // The archive fetch can fail on its own (token lacking archive scope, a mid-incident 5xx) even after
    // the commit resolve succeeded. fetchSubtree must surface that as a GitLabException carrying the HTTP
    // status — and close the body — rather than hang or NPE. Only resolveSha's non-200 branch was pinned
    // before; the archive branch (GitLabSourceClient#fetchSubtree status != 200) was untested.
    GitLabException ex = assertThrows(GitLabException.class,
        () -> client().fetchSubtree("grp/repo", "ffffffffffffffffffffffffffffffffffffffff", "diagrams"));
    assertEquals(403, ex.status());
    assertTrue(ex.getMessage().contains("archive at"), "was: " + ex.getMessage());
  }

  @Test
  void fetchSubtree_rejects_a_non_hex_sha_before_any_request() {
    // The sha flows into the archive `sha=` URL param. fetchSubtree is public; a caller that bypasses
    // resolveSha (which guarantees a 40-hex sha) must still be rejected up front.
    assertThrows(IllegalArgumentException.class,
        () -> client().fetchSubtree("grp/repo", "not-a-sha", "diagrams"));
    assertThrows(IllegalArgumentException.class,
        () -> client().fetchSubtree("grp/../etc", "0123456789abcdef0123456789abcdef01234567", "diagrams"));
  }

  @Test
  void throws_a_clear_error_when_the_token_is_not_configured() {
    GitLabSourceClient noToken = new GitLabSourceClient(HttpClient.newHttpClient(), base, () -> null);
    // A GitLabConfigException (a subtype of IllegalStateException) so the shared breaker can tell this
    // local misconfiguration apart from a GitLab outage and NOT count it (see SourceService.COUNTS_AS_OUTAGE
    // and SourceServiceTest#a_missing_token_misconfiguration_does_not_trip_the_shared_breaker). Also fires
    // for a blank/whitespace token, not only null — a rotation that clears the field to "" must be caught.
    GitLabConfigException ex =
        assertThrows(GitLabConfigException.class, () -> noToken.resolveSha("grp/repo", "main"));
    assertTrue(ex.getMessage().contains("token is not configured"), "was: " + ex.getMessage());
    GitLabSourceClient blankToken = new GitLabSourceClient(HttpClient.newHttpClient(), base, () -> "   ");
    assertThrows(GitLabConfigException.class, () -> blankToken.resolveSha("grp/repo", "main"));
  }

  @Test
  void applies_the_per_request_timeout_when_the_server_never_responds() throws Exception {
    HttpServer hang = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch release = new CountDownLatch(1);
    hang.createContext("/", ex -> {
      try {
        release.await(2, TimeUnit.SECONDS); // hold the response open well past the client timeout
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      try {
        ex.sendResponseHeaders(200, 0);
      } catch (IOException ignored) {
        // client already gave up
      }
      ex.close();
    });
    hang.start();
    String hangBase = "http://127.0.0.1:" + hang.getAddress().getPort();
    try {
      GitLabSourceClient timed = new GitLabSourceClient(
          HttpClient.newHttpClient(), hangBase, () -> "secret-token", Duration.ofMillis(300));
      assertThrows(HttpTimeoutException.class, () -> timed.resolveSha("grp/repo", "main"));
    } finally {
      release.countDown();
      hang.stop(0);
    }
  }

  @Test
  void aborts_a_stalled_archive_body_at_the_download_deadline() throws Exception {
    // The request timeout governs only the response-HEADERS phase. A hostile/compromised GitLab can send
    // 200 headers promptly, then stall the streamed archive BODY indefinitely under the size caps —
    // pinning the request thread, the bundle single-flight lock and a breaker permit. The
    // DeadlineInputStream download budget must abort that. This pins the wiring END-TO-END through
    // fetchSubtree (DeadlineInputStreamTest covers the stream in isolation; nothing asserted that
    // fetchSubtree actually applies it), and the timing bound would catch a regression that dropped the
    // deadline wrapper (the body would then hang until the server released, ~5s, not ~300ms).
    HttpServer stall = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch release = new CountDownLatch(1);
    stall.createContext("/", ex -> {
      try {
        ex.sendResponseHeaders(200, 0); // chunked; headers sent at once so the header timeout won't fire
        OutputStream os = ex.getResponseBody();
        os.write(new byte[] {0x1f, (byte) 0x8b}); // a teaser byte or two, then go silent mid-stream
        os.flush();
        release.await(5, TimeUnit.SECONDS); // stall the body well past the ~300ms download deadline
      } catch (Exception ignored) {
        // client already gave up
      } finally {
        try { ex.close(); } catch (Exception ignored) { /* best-effort */ }
      }
    });
    stall.start();
    String stallBase = "http://127.0.0.1:" + stall.getAddress().getPort();
    try {
      GitLabSourceClient timed = new GitLabSourceClient(
          HttpClient.newHttpClient(), stallBase, () -> "secret-token",
          Duration.ofSeconds(30), Duration.ofMillis(300)); // long header timeout, short body budget
      long start = System.nanoTime();
      // Any IOException (the deadline message, or the watchdog's stream-close surfaced by the extractor)
      // proves the consumption was aborted rather than pinned.
      assertThrows(IOException.class, () -> timed.fetchSubtree(
          "grp/repo", "0123456789abcdef0123456789abcdef01234567", "diagrams"));
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertTrue(elapsedMs < 4000,
          "must abort at the ~300ms download deadline, not hang on the stalled body; took " + elapsedMs + "ms");
    } finally {
      release.countDown();
      stall.stop(0);
    }
  }

  @Test
  void aborts_a_stalled_commit_body_at_the_download_deadline() throws Exception {
    // resolveSha wraps the commit-JSON body in the SAME DeadlineInputStream download budget as the
    // archive body. A hostile/compromised GitLab can send 200 headers promptly then stall the (small)
    // commit body indefinitely under the 1 MiB size cap — pinning the request thread with the header
    // timeout already satisfied. Only fetchSubtree's equivalent was pinned end-to-end
    // (aborts_a_stalled_archive_body...); nothing asserted resolveSha applies the wrapper too, so a
    // regression dropping it on the commit path would have gone unnoticed.
    HttpServer stall = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch release = new CountDownLatch(1);
    stall.createContext("/", ex -> {
      try {
        ex.sendResponseHeaders(200, 0); // chunked; headers sent at once so the header timeout won't fire
        OutputStream os = ex.getResponseBody();
        os.write('{'); // a teaser byte, then go silent mid-body
        os.flush();
        release.await(5, TimeUnit.SECONDS); // stall the body well past the ~300ms download deadline
      } catch (Exception ignored) {
        // client already gave up
      } finally {
        try { ex.close(); } catch (Exception ignored) { /* best-effort */ }
      }
    });
    stall.start();
    String stallBase = "http://127.0.0.1:" + stall.getAddress().getPort();
    try {
      GitLabSourceClient timed = new GitLabSourceClient(
          HttpClient.newHttpClient(), stallBase, () -> "secret-token",
          Duration.ofSeconds(30), Duration.ofMillis(300)); // long header timeout, short body budget
      long start = System.nanoTime();
      // Any IOException (the deadline message, or the watchdog's stream-close surfaced by readBounded)
      // proves the commit-body consumption was aborted rather than pinned.
      assertThrows(IOException.class, () -> timed.resolveSha("grp/repo", "main"));
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertTrue(elapsedMs < 4000,
          "must abort at the ~300ms download deadline, not hang on the stalled commit body; took " + elapsedMs + "ms");
    } finally {
      release.countDown();
      stall.stop(0);
    }
  }
}
