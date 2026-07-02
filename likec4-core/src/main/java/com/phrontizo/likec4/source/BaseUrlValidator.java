package com.phrontizo.likec4.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates and normalizes the configured GitLab base URL. Rejects non-http(s) schemes, embedded
 * credentials, a missing host, and a query string or fragment (which would corrupt the concatenated
 * API path); lowercases the scheme and strips trailing slashes so the result is consistent with
 * {@link GitLabSourceClient}'s own normalization. A trailing path is preserved so a relative-URL-root
 * GitLab (served under e.g. {@code /gitlab}) is supported.
 *
 * <p><b>http vs https.</b> This plugin targets a <em>self-managed</em> GitLab, which is frequently an
 * internal host reachable only on a private network — so plain {@code http} is permitted for hosts
 * that cannot be reached over the public internet (loopback, RFC1918 / IPv6-ULA addresses,
 * single-label intranet names, and the conventional internal TLDs). For a <em>public</em> dotted FQDN
 * {@code https} is required, so the GitLab service token is never shipped in cleartext across the
 * internet.
 *
 * <p><b>No IP pinning (accepted SSRF residual).</b> This validator classifies the host <em>string</em>
 * only; it does not resolve the name and pin the IP, and {@link GitLabSourceClient} re-resolves per
 * request, so a base-URL host whose DNS later rebinds to a different (internal) address is followed.
 * That is acceptable here: the base URL is <em>admin-only</em> configuration and reaching internal hosts
 * is the intended function (the GitLab is internal), and {@link GitLabSourceClient} already forbids
 * redirects, which closes the token-replay-to-another-host vector.
 */
public final class BaseUrlValidator {
  private BaseUrlValidator() {}

