package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LikeC4FileFilterTest {
  @Test
  void keeps_only_c4_likec4_and_snapshots() {
    assertTrue(LikeC4FileFilter.keep("model.c4"));
    assertTrue(LikeC4FileFilter.keep("model.likec4"));
    assertTrue(LikeC4FileFilter.keep("index.likec4.snap"));
    assertTrue(LikeC4FileFilter.keep(".likec4/index.likec4.snap"));
  }

  @Test
  void drops_everything_else_including_secrets_and_bare_snap() {
    assertFalse(LikeC4FileFilter.keep("secrets.env"));
    assertFalse(LikeC4FileFilter.keep(".env"));
    assertFalse(LikeC4FileFilter.keep("README.md"));
    assertFalse(LikeC4FileFilter.keep(".gitlab-ci.yml"));
    assertFalse(LikeC4FileFilter.keep("layout.snap")); // bare .snap, not .likec4.snap
    assertFalse(LikeC4FileFilter.keep("model.c4.bak"));
  }

  @Test
  void keeps_a_bare_suffix_dotfile_by_the_suffix_only_contract() {
    // A nameless dotfile whose WHOLE name is a kept suffix (".c4", ".likec4", ".likec4.snap") matches by
    // the deliberate suffix-only contract. This is benign: such an entry is inert LikeC4 source content
    // and never occurs in a real repo, and the actual safety layers are PathSafety (traversal) and the
    // extractor's directory/symlink/hard-link metadata checks — NOT this by-name filter. Pinned so a
    // future refactor can't silently flip the contract to require a base name (or vice-versa) unnoticed.
    assertTrue(LikeC4FileFilter.keep(".c4"));
    assertTrue(LikeC4FileFilter.keep(".likec4"));
    assertTrue(LikeC4FileFilter.keep(".likec4.snap"));
  }

  @Test
  void drops_upper_and_mixed_case_extensions_by_the_case_sensitive_contract() {
    // Pins the deliberate case-sensitivity: LikeC4 uses lowercase extensions and GitLab paths are
    // case-sensitive, so an upper/mixed-case suffix is dropped (rendering it would reference a file that
    // does not exist under a case-sensitive checkout). Guards against a refactor to endsWithIgnoreCase.
    assertFalse(LikeC4FileFilter.keep("MODEL.C4"));
    assertFalse(LikeC4FileFilter.keep("model.C4"));
    assertFalse(LikeC4FileFilter.keep("Model.LikeC4"));
    assertFalse(LikeC4FileFilter.keep("index.LIKEC4.SNAP"));
  }

  @Test
  void drops_a_null_name() {
    // Defensive: a malformed archive entry can yield a null name; keep() must drop it (false), never NPE.
    assertFalse(LikeC4FileFilter.keep(null));
  }

  @Test
  void drops_slash_terminated_directory_like_names() {
    // Pins the suffix-only contract: a directory entry whose name ends in '/' never matches, so the
    // filter is safe even if a caller forgot to exclude directory entries by metadata first.
    assertFalse(LikeC4FileFilter.keep("model.c4/"));
    assertFalse(LikeC4FileFilter.keep(".likec4/"));
    assertFalse(LikeC4FileFilter.keep("diagrams.likec4/"));
  }
}
