package com.phrontizo.confluence.likec4;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared HTTP-auth helper.
 *
 * <p>The Basic-auth detection is a security-relevant invariant used by BOTH {@link AdminServlet} and
 * {@link AdminConfigResource} to exempt Basic-auth callers from WebSudo (they already present the
 * password per request). Keeping it in ONE place stops the two WebSudo enforcement points from silently
 * drifting apart — if they disagreed, one path could exempt a caller the other challenges.
 */
final class RequestAuth {
  private RequestAuth() {}

  /**
   * Whether the request authenticates via HTTP Basic auth (so it already presents the password).
   *
   * <p>The scheme match is deliberately STRICT: it anchors {@code "Basic "} at offset 0, so a header
   * with leading whitespace (a proxy-mangled {@code "  Basic ..."}) is NOT treated as Basic. This fails
   * CLOSED — the widening direction of a security-relevant WebSudo exemption is the dangerous one, so a
   * malformed header is challenged like any interactive caller rather than waved through. A compliant
   * client never hits this: a servlet container strips leading OWS from a header value (RFC 7230) before
   * {@code getHeader} returns.
   */
  static boolean isBasic(HttpServletRequest request) {
    String h = request.getHeader("Authorization");
    return h != null && h.regionMatches(true, 0, "Basic ", 0, 6);
  }
}
