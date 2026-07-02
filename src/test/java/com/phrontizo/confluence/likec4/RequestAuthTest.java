package com.phrontizo.confluence.likec4;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for the security-load-bearing Basic-auth detection that BOTH WebSudo enforcement points
 * ({@link AdminServlet}, {@link AdminConfigResource}) use to exempt Basic-auth callers. Pins the
 * case-insensitive scheme match AND the required trailing space, so the two paths cannot silently drift
 * and a look-alike scheme (e.g. {@code Basicfoo}) is never mistaken for HTTP Basic auth.
 */
class RequestAuthTest {
  private static HttpServletRequest withAuth(String header) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Authorization")).thenReturn(header);
    return req;
  }

  @Test
  void detects_basic_auth_case_insensitively() {
    assertTrue(RequestAuth.isBasic(withAuth("Basic dXNlcjpwYXNz")));
    assertTrue(RequestAuth.isBasic(withAuth("basic dXNlcjpwYXNz")));
    assertTrue(RequestAuth.isBasic(withAuth("BASIC dXNlcjpwYXNz")));
  }

  @Test
  void rejects_a_missing_or_non_basic_scheme() {
    assertFalse(RequestAuth.isBasic(withAuth(null)));
    assertFalse(RequestAuth.isBasic(withAuth("")));
    assertFalse(RequestAuth.isBasic(withAuth("Bearer token")));
  }

  @Test
  void requires_the_trailing_space_so_a_lookalike_scheme_is_not_matched() {
    // isBasic does regionMatches against "Basic " (6 chars incl. the space): a scheme whose name merely
    // STARTS with "Basic" but lacks the delimiter must NOT be treated as HTTP Basic auth.
    assertFalse(RequestAuth.isBasic(withAuth("Basic")));    // 5 chars, no trailing space
    assertFalse(RequestAuth.isBasic(withAuth("Basicfoo"))); // no delimiter
  }

  @Test
  void does_not_exempt_a_basic_header_with_leading_whitespace() {
    // The scheme is anchored at offset 0, so a proxy-mangled "  Basic ..." is NOT treated as Basic and
    // therefore NOT exempted from WebSudo — it is challenged like any interactive caller. This pins the
    // fail-CLOSED direction: the exemption must never WIDEN on a malformed header. (A compliant client
    // never hits this — the container strips leading OWS per RFC 7230 before getHeader() returns.)
    assertFalse(RequestAuth.isBasic(withAuth("  Basic dXNlcjpwYXNz")));
    assertFalse(RequestAuth.isBasic(withAuth("\tBasic dXNlcjpwYXNz")));
  }
}
