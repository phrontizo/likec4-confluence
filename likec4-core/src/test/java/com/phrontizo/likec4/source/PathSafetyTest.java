package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PathSafetyTest {
  @Test
  void strips_top_dir_and_path_prefix() {
    assertEquals(Optional.of("model.likec4"),
        PathSafety.safeRelative("myrepo-main-abc123/diagrams/model.likec4", "diagrams"));
    assertEquals(Optional.of(".likec4/index.likec4.snap"),
        PathSafety.safeRelative("myrepo-main-abc123/diagrams/.likec4/index.likec4.snap", "diagrams"));
  }

  @Test
  void empty_prefix_keeps_full_subpath() {
    assertEquals(Optional.of("model.likec4"),
        PathSafety.safeRelative("myrepo-main-abc/model.likec4", ""));
    assertEquals(Optional.of("model.likec4"),
        PathSafety.safeRelative("myrepo-main-abc/model.likec4", null));
  }

  @Test
  void returns_empty_for_a_null_entry_name() {
    // A malformed/exotic tar header can in principle yield a null entry name; safeRelative must drop it
    // (Optional.empty) rather than NPE, so GitLabArchiveExtractor raises a clean IOException — not an
    // uncaught raw NPE — for a hostile archive, consistent with how every other bad entry is handled.
    assertTrue(PathSafety.safeRelative(null, "").isEmpty());
    assertTrue(PathSafety.safeRelative(null, "diagrams").isEmpty());
  }

  @Test
  void drops_entries_with_a_non_canonical_single_dot_segment() {
    // A "." path segment is non-canonical (real git archives never emit one). Drop such an entry rather
    // than return a non-canonical key like "./model.likec4" to the downstream bundle map — consistent
    // with how a ".." segment is handled. A "." before the requested prefix would also misalign the
    // subtree match, so it must never survive.
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/./model.likec4", "").isEmpty());
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/diagrams/./model.likec4", "diagrams").isEmpty());
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/./diagrams/model.likec4", "diagrams").isEmpty());
  }

  @Test
  void drops_entries_with_an_empty_doubled_slash_segment() {
    // An interior "" segment produced by a doubled slash ("//") is non-canonical exactly like a "." or
    // ".." segment (real git archives never emit one). Left unchecked it survives the segment loop and
    // yields a non-canonical key such as "/model.likec4" or "sub//x.likec4", breaking the class's
    // "safe project-relative POSIX path" contract; such a key would then flow verbatim to the browser's
    // virtual FS. Drop the entry rather than return a non-canonical path — consistent with "."/".."
    // handling — so only a crafted/MITM'd archive is affected and it is dropped, not mis-keyed.
    assertTrue(PathSafety.safeRelative("myrepo-main-abc//model.likec4", "").isEmpty());
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/diagrams//model.likec4", "diagrams").isEmpty());
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/diagrams/sub//x.likec4", "diagrams").isEmpty());
    assertTrue(PathSafety.safeRelative("myrepo-main-abc//diagrams/model.likec4", "diagrams").isEmpty());
  }

  @Test
  void drops_entries_with_a_control_character_anywhere_in_the_name() {
    // A crafted/MITM'd tar entry name carrying a NUL, newline, CR or other control char is not a "safe
    // project-relative POSIX path" (the class's contract): drop it rather than let the control char flow
    // verbatim into the SourceBundle map key (and thence the browser's virtual-FS path), consistent with
    // how InputValidation rejects control chars in the ref/path request params. Legitimate names (spaces,
    // unicode, punctuation) are unaffected — only C0 controls and DEL are dropped. (Chars built via
    // (char) casts so no literal control byte lives in this source.)
    String top = "myrepo-main-abc/";
    assertTrue(PathSafety.safeRelative(top + "da" + (char) 0x0A + "ta.likec4", "").isEmpty()); // LF
    assertTrue(PathSafety.safeRelative(top + "da" + (char) 0x0D + "ta.likec4", "").isEmpty()); // CR
    assertTrue(PathSafety.safeRelative(top + "da" + (char) 0x00 + "ta.likec4", "").isEmpty()); // NUL
    assertTrue(PathSafety.safeRelative(top + "da" + (char) 0x09 + "ta.likec4", "").isEmpty()); // TAB
    assertTrue(PathSafety.safeRelative(top + "da" + (char) 0x7F + "ta.likec4", "").isEmpty()); // DEL
    assertTrue(PathSafety.safeRelative(top + "diagrams/x" + (char) 0x01 + ".likec4", "diagrams").isEmpty());
    // A legitimate name with a space and a unicode letter is still kept (the check targets controls only).
    assertEquals(Optional.of("my modél.likec4"),
        PathSafety.safeRelative(top + "my modél.likec4", ""));
  }

  @Test
  void drops_entries_with_a_c1_control_character_anywhere_in_the_name() {
    // The C1 control block (U+0080..U+009F — e.g. U+0085 NEL, U+009F APC) is control, not printable, and
    // never part of a safe POSIX path — yet the narrow "c < 0x20 || c == 0x7f" screen used to let it
    // through, so a crafted/MITM'd entry name carrying one would flow verbatim into the SourceBundle map
    // key (and thence the browser's virtual-FS path). Align with InputValidation.describe()'s
    // Character.isISOControl notion of "control char" and drop the whole entry, exactly like a C0 control.
    // (Chars built via (char) casts so no literal control byte lives in this source.)
    String top = "myrepo-main-abc/";
    assertTrue(PathSafety.safeRelative(top + "da" + (char) 0x85 + "ta.likec4", "").isEmpty()); // NEL
    assertTrue(PathSafety.safeRelative(top + "da" + (char) 0x80 + "ta.likec4", "").isEmpty()); // PAD (C1 low)
    assertTrue(PathSafety.safeRelative(top + "da" + (char) 0x9F + "ta.likec4", "").isEmpty()); // APC (C1 high)
    assertTrue(PathSafety.safeRelative(top + "diagrams/x" + (char) 0x85 + ".likec4", "diagrams").isEmpty());
    // The boundary just above the C1 block (U+00A0 NBSP, U+00E9 é) is NOT a control char and is kept.
    assertEquals(Optional.of("da" + (char) 0xA0 + "ta.likec4"),
        PathSafety.safeRelative(top + "da" + (char) 0xA0 + "ta.likec4", ""));
  }

  @Test
  void drops_a_sibling_directory_whose_name_merely_shares_the_requested_prefix() {
    // The subtree match uses `afterTop.startsWith(prefix + "/")` (line 27), NOT a bare
    // startsWith(prefix): a SIBLING directory whose name merely BEGINS with the requested prefix —
    // "diagrams-secret" / "diagramsX" for a "diagrams" request — is outside the subtree and must be
    // dropped. Pins that boundary against a regression to a bare startsWith(prefix), which would leak
    // "diagrams-secret/*.likec4" into a "diagrams" fetch (a cross-directory information disclosure).
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/diagrams-secret/leak.likec4", "diagrams").isEmpty());
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/diagramsX/leak.likec4", "diagrams").isEmpty());
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/diagrams.bak/leak.likec4", "diagrams").isEmpty());
    // The exact requested subtree (and a nested file under it) is still kept.
    assertEquals(Optional.of("model.likec4"),
        PathSafety.safeRelative("myrepo-main-abc/diagrams/model.likec4", "diagrams"));
    assertEquals(Optional.of("sub/model.likec4"),
        PathSafety.safeRelative("myrepo-main-abc/diagrams/sub/model.likec4", "diagrams"));
  }

  @Test
  void rejects_traversal_absolute_dirs_and_outside_subtree() {
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/../etc/passwd", "").isEmpty());
    assertTrue(PathSafety.safeRelative("/etc/passwd", "").isEmpty());
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/diagrams/", "diagrams").isEmpty()); // the dir itself
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/other/x.likec4", "diagrams").isEmpty()); // outside subtree
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/diagrams/sub/", "diagrams").isEmpty()); // nested dir
  }

  @Test
  void rejects_backslash_traversal_and_a_backslash_absolute_path() {
    // safeRelative normalises backslashes to forward slashes BEFORE the traversal/absolute checks, so a
    // Windows-style separator can't smuggle a `..` segment or an absolute path past them. A crafted tar
    // header (real git archives use POSIX '/' separators) is the only source of such names; each must be
    // dropped, not resolved. Pins that normalise-then-check ordering against a regression that moved the
    // segment checks before the replace('\\','/').
    assertTrue(PathSafety.safeRelative("myrepo-main-abc\\..\\..\\etc\\passwd", "").isEmpty());   // backslash ".." traversal
    assertTrue(PathSafety.safeRelative("myrepo-main-abc/sub\\..\\..\\etc", "").isEmpty());       // mixed separators
    assertTrue(PathSafety.safeRelative("\\etc\\passwd", "").isEmpty());                          // backslash-absolute
    // A backslash inside an otherwise-legal name still normalises to a '/' separator, so it splits into
    // clean segments and is kept (no traversal / absolute / control char) — the top dir is stripped.
    assertEquals(Optional.of("sub/model.likec4"),
        PathSafety.safeRelative("myrepo-main-abc\\sub\\model.likec4", ""));
  }
}
