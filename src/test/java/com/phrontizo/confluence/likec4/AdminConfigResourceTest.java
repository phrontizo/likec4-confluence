package com.phrontizo.confluence.likec4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.websudo.WebSudoManager;
import com.atlassian.sal.api.websudo.WebSudoSessionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Authorization-gate tests for the admin REST resource: only a Confluence system administrator may
 * read or change the GitLab base URL / token / allowlist, and validation failures map to 400 (not a
 * 500). These guard the most security-sensitive endpoint in the plugin.
 */
class AdminConfigResourceTest {
  private final AdminConfigResource resource = new AdminConfigResource();

  @AfterEach
  void clearHolder() throws Exception {
    setStatic(null);
  }

  @Test
  void get_is_503_when_plugin_not_initialised() throws Exception {
    setStatic(null);
    assertEquals(503, resource.get().getStatus());
  }

  @Test
  void update_is_503_when_plugin_not_initialised() throws Exception {
    // The POST path has the SAME config==null short-circuit as GET (the plugin is enabled but the
    // AdminConfig singleton has not been wired yet). It must return 503 — before any auth/WebSudo/persist
    // — rather than NPE. Only GET's 503 was pinned; this closes the state-changing side.
    setStatic(null);
    assertEquals(503, resource.update(null, Map.of("baseUrl", "https://gitlab.example.com")).getStatus());
  }

  @Test
  void get_and_update_stamp_the_nosniff_header_on_every_response() throws Exception {
    // Parity with AdminServlet, which sets X-Content-Type-Options: nosniff unconditionally. The GET body
    // reflects the admin-supplied baseUrl/allowlist, so the header is defence-in-depth against MIME
    // sniffing — asserted on the non-admin-403 branch (present on every response, not just the 200).
    setStatic(config(/* admin */ false));
    assertEquals("nosniff", resource.get().getHeaderString("X-Content-Type-Options"));
    assertEquals("nosniff",
        resource.update(null, Map.of("baseUrl", "https://gitlab.example.com"))
            .getHeaderString("X-Content-Type-Options"));
  }

  @Test
  void get_and_update_forbid_caching_of_the_admin_config_on_every_response() throws Exception {
    // The admin GET body reflects the configured baseUrl/allowlist; stamp Cache-Control: no-store so a
    // shared/proxy or shared-workstation browser cache can't retain the admin config past the auth gate.
    // Asserted on the non-admin-403 branch too, so the header is present on EVERY response.
    setStatic(config(/* admin */ false));
    assertEquals("no-store", resource.get().getHeaderString("Cache-Control"));
    assertEquals("no-store",
        resource.update(null, Map.of("baseUrl", "https://gitlab.example.com"))
            .getHeaderString("Cache-Control"));
  }

  @Test
  void get_is_403_for_non_admin() throws Exception {
    setStatic(config(/* admin */ false));
    Response r = resource.get();
    assertEquals(Response.Status.FORBIDDEN.getStatusCode(), r.getStatus());
    // A JSON error body, consistent with every other response on this endpoint (503/400/500 all carry
    // {"error": ...}); a bodyless 403 was an inconsistency, not an intentional silence.
    assertEquals(Map.of("error", "forbidden"), r.getEntity());
  }

  @Test
  void get_is_403_for_an_anonymous_caller() throws Exception {
    // isAdmin() guards on key != null, so an anonymous caller (null remote-user key) is denied. Pin that
    // the anonymous branch is a clean 403 + JSON body, never an NPE from isSystemAdmin(null).
    setStatic(anonymousConfig());
    Response r = resource.get();
    assertEquals(Response.Status.FORBIDDEN.getStatusCode(), r.getStatus());
    assertEquals(Map.of("error", "forbidden"), r.getEntity());
  }

  @Test
  void update_is_403_for_an_anonymous_caller() throws Exception {
    setStatic(anonymousConfig());
    Response r = resource.update(null, Map.of("baseUrl", "https://gitlab.example.com"));
    assertEquals(Response.Status.FORBIDDEN.getStatusCode(), r.getStatus());
    assertEquals(Map.of("error", "forbidden"), r.getEntity());
  }

