package com.phrontizo.likec4.source.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phrontizo.likec4.source.SourceBundle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Disk-backed, LRU-bounded source-bundle cache with single-flight and stale-while-revalidate.
 *
 * <p><b>SWR scope (known limitation):</b> the last-good map that backs stale-while-revalidate is
 * in-memory and is populated only by an in-process successful load ({@link #store}); a disk hit
 * deliberately does NOT seed it (a disk hit can be for an explicitly-requested OLDER sha, and last-good
 * is keyed by {@code (project,path)} without the sha, so seeding it would regress SWR to an older
 * bundle — see {@link #get}). Consequently SWR only protects an outage that begins AFTER this process
 * has loaded a current bundle for the key: immediately after a restart/redeploy the disk tier is warm
 * but last-good is empty, so the first outage for a not-yet-loaded sha rethrows rather than serving a
 * stale disk bundle. Surviving a restart would need a persisted {@code (project,path) -> latest-sha}
 * pointer; that is a deliberate future enhancement, kept out to preserve the current-bundle fidelity.
 */
public final class SourceBundleCache {
  private static final System.Logger LOG = System.getLogger(SourceBundleCache.class.getName());

  // Field separator for composite cache keys. A NUL is chosen precisely because it can never legitimately
  // appear in a component AND is actively rejected if smuggled in: the sanitised production path
  // (InputValidation) excludes it, and get() explicitly rejects any project/sha/path containing one (see
  // the NUL guard there). With the delimiter thus unforgeable, distinct components can never alias — a
  // space, by contrast, would let "a b","c" collide with "a","b c".
  private static final String SEP = "\u0000";

  @FunctionalInterface
  public interface BundleLoader {
    SourceBundle load(String project, String sha, String path) throws Exception;
  }

  private final Path dir;
  private final int maxEntries;
  private final int maxDiskEntries;
  private final Clock clock;
  // Deserializes disk-tier entries in readDisk(). The disk cache dir can be SHARED, cross-process and even
  // hold foreign/tampered JSON, so this mapper must NEVER enable Jackson polymorphic default typing
  // (activateDefaultTyping / enableDefaultTyping) — that would let a hostile {"@class": …} payload
  // instantiate arbitrary types (a deserialization-gadget RCE). It is off by Jackson's default and the
  // target is a fixed, non-polymorphic record (SourceBundle), so a later addition of default typing here
  // is a visible red flag to reject in review.
  private final ObjectMapper json = new ObjectMapper();
  // Access-order LRU maps bounded by BOTH entry count and aggregate weight (see ByteBoundedLru): even
  // get() reorders entries (a STRUCTURAL mutation), so EVERY access — read or write — must hold the map's
  // own monitor (synchronized(mem) / synchronized(lastGood)). Never touch these maps outside that lock.
  private final ByteBoundedLru mem;
  private final ByteBoundedLru lastGood;
  private final long maxTierChars;
  private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
  // In-process estimate of the on-disk .json count, so writeDisk can SKIP the full directory enumeration
  // on the common under-cap write (the previous code listed the whole dir on EVERY write — an O(dir-size)
  // syscall storm, painful over a network filesystem). Seeded at construction, bumped per new-file write,
  // and reconciled to the true surviving count by pruneDisk. The reconcile is deliberately careful NOT to
  // clobber a new-file write that raced the prune scan (see maybePruneDisk): a blind set-to-truth would
  // drop that write's increment and leave the estimate permanently LOW, which — because the fast path
  // below prunes only when the estimate exceeds the cap — would silently stop enforcing the disk cap. A
  // peer process's writes/deletes over a SHARED dir stay best-effort: they are invisible here until this
  // process's next prune re-seeds from a full listing.
  private final java.util.concurrent.atomic.AtomicLong diskEstimate =
      new java.util.concurrent.atomic.AtomicLong();
  // Bumped on every NEW-file publish (writeDisk). maybePruneDisk snapshots it around a prune scan to
  // detect a write that landed WHILE the scan enumerated the dir — such a write's file may be missing
  // from the scan's listing, so its increment must be preserved rather than clobbered by set-to-truth.
  private final java.util.concurrent.atomic.AtomicLong diskWriteEpoch =
      new java.util.concurrent.atomic.AtomicLong();
  // Serializes prune scans WITHIN this process: without it, two threads that both cross the cap can each
  // enumerate the dir, compute `over` from overlapping listings, and over-evict (deleting a bundle the
  // peer just published). One prune at a time + a re-check under the lock keeps in-process pruning
  // correct; cross-PROCESS racing over a shared dir stays best-effort (see pruneDisk).
  private final Object pruneLock = new Object();

  // Upper bound on a single on-disk entry accepted by readDisk. A serialized bundle is at most the GitLab
  // extractor's per-archive byte cap (32 MiB) plus JSON structure/escaping overhead, so 64 MiB never
  // rejects a legitimate entry — but a foreign or corrupt oversized .json (this cache can be shared
  // cross-process over one dir) is treated as a miss BEFORE readString slurps it whole into the heap,
  // mirroring TokenCipher.MAX_CIPHERTEXT_CHARS. The cache store is outside the archive size caps.
  private static final long MAX_DISK_ENTRY_BYTES = 64L * 1024 * 1024;

  // Default aggregate-weight cap for EACH in-memory tier (mem and lastGood), in source CHARS. Entry-count
  // caps alone don't bound bytes: each bundle can hold up to the extractor's 32 MiB source cap, so 200
  // mem + 500 lastGood entries is a ~22 GiB heap worst case (a String is ~2 bytes/char). This caps each
  // tier to ~512 Mi chars (~1 GiB heap/tier, ~2 GiB total worst case) — far above any realistic small
  // source subtree, so it only bites a pathological flood of large allow-listed repos. Sized with the
  // ~2 bytes/char String overhead in mind.
  private static final long DEFAULT_MAX_TIER_CHARS = 512L * 1024 * 1024;

  // The cached content is LikeC4 source (never the GitLab token), so this is not a secret store — but an
  // operator can point the cache dir at a shared path, and there is no reason for another local user to
  // read allow-listed repos' DSL off disk. On POSIX (the production Confluence/Linux path) create the dir
  // owner-only (0700) and each entry file owner-only (0600), mirroring FileTokenKeyStore's discipline; on
  // non-POSIX filesystems this is a best-effort no-op (the umask default applies, as before).
  private static final Set<PosixFilePermission> OWNER_ONLY_DIR = EnumSet.of(
      PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
  private static final Set<PosixFilePermission> OWNER_ONLY_FILE =
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private boolean posix() {
    return dir.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  public SourceBundleCache(Path dir, int maxEntries) throws IOException {
    // The disk tier (and the last-good/SWR map, which is sized to it — see the 4-arg ctor) is kept
    // intentionally LARGER than the hot in-memory tier: a small `maxEntries` still keeps a long
    // last-good history for stale-while-revalidate. Hence the floor of 500, not `maxEntries`.
    this(dir, maxEntries, Math.max(maxEntries, 500));
  }

  public SourceBundleCache(Path dir, int maxEntries, int maxDiskEntries) throws IOException {
    this(dir, maxEntries, maxDiskEntries, Clock.SYSTEM);
  }

  public SourceBundleCache(Path dir, int maxEntries, int maxDiskEntries, Clock clock) throws IOException {
    this(dir, maxEntries, maxDiskEntries, clock, DEFAULT_MAX_TIER_CHARS);
  }

  /** @param maxTierChars aggregate-weight cap (in source chars) for EACH in-memory tier, on top of the
   *  entry-count caps; {@code <= 0} disables it (entry-count cap only). See {@link #DEFAULT_MAX_TIER_CHARS}. */
  public SourceBundleCache(Path dir, int maxEntries, int maxDiskEntries, Clock clock, long maxTierChars)
      throws IOException {
    // A negative maxEntries makes the hot LRU (size() > maxEntries) evict on every put — it holds nothing.
    // A negative maxDiskEntries is worse: pruneDisk computes over = files.size() - maxDiskEntries
    // (size - (-1) = size + 1), deleting EVERY .json on each write, so the disk tier is silently disabled.
    // Fail fast, mirroring RefShaCache/CircuitBreaker rather than degrading invisibly. (0 is permitted: it
    // deliberately disables a single tier — the 2-arg ctor floors the disk cap at 500 for a 0 hot cap.)
    if (maxEntries < 0 || maxDiskEntries < 0) {
      throw new IllegalArgumentException(
          "maxEntries/maxDiskEntries must be >= 0, was " + maxEntries + "/" + maxDiskEntries);
    }
    // maxTierChars is deliberately NOT range-checked here: unlike the entry-count caps above, every
    // value is well-defined — `<= 0` disables the byte cap (ByteBoundedLru falls back to the count cap)
    // and any positive value is an honest (if small) char budget. There is no "silently crippled"
    // degenerate to guard against, so a fail-fast would only reject legitimate tiny caps.
    this.dir = dir;
    this.maxEntries = maxEntries;
    this.maxDiskEntries = maxDiskEntries;
    this.clock = clock;
    this.maxTierChars = maxTierChars;
    // Create a not-yet-existing cache dir owner-only on POSIX; if it already exists, leave the operator's
    // chosen perms untouched (best-effort, non-secret content — see OWNER_ONLY_DIR).
    if (posix() && !Files.exists(dir)) {
      Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
    } else {
      Files.createDirectories(dir);
    }
    sweepStaleTemps();
    this.diskEstimate.set(countDiskEntries());
    this.mem = new ByteBoundedLru(maxEntries, maxTierChars);
    this.lastGood = new ByteBoundedLru(maxDiskEntries, maxTierChars);
  }

  public SourceBundle get(String project, String sha, String path, BundleLoader loader) throws Exception {
    // Fail fast with a named argument: a null project/sha would otherwise concat into a literal "null"
    // key segment (see key()), silently aliasing every null-component request to one cache slot. path
    // stays nullable — nz() maps it to "" — so it is deliberately NOT required non-null here.
    java.util.Objects.requireNonNull(project, "project");
    java.util.Objects.requireNonNull(sha, "sha");
    // Defense-in-depth for the NUL-delimiter anti-aliasing invariant (see SEP): the sanitised production
    // path (InputValidation) already rejects a NUL, but a misbehaving DIRECT caller that smuggled one in
    // would let distinct triples collide -- e.g. a NUL in sha ("a"+"b\u0000c") and one in project
    // ("a\u0000b"+"c") both build the key "a\u0000b\u0000c". Reject it here so the invariant holds without
    // relying on upstream sanitisation, mirroring RefShaCache.get.
    if (project.indexOf('\u0000') >= 0
        || sha.indexOf('\u0000') >= 0
        || (path != null && path.indexOf('\u0000') >= 0)) {
      throw new IllegalArgumentException("project/sha/path must not contain a NUL");
    }
    String key = key(project, sha, path);
    SourceBundle hit = lookup(key);
    if (hit != null) return hit;
    // Only needed on the miss path (store()/SWR lookups below) — compute it AFTER the fast-path hit
    // return so a cache hit doesn't pay for a string concat it never uses.
    String projectPath = project + SEP + nz(path);
    // Per-key single-flight (BEST-EFFORT, not absolute): the remove-in-finally idiom has a small window
    // where one thread removes the lock as another adopts a freshly-created one for the same key, so two
    // loads can briefly run in parallel. Acceptable here — the loader is idempotent, writeDisk publishes
    // atomically, and the upstream circuit breaker caps GitLab hammering — but don't assume strict
    // mutual exclusion. The post-lock re-check below catches the common case.
    Object lock = locks.computeIfAbsent(key, k -> new Object());
    synchronized (lock) {
      // Re-check BOTH tiers inside the lock. The common case is a peer thread's store() having seeded
      // mem while we waited; but in the single-flight race window a peer can instead publish to DISK and
      // drop its lock before we enter (and, under mem pressure, its mem entry may already be evicted), so
      // a mem-only re-check would miss and we'd redundantly re-fetch from GitLab + re-write the same
      // bundle. Consulting disk here serves the just-published bundle instead.
      SourceBundle reHit = lookup(key);
      if (reHit != null) return reHit;
      try {
        SourceBundle loaded = loader.load(project, sha, path);
        if (loaded == null) {
          // A null bundle is a loader failure, not a result: never store/serialize null (it would
          // poison both caches and write the literal "null" to disk). Route it through SWR/throw.
          throw new IllegalStateException("loader returned null bundle for " + key);
        }
        store(key, projectPath, loaded);
        writeDisk(key, loaded);
        return loaded;
      } catch (InterruptedException ie) {
        // Caller cancellation / shutdown — NOT a GitLab failure. Restore the interrupt flag and
        // propagate rather than masking it with a stale bundle: mirrors CircuitBreaker.call, which
        // deliberately re-throws interrupts instead of counting them as failures.
        Thread.currentThread().interrupt();
        throw ie;
      } catch (Exception ex) {
        SourceBundle stale;
        synchronized (lastGood) {
          stale = lastGood.get(projectPath);
        }
        if (stale != null) return stale; // stale-while-revalidate
        throw ex;
      } finally {
        locks.remove(key, lock);
      }
    }
  }

  /** Check the hot in-memory tier then the disk tier for {@code key}; on a disk hit, promote into mem
   *  (the in-memory tier ONLY — never lastGood: a disk hit can be for an explicitly-requested OLDER sha,
   *  and lastGood is keyed by (project,path) without the sha, so seeding it would regress the
   *  stale-while-revalidate fallback from the current bundle to an older one). Returns null on a miss in
   *  both tiers. Used for both the pre-lock check and the in-lock single-flight re-check. */
  private SourceBundle lookup(String key) {
    synchronized (mem) {
      SourceBundle hit = mem.get(key);
      if (hit != null) return hit;
    }
    SourceBundle disk = readDisk(key);
    if (disk != null) {
      synchronized (mem) {
        mem.put(key, disk);
      }
      return disk;
    }
    return null;
  }

  int memSize() {
    synchronized (mem) {
      return mem.size();
    }
  }

  /** Test seam: the aggregate weight (source chars) currently held by the hot in-memory tier. Lets a test
   *  assert the byte cap actually evicts, and that the running weight stays consistent across evictions. */
  long memChars() {
    synchronized (mem) {
      return mem.chars();
    }
  }

  /** Test seam (package-private, like {@link #memSize()}): whether the hot in-memory tier currently
   *  holds this exact {@code (project,sha,path)} key — lets a test assert WHICH entry the LRU evicted,
   *  not merely the surviving count. */
  boolean memContains(String project, String sha, String path) {
    synchronized (mem) {
      return mem.containsKey(key(project, sha, path));
    }
  }

  int lastGoodSize() {
    synchronized (lastGood) {
      return lastGood.size();
    }
  }

  /** Test seam: the in-process estimate of the on-disk .json count. Lets a test assert the estimate is
   *  reconciled to the true surviving count after prunes (i.e. that it does not drift and disable the cap). */
  long diskEstimate() {
    return diskEstimate.get();
  }

  private void store(String key, String projectPath, SourceBundle b) {
    synchronized (mem) {
      mem.put(key, b);
    }
    // Seed the SWR last-good tier with this freshly-loaded bundle. Unlike a DISK hit (which lookup()
    // deliberately does NOT seed, since it can be for an explicitly-requested OLDER sha — see the class
    // javadoc), a fresh LOAD is, in the production flow, always for the resolved-CURRENT sha: SourceService
    // resolves ref->current-sha before calling this cache, and the browser pins /source to that resolved
    // sha. So last-good tracks the current bundle for the key. The one edge the "current-bundle fidelity"
    // claim does not cover is a DIRECT request that pins a non-current ref/sha (e.g. two macros on the same
    // (project,path) pinning different refs): last-writer-wins would then leave last-good holding the
    // last-loaded ref's bundle, which SWR could serve during a later outage. That is an accepted,
    // very-low-severity fidelity edge (valid-but-older content, self-correcting on the next successful
    // load), symmetric with the disk-hit guard and not worth per-ref last-good keying.
    synchronized (lastGood) {
      lastGood.put(projectPath, b);
    }
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  private String key(String project, String sha, String path) {
    return project + SEP + sha + SEP + nz(path);
  }

  private Path fileFor(String key) {
    return dir.resolve(sha256Hex(key) + ".json");
  }

  private SourceBundle readDisk(String key) {
    Path f = fileFor(key);
    if (!Files.exists(f)) return null;
    try {
      if (Files.size(f) > MAX_DISK_ENTRY_BYTES) {
        // An oversized entry (foreign/corrupt — never something this class writes) is a miss + reload,
        // not an unbounded read: bail BEFORE readString so a pathological file cannot OOM a render.
        LOG.log(System.Logger.Level.WARNING, "discarding oversized source-bundle disk entry: " + f);
        return null;
      }
      return json.readValue(Files.readString(f, StandardCharsets.UTF_8), SourceBundle.class);
    } catch (IOException e) {
      // Corrupt/unreadable entry -> treat as a miss. (Jackson reports malformed JSON AND a creator
      // failure — e.g. a {"files":null} payload tripping SourceBundle's Map.copyOf — as IOException
      // subclasses, so both land here.)
      return null;
    } catch (RuntimeException e) {
      // Defence in depth for the disk tier's "never throw out of get()" contract: should Jackson (or
      // a future model change) ever surface a deserialization problem as an unchecked exception, a
      // corrupt disk entry must STILL be a miss + reload, never 500 a render.
      LOG.log(System.Logger.Level.WARNING, "discarding unreadable source-bundle disk entry: " + f, e);
      return null;
    }
  }

  /** Best-effort write of the disk tier. A failed disk write must NOT propagate into {@code get}'s
   *  stale-while-revalidate path (that path is for loader/GitLab failures only) — masking it would
   *  silently disable the disk tier <em>and</em> spuriously serve stale. The in-memory result still
   *  returns; the disk copy is simply skipped, consistent with {@link #pruneDisk}'s best-effort
   *  stance. */
  private void writeDisk(String key, SourceBundle b) {
    Path f = fileFor(key);
    // Whether this publish adds a NEW disk entry (vs overwriting this key's existing one) — a single
    // stat(), far cheaper than the directory enumeration it lets us skip below. Computed before the move.
    boolean isNew = !Files.exists(f);
    // Write to a sibling temp then atomically rename, so a concurrent reader (or another process over
    // the same cache dir) never observes a half-written JSON — readDisk would otherwise parse a
    // truncated file. The temp name ends ".json.tmp" so pruneDisk (which matches ".json") ignores it.
    Path tmp = null;
    try {
      // Create the temp entry owner-only on POSIX; the atomic move to `f` preserves its perms, so the
      // published .json is 0600 (non-POSIX: umask default, as before). See OWNER_ONLY_FILE.
      tmp = posix()
          ? Files.createTempFile(dir, ".src-", ".json.tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE))
          : Files.createTempFile(dir, ".src-", ".json.tmp");
      // Stream the JSON straight to the temp file rather than json.writeValueAsString(b): a bundle can
      // hold up to the extractor's 32 MiB cap, and writeValueAsString would buffer the whole serialized
      // form as one String AND then as a UTF-8 byte[] (~3x the bundle size transiently). writeValue to
      // the stream emits UTF-8 directly (matching readDisk's UTF-8 read) with no full-document buffer.
      try (java.io.OutputStream os = Files.newOutputStream(tmp)) {
        json.writeValue(os, b);
      }
      try {
        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException atomicUnsupported) {
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
      }
      tmp = null; // published
      // The mtime stamp is a best-effort LRU nicety applied AFTER the move has already published the
      // entry, so it must NOT abort the post-publish accounting below. If it were left in the outer try,
      // a setLastModifiedTime failure (e.g. a mount that rejects the timestamp update, or a concurrent
      // relocation of f) would jump to the catch and return BEFORE the isNew increment + maybePruneDisk —
      // leaving the file on disk yet uncounted in diskEstimate and unpruned, drifting the estimate low
      // and silently under-enforcing the disk cap. Swallow a stamp failure (mtime only orders LRU
      // eviction) and let the accounting run.
      try {
        Files.setLastModifiedTime(f, FileTime.fromMillis(clock.nowMillis()));
      } catch (IOException stampFailed) {
        LOG.log(System.Logger.Level.WARNING,
            "source-bundle disk entry published but its LRU timestamp could not be set: " + f, stampFailed);
      }
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "source-bundle disk write failed (skipping disk tier): " + f, e);
      return; // best-effort; do not surface as a loader failure
    } finally {
      if (tmp != null) {
        try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best-effort temp cleanup */ }
      }
    }
    if (isNew) {
      // Bump the epoch BEFORE the estimate. A concurrent prune that reads the OLD epoch (→ set-to-truth
      // path) must be guaranteed the estimate increment has NOT yet landed, so its set() cannot clobber
      // it low; the AtomicLong happens-before ordering gives exactly that when the epoch write precedes
      // the estimate write. (The reverse order leaves a window where the increment is visible but the
      // epoch bump is not, which is the low-drift clobber this reconcile is designed to prevent.)
      diskWriteEpoch.incrementAndGet();
      diskEstimate.incrementAndGet();
    }
    maybePruneDisk();
  }

  /** Prune only when the in-process estimate says we MIGHT be over the cap, avoiding the directory
   *  enumeration on the common under-cap write. The scan resets the estimate to the true surviving
   *  count, so the cap is still enforced PROMPTLY the moment a write pushes the count over it. */
  private void maybePruneDisk() {
    if (diskEstimate.get() <= maxDiskEntries) return; // fast path: no lock on the common under-cap write
    synchronized (pruneLock) {
      // Re-check under the lock: a peer thread may have just pruned and reset the estimate, so we neither
      // enumerate the dir a second time nor over-evict past the cap.
      if (diskEstimate.get() <= maxDiskEntries) return;
      long epochBefore = diskWriteEpoch.get();
      PruneOutcome outcome = pruneDisk();
      // Reconcile the estimate WITHOUT clobbering a new-file write that raced the scan. If no new file was
      // published while pruneDisk enumerated (the common case), its survivor count is authoritative and
      // set() also corrects any accumulated drift (overwrites, a peer over a shared dir). If a write DID
      // race the scan, its file may be absent from pruneDisk's listing, so only SUBTRACT the deletions —
      // a blind set() would drop that write's increment and leave the estimate permanently low, silently
      // disabling the cap. Any residue is reconciled by the next quiescent prune.
      if (diskWriteEpoch.get() == epochBefore) {
        diskEstimate.set(outcome.survivors());
      } else {
        diskEstimate.addAndGet(-outcome.deleted());
      }
    }
  }

  private record PruneOutcome(long survivors, long deleted) {}

  /** Caps the on-disk tier: while it holds more than {@code maxDiskEntries} bundles, delete the
   *  oldest by last-modified time (LRU by write/refresh order). Best-effort; failures are ignored.
   *  Returns the surviving {@code .json} count (used to refresh {@link #diskEstimate}).
   *
   *  <p>Within this process, prunes are serialized by {@link #maybePruneDisk}'s {@code pruneLock} (with
   *  a re-check under the lock), so two threads cannot both enumerate + over-evict. NOT multi-writer-safe
   *  ACROSS processes sharing one dir: two processes' prunes can still each compute {@code over} from the
   *  same listing and over-evict, or delete a bundle another process just published. That is acceptable —
   *  it only costs an extra reload (the next fetch rewrites the entry) — the cross-process safety this
   *  class offers is for <em>reads</em>, not pruning. */
  private PruneOutcome pruneDisk() {
    try (Stream<Path> stream = Files.list(dir)) {
      List<Path> files = stream
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .collect(Collectors.toCollection(ArrayList::new));
      int over = files.size() - maxDiskEntries;
      if (over <= 0) return new PruneOutcome(files.size(), 0);
      // Oldest first; tie-break on filename so entries sharing a millisecond mtime (a fast write burst,
      // or a coarse-granularity filesystem) are evicted in a deterministic, reproducible order.
      files.sort(Comparator.comparingLong(SourceBundleCache::lastModifiedMillis)
          .thenComparing(p -> p.getFileName().toString()));
      int deleted = 0;
      for (int i = 0; i < over; i++) {
        if (Files.deleteIfExists(files.get(i))) deleted++;
      }
      return new PruneOutcome((long) files.size() - deleted, deleted);
    } catch (IOException e) {
      // best-effort prune; leaving extra files is non-fatal. Keep the prior estimate (no deletions).
      return new PruneOutcome(diskEstimate.get(), 0);
    }
  }

  /** Best-effort reclamation of orphaned write temps, run once at construction. {@link #writeDisk}
   *  publishes via a {@code .src-*.json.tmp} temp + atomic rename and deletes the temp in its
   *  {@code finally}; but a {@code kill -9} (or JVM crash) BETWEEN the temp's creation and that
   *  {@code finally} leaves it behind, and neither {@link #pruneDisk} nor {@link #countDiskEntries}
   *  ever touches it (both match {@code .json}, which {@code .json.tmp} is not). Across repeated crashes
   *  these would accumulate unbounded in the cache dir, so sweep them at startup. Cross-process caveat:
   *  a peer process mid-write over a SHARED dir could have its in-flight temp removed here, costing it
   *  one redundant reload — consistent with this tier's documented best-effort cross-process stance
   *  (see {@link #pruneDisk}). */
  private void sweepStaleTemps() {
    try (Stream<Path> stream = Files.list(dir)) {
      stream
          .filter(p -> {
            String n = p.getFileName().toString();
            return n.startsWith(".src-") && n.endsWith(".json.tmp");
          })
          .forEach(p -> {
            try {
              Files.deleteIfExists(p);
            } catch (IOException ignored) {
              /* best-effort: an unremovable temp is non-fatal (it never counts toward the cap) */
            }
          });
    } catch (IOException ignored) {
      // best-effort; a listing failure just leaves any temps in place (they never count toward the cap)
    }
  }

  /** One-time count of existing {@code .json} entries to seed {@link #diskEstimate} at construction. */
  private long countDiskEntries() throws IOException {
    try (Stream<Path> stream = Files.list(dir)) {
      return stream.filter(p -> p.getFileName().toString().endsWith(".json")).count();
    }
  }

  private static long lastModifiedMillis(Path p) {
    try {
      return Files.getLastModifiedTime(p).toMillis();
    } catch (IOException e) {
      return Long.MAX_VALUE; // unknown mtime -> treat as newest so it is not evicted first
    }
  }

  /**
   * An access-order LRU bounded by BOTH an entry count AND an aggregate weight (source chars). The weight
   * cap is the belt-and-braces bound the entry-count cap can't give: each bundle holds up to the
   * extractor's 32 MiB source cap, so a flood of large allow-listed repos could otherwise pin many GiB.
   * Weight is the sum of each file's path+content char lengths (a String costs ~2 bytes/char on the heap,
   * so the real footprint is ~2x this). All mutation runs under the owning cache's monitor
   * (synchronized(mem)/synchronized(lastGood)); this class adds no locking of its own.
   */
  private static final class ByteBoundedLru extends LinkedHashMap<String, SourceBundle> {
    private final int maxEntries;
    private final long maxChars; // <= 0 disables the weight cap (entry-count cap only)
    private long chars = 0;

    ByteBoundedLru(int maxEntries, long maxChars) {
      super(16, 0.75f, true);
      this.maxEntries = maxEntries;
      this.maxChars = maxChars;
    }

    long chars() {
      return chars;
    }

    @Override
    public SourceBundle put(String k, SourceBundle v) {
      // super.put may fire removeEldestEntry (the COUNT cap) before returning; that override keeps `chars`
      // consistent for the one entry it evicts. `chars` is not yet updated for v at that point, but the
      // subtraction of an already-counted eldest is valid regardless — we reconcile v below.
      SourceBundle prev = super.put(k, v);
      if (prev != null) chars -= weight(prev); // overwrite: drop the replaced value's weight
      chars += weight(v);
      // Enforce the weight cap by evicting the eldest (LRU) entries until under it. removeEldestEntry
      // evicts at most ONE per put (the count cap), which cannot bound bytes on its own — so loop here.
      // In access-order the just-inserted entry is the NEWEST (tail); head-first eviction reaches it only
      // when it is the sole entry, which size() > 1 forbids — so we never evict the entry we must return.
      if (maxChars > 0 && chars > maxChars) {
        var it = entrySet().iterator();
        while (chars > maxChars && size() > 1 && it.hasNext()) {
          SourceBundle evicted = it.next().getValue();
          it.remove();
          chars -= weight(evicted);
        }
      }
      return prev;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, SourceBundle> eldest) {
      if (size() > maxEntries) {
        chars -= weight(eldest.getValue()); // LinkedHashMap removes it immediately after we return true
        return true;
      }
      return false;
    }

    private static long weight(SourceBundle b) {
      long w = 0;
      for (Map.Entry<String, String> e : b.files().entrySet()) {
        w += (long) e.getKey().length() + e.getValue().length();
      }
      return w;
    }
  }

  private static String sha256Hex(String s) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(h.length * 2);
      for (byte x : h) {
        sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
