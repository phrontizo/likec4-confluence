package com.phrontizo.confluence.likec4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.phrontizo.likec4.source.CircuitBreaker;
import com.phrontizo.likec4.source.ProjectAllowlist;
import com.phrontizo.likec4.source.ShaResolver;
import com.phrontizo.likec4.source.SourceBundle;
import com.phrontizo.likec4.source.SourceService;
import com.phrontizo.likec4.source.SubtreeFetcher;
import com.phrontizo.likec4.source.TokenCipher;
import com.phrontizo.likec4.source.cache.Clock;
import com.phrontizo.likec4.source.cache.RefShaCache;
import com.phrontizo.likec4.source.cache.SourceBundleCache;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Authorization-gate tests for the source REST resource. These guard the security-load-bearing
 * checks (authenticated-only access, plugin-initialising fallbacks) so a regression that drops a
 * gate is caught in CI. Beans are reached via the static holders, set here by reflection.
 */
class SourceRestResourceTest {
  private final SourceRestResource resource = new SourceRestResource();

  @AfterEach
  void clearHolders() throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", null);
    setStatic(SourceServiceProvider.class, "INSTANCE", null);
  }

  @Test
  void resolve_is_503_when_plugin_not_initialised() throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", null);
    assertEquals(503, resource.resolve("group/proj", "main").getStatus());
  }

  @Test
  void resolve_is_503_with_a_json_body_when_the_user_manager_is_unavailable() throws Exception {
    // A mis-wired <component-import key="userManager"> makes getUserManager() null. gate() runs OUTSIDE
    // the endpoint try/catch, so an unguarded NPE there escapes as a raw framework 500 rather than the
    // uniform {"error": ...} JSON these endpoints keep (which a frontend `.then(r => r.json())` then can't
    // parse). It must fail closed as a 503 "plugin initialising" like the null-config / null-provider gates.
    AdminConfig cfg = mock(AdminConfig.class);
    when(cfg.getUserManager()).thenReturn(null);
    setStatic(AdminConfig.class, "INSTANCE", cfg);
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(503, resp.getStatus());
    assertEquals(Map.of("error", "plugin initialising"), resp.getEntity());
  }

  @Test
  void both_endpoints_stamp_the_nosniff_header_on_every_response() throws Exception {
    // Parity with AdminServlet, which sets X-Content-Type-Options: nosniff unconditionally. These JSON
    // bodies reflect attacker-influenceable data (fetched file contents, the allowlist), so the header
    // is defence-in-depth against MIME sniffing — and it must be present on EVERY branch, so assert it
    // even on the anonymous-401 short-circuit (the branch that never reaches the JSON entity).
    setStatic(AdminConfig.class, "INSTANCE", anonymousConfig());
    assertEquals("nosniff",
        resource.resolve("group/proj", "main").getHeaderString("X-Content-Type-Options"));
    assertEquals("nosniff",
        resource.source("group/proj", "main", null).getHeaderString("X-Content-Type-Options"));
  }

  @Test
  void both_endpoints_forbid_caching_of_the_fetched_source_on_every_response() throws Exception {
    // The /resolve and /source bodies reflect an internal/private GitLab project's DSL, gated on the
    // caller having passed the auth check. Stamp Cache-Control: no-store so a shared/proxy cache or a
    // shared-workstation browser cache can't retain the internal source past the auth gate. Assert it on
    // the anonymous-401 short-circuit too, so the header is present on EVERY branch (mirrors nosniff).
    setStatic(AdminConfig.class, "INSTANCE", anonymousConfig());
    assertEquals("no-store",
        resource.resolve("group/proj", "main").getHeaderString("Cache-Control"));
    assertEquals("no-store",
        resource.source("group/proj", "main", null).getHeaderString("Cache-Control"));
  }

  @Test
  void resolve_is_401_for_anonymous_caller() throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", anonymousConfig());
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), resp.getStatus());
    // The 401 carries a JSON error body like every other error branch (400/403/502/503) — a bodyless 401
    // made a `fetch(...).then(r => r.json())` on the frontend fail to PARSE instead of surfacing the
    // structured error, and was the one error branch that broke the uniform {"error": ...} contract.
    assertEquals(Map.of("error", "authentication required"), resp.getEntity());
  }

  @Test
  void source_is_401_for_anonymous_caller() throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", anonymousConfig());
    Response resp = resource.source("group/proj", "main", null);
    assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), resp.getStatus());
    assertEquals(Map.of("error", "authentication required"), resp.getEntity());
  }

  @Test
  void resolve_is_401_for_anonymous_caller_even_when_the_provider_is_present(@TempDir Path dir) throws Exception {
    // Pin the gate ORDERING: the anonymous 401 must win even when a live provider is set. The other
    // anonymous tests leave the provider null, so they would also pass if the auth check were
    // accidentally reordered after a provider-present check — this one would not.
    setStatic(AdminConfig.class, "INSTANCE", anonymousConfig());
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(),
        resource.resolve("group/proj", "main").getStatus());
  }

  @Test
  void source_is_401_for_anonymous_caller_even_when_the_provider_is_present(@TempDir Path dir) throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", anonymousConfig());
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(),
        resource.source("group/proj", "main", "ok").getStatus());
  }

  @Test
  void resolve_is_503_when_provider_absent_for_authenticated_caller() throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setStatic(SourceServiceProvider.class, "INSTANCE", null);
    assertEquals(503, resource.resolve("group/proj", "main").getStatus());
  }

  @Test
  void source_is_503_when_provider_absent_for_authenticated_caller() throws Exception {
    // Mirror of the resolve test: a regression dropping the provider-absent guard on the source path
    // only would otherwise go uncaught.
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setStatic(SourceServiceProvider.class, "INSTANCE", null);
    assertEquals(503, resource.source("group/proj", "main", "ok").getStatus());
  }

  @Test
  void resolve_is_503_repository_not_configured_when_base_url_is_unset(@TempDir Path dir) throws Exception {
    // A fresh install has no GitLab base URL yet. The endpoint must report a clear 503 "repository not
    // configured" rather than letting BaseUrlValidator.validate(null) throw an IllegalArgumentException
    // that the catch maps to a misleading 400 "invalid request parameter" (the params are fine).
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig(null));
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(503, resp.getStatus());
    assertEquals(Map.of("error", "repository not configured"), resp.getEntity());
  }

  @Test
  void source_is_503_repository_not_configured_when_base_url_is_blank(@TempDir Path dir) throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig("   "));
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    Response resp = resource.source("group/proj", "main", "ok");
    assertEquals(503, resp.getStatus());
    assertEquals(Map.of("error", "repository not configured"), resp.getEntity());
  }

  @Test
  void resolve_is_503_repository_misconfigured_when_base_url_is_invalid(@TempDir Path dir) throws Exception {
    // A tampered/legacy stored base URL that is non-blank but INVALID (e.g. a typo scheme) is a server
    // CONFIG fault, not a bad request parameter. gate() must validate it and return a clear 503
    // "repository misconfigured" rather than letting the endpoint's BaseUrlValidator.validate throw an
    // IllegalArgumentException that the catch maps to a misleading 400 "invalid request parameter".
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig("htp://typo-scheme"));
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(503, resp.getStatus());
    assertEquals(Map.of("error", "repository misconfigured"), resp.getEntity());
  }

  @Test
  void source_is_503_repository_misconfigured_when_base_url_is_invalid(@TempDir Path dir) throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig("not a url"));
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    Response resp = resource.source("group/proj", "main", "ok");
    assertEquals(503, resp.getStatus());
    assertEquals(Map.of("error", "repository misconfigured"), resp.getEntity());
  }

  @Test
  void resolve_is_503_repository_not_configured_when_the_token_is_unset(@TempDir Path dir) throws Exception {
    // A half-configured install: the base URL is set+valid but no GitLab token is stored yet. gate()
    // must report a clear 503 "repository not configured" rather than let the fetch throw
    // IllegalStateException("token is not configured") that the generic catch maps to a misleading 502
    // "cannot reach repository" (which blames GitLab for a local config gap).
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfigNoToken());
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(503, resp.getStatus());
    assertEquals(Map.of("error", "repository not configured"), resp.getEntity());
  }

  @Test
  void source_is_503_repository_not_configured_when_the_token_is_unset(@TempDir Path dir) throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfigNoToken());
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    Response resp = resource.source("group/proj", "main", "ok");
    assertEquals(503, resp.getStatus());
    assertEquals(Map.of("error", "repository not configured"), resp.getEntity());
  }

  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

  @Test
  void resolve_maps_not_allowed_to_403(@TempDir Path dir) throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setProvider(svc(List.of(), (p, r) -> SHA, (p, s, pa) -> null, dir)); // empty allowlist -> NotAllowed
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(403, resp.getStatus());
    // Assert the BODY too, not just the status: the authz-denial must be the GENERIC {"error":"not
    // allowed"} — consistent with every other error branch here and never leaking the project name
    // (NotAllowedException's message embeds it). A body/leak regression on this path was uncaught before.
    assertEquals(Map.of("error", "not allowed"), resp.getEntity());
  }

  @Test
  void resolve_maps_invalid_param_to_400_without_echoing_the_raw_value(@TempDir Path dir) throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    Response resp = resource.resolve("group/proj", "../secret-ref"); // sanitizeRef rejects the traversal
    assertEquals(400, resp.getStatus());
    // The body must be the GENERIC message, never the raw caller value (InputValidation embeds it in
    // the exception message; the resource must not leak it back).
    assertEquals(Map.of("error", "invalid request parameter"), resp.getEntity());
  }

  @Test
  void resolve_maps_upstream_failure_to_502(@TempDir Path dir) throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setProvider(svc(List.of("group"), (p, r) -> { throw new RuntimeException("gitlab down"); }, (p, s, pa) -> null, dir));
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(502, resp.getStatus());
    assertEquals(Map.of("error", "cannot reach repository"), resp.getEntity());
  }

  @Test
  void resolve_maps_a_token_decrypt_failure_to_503_not_a_misleading_502(@TempDir Path dir) throws Exception {
    // A lost/rotated key file makes config::getToken (invoked lazily inside the fetch) throw a
    // TokenCipher.DecryptException. That is a stored-config fault, NOT an upstream outage: the resource
    // must map it to a 503 "repository misconfigured" (like a tampered base URL), never the misleading
    // 502 "cannot reach repository" that blames GitLab for a local key problem.
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setProvider(svc(List.of("group"),
        (p, r) -> { throw new TokenCipher.DecryptException("decrypt failed (bad key or tampered ciphertext)"); },
        (p, s, pa) -> null, dir));
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(503, resp.getStatus());
    assertEquals(Map.of("error", "repository misconfigured"), resp.getEntity());
  }

  @Test
  void source_maps_a_token_decrypt_failure_to_503_not_a_misleading_502(@TempDir Path dir) throws Exception {
    // Mirror on the source path: the decrypt failure surfaces from the fetcher (own @TempDir so the
    // fetch is a genuine cache miss and the throwing fetcher actually runs).
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setProvider(svc(List.of("group"), (p, r) -> SHA,
        (p, s, pa) -> { throw new TokenCipher.DecryptException("decrypt failed (bad key or tampered ciphertext)"); },
        dir));
    Response resp = resource.source("group/proj", "main", "ok");
    assertEquals(503, resp.getStatus());
    assertEquals(Map.of("error", "repository misconfigured"), resp.getEntity());
  }

  @Test
  void resolve_maps_a_null_sha_from_the_service_to_a_clean_502(@TempDir Path dir) throws Exception {
    // A resolver returning null violates the non-null contract. The resource must map it to a clean,
    // logged 502 — never an unhandled crash and never relying on Map.of's NPE as control flow (which
    // would mislabel a null-contract bug as "cannot reach repository" off a bare NullPointerException).
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setProvider(svc(List.of("group"), (p, r) -> null, (p, s, pa) -> null, dir));
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(502, resp.getStatus());
    assertEquals(Map.of("error", "cannot reach repository"), resp.getEntity());
  }

  @Test
  void resolve_returns_200_with_sha_for_an_allowed_authenticated_caller(@TempDir Path dir) throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(200, resp.getStatus());
    assertEquals(Map.of("sha", SHA), resp.getEntity());
  }

  @Test
  void resolve_returns_200_for_an_authenticated_non_admin_caller_pinning_the_access_model(@TempDir Path dir) throws Exception {
    // The access model is DELIBERATE (see the resource javadoc): authentication + project allowlist
    // ONLY — NOT admin, NOT space/page permissions (a documented confused-deputy). Pin it with an
    // EXPLICITLY non-admin authenticated caller so a regression that "hardened" the endpoint to require
    // isSystemAdmin (or any elevated role) would 401/403 this ordinary reader and fail here.
    setStatic(AdminConfig.class, "INSTANCE", authenticatedNonAdminConfig());
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> null, dir));
    Response resp = resource.resolve("group/proj", "main");
    assertEquals(200, resp.getStatus(), "an authenticated non-admin caller must be allowed (auth + allowlist only)");
    assertEquals(Map.of("sha", SHA), resp.getEntity());
  }

  @Test
  void source_returns_200_with_files(@TempDir Path dir) throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setProvider(svc(List.of("group"), (p, r) -> SHA,
        (p, s, pa) -> new SourceBundle(s, Map.of("model.likec4", "x")), dir));
    Response ok = resource.source("group/proj", "main", "ok");
    assertEquals(200, ok.getStatus());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) ok.getEntity();
    assertEquals(SHA, body.get("sha"));
    assertEquals(Map.of("model.likec4", "x"), body.get("files"));
  }

  @Test
  void source_maps_upstream_failure_to_502(@TempDir Path dir) throws Exception {
    // Own @TempDir (empty disk cache) so the fetch is a genuine miss and the throwing fetcher runs.
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setProvider(svc(List.of("group"), (p, r) -> SHA, (p, s, pa) -> { throw new RuntimeException("down"); }, dir));
    Response resp = resource.source("group/proj", "main", "ok");
    assertEquals(502, resp.getStatus());
    assertEquals(Map.of("error", "cannot reach repository"), resp.getEntity());
  }

  @Test
  void source_maps_not_allowed_to_403(@TempDir Path dir) throws Exception {
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    setProvider(svc(List.of(), (p, r) -> SHA, (p, s, pa) -> null, dir));
    Response resp = resource.source("group/proj", "main", "ok");
    assertEquals(403, resp.getStatus());
    // Mirror the resolve path: assert the generic {"error":"not allowed"} body, not just the status.
    assertEquals(Map.of("error", "not allowed"), resp.getEntity());
  }

  @Test
  void resolve_defaults_a_missing_ref_to_HEAD(@TempDir Path dir) throws Exception {
    // defaultRef is "the single place the no-ref -> default-branch policy lives": the macro passes null
    // through when the author omits ref, and the frontend then calls /resolve without a ref param, so
    // this server-side default is authoritative. GitLab resolves the literal ref HEAD to the default
    // branch's tip, so "no ref -> HEAD" IS that policy. Every other test passes an explicit "main", so
    // the default itself was never asserted. Capture the ref the resolver actually receives; use distinct
    // projects per case so the RefShaCache never serves a hit that would skip the loader.
    setStatic(AdminConfig.class, "INSTANCE", authenticatedConfig());
    java.util.concurrent.atomic.AtomicReference<String> seenRef = new java.util.concurrent.atomic.AtomicReference<>();
    setProvider(svc(List.of("group"), (p, r) -> { seenRef.set(r); return SHA; }, (p, s, pa) -> null, dir));
    resource.resolve("group/a", null);
    assertEquals("HEAD", seenRef.get(), "a null ref must default to HEAD (the authoritative server default)");
    seenRef.set(null);
    resource.resolve("group/b", "   ");
    assertEquals("HEAD", seenRef.get(), "a blank ref must default to HEAD too");
    seenRef.set(null);
    resource.resolve("group/c", "release-2.0");
    assertEquals("release-2.0", seenRef.get(), "an explicit ref must pass through, never be overridden by the default");
  }

  /** A real SourceService wired with controllable collaborators, so the resource's exception mapping
   *  is verified against the actual core behaviour (allowlist -> 403, bad input -> 400, upstream -> 502). */
  private static SourceService svc(List<String> allow, ShaResolver resolver, SubtreeFetcher fetcher, Path dir)
      throws Exception {
    return new SourceService(new ProjectAllowlist(allow),
        new RefShaCache(Clock.SYSTEM, 60_000),
        new SourceBundleCache(dir, 10),
        resolver, fetcher,
        new CircuitBreaker(Clock.SYSTEM, 1000, 1000));
  }

  private static void setProvider(SourceService service) throws Exception {
    SourceServiceProvider provider = mock(SourceServiceProvider.class);
    when(provider.get()).thenReturn(service);
    setStatic(SourceServiceProvider.class, "INSTANCE", provider);
  }

  private static AdminConfig anonymousConfig() {
    AdminConfig cfg = mock(AdminConfig.class);
    UserManager um = mock(UserManager.class);
    when(cfg.getUserManager()).thenReturn(um);
    when(um.getRemoteUserKey()).thenReturn(null);
    return cfg;
  }

  private static AdminConfig authenticatedConfig() {
    return authenticatedConfig("https://gitlab.example.com");
  }

  /** An authenticated config whose stored GitLab base URL is {@code baseUrl} (null/blank = unconfigured)
   *  and which HAS a stored token — the common fully-configured case. */
  private static AdminConfig authenticatedConfig(String baseUrl) {
    AdminConfig cfg = mock(AdminConfig.class);
    UserManager um = mock(UserManager.class);
    when(cfg.getUserManager()).thenReturn(um);
    when(um.getRemoteUserKey()).thenReturn(new UserKey("alice"));
    when(cfg.getBaseUrl()).thenReturn(baseUrl);
    when(cfg.hasToken()).thenReturn(true);
    return cfg;
  }

  /** Authenticated + fully configured, with the caller EXPLICITLY a non-admin — pins that the source
   *  endpoints gate on authentication + allowlist only, never on admin/elevated role. */
  private static AdminConfig authenticatedNonAdminConfig() {
    AdminConfig cfg = mock(AdminConfig.class);
    UserManager um = mock(UserManager.class);
    UserKey alice = new UserKey("alice");
    when(cfg.getUserManager()).thenReturn(um);
    when(um.getRemoteUserKey()).thenReturn(alice);
    when(um.isSystemAdmin(alice)).thenReturn(false); // explicitly NOT an admin
    when(um.isSystemAdmin("alice")).thenReturn(false);
    when(cfg.getBaseUrl()).thenReturn("https://gitlab.example.com");
    when(cfg.hasToken()).thenReturn(true);
    return cfg;
  }

  /** Authenticated, base URL set+valid, but NO stored token — a half-configured install. */
  private static AdminConfig authenticatedConfigNoToken() {
    AdminConfig cfg = mock(AdminConfig.class);
    UserManager um = mock(UserManager.class);
    when(cfg.getUserManager()).thenReturn(um);
    when(um.getRemoteUserKey()).thenReturn(new UserKey("alice"));
    when(cfg.getBaseUrl()).thenReturn("https://gitlab.example.com");
    when(cfg.hasToken()).thenReturn(false);
    return cfg;
  }

  static void setStatic(Class<?> cls, String field, Object value) throws Exception {
    Field f = cls.getDeclaredField(field);
    f.setAccessible(true);
    f.set(null, value);
  }
}