  @Test
  @SuppressWarnings("unchecked")
  void get_is_200_for_admin_and_never_returns_the_token() throws Exception {
    AdminConfig cfg = config(true);
    when(cfg.getBaseUrl()).thenReturn("https://gitlab.example.com");
    when(cfg.getAllowlist()).thenReturn(List.of("group/proj"));
    when(cfg.hasToken()).thenReturn(true); // presence only; the page never decrypts the token
    setStatic(cfg);

    Response r = resource.get();
    assertEquals(200, r.getStatus());
    Map<String, Object> body = (Map<String, Object>) r.getEntity();
    assertEquals("https://gitlab.example.com", body.get("baseUrl"));
    assertEquals(Boolean.TRUE, body.get("tokenSet"));
    assertFalse(body.containsValue("super-secret-token"), "token plaintext must never be returned");
    verify(cfg, never()).getToken(); // must NOT decrypt just to render the page
  }

  @Test
  void get_does_not_enforce_websudo_the_read_is_gated_only_by_isSystemAdmin() throws Exception {
    // Unlike the state-changing update(), the read carries NO secret — only baseUrl, the allowlist and a
    // tokenSet BOOLEAN — so it is deliberately NOT behind a Secure Administrator Session; isSystemAdmin is
    // the entire authorization boundary. Pin that: even a WebSudo manager rigged to CHALLENGE is never
    // consulted on GET, so an admin whose session is not yet elevated can still load the config page (its
    // initial fetch). Without this, a future change adding WebSudo to the read would break that first load
    // — the admin page could never bootstrap — with no unit test to catch it.
    AdminConfig cfg = config(true);
    WebSudoManager ws = mock(WebSudoManager.class);
    when(cfg.getWebSudoManager()).thenReturn(ws);
    doThrow(new WebSudoSessionException("not elevated")).when(ws).willExecuteWebSudoRequest(any());
    when(cfg.getBaseUrl()).thenReturn("https://gitlab.example.com");
    when(cfg.getAllowlist()).thenReturn(List.of("group/proj"));
    when(cfg.hasToken()).thenReturn(false);
    setStatic(cfg);

    assertEquals(200, resource.get().getStatus());
    verify(cfg, never()).getWebSudoManager();             // the read never even fetches the WebSudo manager
    verify(ws, never()).willExecuteWebSudoRequest(any()); // ...so it is never gated on an elevated session
  }

