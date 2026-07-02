package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiagramHtmlRendererTest {
  @Test
  void emits_the_macro_div_with_data_attributes() {
    String html = DiagramHtmlRenderer.render("grp/repo", "main", "diagrams", "index", "7", "600px");
    assertTrue(html.contains("class=\"likec4-diagram\""));
    assertTrue(html.contains("data-project=\"grp/repo\""));
    assertTrue(html.contains("data-ref=\"main\""));
    assertTrue(html.contains("data-path=\"diagrams\""));
    assertTrue(html.contains("data-view=\"index\""));
    assertTrue(html.contains("data-instance=\"7\""));
    assertTrue(html.contains("data-height=\"600px\""));
  }

  @Test
  void escape_returns_empty_for_a_null_value() {
    // escape() is public for the wrapper macro to reuse directly; a null must escape to "" rather
    // than NPE on s.length().
    assertEquals("", DiagramHtmlRenderer.escape(null));
  }

  @Test
  void omits_blank_optional_attributes() {
    String html = DiagramHtmlRenderer.render("grp/repo", null, "", null, "1", null);
    assertFalse(html.contains("data-ref"));
    assertFalse(html.contains("data-path"));
    assertFalse(html.contains("data-view"));
    assertFalse(html.contains("data-height"));
    assertTrue(html.contains("data-project=\"grp/repo\""));
  }

  @Test
  void replaces_control_characters_with_the_unicode_replacement_char_so_output_stays_valid_html() {
    // escape() is the public canonical escaper; production inputs are pre-sanitised by InputValidation,
    // but a direct caller passing a control char (NUL/newline/tab) must not emit it raw into an
    // attribute. They are replaced with U+FFFD: a NUMERIC reference like &#x0; for NUL (and the other
    // C0 controls) is NOT a valid HTML5 character reference (a conformant parser flags a parse error and
    // substitutes U+FFFD anyway), so we emit the replacement char directly, keeping the output both inert
    // AND well-formed. (Input is built from char values to keep the source free of escape sequences.)
    String input = "a" + (char) 10 + "b" + (char) 9 + "c" + (char) 0 + "d";
    String out = DiagramHtmlRenderer.escape(input);
    assertFalse(out.contains(String.valueOf((char) 10)), out); // no raw newline survives
    assertFalse(out.contains(String.valueOf((char) 9)), out);  // no raw tab survives
    assertFalse(out.contains(String.valueOf((char) 0)), out);  // no raw NUL survives
    assertFalse(out.contains("&#x"), out); // no (invalid-for-C0) numeric character references emitted
    assertEquals("a\uFFFDb\uFFFDc\uFFFDd", out); // each control char becomes the replacement character
    // A normal space is NOT a control character and must pass through untouched.
    assertEquals("x y", DiagramHtmlRenderer.escape("x y"));
  }

  @Test
  void neutralises_lone_surrogates_and_bmp_noncharacters_but_keeps_valid_astral_pairs() {
    // Character.isISOControl only catches C0/C1 controls; a LONE (unpaired) surrogate or a BMP
    // non-character (U+FFFE/U+FFFF) is not well-formed HTML text and slipped through into the attribute
    // verbatim. It is not XSS (none is an HTML metacharacter, and the value sits in a quoted attribute),
    // but the escaper's "keep output well-formed and inert" contract requires neutralising them to U+FFFD.
    // Crucially a VALID surrogate pair (a real supplementary code point such as an emoji) must be
    // PRESERVED, not corrupted — the pair is inert and legitimate.
    assertEquals("�", DiagramHtmlRenderer.escape("\uD83D"));       // lone high surrogate
    assertEquals("�", DiagramHtmlRenderer.escape("\uDE00"));       // lone low surrogate
    assertEquals("a�b", DiagramHtmlRenderer.escape("a\uD83Db"));   // lone high surrogate between letters
    assertEquals("�", DiagramHtmlRenderer.escape("￾"));       // BMP non-character U+FFFE
    assertEquals("�", DiagramHtmlRenderer.escape("￿"));       // BMP non-character U+FFFF
    // A valid astral pair (U+1F600 GRINNING FACE = 😀) is a legitimate code point and passes
    // through intact — the escaper must never corrupt a well-formed supplementary character.
    assertEquals("😀", DiagramHtmlRenderer.escape("😀"));
    assertEquals("x😀y", DiagramHtmlRenderer.escape("x😀y"));
  }

  @Test
  void escapes_ampersand_and_apostrophe_so_neither_can_break_out_of_markup() {
    // The other two HTML metacharacters (besides <, >, "): '&' must become "&amp;" so a value like
    // "a&amp;b" cannot be misread as an entity, and a single quote must become "&#39;" so a value can
    // never terminate a single-quoted attribute. Pinned because a regression dropping either case (or
    // ordering '&' after the others, double-escaping) would silently reopen an injection vector.
    assertEquals("a&amp;b &#39;q&#39;", DiagramHtmlRenderer.escape("a&b 'q'"));
    assertEquals("&amp;&amp;", DiagramHtmlRenderer.escape("&&")); // '&' is escaped once, not doubly
  }

  @Test
  void escapes_attribute_values_to_prevent_html_injection() {
    String html =
        DiagramHtmlRenderer.render("\"><script>alert(1)</script>", "main", "", "", "1", "");
    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&lt;script&gt;"));
    assertTrue(html.contains("&quot;&gt;"));
  }
}
