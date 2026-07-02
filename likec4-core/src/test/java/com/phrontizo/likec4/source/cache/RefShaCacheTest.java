package com.phrontizo.likec4.source.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RefShaCacheTest {
  @Test
  void caches_within_ttl_then_reloads_after_expiry() throws Exception {
    ManualClock clock = new ManualClock(1_000);
    RefShaCache cache = new RefShaCache(clock, 60_000);
    AtomicInteger calls = new AtomicInteger();
    RefShaCache.ShaLoader loader = (p, r) -> { calls.incrementAndGet(); return "sha-" + calls.get(); };

    assertEquals("sha-1", cache.get("grp/repo", "main", loader));
    assertEquals("sha-1", cache.get("grp/repo", "main", loader)); // cached
    assertEquals(1, calls.get());

    clock.advance(60_001);
    assertEquals("sha-2", cache.get("grp/repo", "main", loader)); // expired -> reload
    assertEquals(2, calls.get());
  }

  @Test
  void the_ttl_is_stamped_from_load_completion_not_load_start() throws Exception {
    // A slow loader (real resolveSha can run up to GitLabSourceClient's 30s request timeout) must not
    // have its cached entry expire early. The entry's TTL must run from when the load COMPLETED, not
    // when it started. Here the loader consumes the whole TTL: with the old pre-load stamp the entry
    // would be born already-expired (loadStart+ttl == now-immediately-after-load) and the very next
    // get() would reload; with the load-completion stamp it stays fresh for a further ttl.
    ManualClock clock = new ManualClock(0);
    long ttl = 100;
    RefShaCache cache = new RefShaCache(clock, ttl);
    AtomicInteger calls = new AtomicInteger();
    RefShaCache.ShaLoader slowLoader = (p, r) -> {
      calls.incrementAndGet();
      clock.advance(ttl); // the load itself takes exactly one TTL of wall-clock
      return "sha-" + calls.get();
    };

    assertEquals("sha-1", cache.get("grp/repo", "main", slowLoader)); // clock is now at `ttl`
    // Immediately after the slow load the entry must still be a hit (expiry = loadCompletion + ttl),
    // NOT a miss that re-invokes the loader (which the pre-load stamp would cause).
    assertEquals("sha-1", cache.get("grp/repo", "main",
        (p, r) -> { throw new AssertionError("must be a cache hit, not a reload"); }));
    assertEquals(1, calls.get(), "the load-completion TTL stamp must keep the entry fresh for a full ttl");
  }

  @Test
  void rejects_a_negative_ttl_at_construction() {
    // A negative TTL makes `now + ttlMillis` land in the past, so every entry is instantly stale and
    // the cache silently no-ops. Fail fast at construction instead of degrading invisibly (a corrupt
    // configured TTL once threatened exactly this; the wrapper now clamps it, and the core is strict).
    assertThrows(IllegalArgumentException.class, () -> new RefShaCache(new ManualClock(0), -1));
    assertThrows(IllegalArgumentException.class, () -> new RefShaCache(new ManualClock(0), -1, 10));
  }

  @Test
  void a_pathologically_large_ttl_saturates_instead_of_overflowing_to_an_instant_expiry() throws Exception {
    // Defense-in-depth: `expiresAt = now + ttlMillis` would OVERFLOW to a negative instant for a TTL near
    // Long.MAX_VALUE, so every entry would be born already-expired and the cache would silently no-op —
    // the exact "silently disabled cache adds load to the very GitLab it protects" failure the negative-TTL
    // guard exists to prevent, reached instead via overflow. The wrapper already clamps its configured TTL
    // (AdminConfig.MAX_REF_TTL_MILLIS), but the core must be self-defending against a direct caller: the
    // expiry stamp saturates to Long.MAX_VALUE ("never expires") on overflow rather than wrapping negative.
    ManualClock clock = new ManualClock(5_000);
    RefShaCache cache = new RefShaCache(clock, Long.MAX_VALUE);
    AtomicInteger calls = new AtomicInteger();
    RefShaCache.ShaLoader loader = (p, r) -> { calls.incrementAndGet(); return "sha-" + calls.get(); };

    assertEquals("sha-1", cache.get("grp/repo", "main", loader));
    // With the overflow the entry would be instantly stale and this second get would reload; with the
    // saturating stamp it stays a cache hit (the loader must not run again).
    assertEquals("sha-1", cache.get("grp/repo", "main",
        (p, r) -> { throw new AssertionError("a pathological TTL must saturate, not expire instantly"); }));
    assertEquals(1, calls.get());
  }

  @Test
  void a_zero_ttl_deliberately_disables_caching_so_every_get_reloads() throws Exception {
    // ttl == 0 is a LEGITIMATE "disable ref caching" value (unlike a negative TTL, which is rejected at
    // construction): `expiresAt == now` and freshEntry uses a strict `expiresAt() > now`, so an entry is
    // never a hit and every resolve re-loads. The wrapper's AdminConfig clamps a configured TTL into
    // [0, MAX], so 0 is reachable; pin the disable semantics so a future `>` -> `>=` change (which would
    // silently grant a single same-instant hit) is caught.
    ManualClock clock = new ManualClock(1_000);
    RefShaCache cache = new RefShaCache(clock, 0);
    AtomicInteger calls = new AtomicInteger();
    RefShaCache.ShaLoader loader = (p, r) -> { calls.incrementAndGet(); return "sha-" + calls.get(); };

    assertEquals("sha-1", cache.get("grp/repo", "main", loader));
    assertEquals("sha-2", cache.get("grp/repo", "main", loader)); // no hit: caching disabled
    assertEquals(2, calls.get(), "a zero TTL must disable caching (every get reloads)");
  }

  @Test
  void rejects_a_nonpositive_max_entries_at_construction() {
    // A maxEntries <= 0 makes removeEldestEntry (size() > maxEntries) evict on EVERY insert, so the ref
    // cache silently holds nothing and every resolve re-hits GitLab through the breaker — a silently
    // disabled cache that adds load to the very GitLab it exists to protect. Fail fast, mirroring the
    // negative-TTL guard above and CircuitBreaker's failureThreshold >= 1 guard.
    assertThrows(IllegalArgumentException.class, () -> new RefShaCache(new ManualClock(0), 60_000, 0));
    assertThrows(IllegalArgumentException.class, () -> new RefShaCache(new ManualClock(0), 60_000, -1));
  }

  @Test
  void a_full_40_hex_ref_bypasses_the_loader_entirely() throws Exception {
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    String full = "0123456789abcdef0123456789abcdef01234567";
    assertEquals(full, cache.get("grp/repo", full, (p, r) -> { throw new AssertionError("loader must not run"); }));
  }

  @Test
  void an_uppercase_full_sha_ref_is_normalized_to_lowercase_on_bypass() throws Exception {
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    String upper = "0123456789ABCDEF0123456789ABCDEF01234567";
    assertEquals(upper.toLowerCase(),
        cache.get("grp/repo", upper, (p, r) -> { throw new AssertionError("loader must not run"); }));
  }

  @Test
  void a_loaded_full_sha_is_lowercased_and_the_cached_hit_is_shared() throws Exception {
    ManualClock clock = new ManualClock(1_000);
    RefShaCache cache = new RefShaCache(clock, 60_000);
    AtomicInteger calls = new AtomicInteger();
    String upper = "ABCDEF0123456789ABCDEF0123456789ABCDEF01";
    RefShaCache.ShaLoader loader = (p, r) -> { calls.incrementAndGet(); return upper; };

    assertEquals(upper.toLowerCase(), cache.get("grp/repo", "main", loader));
    assertEquals(upper.toLowerCase(), cache.get("grp/repo", "main", loader)); // shared cache hit
    assertEquals(1, calls.get());
  }

  @Test
  void bounds_entries_evicting_the_eldest_beyond_max() throws Exception {
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000, 2);
    AtomicInteger calls = new AtomicInteger();
    RefShaCache.ShaLoader loader = (p, r) -> "sha-" + r + "-" + calls.incrementAndGet();

    cache.get("grp/repo", "r1", loader);
    cache.get("grp/repo", "r2", loader);
    cache.get("grp/repo", "r3", loader); // evicts r1 (eldest)

    // r1 was evicted -> a fresh get re-invokes the loader; r3 (recent) stays cached.
    String r3a = cache.get("grp/repo", "r3", (p, r) -> { throw new AssertionError("r3 should be cached"); });
    int before = calls.get();
    cache.get("grp/repo", "r1", loader); // must reload (was evicted)
    assertEquals(before + 1, calls.get());
    assertEquals("sha-r3-3", r3a);
  }

  @Test
  void distinct_project_ref_pairs_never_alias_to_the_same_cache_entry() throws Exception {
    // Under a space delimiter ("a b","c") and ("a","b c") would both key to "a b c" and the second
    // call would wrongly return the first's sha. The NUL delimiter keeps them distinct.
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    assertEquals("sha-one", cache.get("a b", "c", (p, r) -> "sha-one"));
    assertEquals("sha-two", cache.get("a", "b c", (p, r) -> "sha-two"));
  }

  @Test
  void rejects_a_nul_in_a_key_component_so_it_cannot_break_the_anti_aliasing_invariant() {
    // project and ref are joined by a NUL delimiter, relying on neither containing a NUL so distinct
    // pairs cannot alias. Production input is NUL-free (InputValidation rejects it), but a direct
    // caller that smuggled one in would collide ("a"/"b\u0000c" and "a\u0000b"/"c" build the same
    // key). Reject a NUL up front (sibling to the length guard) so the invariant holds independently.
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    RefShaCache.ShaLoader mustNotRun =
        (p, r) -> { throw new AssertionError("must reject a NUL before loading"); };
    assertThrows(IllegalArgumentException.class, () -> cache.get("a", "b\u0000c", mustNotRun));
    assertThrows(IllegalArgumentException.class, () -> cache.get("a\u0000b", "c", mustNotRun));
  }

  @Test
  void a_loader_failure_is_not_negatively_cached_so_the_next_call_retries() throws Exception {
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    AtomicInteger calls = new AtomicInteger();
    assertThrows(RuntimeException.class,
        () -> cache.get("grp/repo", "main", (p, r) -> { calls.incrementAndGet(); throw new RuntimeException("gitlab down"); }));
    // The failure must NOT be cached: a subsequent call re-invokes the loader (and can succeed).
    assertEquals("sha-ok", cache.get("grp/repo", "main", (p, r) -> { calls.incrementAndGet(); return "sha-ok"; }));
    assertEquals(2, calls.get(), "a loader failure must not be negatively cached");
  }

  @Test
  void an_expired_entry_is_evicted_on_access_even_when_the_reload_fails() throws Exception {
    ManualClock clock = new ManualClock(0);
    RefShaCache cache = new RefShaCache(clock, 60_000);
    cache.get("grp/repo", "main", (p, r) -> "sha-1");
    assertEquals(1, cache.size());

    clock.advance(60_001); // entry now expired
    // A failing reload must NOT leave the expired entry behind. Previously the freshness check did a
    // LinkedHashMap.get (access-order), which bumped the stale entry to the MRU end; with the reload
    // throwing, it then lingered there — artificially protected from LRU eviction despite being stale.
    assertThrows(RuntimeException.class,
        () -> cache.get("grp/repo", "main", (p, r) -> { throw new RuntimeException("upstream down"); }));
    assertEquals(0, cache.size(), "the expired entry must be proactively evicted, not left at MRU");
  }

  @Test
  void a_loader_returning_null_is_rejected_and_never_cached() throws Exception {
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    // A null sha is a loader failure, not a value: it must be rejected (mirroring SourceBundleCache),
    // never stored — otherwise the null would be served for the whole TTL.
    assertThrows(IllegalStateException.class, () -> cache.get("grp/repo", "main", (p, r) -> null));
    // Not negatively cached: a subsequent call with a working loader loads fresh and succeeds.
    String good = "0123456789abcdef0123456789abcdef01234567";
    assertEquals(good, cache.get("grp/repo", "main", (p, r) -> good));
  }

  @Test
  void get_rejects_a_null_ref_and_project_with_a_clear_message() {
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    // A null ref would otherwise NPE deep inside FULL_SHA.matcher(ref) with no context; a null project
    // would silently build a cache key whose first segment is the literal "null". Fail fast with a named argument instead.
    NullPointerException refEx = assertThrows(NullPointerException.class,
        () -> cache.get("grp/repo", null, (p, r) -> "x"));
    assertEquals("ref", refEx.getMessage());
    NullPointerException projEx = assertThrows(NullPointerException.class,
        () -> cache.get(null, "main", (p, r) -> "x"));
    assertEquals("project", projEx.getMessage());
  }

  @Test
  void get_rejects_an_over_long_ref_or_project() {
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    // Defense-in-depth memory-DoS guard (mirrors DEFAULT_MAX_ENTRIES, which bounds the entry COUNT): a
    // misbehaving direct caller must not be able to pin an unbounded-length cache key. The sanitised
    // production path (InputValidation caps project/ref far tighter) is nowhere near this bound.
    String tooLong = "x".repeat(RefShaCache.MAX_KEY_COMPONENT_CHARS + 1);
    assertThrows(IllegalArgumentException.class,
        () -> cache.get("grp/repo", tooLong, (p, r) -> { throw new AssertionError("must reject before loading"); }));
    assertThrows(IllegalArgumentException.class,
        () -> cache.get(tooLong, "main", (p, r) -> { throw new AssertionError("must reject before loading"); }));
  }

  @Test
  void a_loaded_value_exceeding_the_sha_bound_is_rejected_and_not_cached() throws Exception {
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    // A loaded sha is length-bounded (defense-in-depth, sibling to the null-result and key-length
    // guards): a misbehaving loader must not be able to pin an unbounded-length VALUE in the cache.
    String tooLong = "a".repeat(RefShaCache.MAX_SHA_CHARS + 1);
    assertThrows(IllegalStateException.class, () -> cache.get("grp/repo", "main", (p, r) -> tooLong));
    // Not negatively cached: a subsequent call with a sane loader loads fresh and succeeds.
    assertEquals("sha-ok", cache.get("grp/repo", "main", (p, r) -> "sha-ok"));
  }

  @Test
  void single_flight_coalesces_concurrent_misses_for_the_same_key() throws Exception {
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    RefShaCache.ShaLoader loader = (p, r) -> {
      calls.incrementAndGet();
      Thread.sleep(50);
      return "0123456789abcdef0123456789abcdef01234567";
    };
    int n = 6;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try {
      for (int i = 0; i < n; i++) {
        pool.submit(() -> {
          start.await();
          return cache.get("grp/repo", "main", loader);
        });
      }
      start.countDown();
      pool.shutdown();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, calls.get(), "concurrent same-key misses must load exactly once");
  }

  @Test
  void an_interrupt_propagates_with_the_flag_restored() throws Exception {
    // An InterruptedException from the loader is caller cancellation / shutdown, NOT a load failure:
    // it must propagate with the interrupt flag restored — mirroring SourceBundleCache.get and
    // CircuitBreaker.call, which deliberately re-throw interrupts rather than mask them. Without the
    // restore, a caller up the stack that polls Thread.interrupted() to decide whether to abort would
    // keep running. The loader here throws WITHOUT restoring the flag, so the cache must restore it.
    RefShaCache cache = new RefShaCache(new ManualClock(0), 60_000);
    assertFalse(Thread.currentThread().isInterrupted(), "precondition: thread not already interrupted");
    try {
      assertThrows(InterruptedException.class,
          () -> cache.get("grp/repo", "main", (p, r) -> { throw new InterruptedException("cancelled"); }));
      assertTrue(Thread.currentThread().isInterrupted(),
          "the interrupt flag must be restored after propagating InterruptedException");
    } finally {
      Thread.interrupted(); // clear the flag so it cannot leak into other tests reusing this thread
    }
  }
}
