package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GitLab CONTRACT test (design spec §14: "GitLab API/version drift — isolated behind
 * GitLabSourceClient + a recorded-response contract test").
 *
 * <p>Where {@link GitLabSourceClientTest} drives the client with a deliberately tiny,
 * hand-built mock (a 1-field commit JSON and a 2-entry tar), THIS test validates
 * {@link GitLabSourceClient} against fixtures that match GitLab's <em>actual documented
 * response shapes</em>, so that real-GitLab schema/layout drift would break the build:
 *
 * <ul>
 *   <li><b>contract/commit.json</b> — the full body of
 *       {@code GET /api/v4/projects/:id/repository/commits/:ref} (a single commit object
 *       with every documented field: {@code id, short_id, created_at, parent_ids, title,
 *       message, author_*, authored_date, committer_*, committed_date, web_url, stats,
 *       last_pipeline, ...}). Proves {@link GitLabSourceClient#resolveSha} extracts the
 *       40-hex {@code id} robustly despite all the surrounding fields.</li>
 *   <li><b>contract/archive.tar.gz</b> — a genuine gzip+tar produced by real GNU
 *       {@code tar czf}, shaped exactly like GitLab's {@code archive.tar.gz}: a single
 *       top directory {@code <project>-<ref>-<sha>/} with explicit directory entries,
 *       holding a realistic repo tree (LikeC4 sources under {@code diagrams/}, a
 *       {@code .likec4/*.likec4.snap} manual layout, a root-level {@code landscape.likec4},
 *       plus non-LikeC4 noise: {@code README.md}, {@code .gitlab-ci.yml}, {@code .env},
 *       {@code diagrams/notes.md}, {@code src/Main.java}). Proves the §8 file-type
 *       guarantee + repo-relative path stripping against a real archive layout.</li>
 * </ul>
 *
 * <p><b>Provenance (honest):</b> these are recorded-<em>SHAPE</em> fixtures — hand-authored
 * to match GitLab's documented REST API and its {@code git archive} output structure, not
 * a literal HTTP capture from a live instance. A real-GitLab capture would replace them
 * verbatim if/when one is available; the contract being asserted (commit schema + archive
 * layout) is identical either way.
 */
class GitLabContractTest {
  /** Must equal {@code id} in commit.json AND the {@code <sha>} in the archive's top dir. */
  private static final String SHA = "e3a1c2b4d5f60718293a4b5c6d7e8f9012345678";
  private static final String PROJECT = "platform/architecture";
  private static final String TOKEN = "glpat-CONTRACT-fixture-token";

  private HttpServer server;
  private String base;
  private volatile String lastToken;
  private volatile String lastQuery;

  private static byte[] resource(String path) throws IOException {
    try (InputStream in = GitLabContractTest.class.getResourceAsStream(path)) {
      assertNotNull(in, "missing test resource " + path);
      return in.readAllBytes();
    }
  }

  @BeforeEach
  void start() throws IOException {
    byte[] commitJson = resource("/contract/commit.json");
    byte[] archive = resource("/contract/archive.tar.gz");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      lastToken = ex.getRequestHeaders().getFirst("PRIVATE-TOKEN");
      lastQuery = ex.getRequestURI().getRawQuery();
      String raw = ex.getRequestURI().getRawPath();
      byte[] body;
      int status = 200;
      if (raw.contains("/repository/commits/")) {
        body = commitJson;
      } else if (raw.contains("/repository/archive.tar.gz")) {
        body = archive;
      } else {
        status = 404;
        body = "{}".getBytes();
      }
      ex.sendResponseHeaders(status, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private GitLabSourceClient client() {
    return new GitLabSourceClient(HttpClient.newHttpClient(), base, () -> TOKEN);
  }

  /** §14: resolveSha pulls the 40-hex commit id out of the FULL realistic commit object. */
  @Test
  void resolveSha_extracts_id_from_full_commit_object_and_sends_token() throws Exception {
    String sha = client().resolveSha(PROJECT, "main");

    assertEquals(SHA, sha);
    assertTrue(sha.matches("[0-9a-f]{40}"), "expected a 40-hex sha, got: " + sha);
    assertEquals(TOKEN, lastToken, "PRIVATE-TOKEN header must be sent on the commits call");
  }

  /**
   * §8 + §14: fetchSubtree("diagrams") returns ONLY the LikeC4 files inside diagrams/,
   * with the diagrams/ prefix stripped, and drops every non-LikeC4 file plus the
   * root-level landscape.likec4 that lives outside the requested subtree.
   */
  @Test
  void fetchSubtree_diagrams_returns_only_likec4_with_stripped_paths() throws Exception {
    SourceBundle bundle = client().fetchSubtree(PROJECT, SHA, "diagrams");

    assertEquals(
        Set.of("spec.likec4", "model.likec4", "views.likec4", ".likec4/index.likec4.snap"),
        bundle.files().keySet());
    assertEquals(SHA, bundle.sha());

    // The path prefix is sent to GitLab and the token travels with the archive call too.
    assertTrue(lastQuery != null && lastQuery.contains("path=diagrams"),
        "archive request must carry path=diagrams, was: " + lastQuery);
    assertEquals(TOKEN, lastToken);

    // Real content survives the round-trip (not just the path map).
    assertTrue(bundle.files().get("spec.likec4").contains("specification"));
    assertTrue(bundle.files().get(".likec4/index.likec4.snap").contains("\"_stage\":\"layouted\""));

    // None of the realistic noise leaks through.
    for (String dropped : new String[] {
        "README.md", ".gitlab-ci.yml", ".env", "src/Main.java", "notes.md",
        "diagrams/notes.md", "landscape.likec4"}) {
      assertFalse(bundle.files().containsKey(dropped), "must drop " + dropped);
    }
  }

  /**
   * §8 + §14: a whole-repo request (path="") keeps every LikeC4 file ACROSS the tree
   * with full repo-relative paths, and still drops all non-LikeC4 files.
   */
  @Test
  void fetchSubtree_wholeRepo_returns_all_likec4_across_the_tree() throws Exception {
    SourceBundle bundle = client().fetchSubtree(PROJECT, SHA, "");

    assertEquals(
        Set.of(
            "landscape.likec4",
            "diagrams/spec.likec4",
            "diagrams/model.likec4",
            "diagrams/views.likec4",
            "diagrams/.likec4/index.likec4.snap"),
        bundle.files().keySet());
    assertEquals(SHA, bundle.sha());

    // A whole-repo request must send NO path= filter to GitLab (it would otherwise scope the archive to
    // an empty subtree). Only sha= is expected — mirrors the real-data sibling's assertion.
    assertTrue(lastQuery != null && !lastQuery.contains("path="),
        "whole-repo (path=\"\") must not send a path= param, was: " + lastQuery);

    // Real content survives the round-trip across the tree, not just the path map.
    assertTrue(bundle.files().get("diagrams/spec.likec4").contains("specification"),
        "kept LikeC4 content must survive the whole-repo round-trip");

    for (String dropped : new String[] {
        "README.md", ".gitlab-ci.yml", ".env", "src/Main.java", "diagrams/notes.md"}) {
      assertFalse(bundle.files().containsKey(dropped), "must drop " + dropped);
    }
  }
}
