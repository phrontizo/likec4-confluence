package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectAllowlistTest {
  private final ProjectAllowlist allow = new ProjectAllowlist(List.of("platform", "team/architecture"));

  @Test
  void rejects_a_null_entries_collection_with_a_named_message() {
    // A null COLLECTION (vs a null element, covered above) must fail fast with a named argument,
    // matching the module's fail-fast convention (RefShaCache/SourceBundle requireNonNull), not an
    // opaque message-less NPE from entries.stream().
    NullPointerException ex =
        assertThrows(NullPointerException.class, () -> new ProjectAllowlist(null));
    assertEquals("entries", ex.getMessage());
  }

  @Test
  void tolerates_a_null_entry_in_the_configured_list() {
    // A null element in admin-supplied config must not NPE construction; it is simply ignored and the
    // remaining entries still match.
    ProjectAllowlist a = new ProjectAllowlist(Arrays.asList("platform", null, "team/architecture"));
    assertTrue(a.isAllowed("platform/arch"));
    assertTrue(a.isAllowed("team/architecture"));
    assertFalse(a.isAllowed("other/repo"));
  }

  @Test
  void allows_projects_under_an_allowed_group_or_exact_match() {
    assertTrue(allow.isAllowed("platform/arch"));
    assertTrue(allow.isAllowed("platform/sub/repo"));
    assertTrue(allow.isAllowed("team/architecture"));
  }

  @Test
  void denies_lookalikes_unknowns_and_blanks() {
    assertFalse(allow.isAllowed("platform-evil/x")); // not under platform/
    assertFalse(allow.isAllowed("other/repo"));
    assertFalse(allow.isAllowed("team/architecture-secret"));
    assertFalse(allow.isAllowed(""));
    assertFalse(allow.isAllowed(null));
  }

  @Test
  void matches_case_insensitively_because_gitlab_paths_are_case_insensitive() {
    // GitLab namespace/project paths are case-insensitive, so a capitalised allowlist entry must still
    // allow the lower-case project (and vice versa) — otherwise an admin who types "Platform" would
    // silently lock out "platform/arch". This is fail-closed-safe: it only folds case variants of the
    // SAME path, never broadening to a genuinely different namespace.
    ProjectAllowlist a = new ProjectAllowlist(List.of("Platform", "Team/Architecture"));
    assertTrue(a.isAllowed("platform/arch"));
    assertTrue(a.isAllowed("PLATFORM/arch"));
    assertTrue(a.isAllowed("team/architecture"));
    assertTrue(allow.isAllowed("Platform/Arch")); // lower-case entry, mixed-case project
    assertFalse(a.isAllowed("platform-evil/x")); // case-folding must not weaken the prefix anchor
  }

  @Test
  void normalizes_a_leading_slash_in_a_configured_entry() {
    // A stray leading "/" in an allowlist entry must not silently break matching.
    ProjectAllowlist a = new ProjectAllowlist(List.of("/platform", "/team/architecture/"));
    assertTrue(a.isAllowed("platform/arch"));
    assertTrue(a.isAllowed("team/architecture"));
  }

  @Test
  void normalizes_surrounding_slashes_in_the_project_argument() {
    // isAllowed must fold surrounding slashes on its ARGUMENT the same way the constructor folds them
    // on entries. Otherwise the decision is asymmetric on cosmetic slashes: a caller passing a stray
    // "/platform/arch" (leading slash) would neither equal "platform" nor start with "platform/" and be
    // spuriously DENIED, while "platform/arch/" happens to still pass — inconsistent for a public gate.
    assertTrue(allow.isAllowed("/platform/arch"), "leading slash must not spuriously deny");
    assertTrue(allow.isAllowed("platform/arch/"), "trailing slash must not change the decision");
    assertTrue(allow.isAllowed("/team/architecture/"));
    // Folding the argument's slashes must NOT weaken the prefix anchor.
    assertFalse(allow.isAllowed("/platform-evil/x"));
  }

  @Test
  void denies_a_project_containing_a_control_char() {
    // This class documents itself as a standalone public security gate whose "match contract must not
    // depend on that upstream normalisation having already run". A project with an INTERIOR control char
    // (NUL/newline/CR/etc.) survives strip() (which only trims surrounding whitespace) and would then
    // match a prefix verbatim — e.g. "platform/ar\u0000ch" startsWith "platform/" → allowed. A control
    // char is never part of a legal GitLab path, so the gate must fail closed on it, matching the
    // module's convention (PathSafety.hasControlChar, InputValidation, RefShaCache's NUL rejection).
    assertFalse(allow.isAllowed("platform/ar\u0000ch"), "an interior NUL must not slip past the prefix match");
    assertFalse(allow.isAllowed("platform/ar\nch"), "a newline must be rejected");
    assertFalse(allow.isAllowed("platform/arch\u007f"), "a DEL must be rejected");
    // Sanity: the same project without the control char IS allowed, so the guard is not over-broad.
    assertTrue(allow.isAllowed("platform/arch"));
  }

  @Test
  void denies_a_project_containing_a_c1_control_char() {
    // The C1 control block (U+0080..U+009F, e.g. U+0085 NEL) is control, not printable, and never part of
    // a legal GitLab path -- yet the narrow "c < 0x20 || c == 0x7f" screen used to let it through, so an
    // interior C1 char survived and still matched a prefix verbatim (e.g. "platform/ar<NEL>ch" startsWith
    // "platform/"). Align with InputValidation's Character.isISOControl notion and fail closed on it, just
    // like a C0 control or DEL. (Chars built via (char) casts so no literal control byte lives in source.)
    assertFalse(allow.isAllowed("platform/ar" + (char) 0x85 + "ch"), "an interior NEL (C1) must be rejected");
    assertFalse(allow.isAllowed("platform/ar" + (char) 0x80 + "ch"), "a PAD (C1 low) must be rejected");
    assertFalse(allow.isAllowed("platform/arch" + (char) 0x9F), "an APC (C1 high) must be rejected");
    // Sanity: U+00A0 (NBSP, just above the C1 block) is NOT a control char, so an interior NBSP is kept and
    // the project still matches -- the guard is not over-broad.
    assertTrue(allow.isAllowed("platform/ar" + (char) 0xA0 + "ch"));
  }

  @Test
  void a_control_char_bearing_configured_entry_is_inert_and_matches_nothing() {
    // Pins the constructor's documented invariant: entries are NOT screened for control chars because a
    // control-char-bearing entry cannot over-permit. isAllowed rejects a control-char PROJECT before
    // matching, so the normalised project is always control-char-free and can never .equals() nor be
    // prefixed by such an entry — it is inert dead weight, never a bypass. (Screening the match argument,
    // not the stored config, is the security-relevant guard.)
    ProjectAllowlist a = new ProjectAllowlist(List.of("plat\u0000form", "team\narch"));
    assertFalse(a.isAllowed("platform/arch"), "a NUL-bearing entry must not match an otherwise-clean project");
    assertFalse(a.isAllowed("plat\u0000form/arch"), "and the project-side control-char screen rejects it first anyway");
    assertFalse(a.isAllowed("team/arch"), "a newline-bearing entry is likewise inert");
  }

  @Test
  void drops_blank_and_slash_only_entries_so_they_never_match_everything() {
    // A blank ("", " ") or slash-only ("/", "//") entry strips to empty and is dropped — critically it
    // must NOT survive as an empty string, because "".equals(p) is false but p.startsWith("" + "/") would
    // match ANY project containing a slash, silently allowlisting the whole world from one stray entry.
    ProjectAllowlist a = new ProjectAllowlist(Arrays.asList("", " ", "/", "//", " / ", "platform"));
    assertTrue(a.isAllowed("platform/arch"), "the one real entry still matches");
    assertFalse(a.isAllowed("anything/else"), "a dropped blank/slash entry must not match everything");
    assertFalse(a.isAllowed("secret/repo"));
    // An allowlist of ONLY blank/slash entries allows nothing at all.
    ProjectAllowlist empty = new ProjectAllowlist(Arrays.asList("", "/", "  ", "///"));
    assertFalse(empty.isAllowed("platform/arch"));
  }
}
