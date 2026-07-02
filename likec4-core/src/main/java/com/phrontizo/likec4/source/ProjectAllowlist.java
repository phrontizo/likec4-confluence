package com.phrontizo.likec4.source;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Allows a project only if it is, or sits under, a configured group/prefix entry (§8). */
public final class ProjectAllowlist {
  private final List<String> entries;

  public ProjectAllowlist(Collection<String> entries) {
    // Strip surrounding whitespace and both leading and trailing slashes so a stray "/platform" or
    // "platform/" entry still matches a bare "platform/..." project path. Lower-case entries (and the
    // project at match time) because GitLab namespace/project paths are case-INSENSITIVE — otherwise a
    // capitalised allowlist entry ("Platform") would silently deny the very project ("platform/arch")
    // an admin meant to allow. Matching never OVER-permits: GitLab treats the case variants as the same
    // path, so the case-folded compare cannot admit a project GitLab would resolve elsewhere. Drop null
    // elements so a single null in admin-supplied config yields a clear no-op entry rather than an opaque
    // NPE in the constructor. A null COLLECTION fails fast with a named argument (matching the module's
    // fail-fast convention) instead of a message-less NPE from entries.stream().
    // Entries are NOT screened for control chars here (isAllowed screens the match ARGUMENT instead):
    // it is unnecessary and cannot over-permit. isAllowed fails closed on a control-char project before
    // matching, so the normalised project `p` is always control-char-free; a control-char-bearing entry
    // can therefore never .equals() it nor be a startsWith() prefix of it — such an entry is inert dead
    // weight that matches nothing, not a bypass. (Screening the argument, not the stored config, is the
    // security-relevant guard.)
    Objects.requireNonNull(entries, "entries");
    this.entries = entries.stream().filter(Objects::nonNull)
        .map(s -> s.strip().replaceAll("^/+|/+$", "").toLowerCase(Locale.ROOT))
        .filter(s -> !s.isEmpty()).toList();
  }

  public boolean isAllowed(String project) {
    if (project == null || project.isBlank()) return false;
    // Fail closed on any ISO control char (C0, DEL, or C1): none is part of a legal GitLab path, yet an interior
    // one survives strip() below (which only trims surrounding whitespace) and would then match a prefix
    // verbatim (e.g. a NUL after "platform/ar" still startsWith "platform/"). This class is a public
    // security gate whose contract "must not depend on that upstream normalisation having already run"
    // (see below), so it must reject control chars itself — mirroring PathSafety.hasControlChar,
    // InputValidation, and RefShaCache's NUL rejection.
    if (hasControlChar(project)) return false;
    // Fold surrounding slashes on the argument the SAME way the constructor folds them on entries, so
    // the allow/deny decision is symmetric: a stray "/platform/arch" or "platform/arch/" must resolve
    // to the same result as "platform/arch", not be spuriously denied on a cosmetic leading slash. In
    // production SourceService sanitises the project first, but this class is a public security gate and
    // its match contract must not depend on that upstream normalisation having already run.
    String p = project.strip().replaceAll("^/+|/+$", "").toLowerCase(Locale.ROOT);
    if (p.isEmpty()) return false;
    for (String entry : entries) {
      if (p.equals(entry) || p.startsWith(entry + "/")) return true;
    }
    return false;
  }

  /** Whether {@code s} contains an ISO control character — the C0 block (U+0000..U+001F), DEL (U+007F), or
   *  the C1 block (U+0080..U+009F, e.g. NEL U+0085) — none of which can appear in a legal GitLab
   *  namespace/project path. Uses the SAME {@link Character#isISOControl(char)} notion as {@code
   *  PathSafety.hasControlChar} and {@code InputValidation.describe} so the module's three control-char
   *  screens agree. Kept in-class (the sibling {@code PathSafety.hasControlChar} is package-private to a
   *  different class) so this gate stays self-contained. */
  private static boolean hasControlChar(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isISOControl(s.charAt(i))) return true;
    }
    return false;
  }
}