  @Test
  void update_is_400_for_a_missing_body() throws Exception {
    setStatic(config(true));
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resource.update(basicReq(), null).getStatus());
  }

  @Test
  @SuppressWarnings("unchecked")
  void update_maps_a_token_storage_failure_to_a_generic_500_not_an_uncaught_stack() throws Exception {
    AdminConfig cfg = config(true);
    // A lost/rotated key or unwritable home makes setToken (encrypt + key-store IO) throw.
    doThrow(new IllegalStateException("encrypt failed: home key unreadable")).when(cfg).setToken("tok");
    setStatic(cfg);
    Response r = resource.update(basicReq(), Map.of("token", "tok"));
    assertEquals(500, r.getStatus());
    Map<String, Object> body = (Map<String, Object>) r.getEntity();
    assertFalse(String.valueOf(body.get("error")).contains("home key unreadable"),
        "the internal exception message must not leak to the client");
  }

  @Test
  void update_does_not_half_apply_baseUrl_or_allowlist_when_token_storage_fails() throws Exception {
    AdminConfig cfg = config(true);
    // A lost/rotated key or unwritable home makes setToken (encrypt + key-store IO) throw. The token is
    // the only persist-stage operation that can fail, so it must run FIRST — otherwise a token failure
    // returns 500 to the admin while baseUrl/allowlist have already been silently committed (a half-apply
    // that contradicts the "a rejected save never half-applies" invariant).
    doThrow(new IllegalStateException("encrypt failed: home key unreadable")).when(cfg).setToken("tok");
    setStatic(cfg);
    Response r = resource.update(basicReq(), Map.of("baseUrl", "https://new-gitlab.example.com",
        "allowlist", List.of("group/proj"), "token", "tok"));
    assertEquals(500, r.getStatus());
    verify(cfg, never()).setBaseUrl(anyString());
    verify(cfg, never()).setAllowlist(anyList());
  }

  @Test
  void update_does_not_half_apply_when_the_baseUrl_is_invalid_even_with_a_valid_allowlist() throws Exception {
    // Symmetric to the token-failure no-half-apply test above: an invalid baseUrl must 400 and persist
    // NOTHING — not the (valid) allowlist, nor the token — because all 400-able validation runs BEFORE any
    // persist. Pins that a future reorder can't commit the allowlist/token while rejecting the baseUrl.
    AdminConfig cfg = config(true);
    setStatic(cfg);
    Response r = resource.update(basicReq(), Map.of(
        "baseUrl", "ftp://not-http.example.com", // invalid scheme -> IllegalArgumentException -> 400
        "allowlist", List.of("group/proj"),       // valid
        "token", "tok"));                          // valid
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r.getStatus());
    verify(cfg, never()).setBaseUrl(anyString());
    verify(cfg, never()).setAllowlist(anyList());
    verify(cfg, never()).setToken(anyString());
  }

  @Test
  void update_proceeds_for_an_interactive_caller_when_secure_admin_sessions_disabled() throws Exception {
    // The DEFAULT Confluence configuration: Secure Admin Sessions off, so willExecuteWebSudoRequest is a
    // no-op (never throws). An interactive cookie caller must then be allowed through and the mutation run.
    AdminConfig cfg = config(true);
    WebSudoManager ws = mock(WebSudoManager.class); // willExecuteWebSudoRequest does nothing (WebSudo disabled)
    when(cfg.getWebSudoManager()).thenReturn(ws);
    setStatic(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class); // cookie session, no Basic header
    Response r = resource.update(req, Map.of("baseUrl", "https://gitlab.example.com"));
    assertEquals(200, r.getStatus());
    verify(ws).willExecuteWebSudoRequest(req); // the gate was consulted...
    verify(cfg).setBaseUrl("https://gitlab.example.com"); // ...and allowed the mutation through
  }

  @Test
  void update_is_403_for_non_admin() throws Exception {
    setStatic(config(false));
    Response r = resource.update(null, Map.of("baseUrl", "https://gitlab.example.com"));
    assertEquals(Response.Status.FORBIDDEN.getStatusCode(), r.getStatus());
    assertEquals(Map.of("error", "forbidden"), r.getEntity());
  }

  @Test
  void update_fails_closed_with_a_generic_500_when_the_websudo_manager_is_unavailable() throws Exception {
    // If the webSudoManager component-import ever failed to wire, getWebSudoManager() returns null and
    // enforceWebSudo NPEs. That NPE must be caught by updateJson's broad catch(RuntimeException) and
    // mapped to a fixed generic 500 — the mutation must NOT proceed ungated. Pin that invariant so a
    // future narrowing of the catch can't silently let a mis-wired import persist config without WebSudo.
    AdminConfig cfg = config(true);
    when(cfg.getWebSudoManager()).thenReturn(null);
    setStatic(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class); // cookie session, no Basic header
    Response r = resource.update(req, Map.of("baseUrl", "https://gitlab.example.com"));
    assertEquals(500, r.getStatus());
    verify(cfg, never()).setBaseUrl(anyString()); // the mutation must not have run
  }

  @Test
  void update_maps_invalid_baseUrl_to_400_not_500() throws Exception {
    AdminConfig cfg = config(true);
    doThrow(new IllegalArgumentException("GitLab base URL must use https"))
        .when(cfg).setBaseUrl("http://evil.example.com");
    setStatic(cfg);
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
        resource.update(basicReq(), Map.of("baseUrl", "http://evil.example.com")).getStatus());
  }

  @Test
  @SuppressWarnings("unchecked")
  void update_400_for_an_invalid_baseUrl_never_echoes_the_submitted_value() throws Exception {
    // The 400 branch returns e.getMessage() verbatim. That is safe ONLY because BaseUrlValidator's
    // messages are fixed literals that never embed the raw input — unlike the /source + /resolve REST
    // resources, which deliberately do NOT echo e.getMessage() precisely because InputValidation embeds
    // the rejected value. Pin that no-leak invariant here so a future validator change that started
    // echoing the submitted (author-controllable) base URL into its message would break CI rather than
    // silently reflect caller input back into the admin response.
    setStatic(config(true));
    String sentinel = "sentinel-leak-marker-abc123";
    // http + a public FQDN is rejected by the real BaseUrlValidator (https required off-intranet).
    Response r = resource.update(basicReq(), Map.of("baseUrl", "http://" + sentinel + ".example.com"));
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r.getStatus());
    Map<String, Object> body = (Map<String, Object>) r.getEntity();
    assertFalse(String.valueOf(body.get("error")).contains(sentinel),
        "the 400 body must not echo the submitted base URL value back to the client");
  }

  @Test
  void update_persists_valid_input_for_admin() throws Exception {
    AdminConfig cfg = config(true);
    setStatic(cfg);
    Response r = resource.update(basicReq(), Map.of("baseUrl", "https://gitlab.example.com",
        "allowlist", List.of("group/proj"), "token", "tok"));
    assertEquals(200, r.getStatus());
    verify(cfg).setBaseUrl("https://gitlab.example.com");
    verify(cfg).setToken("tok");
  }

  @Test
  void update_treats_a_blank_token_as_no_change_not_an_empty_token() throws Exception {
    AdminConfig cfg = config(true);
    setStatic(cfg);
    // The admin page JS only sends `token` when it is non-empty; a direct REST caller POSTing
    // {"token":""} (or whitespace) must NOT be stored as an empty-token ciphertext. Storing one makes
    // hasToken() report a token IS configured (so the /source gate waves the request through) while the
    // fetch then fails on the blank token with a MISLEADING 502 "cannot reach repository" instead of the
    // accurate "repository not configured". Treat a blank token as "no change".
    Response r = resource.update(basicReq(), Map.of("baseUrl", "https://gitlab.example.com", "token", ""));
    assertEquals(200, r.getStatus());
    verify(cfg, never()).setToken(anyString());       // the empty token must NOT be persisted
    verify(cfg).setBaseUrl("https://gitlab.example.com"); // ...but the rest of the update still applies
    // Whitespace is blank too.
    Response r2 = resource.update(basicReq(), Map.of("token", "   "));
    assertEquals(200, r2.getStatus());
    verify(cfg, never()).setToken(anyString());
  }

  @Test
  void update_clears_the_stored_token_when_clearToken_is_true() throws Exception {
    AdminConfig cfg = config(true);
    setStatic(cfg);
    // An explicit REVOKE: {"clearToken":true} removes the stored ciphertext entirely. A blank token is
    // "no change" (above), so this is the ONLY way to un-set a compromised token via the API. The rest of
    // the update still applies, and the token must be cleared, never re-set.
    Response r = resource.update(basicReq(), Map.of("clearToken", true, "baseUrl", "https://gitlab.example.com"));
    assertEquals(200, r.getStatus());
    verify(cfg).clearToken();
    verify(cfg, never()).setToken(anyString());
    verify(cfg).setBaseUrl("https://gitlab.example.com"); // ...and the rest of the update still applies
  }

  @Test
  void update_clearToken_takes_precedence_over_a_supplied_token() throws Exception {
    AdminConfig cfg = config(true);
    setStatic(cfg);
    // A contradictory request that both sets AND clears the token must resolve to the safer outcome —
    // REVOKE — never storing the supplied token.
    Response r = resource.update(basicReq(), Map.of("clearToken", true, "token", "tok"));
    assertEquals(200, r.getStatus());
    verify(cfg).clearToken();
    verify(cfg, never()).setToken(anyString());
  }

  @Test
  void update_never_wipes_the_token_on_a_false_or_non_boolean_clearToken() throws Exception {
    AdminConfig cfg = config(true);
    setStatic(cfg);
    // clearToken must be the literal boolean true (Boolean.TRUE.equals). A false, a stray truthy STRING
    // ("true"), or a number must NEVER wipe a live token — an accidental revoke from a malformed request
    // would be catastrophic. Pin that only the exact boolean true triggers a clear.
    assertEquals(200, resource.update(basicReq(),
        Map.of("clearToken", false, "baseUrl", "https://gitlab.example.com")).getStatus());
    assertEquals(200, resource.update(basicReq(), Map.of("clearToken", "true")).getStatus());
    assertEquals(200, resource.update(basicReq(), Map.of("clearToken", 1)).getStatus());
    verify(cfg, never()).clearToken();
  }

  @Test
  void update_ignores_non_string_baseUrl_without_500() throws Exception {
    setStatic(config(true));
    // A malformed (non-String) baseUrl must not throw an uncaught ClassCastException → 500.
    assertTrue(resource.update(basicReq(), Map.of("baseUrl", 123)).getStatus() < 500);
  }

  @Test
  @SuppressWarnings("unchecked")
  void update_drops_null_allowlist_elements_rather_than_persisting_the_literal_string_null() throws Exception {
    AdminConfig cfg = config(true);
    setStatic(cfg);
    // A JSON allowlist array can carry a null element ({"allowlist":["group/proj", null]}). Mapping it
    // through String.valueOf would persist the literal string "null"; the resource must drop nulls.
    java.util.List<Object> withNull = new java.util.ArrayList<>();
    withNull.add("group/proj");
    withNull.add(null);
    Response r = resource.update(basicReq(), Map.of("allowlist", withNull));
    assertEquals(200, r.getStatus());
    org.mockito.ArgumentCaptor<List<String>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(cfg).setAllowlist(captor.capture());
    assertEquals(List.of("group/proj"), captor.getValue(),
        "a null allowlist element must be dropped, never persisted as the literal \"null\"");
  }

  @Test
  @SuppressWarnings("unchecked")
  void update_drops_non_string_allowlist_elements_rather_than_coercing_them() throws Exception {
    AdminConfig cfg = config(true);
    setStatic(cfg);
    // A JSON allowlist array can carry a non-String element ({"allowlist":["group/proj",{"x":1},7]}).
    // String.valueOf would coerce those to junk like "{x=1}"/"7" and persist them as bogus entries;
    // mirror the baseUrl/token instanceof-String handling and keep only String elements.
    java.util.List<Object> mixed = new java.util.ArrayList<>();
    mixed.add("group/proj");
    mixed.add(Map.of("x", 1));
    mixed.add(7);
    Response r = resource.update(basicReq(), Map.of("allowlist", mixed));
    assertEquals(200, r.getStatus());
    org.mockito.ArgumentCaptor<List<String>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(cfg).setAllowlist(captor.capture());
    assertEquals(List.of("group/proj"), captor.getValue(),
        "non-String allowlist elements must be dropped, never coerced into junk entries");
  }

  @Test
  @SuppressWarnings("unchecked")
  void update_maps_a_malformed_allowlist_entry_to_400_without_persisting_or_echoing_it() throws Exception {
    AdminConfig cfg = config(true);
    setStatic(cfg);
    // A non-blank allowlist entry that could never match a real project (here a traversal payload) is
    // rejected at the admin boundary with a 400, BEFORE anything is persisted (no half-apply): neither
    // the base URL alongside it nor the allowlist itself is written. AdminConfig.validatedAllowlist is a
    // real static method (not mocked), so this exercises the actual validation path.
    java.util.List<Object> bad = new java.util.ArrayList<>();
    bad.add("platform");
    bad.add("../secret-marker-xyz");
    Response r = resource.update(basicReq(), Map.of("baseUrl", "https://gitlab.example.com", "allowlist", bad));
    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r.getStatus());
    verify(cfg, never()).setAllowlist(anyList());
    verify(cfg, never()).setBaseUrl(anyString());
    // The 400 must not echo the caller-supplied entry back (mirrors the base-URL no-echo invariant).
    Map<String, Object> body = (Map<String, Object>) r.getEntity();
    assertFalse(String.valueOf(body.get("error")).contains("secret-marker-xyz"),
        "the 400 body must not reflect the submitted allowlist entry value");
  }

  @Test
  void update_ignores_non_string_token_and_non_list_allowlist_without_500() throws Exception {
    AdminConfig cfg = config(true);
    setStatic(cfg);
    Response r = resource.update(basicReq(), Map.of("token", 123, "allowlist", "not-a-list"));
    assertEquals(200, r.getStatus());
    verify(cfg, never()).setToken(anyString());
    verify(cfg, never()).setAllowlist(anyList());
  }

  @Test
  void update_requires_a_secure_admin_session_for_an_interactive_cookie_caller() throws Exception {
    AdminConfig cfg = config(true);
    WebSudoManager ws = mock(WebSudoManager.class);
    when(cfg.getWebSudoManager()).thenReturn(ws);
    doThrow(new WebSudoSessionException("not elevated")).when(ws).willExecuteWebSudoRequest(any());
    setStatic(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class); // no Authorization header -> cookie session
    Response r = resource.update(req, Map.of("baseUrl", "https://gitlab.example.com"));
    assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), r.getStatus());
    verify(cfg, never()).setBaseUrl(anyString()); // the mutation must NOT run without a secure session
  }

  @Test
  void update_fails_closed_when_the_request_is_null() throws Exception {
    // enforceWebSudo cannot confirm the Basic-auth exemption OR an elevated cookie session without a
    // request to inspect, so a null request is DENIED (401), never waved through — a security gate must
    // not default to "proceed" on the absence of the very evidence it checks. In production the JAX-RS
    // @Context request is always injected non-null, so this branch is defence-in-depth (unreachable
    // there); pin the fail-CLOSED outcome so it can't silently regress to fail-open. Note the isAdmin
    // gate has already passed here (config(true)), so this proves the DENY is the WebSudo gate's doing.
    AdminConfig cfg = config(true);
    setStatic(cfg);
    Response r = resource.update(null, Map.of("baseUrl", "https://gitlab.example.com"));
    assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), r.getStatus());
    verify(cfg, never()).setBaseUrl(anyString()); // the mutation must NOT run for an uninspectable request
  }

  @Test
  @SuppressWarnings("unchecked")
  void update_maps_an_unexpected_websudo_failure_to_a_generic_500_not_an_uncaught_stack() throws Exception {
    AdminConfig cfg = config(true);
    WebSudoManager ws = mock(WebSudoManager.class);
    when(cfg.getWebSudoManager()).thenReturn(ws);
    // An UNEXPECTED RuntimeException from the WebSudo subsystem (or a null manager NPE) — not the
    // WebSudoSessionException the gate handles — must not escape update() as a framework stack trace.
    // It must map to the same fixed generic 500 the token-storage path uses, and run no mutation.
    doThrow(new RuntimeException("websudo internals exploded")).when(ws).willExecuteWebSudoRequest(any());
    setStatic(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class); // cookie session, no Basic header
    Response r = resource.update(req, Map.of("baseUrl", "https://gitlab.example.com"));
    assertEquals(500, r.getStatus());
    Map<String, Object> body = (Map<String, Object>) r.getEntity();
    assertFalse(String.valueOf(body.get("error")).contains("exploded"),
        "the internal exception message must not leak to the client");
    verify(cfg, never()).setBaseUrl(anyString()); // the mutation must NOT run
  }

  @Test
  void update_exempts_basic_auth_from_the_websudo_gate() throws Exception {
    AdminConfig cfg = config(true);
    WebSudoManager ws = mock(WebSudoManager.class);
    when(cfg.getWebSudoManager()).thenReturn(ws);
    setStatic(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Authorization")).thenReturn("Basic YWRtaW46YWRtaW4=");
    Response r = resource.update(req, Map.of("baseUrl", "https://gitlab.example.com"));
    assertEquals(200, r.getStatus());
    verify(ws, never()).willExecuteWebSudoRequest(any()); // Basic auth bypasses WebSudo
    verify(cfg).setBaseUrl("https://gitlab.example.com");
  }

  @Test
  void update_403s_a_forged_basic_header_on_a_non_admin_before_the_websudo_exemption() throws Exception {
    // The Basic-auth WebSudo exemption keys purely off the PRESENCE of an `Authorization: Basic …`
    // header, which any caller can assert. It is safe ONLY because isAdmin (whose identity comes from the
    // authenticated session, never the header) runs FIRST, so a non-admin forging a Basic header is 403'd
    // before the exemption is ever consulted. AdminServletTest pins this ordering for the servlet; pin the
    // twin for the resource — the existing update_exempts_basic_auth... test uses an ADMIN config, so it
    // would still pass even if a refactor reordered the exemption ahead of isAdmin. This closes that gap.
    AdminConfig cfg = config(/* admin */ false); // NON-admin session
    setStatic(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Authorization")).thenReturn("Basic YWRtaW46YWRtaW4="); // forged Basic header
    Response r = resource.update(req, Map.of("baseUrl", "https://gitlab.example.com"));
    assertEquals(Response.Status.FORBIDDEN.getStatusCode(), r.getStatus());
    assertEquals(Map.of("error", "forbidden"), r.getEntity());
    verify(cfg, never()).setBaseUrl(anyString());  // the mutation must NOT run for a non-admin
    verify(cfg, never()).getWebSudoManager();      // isAdmin denied BEFORE the WebSudo/Basic exemption path
  }

  @Test
  void update_consumes_only_application_json_as_a_csrf_barrier() throws Exception {
    // For a cookie-session admin the ONLY CSRF control on this state-changing POST is
    // @Consumes(application/json): a cross-site HTML form can only send urlencoded / multipart /
    // text-plain, so a JSON-only endpoint forces a CORS preflight a forged form cannot satisfy. WebSudo
    // only helps when Secure Admin Sessions is enabled (off by default), and the page JS sends
    // X-Atlassian-Token: no-check, so no server-side XSRF token is verified. Pin the media type so a
    // future @Consumes relaxation (e.g. adding text/plain) — which would silently remove this barrier —
    // breaks CI. The JAX-RS runtime turns a mismatched content type into a 415 we can't exercise in a
    // pure unit test, hence the annotation assertion.
    Method update = AdminConfigResource.class.getMethod("update", HttpServletRequest.class, Map.class);
    Consumes consumes = update.getAnnotation(Consumes.class);
    assertNotNull(consumes, "@Consumes must remain on the admin POST — it is the CSRF barrier");
    assertArrayEquals(new String[] {MediaType.APPLICATION_JSON}, consumes.value(),
        "the admin POST must consume application/json only");
  }

  private static AdminConfig config(boolean admin) {
    AdminConfig cfg = mock(AdminConfig.class);
    UserManager um = mock(UserManager.class);
    UserKey key = new UserKey("alice");
    when(cfg.getUserManager()).thenReturn(um);
    when(um.getRemoteUserKey()).thenReturn(key);
    when(um.isSystemAdmin(key)).thenReturn(admin);
    return cfg;
  }

  /** An anonymous caller: no remote-user key. isAdmin() must short-circuit on key==null (never call
   *  isSystemAdmin(null)) and deny. */
  private static AdminConfig anonymousConfig() {
    AdminConfig cfg = mock(AdminConfig.class);
    UserManager um = mock(UserManager.class);
    when(cfg.getUserManager()).thenReturn(um);
    when(um.getRemoteUserKey()).thenReturn(null);
    return cfg;
  }

  /**
   * A Basic-auth request — WebSudo-exempt, so {@code update()} reaches the persistence path without a
   * Secure Administrator Session. The persistence/validation tests below pass this (rather than a null
   * request) because {@code enforceWebSudo} fails CLOSED on a null request: with no request to inspect it
   * cannot confirm the caller is exempt or elevated, so it denies. Basic auth is also how the live
   * harness (and credential automation) actually authenticates, so this is the realistic shape.
   */
  private static HttpServletRequest basicReq() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Authorization")).thenReturn("Basic YWRtaW46YWRtaW4=");
    return req;
  }

  private static void setStatic(AdminConfig value) throws Exception {
    Field f = AdminConfig.class.getDeclaredField("INSTANCE");
    f.setAccessible(true);
    f.set(null, value);
  }
}
