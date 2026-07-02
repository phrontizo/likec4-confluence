package com.phrontizo.likec4.source.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phrontizo.likec4.source.SourceBundle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceBundleCacheTest {

  private static SourceBundle bundle(String sha) {
    return new SourceBundle(sha, Map.of("model.likec4", "content-" + sha));
  }

  /** A bundle whose in-memory WEIGHT (path+content chars) is exactly {@code 1 + contentChars} — a 1-char
   *  path "f" plus a content of the given length — so a test can drive the aggregate-weight cap precisely. */
  private static SourceBundle sized(String sha, int contentChars) {
    return new SourceBundle(sha, Map.of("f", "x".repeat(contentChars)));
  }

  @Test
  void rejects_negative_entry_caps_at_construction(@TempDir Path dir) throws Exception {
    // A negative maxEntries makes the hot LRU (size() > maxEntries) evict on every put — it holds
    // nothing. A negative maxDiskEntries is worse: pruneDisk computes over = files.size() - maxDiskEntries
    // (e.g. size - (-1) = size + 1), deleting EVERY .json on each write, so the disk tier is silently
    // disabled. Fail fast at construction, mirroring RefShaCache/CircuitBreaker rather than degrading
    // invisibly. (0 is permitted: it deliberately disables a single tier — the 2-arg ctor floors the
    // disk cap at 500 for a 0 hot cap.)
    assertThrows(IllegalArgumentException.class, () -> new SourceBundleCache(dir, -1));
    assertThrows(IllegalArgumentException.class, () -> new SourceBundleCache(dir, 10, -1));
  }

  @Test
  void distinct_project_sha_path_triples_never_alias_to_the_same_cache_entry(@TempDir Path dir)
      throws Exception {
    // The composite key joins (project,sha,path) with a NUL delimiter, relying on neither containing a
    // NUL so distinct triples cannot alias. Under a space delimiter ("a b","c","d") and ("a","b c","d")
    // would both key to "a b c d" and the second call would wrongly serve the first's bundle. The NUL
    // delimiter keeps them distinct — mirrors RefShaCache's anti-aliasing guarantee.
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    assertEquals("one", c.get("a b", "c", "d", (p, s, pa) -> bundle("one")).sha());
    assertEquals("two", c.get("a", "b c", "d", (p, s, pa) -> bundle("two")).sha());
  }

  @Test
  void rejects_a_nul_in_a_key_component_so_it_cannot_break_the_anti_aliasing_invariant(@TempDir Path dir)
      throws Exception {
    // project/sha/path are joined by a NUL delimiter, relying on none containing a NUL so distinct
    // triples cannot alias. Production input is NUL-free (InputValidation rejects it), but a direct
    // caller that smuggled one in would collide ("a"/"b\u0000c"/"d" and "a\u0000b"/"c"/"d" build the
    // same key). Reject a NUL up front so the invariant holds independently of upstream sanitisation —
    // sibling to RefShaCache's identical guard.
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    SourceBundleCache.BundleLoader mustNotRun =
        (p, s, pa) -> { throw new AssertionError("must reject a NUL before loading"); };
    assertThrows(IllegalArgumentException.class, () -> c.get("a", "b\u0000c", "d", mustNotRun));
    assertThrows(IllegalArgumentException.class, () -> c.get("a\u0000b", "c", "d", mustNotRun));
    assertThrows(IllegalArgumentException.class, () -> c.get("a", "b", "c\u0000d", mustNotRun));
  }

  @Test
  void rejects_a_null_project_or_sha_but_keeps_a_null_path_nullable(@TempDir Path dir) throws Exception {
    // A null project/sha would otherwise concat into a literal "null" key segment (project + SEP + sha),
    // silently aliasing every null-component request to one slot. Fail fast with a named argument,
    // mirroring RefShaCache.get. path stays nullable (nz() maps it to "") — a null path must NOT throw.
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    SourceBundleCache.BundleLoader mustNotRun =
        (p, s, pa) -> { throw new AssertionError("must reject a null key component before loading"); };
    assertThrows(NullPointerException.class, () -> c.get(null, "s", "p", mustNotRun));
    assertThrows(NullPointerException.class, () -> c.get("proj", null, "p", mustNotRun));
    assertEquals("ok", c.get("proj", "s", null, (p, s, pa) -> bundle("ok")).sha());
  }

  @Test
  void restricts_the_cache_dir_and_entry_files_to_owner_only_on_posix(@TempDir Path parent) throws Exception {
    Path dir = parent.resolve("cache"); // a not-yet-existing dir, so the cache creates it with our perms
    org.junit.jupiter.api.Assumptions.assumeTrue(
        dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "POSIX-only: file permissions are not enforceable on this filesystem");
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    c.get("grp/repo", "shaP", "diagrams", (p, s, pa) -> bundle("shaP")); // publishes one .json to disk

    // The cache dir the plugin creates must not be group/other accessible (an operator can point the
    // cache at a shared path). Source DSL is not a secret, but mirror the token key store's discipline.
    Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(dir);
    assertFalse(
        dirPerms.contains(PosixFilePermission.GROUP_READ)
            || dirPerms.contains(PosixFilePermission.OTHERS_READ),
        "cache dir must not be group/other readable, was: " + PosixFilePermissions.toString(dirPerms));

    boolean sawEntry = false;
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        if (!p.getFileName().toString().endsWith(".json")) continue;
        sawEntry = true;
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
        assertEquals(
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms,
            "entry " + p.getFileName() + " must be owner-only rw, was: " + PosixFilePermissions.toString(perms));
      }
    }
    assertTrue(sawEntry, "expected the get() to have published a .json disk entry");
  }

  @Test
  void serves_from_disk_on_a_second_instance_without_calling_loader(@TempDir Path dir) throws Exception {
    SourceBundleCache c1 = new SourceBundleCache(dir, 10);
    c1.get("grp/repo", "shaA", "diagrams", (p, s, pa) -> bundle("shaA"));

    SourceBundleCache c2 = new SourceBundleCache(dir, 10);
    SourceBundle got = c2.get("grp/repo", "shaA", "diagrams",
        (p, s, pa) -> { throw new AssertionError("loader must not run - disk hit expected"); });
    assertEquals("content-shaA", got.files().get("model.likec4"));
  }

  @Test
  void sweeps_orphaned_write_temps_at_construction_but_keeps_real_entries(@TempDir Path dir) throws Exception {
    // writeDisk publishes via a ".src-*.json.tmp" temp + atomic rename and deletes the temp in a
    // finally; a kill -9 BETWEEN temp creation and that finally leaves the temp behind, and neither
    // pruneDisk nor countDiskEntries touches it (both match ".json"). Across crashes these would
    // accumulate unbounded, so construction sweeps them. It must NOT touch published .json entries or
    // unrelated files.
    Path orphan1 = Files.createFile(dir.resolve(".src-1234567890.json.tmp"));
    Path orphan2 = Files.createFile(dir.resolve(".src-abcdef.json.tmp"));
    Path realEntry = Files.writeString(dir.resolve("deadbeef.json"), "{\"sha\":\"x\",\"files\":{}}");
    Path unrelated = Files.createFile(dir.resolve("keep-me.txt"));

    new SourceBundleCache(dir, 10);

    assertFalse(Files.exists(orphan1), "orphaned write temp must be swept at construction");
    assertFalse(Files.exists(orphan2), "orphaned write temp must be swept at construction");
    assertTrue(Files.exists(realEntry), "a published .json entry must never be swept");
    assertTrue(Files.exists(unrelated), "an unrelated non-temp file must never be swept");
  }

  @Test
  void bounds_in_memory_entries_by_lru_evicting_the_least_recently_used(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 2);
    c.get("grp/repo", "s1", "d", (p, s, pa) -> bundle("s1"));
    c.get("grp/repo", "s2", "d", (p, s, pa) -> bundle("s2"));
    // Touch s1 (a mem hit -> the throwing loader must NOT run) so s2 becomes the least-recently-used.
    c.get("grp/repo", "s1", "d", (p, s, pa) -> { throw new AssertionError("s1 should be a mem hit"); });
    c.get("grp/repo", "s3", "d", (p, s, pa) -> bundle("s3")); // count 3 > 2 -> evict the LRU (s2)
    assertEquals(2, c.memSize());
    // Assert WHICH entry was evicted: the count alone would pass even if the newest were wrongly dropped.
    assertTrue(c.memContains("grp/repo", "s1", "d"), "the touched entry must survive in mem");
    assertTrue(c.memContains("grp/repo", "s3", "d"), "the freshly-added entry must survive in mem");
    assertFalse(c.memContains("grp/repo", "s2", "d"), "the least-recently-used entry must be evicted");
  }

  @Test
  void a_zero_bound_mem_tier_stores_nothing_and_keeps_its_running_weight_at_zero(@TempDir Path dir)
      throws Exception {
    // maxEntries == 0 disables the hot tier (disk-only mode). A store still runs super.put + the count-cap
    // removeEldestEntry, which evicts the JUST-INSERTED entry: removeEldestEntry subtracts that entry's
    // weight (the running weight momentarily goes negative) and the put's own `chars += weight` adds it
    // back, so the accounting must self-correct to EXACTLY 0 with an empty map — never leave a stranded
    // weight that would make every later weight check drift. The reasoning is subtle; pin the net-zero.
    SourceBundleCache c = new SourceBundleCache(dir, 0);
    c.get("grp/repo", "s1", "d", (p, s, pa) -> sized("s1", 100)); // a non-trivial weight to store+evict
    assertEquals(0, c.memSize(), "the hot tier holds nothing when its bound is 0");
    assertEquals(0L, c.memChars(), "the running weight must net back to exactly 0, not a stranded weight");
    // The net-zero must hold across repeated inserts, not just the first.
    c.get("grp/repo", "s2", "d", (p, s, pa) -> sized("s2", 100));
    assertEquals(0L, c.memChars(), "repeated stores into a 0-bound tier must keep the weight at 0");
  }

  @Test
  void bounds_the_in_memory_tier_by_aggregate_weight_not_only_entry_count(@TempDir Path dir) throws Exception {
    // Entry-count caps alone don't bound heap: each bundle can hold up to the extractor's 32 MiB cap, so
    // a byte cap is the belt-and-braces bound. Use a high entry cap (so COUNT never bites) and a tiny
    // weight cap (25 chars) so aggregate WEIGHT governs eviction. Each sized(...,9) bundle weighs 10.
    SourceBundleCache c = new SourceBundleCache(dir, 100, 100, Clock.SYSTEM, 25);
    c.get("grp/repo", "s1", "d", (p, s, pa) -> sized("s1", 9)); // chars 10
    c.get("grp/repo", "s2", "d", (p, s, pa) -> sized("s2", 9)); // chars 20
    c.get("grp/repo", "s3", "d", (p, s, pa) -> sized("s3", 9)); // chars 30 > 25 -> evict eldest (s1)
    assertEquals(2, c.memSize(), "the weight cap must keep the entry count at 2 despite the 100-entry cap");
    assertEquals(20, c.memChars(), "the running weight must be exactly the two survivors (10 + 10)");
    assertFalse(c.memContains("grp/repo", "s1", "d"), "the least-recently-used entry must be evicted by weight");
    assertTrue(c.memContains("grp/repo", "s2", "d"));
    assertTrue(c.memContains("grp/repo", "s3", "d"));
  }

  @Test
  void a_single_large_bundle_evicts_multiple_lru_entries_to_stay_under_the_weight_cap(@TempDir Path dir)
      throws Exception {
    // removeEldestEntry evicts at most ONE per put; the weight cap must LOOP to shed enough. A big insert
    // that overshoots the cap by more than one eldest's worth must drop as many LRU entries as needed.
    SourceBundleCache c = new SourceBundleCache(dir, 100, 100, Clock.SYSTEM, 25);
    c.get("grp/repo", "s1", "d", (p, s, pa) -> sized("s1", 9));  // chars 10
    c.get("grp/repo", "s2", "d", (p, s, pa) -> sized("s2", 9));  // chars 20
    c.get("grp/repo", "s3", "d", (p, s, pa) -> sized("s3", 19)); // +20 -> 40 > 25 -> evict s1 AND s2
    assertEquals(1, c.memSize(), "one insert overshooting the cap must shed BOTH prior LRU entries");
    assertEquals(20, c.memChars(), "only the freshly-inserted large bundle remains (weight 20)");
    assertTrue(c.memContains("grp/repo", "s3", "d"), "the entry we must serve is never evicted");
    assertFalse(c.memContains("grp/repo", "s1", "d"));
    assertFalse(c.memContains("grp/repo", "s2", "d"));
  }

  @Test
  void single_flight_coalesces_concurrent_misses(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    AtomicInteger calls = new AtomicInteger();
    Callable<SourceBundle> task = () -> c.get("grp/repo", "sha", "d", (p, s, pa) -> {
      calls.incrementAndGet();
      Thread.sleep(50);
      return bundle("sha");
    });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<SourceBundle> f1 = pool.submit(task);
    Future<SourceBundle> f2 = pool.submit(task);
    f1.get();
    f2.get();
    pool.shutdown();
    assertEquals(1, calls.get());
  }

  @Test
  void rechecks_disk_inside_the_lock_so_a_concurrent_writer_is_not_reloaded(@TempDir Path dir)
      throws Exception {
    // Reproduce the best-effort single-flight race: a thread can pass the pre-lock mem+disk miss, park
    // on the per-key lock, and by the time it enters the lock a concurrent loader has already published
    // the bundle to DISK (and dropped its lock). With the hot tier disabled (mem bound 0) the in-lock
    // MEM re-check can't catch this; only an in-lock DISK re-check does. Without it, the waiter
    // redundantly re-runs its loader (an extra GitLab fetch + disk write) instead of serving the
    // just-written disk bundle.
    SourceBundleCache c = new SourceBundleCache(dir, 0); // mem bound 0 -> hot tier disabled, disk only
    CountDownLatch aInLoader = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger bLoaderCalls = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<SourceBundle> fa = pool.submit(() -> c.get("grp/repo", "sha", "d", (p, s, pa) -> {
        aInLoader.countDown(); // A now holds the per-key lock; disk is still empty
        release.await();       // hold the lock until B is parked on it
        return bundle("sha");
      }));
      assertTrue(aInLoader.await(5, TimeUnit.SECONDS), "loader A should start");
      Future<SourceBundle> fb = pool.submit(() -> c.get("grp/repo", "sha", "d", (p, s, pa) -> {
        bLoaderCalls.incrementAndGet();
        return bundle("reloaded"); // a DIFFERENT bundle, so a regression shows in the result too
      }));
      Thread.sleep(300);   // let B miss both tiers pre-lock and park on the lock A holds
      release.countDown(); // A returns -> store (mem evicted), writeDisk, drops lock
      SourceBundle aResult = fa.get(5, TimeUnit.SECONDS);
      SourceBundle bResult = fb.get(5, TimeUnit.SECONDS);
      assertEquals("content-sha", aResult.files().get("model.likec4"));
      assertEquals("content-sha", bResult.files().get("model.likec4"),
          "B must serve A's disk bundle via the in-lock disk re-check, not a reload");
      assertEquals(0, bLoaderCalls.get(),
          "the in-lock disk re-check must avoid re-running B's loader once A published to disk");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void serves_stale_on_loader_failure(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    SourceBundle good = c.get("grp/repo", "shaOld", "d", (p, s, pa) -> bundle("shaOld"));
    SourceBundle stale = c.get("grp/repo", "shaNew", "d", (p, s, pa) -> { throw new RuntimeException("gitlab down"); });
    assertEquals(good.files(), stale.files()); // last-good for (project,path) served
  }

  @Test
  void rethrows_when_no_stale_available(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    assertThrows(RuntimeException.class,
        () -> c.get("grp/repo", "shaX", "d", (p, s, pa) -> { throw new RuntimeException("down"); }));
  }

  @Test
  void an_interrupt_propagates_and_is_not_masked_by_stale_while_revalidate(@TempDir Path dir) throws Exception {
    // An InterruptedException is caller cancellation / shutdown, NOT a GitLab failure: it must
    // propagate (with the interrupt flag restored) rather than be masked by SWR serving a stale
    // bundle — mirroring CircuitBreaker.call, which deliberately re-throws interrupts instead of
    // counting them as failures.
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    c.get("grp/repo", "shaOld", "d", (p, s, pa) -> bundle("shaOld")); // prime a last-good entry
    assertFalse(Thread.currentThread().isInterrupted(), "precondition: thread not already interrupted");
    try {
      assertThrows(InterruptedException.class,
          () -> c.get("grp/repo", "shaNew", "d", (p, s, pa) -> { throw new InterruptedException("cancelled"); }));
      assertTrue(Thread.currentThread().isInterrupted(),
          "the interrupt flag must be restored after propagating InterruptedException");
    } finally {
      Thread.interrupted(); // clear the flag so it cannot leak into other tests reusing this thread
    }
  }

  @Test
  void a_failing_disk_write_does_not_serve_stale_or_throw_and_still_returns_fresh(@TempDir Path root)
      throws Exception {
    // Use a subdirectory as the cache dir so we can delete it (forcing disk-write failures) while
    // leaving the @TempDir root intact for JUnit's cleanup.
    Path dir = root.resolve("cache");
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    // Prime a last-good entry for (project,path) so that, if a disk-write failure WERE mistaken for a
    // loader failure, SWR would serve this stale bundle instead of the freshly loaded one.
    c.get("grp/repo", "shaOld", "d", (p, s, pa) -> bundle("shaOld"));
    // Make every subsequent disk write fail by removing the cache directory out from under the cache.
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        Files.delete(p);
      }
    }
    Files.delete(dir);

    SourceBundle got = c.get("grp/repo", "shaNew", "d", (p, s, pa) -> bundle("shaNew"));
    assertEquals("content-shaNew", got.files().get("model.likec4"),
        "must return the freshly loaded bundle, not the stale one, despite the disk-write failure");
  }

  @Test
  void a_null_bundle_is_treated_as_a_failure_and_never_cached(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    assertThrows(Exception.class, () -> c.get("grp/repo", "shaNull", "d", (p, s, pa) -> null));
    assertEquals(0, c.memSize(), "null must not be cached in memory");
    assertEquals(0, jsonFileCount(dir), "null must not be written to disk");
  }

  @Test
  void a_null_bundle_serves_stale_when_a_last_good_exists(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    SourceBundle good = c.get("grp/repo", "shaOld", "d", (p, s, pa) -> bundle("shaOld"));
    SourceBundle served = c.get("grp/repo", "shaNew", "d", (p, s, pa) -> null);
    assertEquals(good.files(), served.files(), "null routes through SWR -> last-good for (project,path)");
  }

  @Test
  void a_disk_hit_for_an_older_sha_does_not_regress_last_good(@TempDir Path dir) throws Exception {
    // shaOld is written to disk by a first instance.
    SourceBundleCache writer = new SourceBundleCache(dir, 10);
    writer.get("grp/repo", "shaOld", "d", (p, s, pa) -> bundle("shaOld"));

    // A fresh instance (empty mem) loads the CURRENT sha first -> last-good[(grp/repo,d)] = shaNew.
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    c.get("grp/repo", "shaNew", "d", (p, s, pa) -> bundle("shaNew"));
    // An explicit request for the OLDER sha is a disk hit; it must NOT overwrite last-good with shaOld.
    c.get("grp/repo", "shaOld", "d", (p, s, pa) -> { throw new AssertionError("disk hit expected"); });

    // A subsequent loader failure must serve the NEWER last-good (shaNew), not the regressed shaOld.
    SourceBundle served =
        c.get("grp/repo", "shaNewest", "d", (p, s, pa) -> { throw new RuntimeException("down"); });
    assertEquals(bundle("shaNew").files(), served.files(),
        "the disk hit for the older sha must not have regressed last-good to the older bundle");
  }

  @Test
  void a_corrupt_on_disk_entry_is_treated_as_a_miss_and_reloaded(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    c.get("grp/repo", "shaC", "d", (p, s, pa) -> bundle("shaC")); // writes one .json to disk
    // Corrupt every on-disk cache file so readDisk's JSON parse throws.
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        if (p.getFileName().toString().endsWith(".json")) Files.writeString(p, "{ not valid json");
      }
    }
    // A fresh instance (empty mem) must treat the corrupt entry as a miss and re-run the loader.
    SourceBundleCache fresh = new SourceBundleCache(dir, 10);
    AtomicInteger calls = new AtomicInteger();
    SourceBundle got = fresh.get("grp/repo", "shaC", "d", (p, s, pa) -> { calls.incrementAndGet(); return bundle("shaC"); });
    assertEquals("content-shaC", got.files().get("model.likec4"));
    assertEquals(1, calls.get(), "corrupt disk entry must be a miss, forcing a reload");
  }

  @Test
  void a_disk_entry_with_null_files_is_treated_as_a_miss_and_reloaded(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    c.get("grp/repo", "shaN", "d", (p, s, pa) -> bundle("shaN")); // writes one .json to disk
    // Overwrite the on-disk entry with a structurally-parseable but semantically-bad shape: files=null.
    // SourceBundle's canonical ctor runs Map.copyOf(files), so deserializing this throws (an NPE — a
    // RuntimeException, not an IOException) during readValue. It must be treated as a miss and force a
    // reload, never propagate out of get() and 500 a render.
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        if (p.getFileName().toString().endsWith(".json")) {
          Files.writeString(p, "{\"sha\":\"shaN\",\"files\":null}");
        }
      }
    }
    SourceBundleCache fresh = new SourceBundleCache(dir, 10);
    AtomicInteger calls = new AtomicInteger();
    SourceBundle got = fresh.get("grp/repo", "shaN", "d",
        (p, s, pa) -> { calls.incrementAndGet(); return bundle("shaN"); });
    assertEquals("content-shaN", got.files().get("model.likec4"));
    assertEquals(1, calls.get(), "a semantically-bad disk entry (files:null) must be a miss, forcing a reload");
  }

  @Test
  void an_oversized_on_disk_entry_is_treated_as_a_miss_and_reloaded(@TempDir Path dir) throws Exception {
    SourceBundleCache c = new SourceBundleCache(dir, 10);
    c.get("grp/repo", "shaB", "d", (p, s, pa) -> bundle("shaB")); // writes one valid .json to disk
    // Bloat the on-disk entry past the read cap WITHOUT loading it into the heap: extend the file
    // (sparse, so the test stays cheap) one byte over the 64 MiB cap. Its leading bytes are still the
    // valid bundle JSON, so absent the size guard readDisk would parse it as a HIT; with the guard it
    // must size-check, bail before readString, and treat the entry as a miss that forces a reload.
    Path entry;
    try (Stream<Path> s = Files.list(dir)) {
      entry = s.filter(p -> p.getFileName().toString().endsWith(".json")).findFirst().orElseThrow();
    }
    try (var raf = new java.io.RandomAccessFile(entry.toFile(), "rw")) {
      raf.setLength(64L * 1024 * 1024 + 1);
    }
    SourceBundleCache fresh = new SourceBundleCache(dir, 10); // empty mem -> must consult disk
    AtomicInteger calls = new AtomicInteger();
    SourceBundle got = fresh.get("grp/repo", "shaB", "d",
        (p, s, pa) -> { calls.incrementAndGet(); return bundle("shaB"); });
    assertEquals("content-shaB", got.files().get("model.likec4"));
    assertEquals(1, calls.get(), "an oversized disk entry must be a miss, forcing a reload");
  }

  private static long jsonFileCount(Path dir) throws Exception {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.getFileName().toString().endsWith(".json")).count();
    }
  }

  private static java.util.List<String> jsonFileNames(Path dir) throws Exception {
    try (Stream<Path> s = Files.list(dir)) {
      return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted().toList();
    }
  }

  @Test
  void the_disk_estimate_stays_reconciled_to_the_true_count_across_many_over_cap_writes(@TempDir Path dir)
      throws Exception {
    // The in-process estimate exists so writeDisk can skip enumerating the dir on the common under-cap
    // write. If it ever drifts BELOW the true count the fast path stops pruning and the cap is silently
    // disabled. Pin that, across many over-cap writes, the estimate stays exactly the true on-disk count
    // and the cap holds after every write.
    ManualClock clock = new ManualClock(1_000);
    SourceBundleCache c = new SourceBundleCache(dir, 100, 3, clock); // disk bound 3
    for (int i = 0; i < 20; i++) {
      clock.advance(1);
      final int n = i;
      c.get("grp/repo", "s" + n, "d", (p, s, pa) -> bundle("s" + n));
      long onDisk = jsonFileCount(dir);
      assertTrue(onDisk <= 3, "the disk cap must hold after every write (was " + onDisk + ")");
      assertEquals(onDisk, c.diskEstimate(),
          "the estimate must stay reconciled to the true on-disk count (no drift)");
    }
    assertEquals(3, jsonFileCount(dir));
    assertEquals(3, c.diskEstimate());
  }

  @Test
  void overwriting_an_existing_disk_entry_does_not_grow_the_estimate(@TempDir Path dir) throws Exception {
    // writeDisk only bumps diskEstimate when it publishes a NEW file (isNew = !Files.exists). An OVERWRITE
    // of an existing key's file must keep the estimate flat — otherwise repeated rewrites would drift it
    // HIGH and trigger needless prunes. Force an overwrite by corrupting the published entry in place:
    // the file still EXISTS (so isNew is false) but readDisk returns a MISS, so the loader re-runs and
    // writeDisk overwrites the still-present file. Pins the isNew guard, which the reconcile would
    // otherwise mask (high drift is self-correcting, so a regression removing it only shows under load).
    SourceBundleCache c = new SourceBundleCache(dir, 0); // mem disabled -> every get consults disk
    c.get("grp/repo", "shaO", "d", (p, s, pa) -> bundle("shaO")); // publishes one .json; estimate -> 1
    assertEquals(1, c.diskEstimate());
    assertEquals(1, jsonFileCount(dir));
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        if (p.getFileName().toString().endsWith(".json")) Files.writeString(p, "{ not valid json");
      }
    }
    // Same key: readDisk miss (corrupt) -> loader re-runs -> writeDisk overwrites the existing file.
    c.get("grp/repo", "shaO", "d", (p, s, pa) -> bundle("shaO"));
    assertEquals(1, jsonFileCount(dir), "the overwrite must not create a second file");
    assertEquals(1, c.diskEstimate(),
        "overwriting an existing key's disk entry must keep the estimate flat (isNew=false)");
  }

  @Test
  void prune_evicts_deterministically_when_entries_share_a_millisecond_mtime(
      @TempDir Path dirA, @TempDir Path dirB) throws Exception {
    // The clock never advances, so all writes share one mtime and eviction rests entirely on the
    // filename tie-break. Two independent caches given identical inputs must evict the SAME entry —
    // without the tie-break, eviction among equal-mtime entries would follow the unspecified Files.list
    // order and could differ.
    ManualClock clockA = new ManualClock(1_000);
    ManualClock clockB = new ManualClock(1_000);
    for (SourceBundleCache c :
        java.util.List.of(new SourceBundleCache(dirA, 100, 2, clockA),
                           new SourceBundleCache(dirB, 100, 2, clockB))) {
      c.get("grp/repo", "s1", "d", (p, s, pa) -> bundle("s1"));
      c.get("grp/repo", "s2", "d", (p, s, pa) -> bundle("s2"));
      c.get("grp/repo", "s3", "d", (p, s, pa) -> bundle("s3")); // 3rd write, bound 2 -> one eviction
    }
    assertEquals(2, jsonFileCount(dirA));
    // Same keys hash to the same filenames in both dirs, so a deterministic tie-break yields identical
    // surviving filename sets; a non-deterministic one could diverge.
    assertEquals(jsonFileNames(dirA), jsonFileNames(dirB),
        "eviction of equal-mtime entries must be deterministic across identical inputs");
  }

  @Test
  void concurrent_distinct_key_writes_keep_the_cap_and_reconcile_the_estimate(@TempDir Path dir)
      throws Exception {
    // Stress the write-racing-prune path: many threads writing DISTINCT keys, so prunes fire while other
    // writes publish. The reconcile must not clobber a racing write's increment (which would leave the
    // estimate low and disable the cap). After the dust settles the estimate must equal the true count
    // and the cap must hold.
    int cap = 5;
    SourceBundleCache c = new SourceBundleCache(dir, 100, cap);
    int threads = 16, perThread = 40;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    java.util.List<Future<?>> futures = new java.util.ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final int tid = t;
      futures.add(pool.submit(() -> {
        start.await();
        for (int i = 0; i < perThread; i++) {
          String sha = "t" + tid + "-i" + i; // distinct key per write
          c.get("grp/repo", sha, "d", (p, s, pa) -> bundle(sha));
        }
        return null;
      }));
    }
    start.countDown();
    try {
      for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    // Loose drift guard while racing: the cap may be transiently overshot by in-flight writes, but never
    // unboundedly (a low-drift regression would let it grow far past this).
    assertTrue(jsonFileCount(dir) <= cap + threads,
        "concurrent writes must not blow the cap unboundedly (was " + jsonFileCount(dir) + ")");
    // A final quiescent write forces a reconciling prune once racing has stopped.
    c.get("grp/repo", "final-key", "d", (p, s, pa) -> bundle("final"));
    long onDisk = jsonFileCount(dir);
    assertEquals(cap, onDisk, "the disk cap must be enforced after concurrent writes settle");
    assertEquals(onDisk, c.diskEstimate(), "the estimate must reconcile to the true count");
  }

  @Test
  void bounds_on_disk_entries_evicting_the_oldest(@TempDir Path dir) throws Exception {
    ManualClock clock = new ManualClock(1_000);
    // generous in-memory bound so only the disk tier is under test; disk bound = 2.
    SourceBundleCache c = new SourceBundleCache(dir, 100, 2, clock);
    c.get("grp/repo", "s1", "d", (p, s, pa) -> bundle("s1"));
    clock.advance(10);
    c.get("grp/repo", "s2", "d", (p, s, pa) -> bundle("s2"));
    clock.advance(10);
    c.get("grp/repo", "s3", "d", (p, s, pa) -> bundle("s3")); // 3rd write -> evict oldest (s1)

    assertEquals(2, jsonFileCount(dir));

    // s1 was the oldest: it must be gone from disk, so a fresh instance re-loads (throwing loader fails).
    SourceBundleCache fresh = new SourceBundleCache(dir, 100, 2, clock);
    assertThrows(RuntimeException.class,
        () -> fresh.get("grp/repo", "s1", "d",
            (p, s, pa) -> { throw new RuntimeException("evicted from disk - must re-load"); }));

    // s3 (newest) is still on disk: served without invoking the loader.
    SourceBundle got = fresh.get("grp/repo", "s3", "d",
        (p, s, pa) -> { throw new AssertionError("s3 should be a disk hit"); });
    assertEquals("content-s3", got.files().get("model.likec4"));
  }

  @Test
  void bounds_last_good_entries_evicting_the_oldest(@TempDir Path dir) throws Exception {
    // disk bound (3rd arg) also caps the last-good map; vary path to get distinct last-good keys.
    SourceBundleCache c = new SourceBundleCache(dir, 100, 2);
    c.get("grp/repo", "s1", "p1", (p, s, pa) -> bundle("s1"));
    c.get("grp/repo", "s2", "p2", (p, s, pa) -> bundle("s2"));
    c.get("grp/repo", "s3", "p3", (p, s, pa) -> bundle("s3")); // 3rd last-good key > bound 2 -> evict p1
    assertEquals(2, c.lastGoodSize());
    // Assert WHICH last-good key was evicted (the count alone would pass even if the newest were
    // wrongly dropped): a NEW sha for p1 that FAILS has no last-good to serve (p1 evicted) -> rethrows;
    // the same for p3 serves its stale bundle (p3 survived).
    assertThrows(RuntimeException.class,
        () -> c.get("grp/repo", "s1b", "p1", (p, s, pa) -> { throw new RuntimeException("down"); }));
    SourceBundle stale =
        c.get("grp/repo", "s3b", "p3", (p, s, pa) -> { throw new RuntimeException("down"); });
    assertEquals(bundle("s3").files(), stale.files(),
        "the oldest last-good key (p1) was evicted; the newest (p3) survives and serves stale");
  }

  @Test
  void bounds_last_good_entries_by_aggregate_weight_not_only_entry_count(@TempDir Path dir) throws Exception {
    // The last-good (SWR) tier is bounded by aggregate WEIGHT too, not just entry count — each last-good
    // bundle can hold up to the extractor's 32 MiB source cap, so the byte cap is the belt-and-braces
    // bound. Use a high entry cap (so COUNT never bites) and a tiny weight cap (25 chars); each
    // sized(...,9) bundle weighs 10, so the 3rd distinct-path load pushes last-good to 30 > 25 and must
    // evict the oldest KEY by weight. The existing bounds_last_good... test only exercises the COUNT cap,
    // so a regression passing a wrong maxChars to the lastGood LRU would slip through it.
    SourceBundleCache c = new SourceBundleCache(dir, 100, 100, Clock.SYSTEM, 25);
    c.get("grp/repo", "s1", "p1", (p, s, pa) -> sized("s1", 9)); // last-good chars 10
    c.get("grp/repo", "s2", "p2", (p, s, pa) -> sized("s2", 9)); // last-good chars 20
    c.get("grp/repo", "s3", "p3", (p, s, pa) -> sized("s3", 9)); // last-good chars 30 > 25 -> evict p1
    assertEquals(2, c.lastGoodSize(), "the weight cap must bound last-good to 2 despite the 100-entry cap");
    // p1 was evicted by weight: a NEW sha for p1 that FAILS has no last-good to serve -> rethrows.
    assertThrows(RuntimeException.class,
        () -> c.get("grp/repo", "s1b", "p1", (p, s, pa) -> { throw new RuntimeException("down"); }));
    // p3 survived: a failing new sha for p3 serves its stale bundle.
    SourceBundle stale =
        c.get("grp/repo", "s3b", "p3", (p, s, pa) -> { throw new RuntimeException("down"); });
    assertEquals(sized("s3", 9).files(), stale.files(),
        "the oldest last-good key (p1) was evicted by weight; the newest (p3) survives and serves stale");
  }
}
