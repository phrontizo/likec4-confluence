package com.phrontizo.confluence.likec4;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Admin-gate tests for the config servlet: an uninitialised plugin yields 503, an anonymous caller is
 * bounced to login, a logged-in non-admin gets 403, and an admin must clear WebSudo before the form
 * renders — none of the rejected cases may render the config form.
 */
class AdminServletTest {
  private final AdminServlet servlet = new AdminServlet();

  @AfterEach
  void clearHolder() throws Exception {
    setInstance(null);
  }

  @Test
  void doGet_is_503_when_plugin_not_initialised() throws Exception {
    setInstance(null);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    verify(resp).sendError(eq(HttpServletResponse.SC_SERVICE_UNAVAILABLE), anyString());
  }

  @Test
  void doGet_redirects_anonymous_caller_to_login() throws Exception {
    setInstance(configWith(/* key */ null, false));
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn("/confluence/plugins/servlet/likec4/admin");
    when(req.getQueryString()).thenReturn(null);
    when(req.getContextPath()).thenReturn("/confluence");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    verify(resp).sendRedirect(contains("/login.action"));
  }

  @Test
  void doGet_is_403_for_logged_in_non_admin() throws Exception {
    setInstance(configWith(new UserKey("alice"), /* admin */ false));
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
  }

  @Test
  void doGet_renders_form_for_admin_with_websudo_satisfied() throws Exception {
    AdminConfig cfg = configWith(new UserKey("admin"), /* admin */ true);
    WebSudoManager webSudo = cfg.getWebSudoManager();
    setInstance(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class); // cookie session, no Basic header
    when(req.getContextPath()).thenReturn("/confluence");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    servlet.doGet(req, resp);
    verify(resp).setContentType(contains("text/html"));
    assertTrue(sw.toString().contains("likec4-admin-form"), "the admin form HTML must be written");
    // The WebSudo gate must actually be CONSULTED for a cookie-session admin. It is SATISFIED here (the
    // mock does not throw), so the form renders — but a regression that rendered the form WITHOUT ever
    // gating on WebSudo would still write the form and pass the assertion above; pin that the gate ran.
    verify(webSudo).willExecuteWebSudoRequest(req);
  }

