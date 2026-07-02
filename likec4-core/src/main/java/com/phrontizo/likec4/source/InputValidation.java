package com.phrontizo.likec4.source;

import java.util.regex.Pattern;

/** Sanitises untrusted ref/path params before any GitLab call (rejects traversal/injection). */
public final class InputValidation {
  private InputValidation() {}

  private static final Pattern REF = Pattern.compile("[A-Za-z0-9._/\\-]{1,255}");
  private static final Pattern PATH = Pattern.compile("[A-Za-z0-9._/\\-]{1,1024}");
  private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9._/\\-]{1,512}");
  // View/instance ids deliberately reuse the SAME permissive charset as ref/path rather than a strict
  // single-token pattern: a LikeC4 view id can legitimately contain dots (nested ids) and the goal here is
  // only to reject control chars, whitespace, quotes and markup (defence-in-depth — these values flow to
  // the browser as escaped data- attributes and are never used as a path segment or in a shell). The 255
  // bound and `..`/leading-dash guards below apply on top of the charset.
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9._/\\-]{1,255}");

  public static String sanitizeRef(String ref) {
    if (ref == null || ref.isBlank()) throw new IllegalArgumentException("ref is required");
    String r = ref.strip();
    if (r.contains("..") || leadingDash(r) || !REF.matcher(r).matches())
      throw new IllegalArgumentException("invalid ref: " + describe(ref));
    return r;
  }

  public static String sanitizeProject(String project) {
    return sanitizeProjectPath(project, "project");
  }

  /**
   * Validate an allowlist entry — a GitLab group / subgroup / project PREFIX path. An entry shares the
   * project-id charset and traversal guards (it is matched as a prefix of a sanitised project path), so
   * validating it on the admin WRITE path rejects a malformed entry at the boundary with feedback rather
   * than silently persisting it as inert dead weight (it could never match a real project at request
   * time). Returns the normalised (surrounding-slash-stripped) form to store.
   */
  public static String sanitizeAllowlistEntry(String entry) {
    return sanitizeProjectPath(entry, "allowlist entry");
  }

  private static String sanitizeProjectPath(String value, String label) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    // Strip leading/trailing slashes the same way sanitizePath does: a project id is "group/repo" with no
    // surrounding slash, and leaving one on would URL-encode to a different (404) GitLab id and fragment
    // the ref/bundle cache keys ("group/repo" vs "group/repo/" for the same repo).
    String p = value.strip().replaceAll("^/+|/+$", "");
    if (p.isEmpty() || p.contains("..") || leadingDash(p) || !PROJECT.matcher(p).matches())
      throw new IllegalArgumentException("invalid " + label + ": " + describe(value));
    return p;
  }

  public static String sanitizePath(String path) {
    if (path == null) return "";
    String p = path.strip().replaceAll("^/+|/+$", "");
    if (p.isEmpty()) return "";
    if (p.contains("..") || leadingDash(p) || !PATH.matcher(p).matches())
      throw new IllegalArgumentException("invalid path: " + describe(path));
    return p;
  }

  /** Whether {@code s} begins with a dash. Rejected as defence-in-depth: a real git ref / GitLab path
   *  never begins with '-' (git forbids it), and a leading dash is the classic shape of a value that a
   *  downstream tool could mistake for an option flag. (These values only ever reach URL-encoded GitLab
   *  REST params today, so this is belt-and-braces, not a live injection.) */
  private static boolean leadingDash(String s) {
    return !s.isEmpty() && s.charAt(0) == '-';
  }

  /** Sanitise an optional view id: {@code null}/blank -> {@code null}; otherwise enforce the id charset. */
  public static String sanitizeView(String view) {
    return sanitizeOptionalId(view, "view");
  }

  /** Sanitise an optional instance id: {@code null}/blank -> {@code null}; otherwise enforce the id charset. */
  public static String sanitizeInstance(String instance) {
    return sanitizeOptionalId(instance, "instance");
  }

  private static String sanitizeOptionalId(String value, String label) {
    if (value == null) return null;
    String v = value.strip();
    if (v.isEmpty()) return null;
    if (v.contains("..") || leadingDash(v) || !ID.matcher(v).matches())
      throw new IllegalArgumentException("invalid " + label + ": " + describe(value));
    return v;
  }

  /** Render a REJECTED value for an {@link IllegalArgumentException} message WITHOUT smuggling a control
   *  character (a newline, CR, tab, NUL, …) into a would-be downstream log line. The production callers
   *  already reject these values, but this class is public API and a direct caller may log
   *  {@code e.getMessage()}, so any ISO control char is neutralised to {@code '?'} (analogous to, but
   *  distinct from, {@code DiagramHtmlRenderer.escape}'s U+FFFD substitution for HTML — a log line and
   *  HTML text want different sentinels) and the value is bounded so a hostile input can neither forge a
   *  log line nor bloat a log. */
  private static String describe(String value) {
    if (value == null) return "null";
    int limit = 120;
    int n = Math.min(value.length(), limit);
    // Don't split a supplementary character (a surrogate PAIR) at the truncation boundary: if the last
    // kept code unit is a high surrogate whose low half lies just past the cut, drop it so the message
    // stays well-formed UTF-16 (a lone surrogate renders as U+FFFD and can corrupt a log sink).
    if (n < value.length() && Character.isHighSurrogate(value.charAt(n - 1))) n--;
    StringBuilder sb = new StringBuilder(n + 1);
    for (int i = 0; i < n; i++) {
      char c = value.charAt(i);
      sb.append(Character.isISOControl(c) ? '?' : c);
    }
    if (value.length() > limit) sb.append('…');
    return sb.toString();
  }
}
