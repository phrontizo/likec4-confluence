package com.phrontizo.confluence.likec4;

import com.atlassian.sal.api.user.UserManager;
import com.phrontizo.likec4.source.BaseUrlValidator;
import com.phrontizo.likec4.source.SourceBundle;
import com.phrontizo.likec4.source.SourceService;
import com.phrontizo.likec4.source.TokenCipher;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * GET /rest/likec4/1.0/resolve and /source — authenticated; delegates to the core SourceService.
 * The "1.0" version segment comes from the {@code <rest version="1.0">} module, so the class path
 * is "/" (NOT "/1.0", which would double the version to /rest/likec4/1.0/1.0/...).
 *
 * <p><b>Authorization model (deliberate — see README "Security model &amp; trust boundaries"):</b>
 * these endpoints require an authenticated Confluence user but apply <em>no</em> per-space/per-page
 * or per-project permission check. Fetches run with the single admin-configured GitLab service token,
 * so the <b>project allowlist is the only access boundary</b>: ANY logged-in user can read the LikeC4
 * sources of ANY allowlisted project (this is a confused-deputy by design — diagrams are embedded in
 * pages and meant to be broadly viewable). Administrators must therefore only allowlist projects whose
 * architecture DSL is acceptable to expose to all logged-in users. Changing this to bind a macro to
 * its host content's view permission is a deliberate future enhancement, not an accidental omission.
 *
 * <p>Note that these endpoints reject an anonymous caller (401), but the {@link LikeC4DiagramMacro}
 * itself emits the {@code data-project}/{@code data-ref}/{@code data-path} coordinates into the page
 * for any viewer of the host page — so an anonymous viewer of an <em>anonymously-accessible</em> space
 * can read those GitLab coordinates from the page source (the source content behind them stays gated by
 * the 401). This is an accepted residual of the same broadly-viewable model; admins who treat the
 * allowlisted projects' coordinates as sensitive should not make the embedding pages anonymous.
 *
 * <p><b>No per-user rate limiting (accepted residual):</b> these endpoints apply no per-user/per-project
 * request throttle. An authenticated user can drive many {@code /source?project=<allowlisted>&ref=<novel>}
 * calls, each novel ref forcing a cache-miss archive fetch from the internal GitLab under the shared
 * service token. Request cost and failure amplification are already bounded — the ref/bundle caches
 * collapse repeat work, per-request timeouts + the download deadline cap each call, and the circuit
 * breaker trips after repeated upstream failures — so this is a deliberate accepted residual for an
 * internal tool, not an omission. A token-bucket in front of {@code gate()} is a straightforward future
 * enhancement if a deployment needs it.
 *
 * <p>Dependencies are resolved via static holders on {@link AdminConfig} and
 * {@link SourceServiceProvider} rather than through HK2 constructor injection: the Atlassian
 * REST-v2 plugin's {@code SpringInTimeResolver} does not reliably reach the spring-scanner 6.x
 * plugin context on Confluence DC 10.2.13. Both holder beans are normally initialised at plugin-enable
 * time; the {@code getInstance()} null-guards below cover the brief startup window (and any init
 * failure) so an early request gets a clean 503 rather than an NPE — they are not dead code.
 */
@Path("/")
public class SourceRestResource {

  private static final Logger LOG = System.getLogger(SourceRestResource.class.getName());

  /** No-arg constructor: Jersey/HK2 creates instances; beans accessed via static holders. */
  public SourceRestResource() {}

  /**
   * Map an upstream/unexpected failure to a fixed 502 with no internal detail, and LOG it server-side.
   * The body is deliberately generic (no info leak), but without this log a misconfigured token, a
   * GitLab outage, or a genuine server bug left no trace — "diagram won't load" was undiagnosable.
   */
  private static Response upstreamUnavailable(String op, Throwable e) {
    LOG.log(Level.WARNING, () -> "likec4 " + op + " failed: " + e.getClass().getSimpleName(), e);
    return Response.status(Response.Status.BAD_GATEWAY).entity(Map.of("error", "cannot reach repository")).build();
  }

  /**
   * A stored GitLab token that cannot be decrypted (lost/rotated key file or tampered ciphertext) is a
   * local server-config fault, NOT an upstream outage. Map it to the same 503 "repository misconfigured"
   * a tampered base URL gets — pointing the operator at the stored config — instead of the misleading 502
   * "cannot reach repository", and log the specific cause so it is diagnosable.
   */
  private static Response tokenUndecryptable(String op, TokenCipher.DecryptException e) {
    LOG.log(Level.WARNING,
        () -> "likec4 " + op + " failed: stored GitLab token could not be decrypted"
            + " (lost/rotated key file or tampered ciphertext)", e);
    return repositoryMisconfigured();
  }