  @Test
  void doGet_sets_clickjacking_nosniff_no_store_and_csp_headers_on_the_rendered_admin_page() throws Exception {
    setInstance(configWith(new UserKey("admin"), /* admin */ true));
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getContextPath()).thenReturn("/confluence");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    servlet.doGet(req, resp);
    verify(resp).setHeader("X-Content-Type-Options", "nosniff");
    verify(resp).setHeader("X-Frame-Options", "SAMEORIGIN");
    // no-store keeps this admin-only page (inline config script + token-state UI) out of shared/proxy
    // caches so it cannot be re-served after logout — consistent with the REST layer's Cache-Control.
    verify(resp).setHeader("Cache-Control", "no-store");
    // A minimal CSP that hardens against base-tag/object injection and framing without constraining the
    // atl.admin decorator's same-origin script/style/resource loading (which a default-src lockdown would
    // break — caught only by GATE4). object-src 'none' + base-uri 'self' + frame-ancestors 'self'.
    ArgumentCaptor<String> csp = ArgumentCaptor.forClass(String.class);
    verify(resp).setHeader(eq("Content-Security-Policy"), csp.capture());
    assertTrue(csp.getValue().contains("object-src 'none'"), "CSP must block object/embed: " + csp.getValue());
    assertTrue(csp.getValue().contains("base-uri 'self'"), "CSP must pin base-uri: " + csp.getValue());
    assertTrue(csp.getValue().contains("frame-ancestors 'self'"), "CSP must set frame-ancestors: " + csp.getValue());
  }

  @Test
  void doGet_stamps_the_security_headers_on_the_403_short_circuit_too() throws Exception {
    // The defensive headers (nosniff / X-Frame-Options / no-store / CSP) must be stamped on EVERY branch,
    // not only the rendered form — mirroring the REST layer's stamp-on-every-return posture
    // (SourceRestResource/AdminConfigResource.nosniff). A logged-in non-admin gets a 403 short-circuit;
    // pin that the anti-framing / no-store / nosniff protections are present on it too, so the 403
    // admin-surface response cannot be framed for clickjacking nor cached on a shared workstation.
    setInstance(configWith(new UserKey("alice"), /* admin */ false));
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
    verify(resp).setHeader("X-Content-Type-Options", "nosniff");
    verify(resp).setHeader("X-Frame-Options", "SAMEORIGIN");
    verify(resp).setHeader("Cache-Control", "no-store");
    verify(resp).setHeader(eq("Content-Security-Policy"), anyString());
  }

  @Test
  void doGet_html_escapes_the_context_path_so_it_cannot_break_out_of_the_data_attribute() throws Exception {
    // contextPath is the one dynamic value baked into the page markup (data-context-path="..."). It is
    // deployment-controlled, but the htmlAttr escaping is the sole output-encoding control on this page —
    // so pin that a metacharacter-laden context path is neutralised and cannot inject markup/script. A
    // refactor that dropped the escaping would make THIS test fail (every other servlet test uses a clean
    // "/confluence" path and would not).
    setInstance(configWith(new UserKey("admin"), /* admin */ true));
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getContextPath()).thenReturn("/a\"><svg onload=alert(1)>");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    servlet.doGet(req, resp);
    String html = sw.toString();
    assertFalse(html.contains("\"><svg onload=alert(1)>"), "the raw payload must not appear unescaped");
    assertFalse(html.contains("<svg onload"), "no injected element may reach the markup");
    assertTrue(html.contains("data-context-path=\"/a&quot;&gt;&lt;svg onload=alert(1)&gt;\""),
        "the context path must be HTML-attribute-escaped, was: " + html);
  }

  @Test
  void doGet_challenges_websudo_when_required_and_does_not_render() throws Exception {
    AdminConfig cfg = configWith(new UserKey("admin"), /* admin */ true);
    WebSudoManager webSudo = cfg.getWebSudoManager();
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    doThrow(new WebSudoSessionException("websudo required")).when(webSudo).willExecuteWebSudoRequest(req);
    setInstance(cfg);
    servlet.doGet(req, resp);
    verify(webSudo).enforceWebSudoProtection(req, resp);
    verify(resp, never()).getWriter(); // the form must NOT be rendered when WebSudo is required
  }

  @Test
  void doGet_exempts_basic_auth_from_websudo() throws Exception {
    AdminConfig cfg = configWith(new UserKey("admin"), /* admin */ true);
    WebSudoManager webSudo = cfg.getWebSudoManager();
    // Even if WebSudo WOULD challenge an interactive session, a Basic-auth request is exempt: it
    // presents the password per-request, so it renders the form directly without a re-auth challenge.
    doThrow(new WebSudoSessionException("websudo required")).when(webSudo).willExecuteWebSudoRequest(any());
    setInstance(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Authorization")).thenReturn("Basic YWRtaW46YWRtaW4=");
    when(req.getContextPath()).thenReturn("/confluence");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));
    servlet.doGet(req, resp);
    verify(webSudo, never()).enforceWebSudoProtection(any(), any());
    assertTrue(sw.toString().contains("likec4-admin-form"), "Basic-auth admin must get the form, not a challenge");
  }

  @Test
  void doGet_maps_an_unexpected_websudo_failure_to_a_generic_500_not_an_uncaught_stack() throws Exception {
    // An UNEXPECTED RuntimeException from the WebSudo subsystem (or a null-manager NPE) — not the
    // WebSudoSessionException the gate handles — must not escape doGet as a framework stack trace. It
    // must map to a fixed generic 500 and render no form, mirroring AdminConfigResource.update.
    AdminConfig cfg = configWith(new UserKey("admin"), /* admin */ true);
    WebSudoManager webSudo = cfg.getWebSudoManager();
    doThrow(new RuntimeException("websudo internals exploded")).when(webSudo).willExecuteWebSudoRequest(any());
    setInstance(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class); // cookie session, no Basic header
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    verify(resp).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), msg.capture());
    assertFalse(msg.getValue().contains("exploded"), "the internal exception message must not leak to the client");
    verify(resp, never()).getWriter(); // no form may be rendered
  }

  @Test
  void doGet_maps_a_websudo_enforce_failure_to_a_generic_500_not_an_uncaught_stack() throws Exception {
    // The WebSudoSessionException path hands off to enforceWebSudoProtection to write the challenge. If
    // THAT call throws an unchecked exception (a mis-wired proxy, a future platform change), it must not
    // escape doGet as a framework stack trace either — map it to the same fixed generic 500 the
    // willExecuteWebSudoRequest failure maps to, and render no form. Without this test a refactor could
    // silently leave the enforce call outside the no-leak envelope.
    AdminConfig cfg = configWith(new UserKey("admin"), /* admin */ true);
    WebSudoManager webSudo = cfg.getWebSudoManager();
    HttpServletRequest req = mock(HttpServletRequest.class); // cookie session, no Basic header
    HttpServletResponse resp = mock(HttpServletResponse.class);
    doThrow(new WebSudoSessionException("websudo required")).when(webSudo).willExecuteWebSudoRequest(req);
    doThrow(new RuntimeException("enforce internals exploded"))
        .when(webSudo).enforceWebSudoProtection(req, resp);
    setInstance(cfg);
    servlet.doGet(req, resp);
    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    verify(resp).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), msg.capture());
    assertFalse(msg.getValue().contains("exploded"), "the internal exception message must not leak to the client");
    verify(resp, never()).getWriter(); // no form may be rendered
  }

  @Test
  void a_non_get_verb_does_not_render_the_admin_page() throws Exception {
    // The admin form posts via fetch() to the REST resource, not to this servlet, which overrides only
    // doGet. A non-GET verb must therefore never render the token page — it falls through to
    // HttpServlet's default 405 (Method Not Allowed) handling. Pin that no form leaks via POST, matching
    // the rigour with which every other admin-surface invariant here is pinned.
    setInstance(configWith(new UserKey("admin"), /* admin */ true));
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getProtocol()).thenReturn("HTTP/1.1");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.service((jakarta.servlet.ServletRequest) req, (jakarta.servlet.ServletResponse) resp);
    verify(resp, never()).getWriter(); // no form rendered for a non-GET verb
    verify(resp).sendError(eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
    // The 405 must still carry the admin-surface defensive headers — service() stamps them for every verb,
    // not only the GET doGet handles — so a non-GET response is no more sniffable/framable/cacheable than
    // the form. Matches doGet's "EVERY response carries them" posture across methods. CSP is included:
    // applySecurityHeaders sets all four together, so a refactor that moved the CSP setHeader onto only the
    // doGet render path (dropping frame-ancestors/object-src/base-uri from this 405 branch) must fail here.
    verify(resp).setHeader("X-Content-Type-Options", "nosniff");
    verify(resp).setHeader("X-Frame-Options", "SAMEORIGIN");
    verify(resp).setHeader("Cache-Control", "no-store");
    verify(resp).setHeader(eq("Content-Security-Policy"), anyString());
  }

  @Test
  void a_head_request_still_runs_the_admin_gate_and_leaks_no_body() throws Exception {
    // HEAD is a real, reachable verb on this admin surface. HttpServlet dispatches it through doHead →
    // doGet with a body-suppressing response wrapper, so the FULL admin/WebSudo gate still runs and no
    // body escapes. The 405 test only covers POST (which HttpServlet 405s outright); pin that HEAD does
    // NOT get some other handling that bypasses the gate — a non-admin HEAD must be gated (403), never
    // silently served. (Header assertions are omitted here: service() stamps them on the raw response and
    // doGet re-stamps them via the wrapper, so the count is verb-dependent; the gate outcome is the point.)
    setInstance(configWith(new UserKey("alice"), /* admin */ false));
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("HEAD");
    when(req.getProtocol()).thenReturn("HTTP/1.1");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.service((jakarta.servlet.ServletRequest) req, (jakarta.servlet.ServletResponse) resp);
    verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
    verify(resp, never()).getWriter(); // no admin-page body may be written for a HEAD request
  }

  @Test
  void doGet_fails_closed_with_a_503_when_the_user_manager_is_unavailable() throws Exception {
    // If the <component-import key="userManager"> ever failed to wire, getUserManager() returns null and
    // the admin-gate read (getRemoteUserKey) NPEs. Fail CLOSED like the null-config branch — a 503, no
    // form, no leaked stack trace — rather than letting the NPE escape doGet (breaking the no-leak posture
    // its REST twin and the WebSudo branches keep). Without a test a refactor could silently reintroduce it.
    AdminConfig cfg = configWith(new UserKey("admin"), /* admin */ true);
    when(cfg.getUserManager()).thenReturn(null);
    setInstance(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    verify(resp).sendError(eq(HttpServletResponse.SC_SERVICE_UNAVAILABLE), anyString());
    verify(resp, never()).getWriter(); // the token page must NOT render when the user gate can't be read
  }

  @Test
  void doGet_fails_closed_with_a_500_when_writing_the_page_throws_unchecked() throws Exception {
    // The render path is the last branch without a no-leak envelope: an unexpected unchecked failure
    // while obtaining the writer / building the page must map to a fixed 500 while uncommitted, mirroring
    // the WebSudo branches — not escape doGet as a framework stack trace. (A genuine IOException stays the
    // declared, non-sensitive servlet I/O error.) Force getWriter() to throw to pin that envelope.
    setInstance(configWith(new UserKey("admin"), /* admin */ true));
    HttpServletRequest req = mock(HttpServletRequest.class); // cookie session, WebSudo mock does not throw
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.isCommitted()).thenReturn(false);
    when(resp.getWriter()).thenThrow(new RuntimeException("writer boom"));
    servlet.doGet(req, resp);
    verify(resp).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), anyString());
  }

  @Test
  void doGet_fails_closed_with_a_generic_500_when_the_websudo_manager_is_unavailable() throws Exception {
    // If the <component-import key="webSudoManager"> ever failed to wire (import unavailable, a future
    // platform interface change), getWebSudoManager() returns null and willExecuteWebSudoRequest NPEs.
    // The comment on doGet's catch(RuntimeException) claims a null-manager NPE is handled — pin that
    // invariant: it must fail CLOSED (generic 500, no form), never render the token page ungated nor
    // leak a stack trace. Without a test, a refactor narrowing that catch would silently break it.
    AdminConfig cfg = configWith(new UserKey("admin"), /* admin */ true);
    when(cfg.getWebSudoManager()).thenReturn(null);
    setInstance(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class); // cookie session, no Basic header
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    verify(resp).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), anyString());
    verify(resp, never()).getWriter(); // the token page must NOT render when WebSudo can't be consulted
  }

  @Test
  void doGet_non_admin_is_403_before_any_websudo_challenge() throws Exception {
    // The admin gate must short-circuit BEFORE the WebSudo block: a non-admin must get a plain 403,
    // never a WebSudo challenge (which could hint the page exists / leak the admin surface).
    AdminConfig cfg = configWith(new UserKey("alice"), /* admin */ false);
    WebSudoManager webSudo = cfg.getWebSudoManager();
    doThrow(new WebSudoSessionException("websudo required")).when(webSudo).willExecuteWebSudoRequest(any());
    setInstance(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
    verify(webSudo, never()).willExecuteWebSudoRequest(any());
    verify(webSudo, never()).enforceWebSudoProtection(any(), any());
  }

  @Test
  void doGet_forged_basic_header_on_a_non_admin_is_403_before_the_websudo_exemption() throws Exception {
    // The Basic-auth WebSudo EXEMPTION (RequestAuth.isBasic) keys purely off the presence of an
    // `Authorization: Basic …` header, so a caller can ASSERT a Basic scheme. That is safe ONLY because
    // the isSystemAdmin gate runs BEFORE the exemption and identity comes from the authenticated session
    // (never the header) — so a forged Basic header on a non-admin session is 403'd before the exemption
    // is ever consulted. AdminConfigResource has the equivalent test; the servlet did not. Pin the
    // ordering here so a refactor that moved the exemption ahead of isSystemAdmin (which would let a
    // forged header skip WebSudo) fails loudly. The form must never render.
    AdminConfig cfg = configWith(new UserKey("alice"), /* admin */ false);
    WebSudoManager webSudo = cfg.getWebSudoManager();
    setInstance(cfg);
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Authorization")).thenReturn("Basic YWxpY2U6cw=="); // forged: alice is not an admin
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    verify(resp).sendError(eq(HttpServletResponse.SC_FORBIDDEN), anyString());
    verify(webSudo, never()).willExecuteWebSudoRequest(any());
    verify(webSudo, never()).enforceWebSudoProtection(any(), any());
    verify(resp, never()).getWriter(); // the token page must NOT render for a forged-Basic non-admin
  }

  @Test
  void doGet_drops_the_attacker_controllable_query_string_from_the_login_redirect() throws Exception {
    // The admin page takes NO query parameters, so a caller-supplied query string serves no purpose in
    // the post-login os_destination — and reflecting it there (even URL-encoded, even though login.action
    // only honours a same-site destination) is a needless echo of attacker-controllable input. Bounce to
    // the bare servlet path instead; the destination path is still URL-encoded as before.
    setInstance(configWith(/* anonymous */ null, false));
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn("/confluence/plugins/servlet/likec4/admin");
    when(req.getQueryString()).thenReturn("foo=bar&baz=1");
    when(req.getContextPath()).thenReturn("/confluence");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
    verify(resp).sendRedirect(dest.capture());
    String url = dest.getValue();
    assertTrue(url.contains("/login.action?os_destination="), "must bounce to login: " + url);
    // os_destination carries ONLY this servlet's path, URL-encoded — never the caller's query string.
    String encPath = URLEncoder.encode("/confluence/plugins/servlet/likec4/admin", StandardCharsets.UTF_8);
    assertTrue(url.contains(encPath), "the servlet path must be the destination: " + url);
    assertFalse(url.contains("foo") || url.contains("bar") || url.contains("baz"),
        "the caller's query string must NOT be reflected into os_destination: " + url);
  }

  @Test
  void doGet_falls_back_to_the_servlet_path_when_the_request_uri_is_null() throws Exception {
    // Some servlet containers can yield a null request-URI. The anonymous login-bounce must NOT NPE
    // inside URLEncoder.encode(null) — an uncaught NPE would escape doGet as a framework stack trace,
    // breaking the no-leak error posture the WebSudo path deliberately maintains. It must fall back to a
    // safe destination and still redirect to login.
    setInstance(configWith(/* anonymous */ null, false));
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getRequestURI()).thenReturn(null);
    when(req.getQueryString()).thenReturn(null);
    when(req.getContextPath()).thenReturn("/confluence");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
    verify(resp).sendRedirect(dest.capture());
    String url = dest.getValue();
    assertTrue(url.contains("/login.action?os_destination="), "must still bounce to login: " + url);
    assertFalse(url.contains("null"), "a null request-URI must not leak into the redirect: " + url);
    // The fallback must target this servlet's own canonical path so the post-login bounce lands here.
    String enc = URLEncoder.encode("/confluence/plugins/servlet/likec4/admin", StandardCharsets.UTF_8);
    assertTrue(url.contains(enc), "must fall back to the servlet path: " + url);
  }

  @Test
  void doGet_url_encodes_a_metacharacter_laden_request_uri_so_it_cannot_inject_the_login_redirect()
      throws Exception {
    // The post-login os_destination reflects request.getRequestURI() — a value a client fully controls
    // on the request line. It is passed through URLEncoder.encode, but only clean-path cases pin the
    // encoding today; this is the adversarial counterpart (symmetric with the context-path escaping
    // test above). A raw request URI carrying `&`/`<`/`>`/quotes/CRLF must NOT appear verbatim in the
    // redirect — otherwise a caller could break out of os_destination to inject an extra login.action
    // query parameter, splice markup, or (via CR/LF) tamper with the redirect. Pin that every
    // metacharacter is percent-encoded and none survives literally.
    setInstance(configWith(/* anonymous */ null, false));
    HttpServletRequest req = mock(HttpServletRequest.class);
    String malicious = "/confluence/plugins/servlet/likec4/admin&next=<script>\"'\r\nSet-Cookie:x=1";
    when(req.getRequestURI()).thenReturn(malicious);
    when(req.getQueryString()).thenReturn(null);
    when(req.getContextPath()).thenReturn("/confluence");
    HttpServletResponse resp = mock(HttpServletResponse.class);
    servlet.doGet(req, resp);
    ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
    verify(resp).sendRedirect(dest.capture());
    String url = dest.getValue();
    assertTrue(url.startsWith("/confluence/login.action?os_destination="), "must bounce to login: " + url);
    // The whole malicious URI must be present ONLY in its percent-encoded form.
    assertTrue(url.contains(URLEncoder.encode(malicious, StandardCharsets.UTF_8)),
        "the request URI must be URL-encoded into os_destination: " + url);
    // And none of the dangerous raw metacharacters may survive: no injected `&param=`, no raw markup,
    // no CR/LF splice.
    assertFalse(url.contains("&next="), "a raw ampersand must not inject an extra login param: " + url);
    assertFalse(url.contains("<script>"), "raw markup must not survive into the redirect: " + url);
    assertFalse(url.indexOf('\r') >= 0 || url.indexOf('\n') >= 0,
        "no raw CR/LF may reach the redirect target: " + url);
  }

  private static AdminConfig configWith(UserKey key, boolean admin) {
    AdminConfig cfg = mock(AdminConfig.class);
    UserManager um = mock(UserManager.class);
    when(cfg.getUserManager()).thenReturn(um);
    when(um.getRemoteUserKey()).thenReturn(key);
    if (key != null) {
      when(um.isSystemAdmin(key)).thenReturn(admin);
    }
    when(cfg.getWebSudoManager()).thenReturn(mock(WebSudoManager.class));
    return cfg;
  }

  private static void setInstance(AdminConfig value) throws Exception {
    Field f = AdminConfig.class.getDeclaredField("INSTANCE");
    f.setAccessible(true);
    f.set(null, value);
  }
}
