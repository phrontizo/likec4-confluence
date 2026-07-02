package com.phrontizo.confluence.likec4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AdminConfig}'s pure allowlist parse/format helpers (no SAL/PluginSettings). */
class AdminConfigTest {

  @Test
  void parseAllowlist_trims_and_drops_blank_entries() {
    assertEquals(List.of("a", "b"), AdminConfig.parseAllowlist("a, b"));
    // leading/trailing whitespace, an empty entry from a doubled comma, and a trailing comma all drop.
    assertEquals(List.of("a", "b"), AdminConfig.parseAllowlist(" a ,, b , "));
    assertEquals(List.of(), AdminConfig.parseAllowlist(null));
    assertEquals(List.of(), AdminConfig.parseAllowlist("   "));
    assertEquals(List.of(), AdminConfig.parseAllowlist(",,"));
  }

  @Test
  void formatAllowlist_drops_blanks_and_round_trips_with_parse() {
    assertEquals("a,b", AdminConfig.formatAllowlist(List.of("a", "b")));
    assertEquals("a,b", AdminConfig.formatAllowlist(List.of(" a ", "", " ", "b")));
    // round-trip: format then parse yields the cleaned set with no empty entries.
    assertEquals(List.of("a", "b"),
        AdminConfig.parseAllowlist(AdminConfig.formatAllowlist(List.of("a", " ", "b"))));
  }

  @Test
  void validatedAllowlist_normalises_valid_entries_and_drops_blanks() {
    // Valid group/subgroup/project PREFIX entries pass, are trimmed/slash-folded to their normalised
    // form, and interspersed blanks (a stray "," / whitespace / null) are dropped — never an error.
    assertEquals(List.of("platform", "platform/architecture"),
        AdminConfig.validatedAllowlist(List.of("  platform  ", "/platform/architecture/")));
    assertEquals(List.of("grp/sub"),
        AdminConfig.validatedAllowlist(Arrays.asList("", "  ", null, "grp/sub")));
    assertEquals(List.of(), AdminConfig.validatedAllowlist(List.of(" ", "")));
  }

  @Test
  void validatedAllowlist_rejects_a_malformed_entry_rather_than_persisting_inert_dead_weight() {
    // A non-blank entry that could never match a real project (traversal, whitespace, a leading dash,
    // off-charset punctuation) is rejected at the admin boundary, not silently stored as an entry that
    // matches nothing at request time.
    assertThrows(IllegalArgumentException.class, () -> AdminConfig.validatedAllowlist(List.of("../secret")));
    assertThrows(IllegalArgumentException.class, () -> AdminConfig.validatedAllowlist(List.of("grp repo")));
    assertThrows(IllegalArgumentException.class, () -> AdminConfig.validatedAllowlist(List.of("-grp")));
    assertThrows(IllegalArgumentException.class, () -> AdminConfig.validatedAllowlist(List.of("grp/re;po")));
  }
}
