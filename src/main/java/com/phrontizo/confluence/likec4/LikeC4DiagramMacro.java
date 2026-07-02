package com.phrontizo.confluence.likec4;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.plugin.spring.scanner.annotation.component.ConfluenceComponent;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.webresource.api.assembler.PageBuilderService;
import com.phrontizo.likec4.source.DiagramHtmlRenderer;
import com.phrontizo.likec4.source.InputValidation;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/** The "LikeC4 Diagram" macro: validates params, requires the web-resource, emits the placeholder div. */
@ConfluenceComponent
@Named("likeC4DiagramMacro")
public class LikeC4DiagramMacro implements Macro {

  private final PageBuilderService pageBuilder;

  @Inject
  public LikeC4DiagramMacro(@ComponentImport PageBuilderService pageBuilder) {
    this.pageBuilder = pageBuilder;
  }

  @Override
  public String execute(Map<String, String> params, String body, ConversionContext context)
      throws MacroExecutionException {
    String project = trimToNull(params.get("project"));
    if (project == null) {
      return errorDiv("LikeC4 diagram: 'project' is required.");
    }
    String ref;
    String path;
    String view;
    String instance;
    String height;
    try {
      // Validate every author-supplied parameter up front so a malformed value fails at render time
      // with clear feedback, rather than escaping into a div that only errors later via a REST 400.
      // (DiagramHtmlRenderer also HTML-escapes them; this is the value/charset bound, defence-in-depth.)
      project = InputValidation.sanitizeProject(project);
      ref = optional(params.get("ref"), InputValidation::sanitizeRef);
      // path needs the extra blankToNull (unlike ref/view/instance): sanitizePath returns "" for a
      // blank/whitespace path rather than null, so normalise that empty string back to null here.
      path = blankToNull(optional(params.get("path"), InputValidation::sanitizePath));
      view = InputValidation.sanitizeView(trimToNull(params.get("view")));
      instance = InputValidation.sanitizeInstance(trimToNull(params.get("instance")));
      height = sanitizeHeight(trimToNull(params.get("height")));
    } catch (IllegalArgumentException e) {
      // Do not echo the raw value back into the page; just report which kind of value was rejected.
      return errorDiv("LikeC4 diagram: a macro parameter is invalid.");
    }
    // requireWebResource + render are intentionally OUTSIDE the try above: they take only
    // already-validated values (no author input can make them throw), and render() emits solely
    // HTML-escaped constants. A failure of the web-resource assembler is a platform/infrastructure
    // problem, not a bad-parameter case — so we let it propagate to Confluence's macro-error handling
    // (which logs it and renders a generic macro-error placeholder) rather than swallowing it into an
    // errorDiv that would mask the real fault in the logs.
    pageBuilder.assembler().resources().requireWebResource(
        "com.phrontizo.confluence.likec4-confluence:likec4-web");
    return DiagramHtmlRenderer.render(project, ref, path, view, instance, height);
  }

  /** Apply {@code validator} to a trimmed, non-blank param; {@code null}/blank passes through as null. */
  private static String optional(String raw, UnaryOperator<String> validator) {
    String t = trimToNull(raw);
    return t == null ? null : validator.apply(t);
  }

  private static String blankToNull(String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }

  /** A plain number with an optional CSS length unit (no arbitrary CSS). Compiled once (an explicitly
   *  anchored, bounded-quantifier pattern — no backtracking risk) rather than recompiled on every macro
   *  render. The ^…$ anchors are belt-and-braces with sanitizeHeight's Matcher.matches() (which already
   *  anchors the whole input): they make the pattern safe on its own terms, so a future switch to
   *  find()/lookingAt() cannot silently turn the data-height attribute into an injection sink. */
  private static final Pattern HEIGHT = Pattern.compile("^(\\d{1,5})(px|em|rem|vh|vw|%)?$");

  /** Bound the optional height to a plain number with an optional CSS length unit (no arbitrary CSS). */
  private static String sanitizeHeight(String h) {
    if (h == null) return null;
    Matcher m = HEIGHT.matcher(h);
    if (!m.matches()) {
      throw new IllegalArgumentException("invalid height: " + h);
    }
    // Reject a zero magnitude ("0", "00000", "0px", "0%"): it collapses the diagram container to ~0px,
    // which is indistinguishable from a broken render. An author who wants the CSS default height should
    // omit the parameter entirely (see DiagramHtmlRenderer.render). group(1) is exactly the \d{1,5} run
    // (at most 99999, so parseInt cannot overflow), read straight from the match — no manual re-scan.
    if (Integer.parseInt(m.group(1)) == 0) {
      throw new IllegalArgumentException("invalid height (zero collapses the diagram): " + h);
    }
    return h;
  }

  private static String errorDiv(String message) {
    // Escape even though every current caller passes a constant: errorDiv emits into the page body, so
    // it must not be a latent XSS sink a future "improvement" (e.g. echoing a bad value) could trip.
    return "<div class=\"aui-message aui-message-error\">" + DiagramHtmlRenderer.escape(message) + "</div>";
  }

  @Override
  public BodyType getBodyType() {
    return BodyType.NONE;
  }

  @Override
  public OutputType getOutputType() {
    return OutputType.BLOCK;
  }

  private static String trimToNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
