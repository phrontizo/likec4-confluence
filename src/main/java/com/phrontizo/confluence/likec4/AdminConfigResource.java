package com.phrontizo.confluence.likec4;

import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.websudo.WebSudoManager;
import com.atlassian.sal.api.websudo.WebSudoSessionException;
import com.phrontizo.likec4.source.BaseUrlValidator;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Admin REST resource: GET/POST /rest/likec4/1.0/admin.
 *
 * <p>Spring/SAL beans are resolved via static holders — same rationale as {@link SourceRestResource}.
 * (JAX-RS-native {@code @Context} injection — the {@link HttpServletRequest} on {@link #update} — works
 * fine; it is only the Spring/SAL beans that HK2 cannot reach, hence the holders.)
 *
 * <p><b>Authorization &amp; CSRF:</b> every call is gated by {@code isSystemAdmin}. The state-changing
 * {@code POST} additionally enforces WebSudo (a Secure Administrator Session) for interactive
 * cookie-session callers, programmatically rather than via {@code @WebSudoRequired} (a plain-Jersey
 * resource does not reliably honour the annotation). HTTP Basic-auth callers are exempt — they present
 * the password on every request and a CSRF'd cookie session cannot forge a Basic request — which keeps
 * credential-based automation working, mirroring {@link AdminServlet}. Together with {@code @Consumes}
 * {@code application/json} (a content type a cross-site HTML form cannot set, so a forged POST is
 * rejected/preflighted) this defends the token/base-URL mutation against CSRF and SSRF-token-exfil. The
 * admin page's own JS calls this within the already-elevated session the servlet established.
 *
 * <p><b>CSRF residual (honest):</b> in the DEFAULT instance config — Secure Admin Sessions off, so
 * WebSudo is a no-op — and given the page JS sends {@code X-Atlassian-Token: no-check} (skipping
 * Confluence's own XSRF token), the {@code @Consumes(application/json)} content-type check is the SOLE
 * CSRF control on this mutation. That control is sound but browser-policy-dependent, so the {@code
 * @Consumes} media type is pinned by a unit test to prevent a future relaxation silently removing it.
 */
@Path("/admin")
public class AdminConfigResource {

  private static final Logger LOG = System.getLogger(AdminConfigResource.class.getName());

  /** No-arg constructor: Jersey/HK2 creates instances; beans accessed via static holders. */
  public AdminConfigResource() {}

  /**
   * Stamp {@code X-Content-Type-Options: nosniff} and {@code Cache-Control: no-store} on every response,
   * mirroring {@link AdminServlet}'s nosniff/no-store posture. (The servlet additionally sets
   * {@code X-Frame-Options} and a {@code Content-Security-Policy}; those are deliberately NOT applied
   * here — this resource only ever returns JSON, which is not framable/clickjackable, so a frame/CSP
   * header would be inert.) The GET body reflects the admin-supplied baseUrl/allowlist; the
   * {@code nosniff} header is defence-in-depth against a browser MIME-sniffing this JSON as HTML (even
   * though {@code @Produces(application/json)} already fixes the Content-Type), and {@code no-store}
   * keeps the admin config out of any shared/proxy or shared-workstation browser cache. Applied once
   * here so no branch can forget either header.
   */
  private static Response nosniff(Response r) {
    return Response.fromResponse(r)
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "no-store")
        .build();
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response get() {
    return nosniff(getJson());
  }

  private Response getJson() {
    AdminConfig config = AdminConfig.getInstance();
    if (config == null) return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Map.of("error", "plugin initialising")).build();
    if (!isAdmin(config)) return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "forbidden")).build();
    // WebSudo is deliberately NOT enforced on this read (unlike the POST): the response carries no
    // secret — only baseUrl, the allowlist, and a tokenSet BOOLEAN, never the token itself. Both are
    // admin-visible configuration, not credentials, so the isSystemAdmin gate above is the entire
    // authorization boundary here; only the state-changing update() additionally requires a Secure
    // Administrator Session.
    // Use hasToken() (a plain settings read), NOT getToken() — decrypting the token just to null-check
    // it would 500 the whole config page if the key file was lost/rotated or the ciphertext is corrupt.
    return Response.ok(Map.of(
        "baseUrl", nz(config.getBaseUrl()),
        "allowlist", config.getAllowlist(),
        "tokenSet", config.hasToken())).build();
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response update(@Context HttpServletRequest request, Map<String, Object> body) {
    return nosniff(updateJson(request, body));
  }

  private Response updateJson(HttpServletRequest request, Map<String, Object> body) {
    AdminConfig config = AdminConfig.getInstance();
    if (config == null) return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Map.of("error", "plugin initialising")).build();
    if (!isAdmin(config)) return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "forbidden")).build();
    // WebSudo + body checks are INSIDE the try: an unexpected RuntimeException from the WebSudo
    // subsystem (or a null manager NPE) must map to the fixed generic 500 below, never escape as a
    // framework stack trace (which can leak the exception chain) — the same no-leak posture the
    // token-storage path has. The WebSudoSessionException the gate expects is caught within
    // enforceWebSudo itself and returned as a 401, so it does not reach the generic 500.
    try {
      Response webSudo = enforceWebSudo(config, request);
      if (webSudo != null) return webSudo;
      if (body == null) return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "missing body")).build();
      // Validate everything that can fail with a 400 (the base URL) BEFORE persisting anything, so a
      // rejected save never half-applies (e.g. base URL repointed but the rest unsaved).
      // BaseUrlValidator.validate is pure; setBaseUrl re-validates the same already-valid string.
      // instanceof patterns: a malformed value (non-String baseUrl/token) is ignored rather than
      // throwing an uncaught ClassCastException (→ 500).
      String baseUrl = body.get("baseUrl") instanceof String s ? BaseUrlValidator.validate(s) : null;
      // Keep only String elements, mirroring the baseUrl/token instanceof-String handling. A JSON
      // allowlist array can carry a null ({"allowlist":["a",null]}) or a non-String — a number or even a
      // nested object/array ({"allowlist":[{"x":1}]}). String.valueOf would coerce those into junk like
      // "null"/"7"/"{x=1}" that survives formatAllowlist's nonNull/blank filters and is persisted as a
      // bogus allowlist entry. The instanceof-String filter drops both nulls and non-String coercion junk.
      // Validate the allowlist entries upfront too (like the base URL), so a malformed entry yields a
      // 400 BEFORE anything is persisted (no half-apply). validatedAllowlist drops blanks, normalises,
      // and throws IllegalArgumentException on a bad entry; setAllowlist re-validates as defence-in-depth.
      List<String> allowlist =
          body.get("allowlist") instanceof List<?> l
              ? AdminConfig.validatedAllowlist(
                  l.stream().filter(String.class::isInstance).map(String.class::cast).toList())
              : null;
      // A BLANK token is treated as "no change", mirroring the admin page JS (which only sends `token`
      // when it is non-empty). Storing an empty-token ciphertext would make hasToken() report a token IS
      // configured — so the /source gate waves the request through — while the fetch then fails on the
      // blank token with a misleading 502 "cannot reach repository" instead of the accurate "repository
      // not configured". So a direct REST caller POSTing {"token":""} (or whitespace) leaves the token
      // untouched rather than corrupting it.
      String token = body.get("token") instanceof String s && !s.isBlank() ? s : null;
      // Explicit token REVOKE: {"clearToken":true} removes the stored ciphertext entirely (there is no
      // other way to un-set a compromised token — a blank token is "no change", above). The flag must be
      // the literal boolean true (Boolean.TRUE.equals), NOT any truthy value: a stray {"clearToken":"..."}
      // string or a false must never wipe a live token. It takes precedence over a supplied `token` (a
      // request that both sets and clears is contradictory; revoking is the safer resolution).
      boolean clearToken = Boolean.TRUE.equals(body.get("clearToken"));
      // Persist the token FIRST: it is the only persist-stage operation that can still throw (encrypt +
      // key-store IO → 500). Doing it before setBaseUrl/setAllowlist (plain PluginSettings writes) keeps
      // the no-half-apply invariant on the 500 path too — a token-storage failure leaves baseUrl and
      // allowlist untouched rather than committing them under a returned error. (clearToken does no crypto,
      // so it cannot throw here.)
      if (clearToken) config.clearToken();
      else if (token != null) config.setToken(token);
      if (baseUrl != null) config.setBaseUrl(baseUrl);
      if (allowlist != null) config.setAllowlist(allowlist);
    } catch (IllegalArgumentException e) {
      // Admin-only endpoint; the validation message (e.g. base URL must use https) is safe to surface.
      return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
    } catch (RuntimeException e) {
      // Token encryption / key-store IO can throw IllegalStateException / UncheckedIOException (lost or
      // rotated key, unwritable home). The catch stays broad (RuntimeException) so an UNEXPECTED bug —
      // e.g. an NPE from a future refactor — also maps to a fixed generic 500 rather than escaping as a
      // framework stack trace that can leak the exception chain. The client message is therefore kept
      // generic ("configuration update failed"), not storage-specific, since the cause may not be
      // storage; the real throwable is logged server-side for the operator.
      LOG.log(Level.WARNING, "admin config update failed", e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "configuration update failed")).build();
    }
    return Response.ok(Map.of("ok", true)).build();
  }

  private boolean isAdmin(AdminConfig config) {
    UserManager userManager = config.getUserManager();
    var key = userManager.getRemoteUserKey();
    return key != null && userManager.isSystemAdmin(key);
  }

  /**
   * Enforces a Secure Administrator Session for an interactive (cookie-session) caller mutating the
   * GitLab token / base URL; returns a 401 when the session is not elevated. HTTP Basic-auth callers
   * are exempt (and the test/automation harness is Basic-auth-only). Returns {@code null} when the
   * call may proceed. When Secure Admin Sessions is disabled, {@code willExecuteWebSudoRequest} is a
   * no-op and this returns {@code null}.
   *
   * <p>A {@code null} {@code request} FAILS CLOSED (denied with a 401): with no request we can confirm
   * neither the Basic-auth exemption nor an elevated cookie session, so the safe outcome for a
   * state-changing admin mutation is to deny — a security gate must not default to "proceed" on the
   * absence of the very evidence it exists to check. At runtime the JAX-RS {@code @Context
   * HttpServletRequest} is always injected (non-null), so this branch is defence-in-depth against a
   * future wiring/proxy change rather than a path real callers hit. Unit tests exercising the persistence
   * path therefore pass a Basic-auth request (WebSudo-exempt), not null.
   *
   * <p><b>Ordering invariant:</b> {@code update} calls this only AFTER the {@link #isAdmin} gate, so the
   * Basic-auth exemption cannot be abused to bypass anything: the admin identity is taken from the
   * authenticated session/user ({@code isSystemAdmin}), never from the {@code Authorization} header, so a
   * forged {@code Basic …} header on an un-elevated cookie session is already rejected by {@code isAdmin}
   * before it reaches this exemption. Do not reorder the exemption ahead of the admin check.
   *
   * <p><b>Fail-closed on a null manager:</b> only {@link WebSudoSessionException} is caught here. A
   * mis-wired {@code <component-import>} that leaves {@code getWebSudoManager()} null NPEs on the
   * {@code webSudo.willExecuteWebSudoRequest} dispatch (null receiver) and propagates to
   * {@link #updateJson}'s generic {@code catch (RuntimeException)} → fixed 500, i.e. the update is
   * denied, not waved through. This is deliberate; a future refactor that narrows that caller catch
   * must preserve the fail-closed outcome.
   */
  private Response enforceWebSudo(AdminConfig config, HttpServletRequest request) {
    // Fail closed when there is no request to inspect (see the Javadoc): deny rather than proceed.
    if (request == null) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(Map.of("error", "Secure Administrator Session required")).build();
    }
    if (RequestAuth.isBasic(request)) return null;
    WebSudoManager webSudo = config.getWebSudoManager();
    try {
      webSudo.willExecuteWebSudoRequest(request);
      return null;
    } catch (WebSudoSessionException e) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(Map.of("error", "Secure Administrator Session required")).build();
    }
  }

  private static String nz(String s) { return s == null ? "" : s; }
}
