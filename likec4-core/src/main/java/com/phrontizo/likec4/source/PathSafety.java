package com.phrontizo.likec4.source;

import java.util.Optional;

/** Maps a GitLab archive entry name to a safe project-relative POSIX path, or empty if it must be dropped. */
public final class PathSafety {
  private PathSafety() {}

  public static Optional<String> safeRelative(String entryName, String pathPrefix) {
    if (entryName == null) return Optional.empty();                    // malformed/exotic tar header
    if (hasControlChar(entryName)) return Optional.empty();            // NUL/newline/CR/etc. — not a safe POSIX name
    String norm = entryName.replace('\\', '/');
    if (norm.startsWith("/")) return Optional.empty();                 // absolute
    for (String seg : norm.split("/", -1)) {
      if (seg.equals("..")) return Optional.empty();                   // traversal
      if (seg.equals(".")) return Optional.empty();                    // non-canonical "." segment
      if (seg.isEmpty()) return Optional.empty();                      // non-canonical "" segment (doubled slash)
    }
    int firstSlash = norm.indexOf('/');
    if (firstSlash < 0) return Optional.empty();                       // no top dir
    String afterTop = norm.substring(firstSlash + 1);                  // strip "<top>/"
    if (afterTop.isEmpty()) return Optional.empty();

    String prefix = pathPrefix == null ? "" : pathPrefix.replace('\\', '/').replaceAll("^/+|/+$", "");
    if (!prefix.isEmpty()) {
      if (afterTop.equals(prefix)) return Optional.empty();            // the requested dir entry itself
      if (!afterTop.startsWith(prefix + "/")) return Optional.empty(); // outside the requested subtree
      afterTop = afterTop.substring(prefix.length() + 1);
    }
    if (afterTop.isEmpty() || afterTop.endsWith("/")) return Optional.empty(); // directory entry
    return Optional.of(afterTop);
  }

  /** Whether {@code s} contains an ISO control character — the C0 block (U+0000..U+001F), DEL (U+007F),
   *  or the C1 block (U+0080..U+009F, e.g. NEL U+0085). A tar entry name carrying one (a NUL, newline, CR,
   *  tab, NEL, …) is not the "safe project-relative POSIX path" this class promises: left in, it would flow
   *  verbatim into the {@code SourceBundle} map key and thence the browser's virtual-FS path. Only a
   *  crafted/MITM'd archive produces such a name; drop the whole entry, using the SAME
   *  {@link Character#isISOControl(char)} notion of "control char" as {@code InputValidation.describe} (and
   *  mirroring how {@code InputValidation} rejects control chars in the ref/path request params) so the
   *  three sites agree. Legitimate names (spaces, unicode letters, punctuation) are unaffected. */
  private static boolean hasControlChar(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isISOControl(s.charAt(i))) return true;
    }
    return false;
  }
}