  /** A short-circuit {@link Response} (503/401) OR the resolved provider to delegate to — never both. */
  private record Gate(Response shortCircuit, SourceServiceProvider provider) {}

  private static Response unauthenticated() {
    // Carry a JSON body like every other error branch (400/403/502/503): a bodyless 401 made a frontend
    // `fetch(...).then(r => r.json())` fail to PARSE rather than surface the structured error, and was the
    // one branch that broke the uniform {"error": ...} contract these endpoints otherwise keep.
    return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("error", "authentication required")).build();
  }

  private static Response pluginInitialising() {
    return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Map.of("error", "plugin initialising")).build();
  }

  private static Response repositoryNotConfigured() {
    return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Map.of("error", "repository not configured")).build();
  }

  private static Response repositoryMisconfigured() {
    return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Map.of("error", "repository misconfigured")).build();
  }

  /**
   * Shared preamble for both endpoints: plugin-initialised (config + provider present),
   * authenticated-caller, and repository-configured checks, in that order. Returns a {@link Gate} whose
   * {@code shortCircuit} is non-null when the caller must be bounced — in that gate order: 503 initialising
   * (config or provider absent) / 401 anonymous / 503 repository-not-configured (blank stored URL) / 503
   * repository-misconfigured (non-blank but invalid stored URL) / 503 repository-not-configured (valid URL
   * but no stored token). The not-configured status thus covers two DISTINCT gates — a blank URL and a
   * token-less URL — separated by the misconfigured check between them. Otherwise {@code shortCircuit} is
   * null and {@code provider} is the live {@link SourceServiceProvider}.
   * The base-URL checks live here so a misconfigured install never surfaces as a misleading 400. A blank
   * URL (fresh, unconfigured install) returns a clear "repository not configured" 503; a non-blank but
   * invalid URL (a tampered or legacy stored value — a server-config fault, not a bad request parameter)
   * returns "repository misconfigured" 503. Without this validation here, {@code BaseUrlValidator.validate}
   * would throw an {@code IllegalArgumentException} inside the endpoint's try-block (the provider's
   * {@code get()} re-validates the stored URL) that the catch maps to a misleading 400 "invalid request
   * parameter" — pointing the operator at the macro params rather than the stored config.
   */
  private static Gate gate() {
    AdminConfig cfg = AdminConfig.getInstance();
    if (cfg == null) return new Gate(pluginInitialising(), null);
    UserManager userManager = cfg.getUserManager();
    // gate() runs OUTSIDE the endpoints' try/catch, so a mis-wired userManager import (null) must not
    // NPE here and escape as a raw framework 500 — that breaks the uniform {"error": ...} contract a
    // frontend `.then(r => r.json())` relies on. Fail closed as "plugin initialising" like the null-cfg
    // / null-provider gates (a not-fully-wired install is the same "not ready" category).
    if (userManager == null) return new Gate(pluginInitialising(), null);
    if (userManager.getRemoteUserKey() == null) return new Gate(unauthenticated(), null);
    SourceServiceProvider ssp = SourceServiceProvider.getInstance();
    if (ssp == null) return new Gate(pluginInitialising(), null);
    String baseUrl = cfg.getBaseUrl();
    if (baseUrl == null || baseUrl.isBlank()) return new Gate(repositoryNotConfigured(), null);
    try {
      BaseUrlValidator.validate(baseUrl); // pure; the endpoint's provider.get() re-validates the same string
    } catch (IllegalArgumentException e) {
      return new Gate(repositoryMisconfigured(), null);
    }
    // A valid base URL but no stored token is a half-configured install, not an upstream outage. Without
    // this, the fetch reaches GitLabSourceClient.send() which throws IllegalStateException("token is not
    // configured") — the endpoints' generic catch then maps it to a misleading 502 "cannot reach
    // repository" (blaming GitLab for a local config gap). hasToken() only checks PRESENCE and never
    // decrypts, so it cannot itself fail on a lost/rotated key (that still surfaces later as the
    // DecryptException → 503 "repository misconfigured" path).
    if (!cfg.hasToken()) return new Gate(repositoryNotConfigured(), null);
    return new Gate(null, ssp);
  }

  /**
   * Stamp {@code X-Content-Type-Options: nosniff} and {@code Cache-Control: no-store} on every response,
   * mirroring {@link AdminServlet}. These JSON bodies reflect attacker-influenceable data (the fetched
   * GitLab file contents, the allowlist), so — even though {@code @Produces(application/json)} already
   * fixes the Content-Type — {@code nosniff} is defence-in-depth against a browser MIME-sniffing a
   * response as HTML. {@code no-store} keeps the fetched internal/private source out of any shared/proxy
   * or shared-workstation browser cache, so it can never be retrieved past the auth gate. Applied once
   * here rather than at each scattered {@code return} so no branch can forget either header.
   */
  private static Response nosniff(Response r) {
    return Response.fromResponse(r)
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "no-store")
        .build();
  }

  @GET
  @Path("/resolve")
  @Produces(MediaType.APPLICATION_JSON)
  public Response resolve(@QueryParam("project") String project, @QueryParam("ref") String ref) {
    return nosniff(resolveJson(project, ref));
  }

  private Response resolveJson(String project, String ref) {
    Gate gate = gate();
    if (gate.shortCircuit() != null) return gate.shortCircuit();
    try {
      String sha = gate.provider().get().resolve(project, defaultRef(ref));
      // The SourceService contract is non-null; guard it explicitly rather than letting a null trip
      // Map.of's NPE (which the generic catch below would then mislabel as "cannot reach repository"
      // off a bare NullPointerException, leaving a null-contract bug undiagnosable).
      if (sha == null) return upstreamUnavailable("resolve", new IllegalStateException("resolver returned a null sha"));
      return Response.ok(Map.of("sha", sha)).build();
    } catch (SourceService.NotAllowedException e) {
      return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "not allowed")).build();
    } catch (IllegalArgumentException e) {
      // Do NOT echo e.getMessage(): InputValidation embeds the raw caller-supplied value.
      return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "invalid request parameter")).build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // restore the interrupt flag on the pooled request thread
      return upstreamUnavailable("resolve", e);
    } catch (TokenCipher.DecryptException e) {
      return tokenUndecryptable("resolve", e); // lost/rotated key: a config fault, not an outage
    } catch (Exception e) {
      return upstreamUnavailable("resolve", e);
    }
  }

  @GET
  @Path("/source")
  @Produces(MediaType.APPLICATION_JSON)
  public Response source(@QueryParam("project") String project, @QueryParam("ref") String ref,
                         @QueryParam("path") String path) {
    return nosniff(sourceJson(project, ref, path));
  }

  private Response sourceJson(String project, String ref, String path) {
    Gate gate = gate();
    if (gate.shortCircuit() != null) return gate.shortCircuit();
    try {
      SourceBundle bundle = gate.provider().get().source(project, defaultRef(ref), path);
      // Non-null by contract (the bundle cache throws on a null loader result); guard explicitly so a
      // contract regression is a clear logged 502, not a Map.of NPE mislabelled as an upstream outage.
      if (bundle == null) return upstreamUnavailable("source", new IllegalStateException("source returned a null bundle"));
      return Response.ok(Map.of("sha", bundle.sha(), "files", bundle.files())).build();
    } catch (SourceService.NotAllowedException e) {
      return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "not allowed")).build();
    } catch (IllegalArgumentException e) {
      // Do NOT echo e.getMessage(): InputValidation embeds the raw caller-supplied value.
      return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "invalid request parameter")).build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // restore the interrupt flag on the pooled request thread
      return upstreamUnavailable("source", e);
    } catch (TokenCipher.DecryptException e) {
      return tokenUndecryptable("source", e); // lost/rotated key: a config fault, not an outage
    } catch (Exception e) {
      return upstreamUnavailable("source", e);
    }
  }

  /**
   * The single place the "no ref supplied → default branch" policy lives. The macro
   * ({@link LikeC4DiagramMacro}) validates a present {@code ref} but passes {@code null} through when
   * the author omits it, and the frontend then calls /resolve and /source without a {@code ref} param,
   * so this server-side default is authoritative — keep the default here rather than baking a duplicate
   * one into the macro. GitLab's commits API resolves the literal ref {@code HEAD} to the tip of the
   * repository's default branch, so "no ref → HEAD" is exactly the "default branch" policy.
   */
  private static String defaultRef(String ref) {
    return (ref == null || ref.isBlank()) ? "HEAD" : ref;
  }
}
