package com.phrontizo.likec4.source;

/** The ONLY files ever fetched/delivered: LikeC4 source and manual-layout snapshots. */
public final class LikeC4FileFilter {
  private LikeC4FileFilter() {}

  /**
   * Whether {@code name} is a LikeC4 source / snapshot, by suffix only. A slash-terminated (directory)
   * name can never match — none of the kept suffixes ends in {@code /} — so this is safe in isolation;
   * but it cannot tell a <em>directory entry with a file-like name and no trailing slash</em> from a
   * real file, which is inherently impossible from the name alone. Callers that consume an archive
   * (see {@link GitLabArchiveExtractor}) must therefore exclude directory/symlink/hard-link entries
   * via the entry metadata before consulting {@code keep}.
   *
   * <p>Matching is deliberately <strong>case-sensitive</strong>: LikeC4's file-naming convention is
   * lowercase extensions and GitLab archive paths are case-sensitive, so an upper/mixed-case name such
   * as {@code MODEL.C4} is dropped by design (rather than silently rendering a file that would not exist
   * under a case-sensitive checkout).
   */
  public static boolean keep(String name) {
    if (name == null) return false;                                   // malformed archive entry
    return name.endsWith(".c4") || name.endsWith(".likec4") || name.endsWith(".likec4.snap");
  }
}
