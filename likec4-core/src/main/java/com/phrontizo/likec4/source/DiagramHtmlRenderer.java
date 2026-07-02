package com.phrontizo.likec4.source;

/** Emits the macro placeholder `<div class="likec4-diagram" data-...>` with escaped attributes. */
public final class DiagramHtmlRenderer {
  private DiagramHtmlRenderer() {}

  public static String render(
      String project, String ref, String path, String view, String instance, String height) {
    StringBuilder sb = new StringBuilder("<div class=\"likec4-diagram\"");
    attr(sb, "data-project", project);
    attr(sb, "data-ref", ref);
    attr(sb, "data-path", path);
    attr(sb, "data-view", view);
    attr(sb, "data-instance", instance);
    // Optional author-set height (e.g. "600", "600px", "80vh"); boot.tsx applies it to the
    // container, overriding the CSS default. Without it the macro container uses .likec4-diagram's
    // default height so the diagram is visible (not collapsed to ~0px).
    attr(sb, "data-height", height);
    sb.append("></div>");
    return sb.toString();
  }

  private static void attr(StringBuilder sb, String name, String value) {
    if (value == null || value.isEmpty()) return;
    sb.append(' ').append(name).append("=\"").append(escape(value)).append('"');
  }

  /** HTML-escapes a value for emission into element text or a double-quoted attribute. Public so the
   *  Confluence wrapper's macro can reuse this one canonical escaper rather than copying it. A null
   *  input escapes to the empty string so a direct caller never NPEs on a missing value. */
  public static String escape(String s) {
    if (s == null) return "";
    // Small headroom over the input length: every escaped char EXPANDS (e.g. '"' -> "&quot;"), so sizing
    // to exactly s.length() forces a reallocation as soon as any metachar is escaped. +16 absorbs a
    // handful of escapes without over-allocating for the common (already-inert) value.
    StringBuilder out = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> {
          if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
            // A VALID surrogate pair (a supplementary code point, e.g. an emoji): a legitimate, inert
            // character in a double-quoted attribute / element text. Emit BOTH halves verbatim and skip
            // the low surrogate — never corrupt a well-formed astral char into U+FFFD.
            out.append(c).append(s.charAt(i + 1));
            i++;
          } else if (Character.isISOControl(c) || Character.isSurrogate(c) || c == '\uFFFE' || c == '\uFFFF') {
            // Defense-in-depth: replace with U+FFFD anything that is not well-formed HTML text — control
            // characters (incl. NUL, newline, tab), a LONE (unpaired) surrogate (a valid pair was handled
            // above), and the noncharacters U+FFFE/U+FFFF (the U+FDD0..U+FDEF noncharacters are equally
            // inert in HTML text/attributes and are left as-is) — so the handled cases can never affect
            // HTML parsing or be smuggled past a downstream sanitiser. A numeric
            // reference like &#x0; for NUL (and the other C0 controls) is NOT a valid HTML5 character
            // reference — a conformant parser flags a parse error and substitutes U+FFFD anyway — so we
            // emit the replacement char directly, keeping the output both inert AND well-formed. Inert in
            // a double-quoted attribute and in element text. Production inputs are already sanitised by
            // InputValidation; this guards a direct caller of this public canonical escaper.
            out.append('\uFFFD');
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }
}
