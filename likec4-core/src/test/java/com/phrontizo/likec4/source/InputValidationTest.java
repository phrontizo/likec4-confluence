package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class InputValidationTest {
  @Test
  void accepts_normal_refs_and_paths() {
    assertEquals("main", InputValidation.sanitizeRef("main"));
    assertEquals("v1.2.3", InputValidation.sanitizeRef("v1.2.3"));
    assertEquals("feature/x", InputValidation.sanitizeRef("feature/x"));
    assertEquals("diagrams/c4", InputValidation.sanitizePath("/diagrams/c4/"));
    assertEquals("", InputValidation.sanitizePath(null));
    assertEquals("", InputValidation.sanitizePath("  "));
  }

  @Test
  void accepts_single_dots_but_the_double_dot_guard_still_rejects_them() {
    // The traversal guard rejects any ".." SUBSTRING, not a lone "." — pin that boundary. A single dot
    // and a "./"-containing ref (no consecutive dots) are valid git refs and must pass; anything with a
    // ".." (incl. "..." and "a..b") is rejected even though it matches the charset.
    assertEquals(".", InputValidation.sanitizeRef("."));
    assertEquals("a/./b", InputValidation.sanitizeRef("a/./b"));
    assertEquals("release-1.0", InputValidation.sanitizeRef("release-1.0"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef(".."));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef("..."));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef("a..b"));
  }

  @Test
  void rejects_traversal_and_injection() {
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef("../etc"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef("a; rm -rf /"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef("a\nb"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef(""));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizePath("../../secret"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizePath("a b"));
  }

  @Test
  void rejects_a_leading_dash_as_defence_in_depth() {
    // A real git ref / GitLab path never begins with '-'; reject it so such a value can never be
    // mistaken for an option flag by any downstream tool. (Interior dashes remain valid.)
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeRef("-x"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeProject("-group/repo"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizePath("-rf"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeView("-x"));
    // Interior dashes are still fine.
    assertEquals("feature-x", InputValidation.sanitizeRef("feature-x"));
    assertEquals("a.b-c_d/repo.git", InputValidation.sanitizeProject("a.b-c_d/repo.git"));
  }

  @Test
  void accepts_normal_project_paths() {
    assertEquals("group/repo", InputValidation.sanitizeProject("group/repo"));
    assertEquals("group/sub/repo", InputValidation.sanitizeProject("group/sub/repo"));
    assertEquals("a.b-c_d/repo.git", InputValidation.sanitizeProject("a.b-c_d/repo.git"));
    assertEquals("group/repo", InputValidation.sanitizeProject("  group/repo  ")); // trimmed
    // A leading/trailing slash is normalised away, mirroring sanitizePath — otherwise "group/repo/"
    // survives the charset/.. checks, URL-encodes to a different GitLab project id (404) AND fragments
    // the ref/bundle cache keys ("group/repo" vs "group/repo/" are two keys for the same repo).
    assertEquals("group/repo", InputValidation.sanitizeProject("group/repo/"));   // trailing slash
    assertEquals("group/repo", InputValidation.sanitizeProject("/group/repo"));   // leading slash
    assertEquals("group/repo", InputValidation.sanitizeProject(" /group/repo/ ")); // both + whitespace
  }

  @Test
  void rejects_bad_project_paths() {
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeProject(null));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeProject(""));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeProject("   "));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeProject("../etc"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeProject("group/../secret"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeProject("group\nrepo"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeProject("gr\u0000oup/repo"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeProject("group repo"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeProject("group/re;po"));
  }

  @Test
  void sanitizes_optional_view_and_instance_ids() {
    assertEquals(null, InputValidation.sanitizeView(null));
    assertEquals(null, InputValidation.sanitizeView("  "));
    assertEquals("index", InputValidation.sanitizeView("  index  "));
    assertEquals("cloud.next", InputValidation.sanitizeInstance("cloud.next"));
    // control chars, whitespace, markup and traversal are rejected
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeView("a b"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeView("\"><script>"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeInstance("../secret"));
  }

  @Test
  void sanitizes_allowlist_entries_as_group_or_project_prefix_paths() {
    // An allowlist entry is a group / subgroup / project PREFIX path — same charset + traversal guards as
    // a project id, with surrounding slashes folded. Valid prefixes pass and are normalised; malformed
    // ones are rejected so the admin write path never persists an inert (never-matching) entry.
    assertEquals("platform", InputValidation.sanitizeAllowlistEntry("platform"));
    assertEquals("platform/architecture", InputValidation.sanitizeAllowlistEntry("platform/architecture"));
    assertEquals("a.b-c_d/repo.git", InputValidation.sanitizeAllowlistEntry("a.b-c_d/repo.git"));
    assertEquals("group/sub", InputValidation.sanitizeAllowlistEntry(" /group/sub/ ")); // slashes + ws folded
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeAllowlistEntry(null));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeAllowlistEntry("  "));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeAllowlistEntry("../secret"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeAllowlistEntry("-group"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeAllowlistEntry("group repo"));
    assertThrows(IllegalArgumentException.class, () -> InputValidation.sanitizeAllowlistEntry("grp\u0000/repo"));
  }

  @Test
  void a_rejection_message_never_echoes_a_raw_control_character() {
    // The rejection messages echo the offending value for diagnostics. This class is public API and a
    // direct caller may log getMessage(), so a control char (newline/CR/tab/NUL) that a hostile value
    // smuggles in must be neutralised, never reach the message verbatim -- otherwise it could forge a
    // spurious log line. Cover every sanitiser and every common control char.
    record Sanitiser(String label, Consumer<String> call) {}
    Sanitiser[] sanitisers = {
      new Sanitiser("ref", InputValidation::sanitizeRef),
      new Sanitiser("project", InputValidation::sanitizeProject),
      new Sanitiser("path", InputValidation::sanitizePath),
      new Sanitiser("view", InputValidation::sanitizeView),
      new Sanitiser("instance", InputValidation::sanitizeInstance),
      new Sanitiser("allowlist entry", InputValidation::sanitizeAllowlistEntry),
    };
    for (Sanitiser s : sanitisers) {
      for (String bad : new String[] {"a\nb", "a\r\nb", "a\tb", "a b"}) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> s.call().accept(bad), s.label() + " must reject a control-bearing value");
        String msg = ex.getMessage();
        for (int i = 0; i < msg.length(); i++) {
          assertFalse(Character.isISOControl(msg.charAt(i)),
              s.label() + " message must not echo a raw control char; was: "
                  + msg.replaceAll("\\p{Cntrl}", "?"));
        }
      }
    }
  }

  @Test
  void a_rejection_message_never_ends_in_a_split_surrogate_pair() {
    // describe() truncates at 120 CHARS (UTF-16 code units). A supplementary character (a surrogate PAIR)
    // straddling that boundary must not be split, or the message would carry a lone unpaired high surrogate
    // before the ellipsis -- an ill-formed UTF-16 diagnostic that renders as U+FFFD and can corrupt a log
    // sink. Build a value whose char index 119 is a high surrogate and 120 its low surrogate, off-charset
    // so the ref is rejected and describe() runs. The emitted message must be well-formed UTF-16.
    String astral = "😀"; // U+1F600, a supplementary char == one high + one low surrogate
    String value = "a".repeat(119) + astral + "x"; // len 122; charAt(119)=high, charAt(120)=low
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> InputValidation.sanitizeRef(value));
    String msg = ex.getMessage();
    for (int i = 0; i < msg.length(); i++) {
      char c = msg.charAt(i);
      if (Character.isHighSurrogate(c))
        assertFalse(i + 1 >= msg.length() || !Character.isLowSurrogate(msg.charAt(i + 1)),
            "message has a lone/unpaired high surrogate at " + i);
      if (Character.isLowSurrogate(c))
        assertFalse(i == 0 || !Character.isHighSurrogate(msg.charAt(i - 1)),
            "message has a lone/unpaired low surrogate at " + i);
    }
  }

  @Test
  void a_rejection_message_is_length_bounded_so_a_huge_value_cannot_bloat_a_log() {
    // A pathologically long rejected value must be truncated (with an ellipsis) in the message rather
    // than echoed whole -- a direct caller logging getMessage() must not be handed an unbounded string.
    String huge = "z".repeat(10_000);
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> InputValidation.sanitizeRef(huge + "!")); // trailing '!' is off-charset -> rejected
    assertFalse(ex.getMessage().length() > 200, "message must be bounded; was " + ex.getMessage().length());
  }
}
