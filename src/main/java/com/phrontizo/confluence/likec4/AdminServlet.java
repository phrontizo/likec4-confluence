package com.phrontizo.confluence.likec4;

import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.websudo.WebSudoManager;
import com.atlassian.sal.api.websudo.WebSudoSessionException;
import com.phrontizo.likec4.source.DiagramHtmlRenderer;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Confluence servlet backing the admin config page (spec §4.5).
 *
 * <p>Mapped to {@code /plugins/servlet/likec4/admin} via the {@code <servlet>} module (url-pattern
 * {@code /likec4/admin}); the {@code <web-item>} in the system-admin menu links here.
 *
 * <p>This servlet only renders the HTML form and admin-gates access. All persistence and crypto
 * live in {@link AdminConfigResource} ({@code GET}/{@code POST /rest/likec4/1.0/admin}); the page's
 * inline script talks to that REST endpoint, so nothing is duplicated here.
 *
 * <p>SAL beans are reached through {@link AdminConfig#getInstance()} (the static-holder pattern used
 * across this plugin) rather than constructor injection, because the servlet is instantiated by the
 * Confluence servlet container rather than the plugin Spring context.
 *
 * <p><b>WebSudo (Secure Administrator Sessions):</b> after the {@code isSystemAdmin} gate, an
 * interactive (cookie-session) caller must also hold a Secure Administrator Session via
 * {@link com.atlassian.sal.api.websudo.WebSudoManager} — the platform norm for an admin page that
 * stores a secret (here the GitLab service token + the outbound base URL). HTTP Basic-auth requests
 * are exempted (they present the password on every call, so the password re-verification WebSudo adds
 * is redundant, and WebSudo's threat model — a hijacked/CSRF'd cookie session — cannot produce a
 * Basic-auth request); this keeps credential-based automation working. The admin REST resource is
 * gated by {@code isSystemAdmin} (its callers are this page's JS, within the elevated session, and
 * credential-based automation).
 */
public class AdminServlet extends HttpServlet {

  @Override
  protected void service(HttpServletRequest request, HttpServletResponse response)
      throws jakarta.servlet.ServletException, IOException {
    // Stamp the defensive headers for EVERY verb, before dispatch — not only the GET the admin page uses.
    // A non-GET request falls through to HttpServlet's default 405 (Method Not Allowed), which would
    // otherwise carry none of the nosniff / X-Frame-Options / no-store / CSP protection, leaving this
    // admin surface's error responses less locked-down than its form. This makes the doGet comment's
    // "so EVERY response carries them" literally true across methods. doGet re-applies them (an idempotent
    // overwrite) so a direct doGet call — and the many unit tests that invoke it — stay covered on their own.
    applySecurityHeaders(response);
    super.service(request, response);
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    // Stamp the defensive headers UNCONDITIONALLY, before any gate branch, so EVERY response carries
    // them — not just the rendered form but the 503/login-redirect/403/WebSudo-challenge short-circuits
    // too. This mirrors the REST layer's stamp-on-every-return posture (SourceRestResource /
    // AdminConfigResource.nosniff): a short-circuit admin-surface response should be no more framable or
    // cacheable than the form itself. Set before the short-circuits (not after, as they used to be) so a
    // sendError/sendRedirect — which commits the response — cannot race ahead of the headers.
    applySecurityHeaders(response);

    AdminConfig config = AdminConfig.getInstance();
    if (config == null) {
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Plugin initialising");
      return;
    }

    // Admin gate: reuse the SAL UserManager wired into AdminConfig.
    UserManager userManager = config.getUserManager();
    if (userManager == null) {
      // The <component-import key="userManager"> is not wired yet (or failed to wire). Fail closed the
      // same way the null-config branch does — a 503 — rather than letting getRemoteUserKey() NPE escape
      // doGet as a framework stack trace, which would break the no-leak posture the WebSudo branches keep.
      response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Plugin initialising");
      return;
    }
    UserKey userKey = userManager.getRemoteUserKey();
    if (userKey == null) {
      // Not logged in — bounce to the Confluence login, returning here afterwards.
      String ctx = request.getContextPath() == null ? "" : request.getContextPath();
      // getRequestURI() is normally non-null, but some containers can yield null; guard it so
      // URLEncoder.encode(null) below cannot NPE and escape doGet as a framework stack trace (which
      // would break the no-leak error posture the WebSudo path deliberately keeps). Fall back to this
      // servlet's canonical path so the post-login bounce still lands here.
      String dest = request.getRequestURI();
      if (dest == null) {
        dest = ctx + "/plugins/servlet/likec4/admin";
      }
      // Deliberately DROP the request's query string from the post-login destination. This admin page
      // takes no query parameters, so a caller-supplied query serves no functional purpose in the bounce
      // — and reflecting it into os_destination (even URL-encoded, even though login.action only honours a
      // same-site destination) is a needless echo of attacker-controllable input. Bounce to the bare
      // servlet path only.
      response.sendRedirect(ctx + "/login.action?os_destination="
          + URLEncoder.encode(dest, StandardCharsets.UTF_8));
      return;
    }
    if (!userManager.isSystemAdmin(userKey)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "Confluence system administrator access required");
      return;
    }

