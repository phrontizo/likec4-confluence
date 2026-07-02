package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BaseUrlValidatorTest {

  @Test
  void accepts_an_https_url() {
    assertEquals("https://gitlab.example.com", BaseUrlValidator.validate("https://gitlab.example.com"));
  }

  @Test
  void accepts_http_for_a_loopback_host() {
    assertEquals("http://localhost:8080", BaseUrlValidator.validate("http://localhost:8080"));
    assertEquals("http://127.0.0.1:8080", BaseUrlValidator.validate("http://127.0.0.1:8080"));
  }

  @Test
  void accepts_http_for_internal_hosts() {
    // Self-managed GitLab is frequently an internal http host — none of these is publicly routable.
    assertEquals("http://mockgitlab", BaseUrlValidator.validate("http://mockgitlab"));          // single-label
    assertEquals("http://gitlab", BaseUrlValidator.validate("http://gitlab/"));                 // single-label + slash
    assertEquals("http://10.0.0.5:8080", BaseUrlValidator.validate("http://10.0.0.5:8080"));    // RFC1918 10/8
    assertEquals("http://192.168.1.10", BaseUrlValidator.validate("http://192.168.1.10"));      // RFC1918 192.168/16
    assertEquals("http://172.16.0.1", BaseUrlValidator.validate("http://172.16.0.1"));          // RFC1918 172.16/12
    assertEquals("http://gitlab.internal", BaseUrlValidator.validate("http://gitlab.internal")); // internal TLD
  }

  @Test
  void rejects_http_for_a_public_host() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://gitlab.example.com"));
    assertTrue(ex.getMessage().contains("https"), "was: " + ex.getMessage());
  }

  @Test
  void accepts_http_across_the_whole_loopback_range() {
    // 127.0.0.0/8 is loopback — not just the .1 literal. Cleartext never leaves the host.
    assertEquals("http://127.0.0.2:8080", BaseUrlValidator.validate("http://127.0.0.2:8080"));
    assertEquals("http://127.1.2.3", BaseUrlValidator.validate("http://127.1.2.3"));
  }

  @Test
  void accepts_http_for_ipv6_internal_literals() {
    assertEquals("http://[::1]", BaseUrlValidator.validate("http://[::1]"));        // loopback
    assertEquals("http://[fc00::1]", BaseUrlValidator.validate("http://[fc00::1]")); // unique-local
    assertEquals("http://[fe80::1]", BaseUrlValidator.validate("http://[fe80::1]")); // link-local
    // An IPv6 literal WITH a port: URI.getHost() returns the bracketed literal and parses the :port
    // separately, so the internal classification (and the http allowance) must still hold.
    assertEquals("http://[::1]:8080", BaseUrlValidator.validate("http://[::1]:8080"));       // loopback + port
    assertEquals("http://[fc00::1]:8929", BaseUrlValidator.validate("http://[fc00::1]:8929")); // ULA + port
    // A link-local literal carrying an RFC 6874 zone id (the '%25' is the percent-encoded '%' scope
    // delimiter, e.g. fe80::1%eth0): URI.getHost() returns the whole bracketed token, and after the
    // bracket-strip the "fe80:" prefix test still classifies it internal — a scoped link-local address
    // is unroutable off-link, so plain http is correctly permitted. Pins that the zone id doesn't
    // defeat the classification (nor throw), since fe80::/10 addresses are only ever used with a scope.
    assertEquals("http://[fe80::1%25eth0]", BaseUrlValidator.validate("http://[fe80::1%25eth0]"));
  }

  @Test
  void rejects_http_for_numeric_encodings_of_a_public_ip() {
    // SSRF/token-cleartext bypass: a public address disguised as a non-dotted numeric host must NOT
    // be mistaken for a single-label intranet name. http://0x08080808 == 8.8.8.8, http://2130706433
    // == a 32-bit-decimal IPv4 — both must require https so the service token is never shipped in
    // cleartext to a public host.
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://0x08080808"));
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://2130706433"));
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://8.8.8.8"));
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://0x7f000001"));
  }

  @Test
  void rejects_http_for_a_bare_all_numeric_single_label_host() {
    // A bare all-numeric single-label host (e.g. 12345, 8080) is neither a dotted RFC1918/loopback
    // literal nor a normal name — it is classified as a numeric IP literal, fails the canonical dotted
    // match, and so requires https (fail-closed; it could be decimal-IP obfuscation). Pin that, and pin
    // that the message names the "unrecognised host" case rather than only "public".
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("http://12345"));
    assertTrue(ex.getMessage().contains("https"), "was: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("unrecognised"), "was: " + ex.getMessage());
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://8080"));
  }

  @Test
  void rejects_http_for_an_out_of_range_octet_that_only_looks_internal() {
    // A malformed dotted literal whose octet exceeds 255 (e.g. 127.999.0.1, 10.0.0.256) is NOT a valid
    // internal IPv4 address — it must not be misclassified as loopback/RFC1918 and waved through over
    // plain http. Bounding each octet to 0–255 fails these closed (a non-canonical numeric host then
    // requires https), rather than silently accepting an unconnectable cleartext config.
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://127.999.0.1"));
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://10.0.0.256"));
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://192.168.300.1"));
    // The full 0–255 octet range is still accepted for genuine internal addresses (boundary check).
    assertEquals("http://10.255.255.255", BaseUrlValidator.validate("http://10.255.255.255"));
    assertEquals("http://127.255.255.255", BaseUrlValidator.validate("http://127.255.255.255"));
    assertEquals("http://192.168.255.254", BaseUrlValidator.validate("http://192.168.255.254"));
  }

  @Test
  void rejects_http_for_a_public_host_that_merely_starts_with_an_ula_prefix() {
    // A hostname beginning "fc"/"fd" (e.g. fcc.example.com) is a PUBLIC FQDN, not an IPv6 ULA literal.
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("http://fcc.example.com"));
    assertTrue(ex.getMessage().contains("https"), "was: " + ex.getMessage());
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://fd-corp.example.com"));
  }

  @Test
  void accepts_http_for_an_internal_host_whose_name_begins_with_0x() {
    // "0x…" only denotes a hex-IPv4 literal when the rest is all hex digits; a hostname that merely
    // starts with 0x (e.g. 0xen.local, a single-label 0xbox) is a name, not a numeric IP, so the
    // internal-host rule applies and plain http is allowed.
    assertEquals("http://0xen.local", BaseUrlValidator.validate("http://0xen.local"));
    assertEquals("http://0xbox", BaseUrlValidator.validate("http://0xbox"));
    // A genuine hex-IPv4 literal of a PUBLIC address is still rejected.
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://0x08080808"));
  }

  @Test
  void accepts_http_for_an_internal_host_with_an_underscore() {
    // URI.getHost() returns null for an authority containing an underscore (e.g. http://gitlab_internal),
    // even though such names are common on Windows/AD and docker-compose internal networks — exactly the
    // internal-http case this validator exists to permit. They must not be rejected as "missing a host".
    assertEquals("http://gitlab_internal", BaseUrlValidator.validate("http://gitlab_internal")); // single-label
    assertEquals("http://gitlab_internal:8080", BaseUrlValidator.validate("http://gitlab_internal:8080/")); // + port
    assertEquals("http://build_server.intranet", BaseUrlValidator.validate("http://build_server.intranet")); // internal TLD
    assertEquals("https://git_lab.example.com", BaseUrlValidator.validate("https://git_lab.example.com")); // https always ok
  }

  @Test
  void rejects_http_for_a_public_underscore_host() {
    // A dotted public-ish name that merely contains an underscore is NOT internal — still require https.
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://git_lab.example.com"));
  }

  @Test
  void rejects_embedded_credentials_even_when_the_host_has_an_underscore() {
    // getUserInfo() is also null on the underscore (registry-based authority) path, so the credentials
    // check must fall back to inspecting the authority for '@' — otherwise a token could be smuggled in.
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("http://user:pass@gitlab_internal"));
    assertTrue(ex.getMessage().contains("credentials"), "was: " + ex.getMessage());
  }

  @Test
  void treats_reserved_internal_tlds_as_internal_by_design() {
    // A reserved/internal TLD is classified internal even though a public DNS server could be made to
    // answer for it — this is the intended trade-off (these TLDs are reserved for private use); pin it.
    assertEquals("http://anything.local", BaseUrlValidator.validate("http://anything.local"));
    assertEquals("http://anything.test", BaseUrlValidator.validate("http://anything.test"));
    assertEquals("http://host.lan", BaseUrlValidator.validate("http://host.lan"));
  }

  @Test
  void rejects_http_for_fail_closed_edge_hosts() {
    // These all classify as NON-internal, so plain http requires https — fail-closed, never opening
    // a cleartext-token path. Pinned because they are classic SSRF/cleartext bypass shapes.
    // Trailing-dot public FQDN (absolute DNS name) — not one of the reserved internal TLDs.
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://gitlab.example.com."));
    // 0.0.0.0 is neither loopback (127/8) nor RFC1918.
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://0.0.0.0"));
    // IPv6 link-local OUTSIDE the recognised fe80: literal (fe80::/10 actually spans fe80..febf).
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://[fe90::1]"));
    // IPv4-mapped IPv6 of a public address (::ffff:8.8.8.8) must not be treated as internal.
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://[::ffff:8.8.8.8]"));
  }

  @Test
  void rejects_http_for_malformed_or_short_numeric_hosts_fail_closed() {
    // Malformed / wrong-arity / non-canonical dotted-numeric authorities reach the registry-based-authority
    // fallback (URI.getHost() returns null for e.g. a trailing-dot or short dotted literal on JDK 21) and
    // are then classified as numeric IP literals. They fail the ANCHORED dotted RFC1918/loopback full-match,
    // so plain http is refused — fail-closed, never opening a cleartext-token path. No regression guard
    // existed for this path: a future refactor that stripped a trailing dot, or loosened the octet arity,
    // could silently turn one of these into a mis-classified "internal" host reachable over cleartext http.
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://10.0.0.1."));  // trailing dot on a private literal
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://127.0.0.1."));  // trailing dot on loopback
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://10.0.0"));      // 3-part (short) dotted form
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://1.2.3.4.5"));   // 5-part (long) dotted form
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("http://0177.0.0.1"));  // octal-looking first octet
    // https stays acceptable regardless of the host shape (the token never crosses the wire in clear).
    assertEquals("https://10.0.0.1.", BaseUrlValidator.validate("https://10.0.0.1."));
  }

  @Test
  void accepts_https_for_a_trailing_dot_fqdn() {
    // https is always acceptable regardless of host shape (the token never crosses the wire in clear).
    assertEquals("https://gitlab.example.com.", BaseUrlValidator.validate("https://gitlab.example.com."));
  }

  @Test
  void rejects_a_non_http_scheme() {
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("ftp://gitlab.example.com"));
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("file:///etc/passwd"));
  }

  @Test
  void rejects_a_protocol_relative_url_with_no_scheme() {
    // A "//host/path" URI parses with a NON-null host but a null scheme, so it passes the host check and
    // reaches the scheme branch. It must be rejected with the http(s) guidance (never fall through to the
    // scheme-delimiter substring, which would have no ':' to split on). Pins the explicit null-scheme reject.
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("//gitlab.example.com/path"));
    assertTrue(ex.getMessage().contains("http"), "was: " + ex.getMessage());
  }

  @Test
  void rejects_embedded_credentials() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("https://user:pass@gitlab.example.com"));
    assertTrue(ex.getMessage().contains("credentials"), "was: " + ex.getMessage());
  }

  @Test
  void rejects_blank_or_null() {
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate(null));
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate(""));
    assertThrows(IllegalArgumentException.class, () -> BaseUrlValidator.validate("   "));
  }

  @Test
  void strips_trailing_slashes() {
    assertEquals("https://gitlab.example.com", BaseUrlValidator.validate("https://gitlab.example.com/"));
    assertEquals("https://gitlab.example.com", BaseUrlValidator.validate("https://gitlab.example.com///"));
  }

  @Test
  void rejects_a_query_string() {
    // The result is concatenated as baseUrl + "/api/v4/..." in GitLabSourceClient, so a query on the
    // base URL would swallow the API path (https://host?x=1/api/v4/...) and every call would silently
    // 404. Reject it at config time with an actionable message instead of an opaque downstream failure.
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("https://gitlab.example.com?foo=1"));
    assertTrue(ex.getMessage().contains("query"), "was: " + ex.getMessage());
    assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("https://gitlab.example.com/sub?foo=1"));
  }

  @Test
  void rejects_a_fragment() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("https://gitlab.example.com#frag"));
    assertTrue(ex.getMessage().contains("fragment"), "was: " + ex.getMessage());
  }

  @Test
  void rejects_a_query_or_fragment_even_on_the_underscore_authority_path() {
    // The query/fragment checks run BEFORE host resolution, so they must also fire on the registry-based
    // authority (underscore host) branch where URI.getHost() is null and the code falls back to the raw
    // authority. Otherwise an internal http URL with a stray query/fragment would slip past and corrupt
    // the concatenated "/api/v4/..." path. The dotted-host cases above cover getHost()!=null only.
    IllegalArgumentException q = assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("http://gitlab_internal?foo=1"));
    assertTrue(q.getMessage().contains("query"), "was: " + q.getMessage());
    IllegalArgumentException f = assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("http://gitlab_internal#frag"));
    assertTrue(f.getMessage().contains("fragment"), "was: " + f.getMessage());
  }

  @Test
  void accepts_a_subpath_base_url() {
    // A relative-URL-root GitLab (served under /gitlab) is a legitimate deployment; the path must be
    // preserved so GitLabSourceClient builds https://host/gitlab/api/v4/... correctly.
    assertEquals("https://gitlab.example.com/gitlab",
        BaseUrlValidator.validate("https://gitlab.example.com/gitlab"));
    assertEquals("https://gitlab.example.com/gitlab",
        BaseUrlValidator.validate("https://gitlab.example.com/gitlab/"));
  }

  @Test
  void rejects_a_path_with_a_dot_dot_traversal_segment() {
    // A ".." segment in the base URL path is a misconfiguration for the same reason a query/fragment is:
    // the result is concatenated as baseUrl + "/api/v4/..." in GitLabSourceClient (a raw string join,
    // never URI.resolve), so the ".." is sent literally in the request-target and every fetch silently
    // 404s. Reject it at the admin boundary with a clear message rather than surface an opaque downstream
    // 404. Covers an interior, trailing and leading ".." segment.
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("https://gitlab.example.com/foo/../bar"));
    assertTrue(ex.getMessage().contains(".."), "was: " + ex.getMessage());
    assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("https://gitlab.example.com/gitlab/.."));
    assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("https://gitlab.example.com/../etc"));
    // Fires on the registry-based-authority (underscore host) branch too, mirroring the query/fragment
    // coverage: the path check runs before host resolution, so it must not depend on getHost() != null.
    assertThrows(IllegalArgumentException.class,
        () -> BaseUrlValidator.validate("http://gitlab_internal/a/../b"));
  }

  @Test
  void accepts_a_path_segment_that_merely_contains_dots() {
    // Only a WHOLE ".." segment is a traversal; a segment that merely contains dots (or ends in them) is
    // a legitimate path component and must be preserved, not caught by an over-broad substring check.
    assertEquals("https://gitlab.example.com/my..repo",
        BaseUrlValidator.validate("https://gitlab.example.com/my..repo"));
    assertEquals("https://gitlab.example.com/gitlab..",
        BaseUrlValidator.validate("https://gitlab.example.com/gitlab.."));
  }

  @Test
  void normalises_the_scheme_to_lowercase() {
    // The scheme is case-insensitive (RFC 3986); store it lowercased so the persisted base URL is
    // canonical regardless of how the admin typed it.
    assertEquals("https://gitlab.example.com", BaseUrlValidator.validate("HTTPS://gitlab.example.com"));
    assertEquals("http://localhost:8080", BaseUrlValidator.validate("HTTP://localhost:8080"));
    // ONLY the leading scheme is lowercased — a mixed-case host/path (case can be significant on the
    // GitLab side) and a trailing :port are preserved verbatim. Pins that the scheme split does not
    // corrupt the rest of the URL.
    assertEquals("https://GitLab.Example.com/Base/Path",
        BaseUrlValidator.validate("HtTpS://GitLab.Example.com/Base/Path"));
    assertEquals("http://gitlab_internal:8080/Sub",
        BaseUrlValidator.validate("HTTP://gitlab_internal:8080/Sub"));
  }
}
