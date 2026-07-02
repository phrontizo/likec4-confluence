package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * GitLab REAL contract test (design spec §14: "GitLab API/version drift — isolated behind
 * GitLabSourceClient + a recorded-response contract test"), residual 7.
 *
 * <p>Where {@link GitLabContractTest} drives the client against hand-authored, recorded-<em>SHAPE</em>
 * fixtures, THIS test drives {@link GitLabSourceClient} against fixtures that are GENUINE,
 * byte-for-byte HTTP response bodies captured from the canonical public GitLab API at
 * gitlab.com (see {@code src/test/resources/realgitlab/PROVENANCE.txt}). gitlab.com runs the
 * same GitLab code as a self-managed instance, so these REST + git-archive responses are
 * <b>byte-identical</b> to what a self-managed GitLab returns — real GitLab schema/format
 * drift would break this build.
 *
 * <ul>
 *   <li><b>realgitlab/commits.json</b> — the real body of
 *       {@code GET /api/v4/projects/dslackw%2Fcolored/repository/commits/master}, a full
 *       commit object with every real field ({@code id, short_id, created_at, parent_ids,
 *       title, message, author_*, authored_date, committer_*, committed_date, web_url, stats,
 *       last_pipeline, trailers, ...}). Pinned {@code id} = {@value #SHA}.</li>
 *   <li><b>realgitlab/archive.tar.gz</b> — the real {@code archive.tar.gz} for that commit:
 *       a real gzip+tar whose single top dir is {@code colored-<ref>-<sha>/} (ref==sha here),
 *       holding 50 real blobs across nested dirs ({@code colored/}, {@code tests/},
 *       {@code tests_pytest/}, {@code unittests/}) plus root files.</li>
 * </ul>
 *
 * <p><b>Honest scope.</b> The goal was a public gitlab.com repo that contains LikeC4 files so
 * the §8 file-type filter is exercised on the <i>keep</i> side against real bytes. A genuine,
 * exhaustive unauthenticated search of gitlab.com (name search, {@code topic=} c4/likec4,
 * ~20 recursive tree probes, the LikeC4 author's account, the {@code likec4} group; the only
 * content search {@code /search?scope=blobs} requires auth — 401) found none: LikeC4's
 * ecosystem lives on GitHub. So this is the spec-sanctioned FALLBACK — a small, stable,
 * generic public repo ({@code dslackw/colored}) — and the contract asserted against the real
 * bytes is: (1) the real commits-JSON parse, (2) the real archive's gzip+tar extraction with
 * the {@code <proj>-<sha>-<sha>/} top dir stripped and repo-relative nested paths exact (whole
 * repo and a {@code path=} subtree), and (3) the §8 filter correctly DROPPING every one of the
 * 50 real non-LikeC4 files (keep-set empty) — the drop side mutation-checked non-vacuous.
 */
class GitLabRealContractTest {

  /** The pinned 40-hex {@code id} in realgitlab/commits.json AND the {@code <sha>} in the archive top dir. */
  private static final String SHA = "f3d799873a7cde62f3c43d74ed6ffc258240d956";

  private static final String PROJECT = "dslackw/colored";
  private static final String TOKEN = "glpat-REAL-fixture-token";

  /** GitLab names the archive top dir {@code <project>-<ref>-<sha>}; recorded with ref==sha. */
  private static final String TOP_DIR = "colored-" + SHA + "-" + SHA + "/";

  /** The exact 50 real repo-relative blob paths (whole repo, top dir stripped). */
  private static final Set<String> WHOLE_REPO = Set.of(
      ".gitignore", ".gitlab-ci.yml", ".pre-commit-config.yaml",
      "CHANGES.md", "CONTRIBUTION.md", "LICENSE.txt", "MANIFEST.in",
      "README.md", "pyproject.toml", "setup.cfg",
      "colored/__init__.py", "colored/attributes.py", "colored/background.py",
      "colored/colored.py", "colored/controls.py", "colored/convert.py",
      "colored/cprint.py", "colored/exceptions.py", "colored/foreground.py",
      "colored/hexadecimal.py", "colored/library.py", "colored/py.typed",
      "colored/utilities.py",
      "tests/__init__.py", "tests/test_1.py", "tests/test_2.py", "tests/test_3.py",
      "tests/test_4.py", "tests/test_controls.py", "tests/test_convert.py",
      "tests/test_cprint.py", "tests/test_hex_1.py", "tests/test_hex_2.py",
      "tests/test_rgb_1.py", "tests/test_rgb_2.py",
      "tests_pytest/__init__.py", "tests_pytest/conftest.py",
      "tests_pytest/test_background.py", "tests_pytest/test_colored.py",
      "tests_pytest/test_controls.py", "tests_pytest/test_convert.py",
      "tests_pytest/test_cprint.py", "tests_pytest/test_exceptions.py",
      "tests_pytest/test_foreground.py", "tests_pytest/test_hex.py",
      "tests_pytest/test_styles.py",
      "unittests/test_background.py", "unittests/test_exceptions.py",
      "unittests/test_foreground.py", "unittests/test_styles.py");

  /** The exact 13 real files under {@code colored/} with the {@code colored/} prefix stripped. */
  private static final Set<String> COLORED_SUBTREE = Set.of(
      "__init__.py", "attributes.py", "background.py", "colored.py", "controls.py",
      "convert.py", "cprint.py", "exceptions.py", "foreground.py", "hexadecimal.py",
      "library.py", "py.typed", "utilities.py");

  private HttpServer server;
  private String base;
  private volatile String lastToken;
  private volatile String lastQuery;

  private static byte[] resource(String path) throws IOException {
    try (InputStream in = GitLabRealContractTest.class.getResourceAsStream(path)) {
      assertNotNull(in, "missing test resource " + path);
      return in.readAllBytes();
    }
  }

  @BeforeEach
  void start() throws IOException {
    byte[] commitJson = resource("/realgitlab/commits.json");
    byte[] archive = resource("/realgitlab/archive.tar.gz");
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

  /**
   * Lists the REAL committed archive's repo-relative blob paths, scoped to {@code prefix},
   * using the same gzip/tar + {@link PathSafety} pipeline as {@link GitLabArchiveExtractor}.
   * Proves "top dir stripped + repo-relative paths correct" against real bytes <em>before</em>
   * the §8 filter empties the end-to-end result for this LikeC4-free repo.
   */
  private static Set<String> realRepoRelativePaths(String prefix) throws IOException {
    Set<String> out = new LinkedHashSet<>();
    byte[] archive = resource("/realgitlab/archive.tar.gz");
    try (TarArchiveInputStream tar =
        new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(archive)))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (entry.isDirectory()) continue;
        Optional<String> rel = PathSafety.safeRelative(entry.getName(), prefix);
        rel.ifPresent(out::add);
      }
    }
    return out;
  }

  /** The raw name of the first entry in the real archive (the unstripped top directory). */
  private static String firstEntryName() throws IOException {
    byte[] archive = resource("/realgitlab/archive.tar.gz");
    try (TarArchiveInputStream tar =
        new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(archive)))) {
      TarArchiveEntry first = tar.getNextEntry();
      assertNotNull(first, "real archive must not be empty");
      return first.getName();
    }
  }

  /** §14: resolveSha pulls the pinned 40-hex commit id out of the FULL real commit object. */
  @Test
  void resolveSha_extracts_pinned_id_from_real_commit_json_and_sends_token() throws Exception {
    String sha = client().resolveSha(PROJECT, "master");

    assertEquals(SHA, sha);
    assertTrue(sha.matches("[0-9a-f]{40}"), "expected a 40-hex sha, got: " + sha);
    assertEquals(TOKEN, lastToken, "PRIVATE-TOKEN header must be sent on the commits call");
  }

  /**
   * Real archive extraction: the genuine GitLab {@code <proj>-<sha>-<sha>/} top dir is stripped
   * and the 50 repo-relative nested paths are exact (whole repo), and the {@code colored/}
   * subtree scoping strips its prefix — asserted against real bytes via the production
   * {@link PathSafety}. Non-vacuous: exact 50- and 13-element sets.
   */
  @Test
  void real_archive_strips_top_dir_and_yields_exact_repo_relative_paths() throws Exception {
    // The real GitLab archive's single top dir is "<project>-<ref>-<sha>/" (ref==sha here).
    assertEquals(TOP_DIR, firstEntryName(), "real GitLab archive top dir");

    Set<String> whole = realRepoRelativePaths("");
    assertEquals(WHOLE_REPO, whole, "real archive must strip the top dir and keep repo-relative paths");
    assertEquals(50, whole.size());
    // No path leaks the real top dir or the sha, and none escapes via traversal.
    for (String p : whole) {
      assertFalse(p.startsWith("colored-"), "top dir must be stripped: " + p);
      assertFalse(p.contains(SHA), "sha must not leak into a repo-relative path: " + p);
      assertFalse(p.contains(".."), "no traversal: " + p);
    }

    assertEquals(COLORED_SUBTREE, realRepoRelativePaths("colored"),
        "path=colored must scope to colored/ and strip the prefix");
  }

  /**
   * §8 + §14, end-to-end through {@link GitLabSourceClient}: fetching the whole real repo
   * returns the recorded sha and an EMPTY file map — the §8 filter drops every one of the 50
   * real non-LikeC4 files. Mutation-checked non-vacuous: all 50 real files really existed in
   * the archive and every one is dropped; the token travels and no path= is sent.
   */
  @Test
  void fetchSubtree_wholeRepo_real_archive_filter_drops_all_non_likec4() throws Exception {
    SourceBundle bundle = client().fetchSubtree(PROJECT, SHA, "");

    assertEquals(SHA, bundle.sha());
    assertTrue(bundle.files().isEmpty(),
        "dslackw/colored has no .c4/.likec4/.snap — the §8 filter must drop everything");

    // Non-vacuous: the 50 real files genuinely existed (top dir stripped) and every one is dropped.
    Set<String> realFiles = realRepoRelativePaths("");
    assertEquals(50, realFiles.size(), "the recorded archive must really hold 50 droppable files");
    for (String dropped : realFiles) {
      assertFalse(bundle.files().containsKey(dropped), "the §8 filter must drop " + dropped);
    }

    assertEquals(TOKEN, lastToken, "PRIVATE-TOKEN header must travel with the archive call");
    assertFalse(lastQuery != null && lastQuery.contains("path="),
        "whole-repo fetch must not send a path= scope, was: " + lastQuery);
  }

  /**
   * §8 + §14, end-to-end: a {@code path=colored} fetch sends the scope to GitLab and the
   * extractor scopes to the colored/ subtree; the §8 filter still drops all 13 real .py files,
   * so the bundle is empty. Proves path= scoping + token on the real archive call.
   */
  @Test
  void fetchSubtree_subdir_real_archive_sends_path_scope_and_filters_out_python() throws Exception {
    SourceBundle bundle = client().fetchSubtree(PROJECT, SHA, "colored");

    assertEquals(SHA, bundle.sha());
    assertTrue(bundle.files().isEmpty(), "colored/ holds only .py files — all dropped by the §8 filter");

    // The real colored/ subtree was non-empty (13 files) and every one was dropped.
    assertEquals(13, realRepoRelativePaths("colored").size());

    assertTrue(lastQuery != null && lastQuery.contains("path=colored"),
        "archive request must carry path=colored, was: " + lastQuery);
    assertEquals(TOKEN, lastToken);
  }
}
