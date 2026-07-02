package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phrontizo.likec4.source.cache.Clock;
import com.phrontizo.likec4.source.cache.ManualClock;
import com.phrontizo.likec4.source.cache.RefShaCache;
import com.phrontizo.likec4.source.cache.SourceBundleCache;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceServiceTest {

  private SourceService service(Path dir, ShaResolver resolver, SubtreeFetcher fetcher) throws Exception {
    // generous breaker so the happy-path tests never trip it.
    return service(dir, resolver, fetcher, new CircuitBreaker(Clock.SYSTEM, 1000, 1000));
  }

  private SourceService service(Path dir, ShaResolver resolver, SubtreeFetcher fetcher, CircuitBreaker breaker)
      throws Exception {
    return new SourceService(
        new ProjectAllowlist(List.of("grp")),
        new RefShaCache(Clock.SYSTEM, 60_000),
        new SourceBundleCache(dir, 10),
        resolver,
        fetcher,
        breaker);
  }

  @Test
  void resolves_then_fetches_with_caching(@TempDir Path dir) throws Exception {
    AtomicInteger resolves = new AtomicInteger();
    AtomicInteger fetches = new AtomicInteger();
    ShaResolver resolver = (p, r) -> { resolves.incrementAndGet(); return "0123456789abcdef0123456789abcdef01234567"; };
    SubtreeFetcher fetcher = (p, s, pa) -> { fetches.incrementAndGet(); return new SourceBundle(s, Map.of("m.likec4", "x")); };
    SourceService svc = service(dir, resolver, fetcher);

    assertEquals("0123456789abcdef0123456789abcdef01234567", svc.resolve("grp/repo", "main"));
    SourceBundle b = svc.source("grp/repo", "main", "diagrams");
    assertEquals(Map.of("m.likec4", "x"), b.files());
    // second source() reuses both caches
    svc.source("grp/repo", "main", "diagrams");
    assertEquals(1, resolves.get());
    assertEquals(1, fetches.get());
  }

  @Test
  void serves_the_prior_bundle_when_a_fresh_fetch_fails_after_the_ref_advances(@TempDir Path dir)
      throws Exception {
    // The core resilience promise (SourceService lines 43-48), exercised end-to-end through the
    // service rather than only at the cache layer: a first source() succeeds and records a last-good
    // bundle for (project, path); the ref TTL then expires so resolveSha re-resolves to a NEW sha
    // (a bundle-cache miss); the fetch for that new sha fails, so the bundle SWR serves the prior
    // bundle for (project, path) instead of erroring.
    ManualClock clock = new ManualClock(0);
    RefShaCache refCache = new RefShaCache(clock, 1_000); // short ref TTL we can step past
    SourceBundleCache bundleCache = new SourceBundleCache(dir, 10);
    AtomicInteger fetches = new AtomicInteger();
    ShaResolver resolver = (p, r) -> "sha" + clock.nowMillis();     // a clock advance yields a NEW sha
    SubtreeFetcher fetcher = (p, s, pa) -> {
      if (fetches.incrementAndGet() == 1) return new SourceBundle(s, Map.of("m.likec4", "good-" + s));
      throw new RuntimeException("gitlab down");                     // every later fetch fails
    };
    SourceService svc = new SourceService(
        new ProjectAllowlist(List.of("grp")), refCache, bundleCache, resolver, fetcher,
        new CircuitBreaker(clock, 1000, 1000)); // generous breaker: a single fetch failure won't trip it

    SourceBundle first = svc.source("grp/repo", "main", "d");
    assertEquals("good-sha0", first.files().get("m.likec4"));

    clock.advance(1_001); // ref entry expires -> resolveSha returns a new sha -> bundle miss -> fetch fails
    SourceBundle served = svc.source("grp/repo", "main", "d");
    assertEquals("good-sha0", served.files().get("m.likec4"),
        "stale-while-revalidate must serve the prior bundle for (project, path) when the fresh fetch fails");
    assertEquals(2, fetches.get(), "a second fetch was attempted (and failed), triggering SWR");
  }

  @Test
  void an_outage_that_outlasts_the_ref_ttl_fails_fast_and_never_serves_a_stale_bundle(@TempDir Path dir)
      throws Exception {
    // The documented BOUNDARY of the bundle-level SWR (SourceService lines 43-48): stale-while-revalidate
    // serves last-good only for an outage that strikes WITHIN the ref TTL (resolveSha still returns a
    // cached sha, the FETCH fails, the bundle cache serves last-good). Once the ref TTL expires, resolveSha
    // must re-resolve — and if THAT fails, the failure propagates (and, after the breaker trips, fails fast
    // as CircuitOpenException) rather than reaching the bundle SWR. Pins that we never serve a stale bundle
    // behind a sha we can no longer resolve, and never attempt a fetch for an unresolved sha.
    ManualClock clock = new ManualClock(0);
    RefShaCache refCache = new RefShaCache(clock, 1_000); // short ref TTL we can step past
    SourceBundleCache bundleCache = new SourceBundleCache(dir, 10);
    AtomicInteger resolves = new AtomicInteger();
    AtomicInteger fetches = new AtomicInteger();
    ShaResolver resolver = (p, r) -> {
      // succeeds once (seeding the last-good bundle), then the ref TTL expires and every re-resolution
      // fails (the outage) — modelling GitLab going down AFTER the ref cache entry has expired.
      if (resolves.incrementAndGet() == 1) return "0123456789abcdef0123456789abcdef01234567";
      throw new RuntimeException("gitlab down");
    };
    SubtreeFetcher fetcher = (p, s, pa) -> { fetches.incrementAndGet(); return new SourceBundle(s, Map.of("m.likec4", "good")); };
    SourceService svc = new SourceService(
        new ProjectAllowlist(List.of("grp")), refCache, bundleCache, resolver, fetcher,
        new CircuitBreaker(clock, 2, 60_000)); // trips after 2 consecutive resolve failures

    assertEquals("good", svc.source("grp/repo", "main", "d").files().get("m.likec4")); // seeds last-good
    assertEquals(1, fetches.get());

    clock.advance(1_001); // ref entry expires -> resolveSha must re-resolve, and now it fails (outage)
    assertThrows(RuntimeException.class, () -> svc.source("grp/repo", "main", "d")); // resolve failure 1
    assertThrows(RuntimeException.class, () -> svc.source("grp/repo", "main", "d")); // resolve failure 2 -> breaker OPENs
    // Breaker OPEN: source() now fails fast with CircuitOpenException, and the resolver is NOT called again.
    assertThrows(CircuitBreaker.CircuitOpenException.class, () -> svc.source("grp/repo", "main", "d"));
    assertEquals(3, resolves.get(), "once OPEN, source() fails fast without re-invoking the resolver");
    // Crucially NO fetch was ever attempted for an unresolved sha: the prior good bundle is NOT served
    // behind a sha we can no longer resolve — the bundle SWR path is never reached past the ref TTL.
    assertEquals(1, fetches.get(), "no stale bundle is served once the ref TTL expires and resolve fails");
  }

  @Test
  void rejects_a_project_outside_the_allowlist(@TempDir Path dir) throws Exception {
    AtomicInteger resolves = new AtomicInteger();
    SourceService svc = service(dir, (p, r) -> { resolves.incrementAndGet(); return "s"; }, (p, s, pa) -> null);
    assertThrows(SourceService.NotAllowedException.class, () -> svc.resolve("secret/repo", "main"));
    assertEquals(0, resolves.get());
  }

  @Test
  void rejects_an_invalid_ref(@TempDir Path dir) throws Exception {
    SourceService svc = service(dir, (p, r) -> "s", (p, s, pa) -> null);
    assertThrows(IllegalArgumentException.class, () -> svc.resolve("grp/repo", "../etc"));
  }

  @Test
  void rejects_a_traversal_project_before_allowlist_or_resolver(@TempDir Path dir) throws Exception {
    // "grp/../secret" sneaks past the allowlist (it startsWith "grp/"), so sanitizeProject must
    // run FIRST and reject the traversal before the allowlist check or any resolver call.
    AtomicInteger resolves = new AtomicInteger();
    SourceService svc = service(dir, (p, r) -> { resolves.incrementAndGet(); return "s"; }, (p, s, pa) -> null);
    assertThrows(IllegalArgumentException.class, () -> svc.resolve("grp/../secret", "main"));
    assertEquals(0, resolves.get());
  }

  @Test
  void opens_the_breaker_after_repeated_gitlab_failures_then_fails_fast(@TempDir Path dir) throws Exception {
    AtomicInteger resolves = new AtomicInteger();
    ShaResolver resolver = (p, r) -> { resolves.incrementAndGet(); throw new RuntimeException("gitlab down"); };
    CircuitBreaker breaker = new CircuitBreaker(Clock.SYSTEM, 2, 60_000);
    SourceService svc = service(dir, resolver, (p, s, pa) -> null, breaker);

    assertThrows(RuntimeException.class, () -> svc.resolve("grp/repo", "main")); // failure 1
    assertThrows(RuntimeException.class, () -> svc.resolve("grp/repo", "main")); // failure 2 -> breaker OPENs
    assertEquals(2, resolves.get());

    // Breaker is OPEN: a further resolve() must fail fast WITHOUT reaching the resolver.
    assertThrows(CircuitBreaker.CircuitOpenException.class, () -> svc.resolve("grp/repo", "main"));
    assertEquals(2, resolves.get());
  }

  @Test
  void a_gitlab_client_error_4xx_does_not_trip_the_shared_breaker(@TempDir Path dir) throws Exception {
    // The whole point of SourceService.COUNTS_AS_OUTAGE: a macro pointing at a typo'd/deleted ref makes
    // GitLab return 404 (a client error, not an outage). With the production classifier wired into the
    // shared breaker, such 4xx errors must NOT count toward the threshold — otherwise one misconfigured
    // macro would, after a handful of renders, trip the process-shared breaker and fail EVERY diagram.
    AtomicInteger resolves = new AtomicInteger();
    ShaResolver resolver = (p, r) -> {
      resolves.incrementAndGet();
      throw new GitLabException(404, "resolve ref " + r);
    };
    CircuitBreaker breaker = new CircuitBreaker(Clock.SYSTEM, 2, 60_000, SourceService.COUNTS_AS_OUTAGE);
    SourceService svc = service(dir, resolver, (p, s, pa) -> null, breaker);

    // Far more than threshold(2) 404s: the breaker must stay CLOSED, so every call reaches the resolver
    // and surfaces the real 404 rather than a masking CircuitOpenException.
    for (int i = 0; i < 5; i++) {
      assertThrows(GitLabException.class, () -> svc.resolve("grp/repo", "main"));
    }
    assertEquals(5, resolves.get(), "every 404 reached the resolver; the shared breaker never opened");
  }

  @Test
  void a_missing_token_misconfiguration_does_not_trip_the_shared_breaker(@TempDir Path dir) throws Exception {
    // A blank/unconfigured (or rotated-away) token makes GitLabSourceClient.send() throw a
    // GitLabConfigException on EVERY call. GitLab is up — the fault is local config — so that must NOT
    // count toward the shared breaker: otherwise a token cleared during rotation would, after a handful
    // of renders, trip the process-shared breaker and fail EVERY diagram behind a masking
    // CircuitOpenException instead of surfacing the actionable "token is not configured" error.
    AtomicInteger resolves = new AtomicInteger();
    ShaResolver resolver = (p, r) -> {
      resolves.incrementAndGet();
      throw new GitLabConfigException("GitLab token is not configured");
    };
    CircuitBreaker breaker = new CircuitBreaker(Clock.SYSTEM, 2, 60_000, SourceService.COUNTS_AS_OUTAGE);
    SourceService svc = service(dir, resolver, (p, s, pa) -> null, breaker);

    // Far more than threshold(2) misconfigured calls: the breaker must stay CLOSED, so every call reaches
    // the resolver and surfaces the real config error rather than a masking CircuitOpenException.
    for (int i = 0; i < 5; i++) {
      GitLabConfigException ex = assertThrows(GitLabConfigException.class, () -> svc.resolve("grp/repo", "main"));
      assertTrue(ex.getMessage().contains("token is not configured"), "was: " + ex.getMessage());
    }
    assertEquals(5, resolves.get(), "every misconfig error reached the resolver; the shared breaker never opened");
  }

  @Test
  void a_gitlab_server_error_5xx_still_trips_the_shared_breaker(@TempDir Path dir) throws Exception {
    // The dual of the 4xx case: a 5xx (GitLab down/erroring) IS a genuine outage and must still trip the
    // shared breaker so a down GitLab is not hammered.
    AtomicInteger resolves = new AtomicInteger();
    ShaResolver resolver = (p, r) -> {
      resolves.incrementAndGet();
      throw new GitLabException(503, "resolve ref " + r);
    };
    CircuitBreaker breaker = new CircuitBreaker(Clock.SYSTEM, 2, 60_000, SourceService.COUNTS_AS_OUTAGE);
    SourceService svc = service(dir, resolver, (p, s, pa) -> null, breaker);

    assertThrows(GitLabException.class, () -> svc.resolve("grp/repo", "main")); // failure 1
    assertThrows(GitLabException.class, () -> svc.resolve("grp/repo", "main")); // failure 2 -> OPEN
    assertThrows(CircuitBreaker.CircuitOpenException.class, () -> svc.resolve("grp/repo", "main"));
    assertEquals(2, resolves.get(), "the 5xx outage tripped the breaker; the third call fast-failed");
  }

  @Test
  void outage_classifier_counts_only_server_side_failures() {
    // Pin the exact status boundaries of SourceService.COUNTS_AS_OUTAGE. Server-down / overloaded / slow
    // signals count; a plain client error (bad ref / auth / bad request) and the malformed-200-body cases
    // do not; any non-GitLab exception (a network IOException) counts.
    assertTrue(SourceService.COUNTS_AS_OUTAGE.test(new GitLabException(500, "x")));
    assertTrue(SourceService.COUNTS_AS_OUTAGE.test(new GitLabException(503, "x")));
    assertTrue(SourceService.COUNTS_AS_OUTAGE.test(new GitLabException(429, "x")), "rate-limited -> back off");
    assertTrue(SourceService.COUNTS_AS_OUTAGE.test(new GitLabException(408, "x")), "server-side timeout");
    assertTrue(SourceService.COUNTS_AS_OUTAGE.test(new java.io.IOException("connection refused")));
    assertFalse(SourceService.COUNTS_AS_OUTAGE.test(new GitLabException(404, "x")), "missing ref");
    assertFalse(SourceService.COUNTS_AS_OUTAGE.test(new GitLabException(403, "x")), "forbidden");
    assertFalse(SourceService.COUNTS_AS_OUTAGE.test(new GitLabException(401, "x")), "unauthorized");
    assertFalse(SourceService.COUNTS_AS_OUTAGE.test(new GitLabException(400, "x")), "bad request");
    assertFalse(SourceService.COUNTS_AS_OUTAGE.test(new GitLabException(200, "malformed body")), "responded");
    assertFalse(SourceService.COUNTS_AS_OUTAGE.test(new GitLabConfigException("GitLab token is not configured")),
        "a local misconfiguration (blank token) is not a GitLab outage");
  }

  @Test
  void recovers_through_the_service_after_the_breaker_cools_to_half_open(@TempDir Path dir) throws Exception {
    // Companion to opens_the_breaker_…: proves the OPEN -> HALF_OPEN -> CLOSED recovery path end-to-end
    // through SourceService (the CircuitBreaker unit tests cover it in isolation; this pins that the
    // service's refCache/breaker/resolver wiring actually lets a real resolve() recover once GitLab is
    // back). A ManualClock drives both the ref TTL and the breaker's cooldown deterministically.
    ManualClock clock = new ManualClock(0);
    AtomicInteger resolves = new AtomicInteger();
    AtomicBoolean down = new AtomicBoolean(true);
    ShaResolver resolver = (p, r) -> {
      resolves.incrementAndGet();
      if (down.get()) throw new RuntimeException("gitlab down");
      return "cafebabecafebabecafebabecafebabecafebabe";
    };
    SourceService svc = new SourceService(
        new ProjectAllowlist(List.of("grp")),
        new RefShaCache(clock, 60_000),           // large TTL: a resolved ref stays cached across the advance
        new SourceBundleCache(dir, 10),
        resolver,
        (p, s, pa) -> null,
        new CircuitBreaker(clock, 2, 1_000));     // trips after 2 failures; cools after 1_000ms

    // Two failures at t=0 trip the breaker OPEN; a third call then fails fast without reaching the resolver.
    assertThrows(RuntimeException.class, () -> svc.resolve("grp/repo", "main")); // failure 1
    assertThrows(RuntimeException.class, () -> svc.resolve("grp/repo", "main")); // failure 2 -> OPEN
    assertThrows(CircuitBreaker.CircuitOpenException.class, () -> svc.resolve("grp/repo", "main"));
    assertEquals(2, resolves.get(), "while OPEN the resolver is not invoked");

    // GitLab recovers and the open window elapses: the next call is the single half-open trial, which
    // succeeds and closes the breaker.
    down.set(false);
    clock.advance(1_000);
    assertEquals("cafebabecafebabecafebabecafebabecafebabe", svc.resolve("grp/repo", "main"),
        "the half-open trial reaches the recovered resolver and returns its sha");
    assertEquals(3, resolves.get(), "the half-open trial invoked the resolver exactly once");

    // Breaker CLOSED again: a fresh ref (cache miss) flows straight through to the resolver — no
    // CircuitOpenException — confirming the breaker really closed rather than merely serving one trial.
    assertEquals("cafebabecafebabecafebabecafebabecafebabe", svc.resolve("grp/repo", "release"));
    assertEquals(4, resolves.get(), "with the breaker closed the resolver is reached again for a new ref");
  }
}