  // A single dotted-decimal octet, bounded to 0–255. A loose \d{1,3} would misclassify a malformed
  // literal such as 127.999.0.1 or 10.0.0.256 as internal and wave it through over plain http; bounding
  // each octet fails those closed (a non-canonical numeric host then requires https) instead.
  private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)";

  // RFC1918 private IPv4 ranges: 10/8, 172.16/12, 192.168/16.
  private static final Pattern PRIVATE_IPV4 = Pattern.compile(
      "10\\." + OCTET + "\\." + OCTET + "\\." + OCTET
      + "|192\\.168\\." + OCTET + "\\." + OCTET
      + "|172\\.(?:1[6-9]|2\\d|3[01])\\." + OCTET + "\\." + OCTET);

  // Loopback 127.0.0.0/8 — the whole block, not just the .1 literal.
  private static final Pattern LOOPBACK_IPV4 =
      Pattern.compile("127\\." + OCTET + "\\." + OCTET + "\\." + OCTET);

  public static String validate(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("GitLab base URL is required");
    }
    String trimmed = raw.trim();
    URI uri;
    try {
      uri = new URI(trimmed);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("GitLab base URL is not a valid URL", e);
    }

    if (uri.getUserInfo() != null) {
      throw new IllegalArgumentException("base URL must not contain credentials");
    }

    // A query or fragment on the base URL is always a misconfiguration: the result is concatenated as
    // baseUrl + "/api/v4/..." (see GitLabSourceClient), so a query would swallow the API path
    // (https://host?x=1/api/v4/...) and a fragment would truncate it — every request then silently
    // fails. Reject them here with a clear message rather than surface an opaque downstream 404. A
    // trailing PATH is deliberately allowed: a relative-URL-root GitLab (served under /gitlab) is a
    // legitimate deployment, and baseUrl + "/api/v4/..." resolves it correctly.
    if (uri.getQuery() != null) {
      throw new IllegalArgumentException("GitLab base URL must not contain a query string");
    }
    if (uri.getFragment() != null) {
      throw new IllegalArgumentException("GitLab base URL must not contain a fragment");
    }
    // A ".." path SEGMENT is a misconfiguration for the same reason a query/fragment is: the base URL is
    // concatenated as baseUrl + "/api/v4/..." (a raw string join, never URI.resolve), so a ".." is sent
    // literally in the request-target and every fetch silently 404s. Reject it here — before host
    // resolution, so it fires on the underscore-authority branch too — rather than surface an opaque
    // downstream 404. Only a WHOLE ".." segment is a traversal; a segment that merely contains dots
    // (e.g. "my..repo") is a legitimate path component and is preserved.
    String path = uri.getPath();
    if (path != null) {
      for (String seg : path.split("/", -1)) {
        if (seg.equals("..")) {
          throw new IllegalArgumentException("GitLab base URL must not contain a '..' path segment");
        }
      }
    }

    String host = uri.getHost();
    if (host == null) {
      // URI.getHost() returns null for a registry-based authority — most notably one containing an
      // underscore (e.g. http://gitlab_internal), which is common on Windows/AD and docker-compose
      // internal networks and is exactly the internal-http case this validator permits. Fall back to
      // the raw authority (stripping any :port) so such a host isn't wrongly rejected as missing. In
      // this path getUserInfo() is also null, so re-check the authority for embedded credentials.
      String authority = uri.getAuthority();
      if (authority != null) {
        if (authority.indexOf('@') >= 0) {
          throw new IllegalArgumentException("base URL must not contain credentials");
        }
        host = authority.replaceFirst(":\\d+$", ""); // drop a trailing :port (no IPv6 literal here)
      }
    }
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("GitLab base URL must include a host");
    }

    // A null scheme reaches here only for a protocol-relative "//host" form (a scheme-less "gitlab…" is
    // already rejected above as host-less). Reject it explicitly — rather than via an empty-string
    // sentinel that falls through to the final else — so the branches below only ever see a real scheme,
    // which also makes the scheme-delimiter substring at the end provably safe (a matched http/https
    // scheme always contains the ':' that indexOf finds).
    String rawScheme = uri.getScheme();
    if (rawScheme == null) {
      throw new IllegalArgumentException("GitLab base URL must use http(s)");
    }
    String scheme = rawScheme.toLowerCase(Locale.ROOT);
    if (scheme.equals("https")) {
      // always acceptable
    } else if (scheme.equals("http")) {
      if (!isInternalHost(host)) {
        // http to a public host would ship the service token in cleartext over the internet. This also
        // fires (fail-closed) for a host we cannot classify as internal — notably an all-numeric
        // single-label host like "12345" that is neither a dotted RFC1918/loopback literal nor a name —
        // hence "public or unrecognised" rather than just "public".
        throw new IllegalArgumentException("GitLab base URL must use https for a public or unrecognised host");
      }
    } else {
      throw new IllegalArgumentException("GitLab base URL must use http(s)");
    }

    // Canonicalise the (case-insensitive) scheme to lowercase and strip trailing slashes, so the stored
    // base URL is consistent regardless of how the admin typed it. Only the scheme prefix is rewritten;
    // host/path case is left intact (the path can be case-sensitive on the GitLab side). Split at the
    // FIRST ':' — for a valid absolute URI that is exactly the scheme delimiter (a scheme is
    // ALPHA *(ALPHA / DIGIT / "+" / "-" / ".") and contains no ':'), which is more self-evidently the
    // scheme boundary than substring(getScheme().length()) and does not depend on getScheme() echoing
    // the source token verbatim.
    String rest = trimmed.substring(trimmed.indexOf(':'));
    return (scheme + rest).replaceAll("/+$", "");
  }

  /** Whether plain http is acceptable for {@code host}: it cannot be reached over the public internet. */
  private static boolean isInternalHost(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    if (h.startsWith("[") && h.endsWith("]")) {
      h = h.substring(1, h.length() - 1); // strip IPv6 literal brackets
    }
    if (h.equals("localhost")) {
      return true;
    }
    // IPv6 literal: classify only genuine loopback / unique-local / link-local addresses. (A '[' is
    // never present in a hostname, and getHost() only yields ':' for an IPv6 literal.)
    if (h.indexOf(':') >= 0) {
      return h.equals("::1") || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:");
    }
    // A numeric IPv4 encoding must be classified as an IP literal, NOT as a single-label intranet
    // name. We do NOT decode the number; we simply require it to MATCH the canonical dotted-decimal
    // RFC1918/loopback regexes to be trusted for cleartext http. Any non-canonical numeric form —
    // decimal (2130706433), octal (010.0.0.1), or hex (0x08080808) — fails that match and therefore
    // requires https. This closes the bypass where a PUBLIC address disguised as a non-dotted number
    // (e.g. 0x08080808 == 8.8.8.8) fell through to the single-label rule and shipped the token in clear.
    if (isNumericIpLiteral(h)) {
      return LOOPBACK_IPV4.matcher(h).matches() || PRIVATE_IPV4.matcher(h).matches();
    }
    if (!h.contains(".")) {
      return true; // single-label intranet name (e.g. "gitlab", "mockgitlab")
    }
    return h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan")
        || h.endsWith(".intranet") || h.endsWith(".test");
  }

  /** Whether the de-bracketed, lowercased {@code h} is a numeric IPv4 encoding rather than a name. */
  private static boolean isNumericIpLiteral(String h) {
    if (h.isEmpty()) {
      return false;
    }
    if (h.startsWith("0x")) {
      // A "0x…" host is a hex-IPv4 literal (e.g. 0x7f000001) ONLY when every remaining char is a hex
      // digit. Otherwise it is a hostname that merely starts with 0x (e.g. 0xen.local) and must fall
      // through to the name-based rules — not be force-classified as a numeric IP (which would wrongly
      // require https for an internal host). h is already lower-cased, so only 'a'..'f' are hex.
      if (h.length() == 2) {
        return false;
      }
      for (int i = 2; i < h.length(); i++) {
        char c = h.charAt(i);
        if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
          return false;
        }
      }
      return true;
    }
    for (int i = 0; i < h.length(); i++) {
      char c = h.charAt(i);
      if ((c < '0' || c > '9') && c != '.') {
        return false; // contains a letter → it's a hostname, not a numeric IPv4 form
      }
    }
    return true; // only digits and dots → decimal / octal / dotted IPv4
  }
}
