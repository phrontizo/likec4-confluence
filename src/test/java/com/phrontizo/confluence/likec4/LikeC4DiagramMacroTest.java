package com.phrontizo.confluence.likec4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.atlassian.webresource.api.assembler.PageBuilderService;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Macro tests: the missing-project error path, that valid params reach the placeholder div (escaped)
 * and require the web-resource, and that a malformed/XSS-bearing param is rejected with an error
 * rather than rendered into the page.
 */
class LikeC4DiagramMacroTest {

  private static PageBuilderService pageBuilder() {
    return mock(PageBuilderService.class, RETURNS_DEEP_STUBS);
  }

  @Test
  void missing_project_renders_an_error_and_requires_no_web_resource() throws Exception {
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb).execute(Map.of(), null, null);
    assertTrue(html.contains("aui-message-error"), "missing project must surface a styled error");
    verify(pb, never()).assembler();
  }

  @Test
  void valid_params_emit_the_escaped_placeholder_and_require_the_web_resource() throws Exception {
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb).execute(
        Map.of("project", "acme/architecture", "ref", "main2", "path", "ok", "view", "index"), null, null);
    assertTrue(html.contains("class=\"likec4-diagram\""), "must emit the placeholder div");
    assertTrue(html.contains("data-project=\"acme/architecture\""));
    assertTrue(html.contains("data-view=\"index\""));
    verify(pb.assembler().resources())
        .requireWebResource("com.phrontizo.confluence.likec4-confluence:likec4-web");
  }

  @Test
  void an_xss_bearing_project_is_rejected_with_an_error_not_rendered_into_the_page() throws Exception {
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "\"><script>alert(1)</script>"), null, null);
    assertTrue(html.contains("aui-message-error"));
    assertFalse(html.contains("<script>"), "the script payload must never reach the output");
    verify(pb, never()).assembler();
  }

  @Test
  void a_traversal_ref_is_rejected() throws Exception {
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "acme/architecture", "ref", "../../etc"), null, null);
    assertTrue(html.contains("aui-message-error"));
  }

  @Test
  void a_traversal_path_is_rejected() throws Exception {
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "acme/architecture", "path", "../../etc"), null, null);
    assertTrue(html.contains("aui-message-error"));
    verify(pb, never()).assembler();
  }

  @Test
  void a_markup_bearing_view_is_rejected_and_never_rendered() throws Exception {
    // Guards the sanitizeView wiring: a regression dropping it would let a markup-bearing view reach
    // the data-view attribute. (escape() is a second layer, but the value/charset bound must hold here.)
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "acme/architecture", "view", "\"><script>alert(1)</script>"), null, null);
    assertTrue(html.contains("aui-message-error"));
    assertFalse(html.contains("<script>"), "the view payload must never reach the output");
    verify(pb, never()).assembler();
  }

  @Test
  void a_markup_bearing_instance_is_rejected_and_never_rendered() throws Exception {
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "acme/architecture", "instance", "prod\" onload=alert(1)"), null, null);
    assertTrue(html.contains("aui-message-error"));
    assertFalse(html.contains("onload"), "the instance payload must never reach the output");
    verify(pb, never()).assembler();
  }

  @Test
  void all_valid_optional_params_are_emitted_as_escaped_data_attributes() throws Exception {
    // Locks that path/ref/instance/height actually reach the placeholder (not just project/view), so a
    // wiring regression that silently drops one is caught.
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb).execute(
        Map.of("project", "acme/architecture", "ref", "release/1.0", "path", "diagrams",
            "view", "index", "instance", "prod", "height", "80vh"),
        null, null);
    assertTrue(html.contains("data-ref=\"release/1.0\""), html);
    assertTrue(html.contains("data-path=\"diagrams\""), html);
    assertTrue(html.contains("data-instance=\"prod\""), html);
    assertTrue(html.contains("data-height=\"80vh\""), html);
  }

  @Test
  void a_slash_only_or_blank_path_drops_data_path_rather_than_emitting_an_empty_one() throws Exception {
    // path carries the extra blankToNull: sanitizePath returns "" (not null) for a slash-only value like
    // "/" (it strips leading/trailing slashes), and trimToNull passes a whitespace-only path through as
    // null. Either way the macro must DROP data-path — never emit data-path="", which the frontend would
    // read as a real empty path rather than "no subpath". The diagram must still render (not an error).
    String slashOnly = new LikeC4DiagramMacro(pageBuilder())
        .execute(Map.of("project", "acme/architecture", "path", "/"), null, null);
    assertTrue(slashOnly.contains("class=\"likec4-diagram\""), "the diagram must still render");
    assertTrue(slashOnly.contains("data-project=\"acme/architecture\""));
    assertFalse(slashOnly.contains("data-path"), "a slash-only path must drop data-path, not emit an empty one");

    String whitespace = new LikeC4DiagramMacro(pageBuilder())
        .execute(Map.of("project", "acme/architecture", "path", "   "), null, null);
    assertTrue(whitespace.contains("class=\"likec4-diagram\""));
    assertFalse(whitespace.contains("data-path"), "a whitespace-only path must drop data-path too");
  }

  @Test
  void a_blank_or_whitespace_ref_drops_data_ref_and_renders_rather_than_erroring() throws Exception {
    // Symmetric with the blank-path case: a whitespace-only ref is passed through as null by
    // optional()/trimToNull, so the macro DROPS data-ref (never emits data-ref="") and the diagram still
    // renders. The frontend then calls /resolve and /source with no ref param, and the server-side
    // defaultRef() applies the "no ref → HEAD (default branch)" policy — so a blank ref and an omitted
    // ref behave identically. Pin it so a regression that emitted data-ref="" (which the frontend would
    // read as a real empty ref) is caught.
    String whitespace = new LikeC4DiagramMacro(pageBuilder())
        .execute(Map.of("project", "acme/architecture", "ref", "   "), null, null);
    assertTrue(whitespace.contains("class=\"likec4-diagram\""), "the diagram must still render");
    assertTrue(whitespace.contains("data-project=\"acme/architecture\""));
    assertFalse(whitespace.contains("data-ref"), "a whitespace-only ref must drop data-ref, not emit an empty one");
  }

  @Test
  void an_arbitrary_css_height_is_rejected_but_a_plain_number_with_unit_is_accepted() throws Exception {
    PageBuilderService pb = pageBuilder();
    String bad = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "acme/architecture", "height", "calc(100% - evil)"), null, null);
    assertTrue(bad.contains("aui-message-error"));

    String ok = new LikeC4DiagramMacro(pageBuilder())
        .execute(Map.of("project", "acme/architecture", "height", "600px"), null, null);
    assertEquals(true, ok.contains("data-height=\"600px\""));
  }

  @Test
  void a_unitless_numeric_height_is_accepted() throws Exception {
    // DiagramHtmlRenderer documents "600" (no unit) as supported; the regex allows it, so lock it in.
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "acme/architecture", "height", "600"), null, null);
    assertTrue(html.contains("data-height=\"600\""), html);
  }

  @Test
  void an_over_long_numeric_height_is_rejected() throws Exception {
    // The height regex bounds the digit run to \d{1,5}; a 6-digit value must be rejected (boundary),
    // so a regression loosening it (e.g. to permit arbitrary CSS) is caught.
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "acme/architecture", "height", "123456"), null, null);
    assertTrue(html.contains("aui-message-error"), "a 6-digit height must be rejected");
    verify(pb, never()).assembler();
  }

  @Test
  void the_max_five_digit_height_boundary_is_accepted() throws Exception {
    // The height regex bounds the digit run to \d{1,5}; 99999 is the LARGEST value it admits (and the
    // class relies on that: its comment notes parseInt of the \d{1,5} run "at most 99999, so parseInt
    // cannot overflow"). Its sibling an_over_long_numeric_height_is_rejected pins the reject side (6
    // digits); this pins the ACCEPT side of the boundary so a regression tightening the run to {1,4} —
    // silently rejecting a valid 5-digit height — is caught.
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "acme/architecture", "height", "99999px"), null, null);
    assertTrue(html.contains("data-height=\"99999px\""), html);
    // Bare 99999 (no unit) is likewise the max unitless value.
    String bare = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "acme/architecture", "height", "99999"), null, null);
    assertTrue(bare.contains("data-height=\"99999\""), bare);
  }

  @Test
  void a_zero_magnitude_height_is_rejected() throws Exception {
    // A zero height ("0", "00000", "0px", "0%") collapses the diagram container to ~0px, which is
    // indistinguishable from a broken render. It matches the length regex but must be rejected so the
    // author gets a clear error instead of a silently-invisible diagram; the web-resource must not load.
    PageBuilderService pb = pageBuilder();
    for (String zero : new String[] {"0", "00000", "0px", "0%", "000vh"}) {
      String html = new LikeC4DiagramMacro(pb)
          .execute(Map.of("project", "acme/architecture", "height", zero), null, null);
      assertTrue(html.contains("aui-message-error"), "a zero height must be rejected: " + zero);
    }
    verify(pb, never()).assembler();
  }

  @Test
  void an_attribute_injecting_height_is_rejected_and_never_reaches_the_data_attribute() throws Exception {
    // The anchored height regex already makes a quote-bearing value invalid, but data-height is the one
    // author value that flows into an HTML attribute — pin (symmetric with the view/instance markup
    // tests) that a break-out payload is rejected and never appears in the output, so a future loosening
    // of the regex to allow richer CSS cannot silently open an attribute-injection sink.
    PageBuilderService pb = pageBuilder();
    String html = new LikeC4DiagramMacro(pb)
        .execute(Map.of("project", "acme/architecture", "height", "600px\" onload=alert(1)"), null, null);
    assertTrue(html.contains("aui-message-error"), "a quote-bearing height must be rejected");
    assertFalse(html.contains("onload"), "the height payload must never reach the output");
    verify(pb, never()).assembler();
  }
}