    // Secure Administrator Session (WebSudo) for this token-management page — but ONLY for interactive
    // (cookie-session) requests. WebSudo's threat model is a hijacked/CSRF'd session where the attacker
    // holds the session cookie but NOT the password; it re-verifies the password before a sensitive
    // action. An HTTP Basic-auth request already presents the password on every call, so WebSudo is
    // redundant for it (and a session-hijack/CSRF attacker cannot forge a Basic-auth request) — so we
    // exempt Basic auth, which also keeps credential-based automation/CI working. When Secure Admin
    // Sessions is disabled (Confluence default) willExecuteWebSudoRequest is itself a no-op.
    if (!RequestAuth.isBasic(request)) {
      WebSudoManager webSudo = config.getWebSudoManager();
      try {
        webSudo.willExecuteWebSudoRequest(request);
      } catch (WebSudoSessionException e) {
        // Not elevated — hand off to WebSudo to write the challenge (a redirect to /authenticate.action).
        // Guard THIS call with the same generic-500 mapping the willExecuteWebSudoRequest failure below
        // gets: an unexpected unchecked exception from enforceWebSudoProtection (a mis-wired proxy, a
        // future platform change) would otherwise escape doGet as a framework stack trace — Confluence's
        // default servlet error handling can surface the chain — defeating the no-leak posture. If it
        // throws AFTER already committing the challenge response there is nothing left to write, so only
        // map to 500 while the response is still uncommitted.
        try {
          webSudo.enforceWebSudoProtection(request, response);
        } catch (RuntimeException enforceFailed) {
          if (!response.isCommitted()) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Configuration page unavailable");
          }
        }
        return;
      } catch (RuntimeException e) {
        // An UNEXPECTED RuntimeException from the WebSudo subsystem (or a null-manager NPE) — not the
        // WebSudoSessionException the gate handles — must not escape doGet as a framework stack trace
        // (Confluence's default servlet error handling can surface the chain). Map it to a fixed generic
        // 500 and render nothing, mirroring AdminConfigResource.update's no-leak posture.
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Configuration page unavailable");
        return;
      }
    }

    response.setContentType("text/html; charset=utf-8");
    try {
      PrintWriter out = response.getWriter();
      out.write(page(request.getContextPath()));
    } catch (RuntimeException e) {
      // The render path is the last branch: an unexpected unchecked failure while obtaining the writer or
      // building the page must not escape doGet as a framework stack trace either (the no-leak posture the
      // gate/WebSudo branches keep). Map it to a fixed 500 while the response is still uncommitted. A
      // genuine IOException (e.g. a client disconnect mid-write) stays the declared, non-sensitive servlet
      // I/O error and propagates — it carries no config internals.
      if (!response.isCommitted()) {
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Configuration page unavailable");
      }
    }
  }

  /**
   * Defensive headers on this admin-secret surface, applied to EVERY response branch (see {@link #doGet}).
   * The atl.admin decorator normally supplies framing protection on the rendered page, but {@code doGet}
   * explicitly anticipates the undecorated / short-circuit cases too, so these are set unconditionally:
   * <ul>
   *   <li>{@code nosniff} stops MIME-sniffing of the inline content;</li>
   *   <li>{@code X-Frame-Options: SAMEORIGIN} (+ the CSP {@code frame-ancestors 'self'}) blocks framing
   *       the admin page for clickjacking;</li>
   *   <li>{@code Cache-Control: no-store} keeps this admin-only page — bearing the inline
   *       config-management script and the token-state UI — out of shared/proxy/browser caches so it
   *       cannot be re-served on a shared workstation after logout (consistent with the REST layer's
   *       explicit no-store posture);</li>
   *   <li>a DELIBERATELY MINIMAL Content-Security-Policy: the HTML is post-processed by the atl.admin
   *       Sitemesh decorator, which injects platform AUI/AJS scripts, styles, fonts and images from
   *       same-origin batch URLs — so constraining {@code script-src}/{@code style-src}/{@code img-src}
   *       here would break the decorated page (caught only by the live GATE4). We restrict only
   *       directives that CANNOT affect the decorator's resource loading yet still close real injection
   *       classes: {@code object-src 'none'} blocks {@code <object>}/{@code <embed>} plugin injection,
   *       {@code base-uri 'self'} stops a {@code <base>} tag from re-homing every relative script URL,
   *       {@code frame-ancestors 'self'} is the modern clickjacking control. The page's one dynamic value
   *       (contextPath) is htmlAttr-escaped, so there is no live XSS sink for a script-src policy to
   *       backstop.</li>
   * </ul>
   */
  private static void applySecurityHeaders(HttpServletResponse response) {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "SAMEORIGIN");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Content-Security-Policy",
        "base-uri 'self'; object-src 'none'; frame-ancestors 'self'");
  }

  /**
   * Renders the self-contained admin page. The only dynamic value baked into the markup is the
   * context path; it is HTML-escaped and surfaced to the script via a {@code data-} attribute so no
   * value is interpolated into a JavaScript string literal.
   */
  private static String page(String contextPath) {
    String ctx = htmlAttr(contextPath);
    return "<!DOCTYPE html>\n"
        + "<html lang=\"en\">\n"
        + "<head>\n"
        + "  <meta charset=\"utf-8\"/>\n"
        + "  <meta name=\"decorator\" content=\"atl.admin\"/>\n"
        + "  <title>LikeC4 Diagrams — Configuration</title>\n"
        + "  <style>\n"
        + "    body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;margin:0;padding:24px;color:#172b4d;}\n"
        + "    .likec4-admin{max-width:680px;}\n"
        + "    .likec4-admin h1{font-size:24px;margin:0 0 4px;}\n"
        + "    .likec4-admin p.intro{color:#5e6c84;margin:0 0 24px;}\n"
        + "    .field-group{margin-bottom:20px;}\n"
        + "    .field-group label{display:block;font-weight:600;margin-bottom:6px;}\n"
        + "    .field-group .description{color:#5e6c84;font-size:12px;margin-top:6px;}\n"
        + "    .text,textarea.textarea{width:100%;box-sizing:border-box;padding:6px 8px;border:1px solid #dfe1e6;border-radius:3px;font-size:14px;}\n"
        + "    textarea.textarea{min-height:72px;font-family:monospace;}\n"
        + "    .buttons-container{margin-top:8px;}\n"
        + "    .aui-button{cursor:pointer;}\n"
        + "    #likec4-status{margin-top:16px;}\n"
        + "    .aui-message{padding:10px 12px;border-radius:3px;}\n"
        + "    .aui-message.success{background:#e3fcef;border:1px solid #abf5d1;}\n"
        + "    .aui-message.error{background:#ffebe6;border:1px solid #ffbdad;}\n"
        + "    .aui-message.info{background:#deebff;border:1px solid #b3d4ff;}\n"
        + "    .hidden{display:none;}\n"
        + "  </style>\n"
        + "</head>\n"
        + "<body data-context-path=\"" + ctx + "\">\n"
        + "  <section class=\"aui-page-panel likec4-admin\">\n"
        + "    <div class=\"aui-page-panel-inner\">\n"
        + "      <h1>LikeC4 Diagrams — Configuration</h1>\n"
        + "      <p class=\"intro\">Configure the GitLab source used by the LikeC4 diagram macro. The"
        + " service token is stored encrypted and is never sent to the browser.</p>\n"
        + "      <form id=\"likec4-admin-form\" class=\"aui\" action=\"#\">\n"
        + "        <div class=\"field-group\">\n"
        + "          <label for=\"likec4-baseUrl\">GitLab base URL</label>\n"
        + "          <input class=\"text\" type=\"url\" id=\"likec4-baseUrl\" name=\"baseUrl\""
        + " placeholder=\"https://gitlab.example.com\" autocomplete=\"off\"/>\n"
        + "          <div class=\"description\">Base URL of the GitLab instance (no trailing path).</div>\n"
        + "        </div>\n"
        + "        <div class=\"field-group\">\n"
        + "          <label for=\"likec4-token\">Service token</label>\n"
        + "          <input class=\"text\" type=\"password\" id=\"likec4-token\" name=\"token\""
        + " placeholder=\"(unchanged)\" autocomplete=\"new-password\"/>\n"
        + "          <div class=\"description\" id=\"likec4-token-state\">Leave blank to keep the existing token.</div>\n"
        + "        </div>\n"
        + "        <div class=\"field-group\">\n"
        + "          <label for=\"likec4-allowlist\">Allowlist</label>\n"
        + "          <textarea class=\"textarea\" id=\"likec4-allowlist\" name=\"allowlist\""
        + " placeholder=\"group/project, another-group/\"></textarea>\n"
        + "          <div class=\"description\">Comma-separated GitLab project paths or group prefixes permitted as diagram sources.</div>\n"
        + "        </div>\n"
        + "        <div class=\"field-group buttons-container\">\n"
        + "          <button type=\"submit\" id=\"likec4-save\" class=\"aui-button aui-button-primary\">Save</button>\n"
        + "        </div>\n"
        + "      </form>\n"
        + "      <div id=\"likec4-status\" class=\"hidden\"></div>\n"
        + "    </div>\n"
        + "  </section>\n"
        + "  <script>\n"
        + "  (function(){\n"
        // The atl.admin Sitemesh decorator supplies its own <body> tag, so the data-context-path
        // attribute we set is dropped from the rendered page. Derive the context path from AJS
        // (always present in the Confluence admin context) and fall back to the body attribute only
        // when this page happens to be served undecorated.
        + "    var ctx = (window.AJS && AJS.contextPath && AJS.contextPath())\n"
        + "      || document.body.getAttribute('data-context-path') || '';\n"
        + "    var endpoint = ctx + '/rest/likec4/1.0/admin';\n"
        + "    var form = document.getElementById('likec4-admin-form');\n"
        + "    var baseUrl = document.getElementById('likec4-baseUrl');\n"
        + "    var token = document.getElementById('likec4-token');\n"
        + "    var tokenState = document.getElementById('likec4-token-state');\n"
        + "    var allowlist = document.getElementById('likec4-allowlist');\n"
        + "    var statusEl = document.getElementById('likec4-status');\n"
        + "    var saveBtn = document.getElementById('likec4-save');\n"
        + "    function setStatus(kind, msg){\n"
        + "      statusEl.className = 'aui-message ' + kind;\n"
        + "      statusEl.textContent = msg;\n"
        + "    }\n"
        + "    function load(){\n"
        + "      fetch(endpoint, {credentials:'same-origin', headers:{'Accept':'application/json'}})\n"
        + "        .then(function(r){ if(!r.ok){ throw new Error('HTTP ' + r.status); } return r.json(); })\n"
        // The GET reflects the stored baseUrl/allowlist verbatim (validated on write, but a direct
        // settings-DB tamper could in theory place markup there). These MUST stay .value / .textContent
        // assignments (safe DOM sinks) and NEVER become .innerHTML — an innerHTML sink here would turn the
        // reflected stored value into a self-XSS on the admin page. Keep it this way if you refactor.
        + "        .then(function(data){\n"
        + "          baseUrl.value = data.baseUrl || '';\n"
        + "          allowlist.value = (data.allowlist || []).join(', ');\n"
        + "          tokenState.textContent = data.tokenSet\n"
        + "            ? 'A token is currently set. Leave blank to keep it, or enter a new value to replace it.'\n"
        + "            : 'No token is set yet. Enter the GitLab service token.';\n"
        + "        })\n"
        + "        .catch(function(e){ setStatus('error', 'Could not load current configuration: ' + e.message); });\n"
        + "    }\n"
        + "    function parseAllowlist(raw){\n"
        + "      return (raw || '').split(',').map(function(s){ return s.trim(); }).filter(function(s){ return s.length > 0; });\n"
        + "    }\n"
        + "    form.addEventListener('submit', function(ev){\n"
        + "      ev.preventDefault();\n"
        + "      saveBtn.setAttribute('disabled','disabled');\n"
        + "      setStatus('info', 'Saving…');\n"
        + "      var body = { baseUrl: baseUrl.value.trim(), allowlist: parseAllowlist(allowlist.value) };\n"
        + "      if (token.value.length > 0) { body.token = token.value; }\n"
        + "      fetch(endpoint, {\n"
        + "        method:'POST', credentials:'same-origin',\n"
        + "        headers:{'Content-Type':'application/json','Accept':'application/json','X-Atlassian-Token':'no-check'},\n"
        + "        body: JSON.stringify(body)\n"
        + "      })\n"
        + "        .then(function(r){ if(!r.ok){ throw new Error('HTTP ' + r.status); } return r.json(); })\n"
        + "        .then(function(){ setStatus('success', 'Configuration saved.'); token.value=''; load(); })\n"
        + "        .catch(function(e){ setStatus('error', 'Save failed: ' + e.message); })\n"
        + "        .then(function(){ saveBtn.removeAttribute('disabled'); });\n"
        + "    });\n"
        + "    load();\n"
        + "  })();\n"
        + "  </script>\n"
        + "</body>\n"
        + "</html>\n";
  }

  /** HTML-attribute escaping for values written into double-quoted attributes. Delegates to the one
   *  canonical escaper in {@link DiagramHtmlRenderer} (also used by the macro) rather than hand-rolling a
   *  near-duplicate — that also neutralises ISO control characters to U+FFFD for free. */
  private static String htmlAttr(String value) {
    return DiagramHtmlRenderer.escape(value);
  }
}
