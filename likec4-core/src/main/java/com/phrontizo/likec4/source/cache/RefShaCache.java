package com.phrontizo.likec4.source.cache;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Caches ref->sha for a short TTL. Full 40-hex shas are immutable and bypass the cache. */
public final class RefShaCache {
  @FunctionalInterface
  public interface ShaLoader {
    String load(String project, String ref) throws Exception;
  }

  private static final Pattern FULL_SHA = Pattern.compile("[0-9a-fA-F]{40}");

  /** Default upper bound on cached (project,ref)->sha entries (memory-DoS guard). */
  public static final int DEFAULT_MAX_ENTRIES = 1000;

  /** Upper bound on the length of either key component (memory-DoS guard, sibling to
   *  {@link #DEFAULT_MAX_ENTRIES} which bounds the entry COUNT). The sanitised production path
   *  ({@code InputValidation}) caps project/ref far tighter; this is defense-in-depth so a misbehaving
   *  direct caller can't pin an unbounded-length cache key. Generous so it never rejects a real ref. */
  public static final int MAX_KEY_COMPONENT_CHARS = 4096;

  /** Upper bound on the length of a loaded sha VALUE before it is cached (memory-DoS guard, sibling to
   *  {@link #MAX_KEY_COMPONENT_CHARS} which bounds the key). The production loader returns a 40-hex
   *  SHA-1 commit id (a 64-hex SHA-256 id is rejected upstream by {@code GitLabSourceClient.HEX_SHA}
   *  before it could reach here); this is generous headroom so it never rejects a real sha, while a
   *  misbehaving direct caller still can't pin an unbounded-length value. */
  public static final int MAX_SHA_CHARS = 256;

  private record Entry(String sha, long expiresAt) {}

  private final Clock clock;
  private final long ttlMillis;
  private final int maxEntries;
  // Access-order LinkedHashMap: even get() reorders entries (a STRUCTURAL mutation), so EVERY access —
  // read or write — must hold synchronized(entries). Never touch this map outside that lock.
  private final Map<String, Entry> entries;
  private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

  public RefShaCache(Clock clock, long ttlMillis) {
    this(clock, ttlMillis, DEFAULT_MAX_ENTRIES);
  }

  public RefShaCache(Clock clock, long ttlMillis, int maxEntries) {
    // A negative TTL is never legitimate: `now + ttlMillis` would land in the past, so every entry is
    // instantly stale and the cache silently no-ops (proactively evicting on each access). Fail fast at
    // construction rather than degrade invisibly — the wrapper clamps its configured TTL to >= 0 before
    // it reaches here (see AdminConfig.getRefTtlMillis), so this only ever catches a programming error.
    if (ttlMillis < 0) {
      throw new IllegalArgumentException("ttlMillis must be >= 0, was " + ttlMillis);
    }
    // A maxEntries <= 0 makes removeEldestEntry (size() > maxEntries) evict on EVERY insert, so the cache
    // silently holds nothing and every resolve re-hits GitLab through the breaker — a silently disabled
    // cache that adds load to the very GitLab it exists to protect. Fail fast, mirroring the TTL guard
    // above and CircuitBreaker's failureThreshold >= 1 guard (defense-in-depth; the wrapper passes a
    // positive constant, so this only ever catches a programming error).
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries must be >= 1, was " + maxEntries);
    }
    this.clock = clock;
    this.ttlMillis = ttlMillis;
    this.maxEntries = maxEntries;
    this.entries = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
        return size() > RefShaCache.this.maxEntries;
      }
    };
  }

  public String get(String project, String ref, ShaLoader loader) throws Exception {
    // Fail fast with a named argument: a null ref would otherwise NPE deep in FULL_SHA.matcher(ref)
    // with no context, and a null project would silently build a cache key whose first segment is the
    // literal string "null" (project and ref are joined by the NUL delimiter below).
    Objects.requireNonNull(project, "project");
    Objects.requireNonNull(ref, "ref");
    // Defense-in-depth memory-DoS guard: reject an unbounded-length component before it becomes a cache
    // key (the production path is already sanitised by InputValidation, far under this bound).
    if (project.length() > MAX_KEY_COMPONENT_CHARS || ref.length() > MAX_KEY_COMPONENT_CHARS) {
      throw new IllegalArgumentException("project/ref exceeds " + MAX_KEY_COMPONENT_CHARS + " chars");
    }
    // Defense-in-depth for the NUL-delimiter anti-aliasing invariant asserted below: the sanitised
    // production path (InputValidation) already rejects a NUL, but a misbehaving DIRECT caller that
    // smuggled one into project/ref would let distinct pairs collide on the same key -- e.g. a NUL in
    // ref ("a" + "b\u0000c") and one in project ("a\u0000b" + "c") both build the key "a\u0000b\u0000c".
    // Reject it here so the invariant holds without relying on upstream sanitisation (sibling to the
    // length guard above).
    if (project.indexOf('\u0000') >= 0 || ref.indexOf('\u0000') >= 0) {
      throw new IllegalArgumentException("project/ref must not contain a NUL");
    }
    // A full 40-hex sha is immutable; bypass the cache but normalize case so ABC… == abc….
    if (FULL_SHA.matcher(ref).matches()) return ref.toLowerCase(Locale.ROOT);
    // NUL delimiter: it can never appear in a sanitized project/ref, so distinct (project,ref) pairs
    // can never alias to the same key (a space would let "a b"/"c" collide with "a"/"b c").
    String key = project + '\u0000' + ref;
    Entry hit = freshEntry(key, clock.nowMillis());
    if (hit != null) return hit.sha();

    // Per-key single-flight: concurrent misses for the same (project,ref) load once. This is
    // BEST-EFFORT, not absolute: the remove-in-finally idiom has a small window where one thread
    // removes the lock just as another adopts a freshly-created one for the same key, so two loads can
    // briefly run in parallel. That is acceptable here — the loader is idempotent and the upstream
    // circuit breaker caps how hard a slow/down GitLab can be hit — but do not assume strict mutual
    // exclusion when reasoning about the loader's side effects.
    Object lock = locks.computeIfAbsent(key, k -> new Object());
    synchronized (lock) {
      try {
        long now = clock.nowMillis();
        Entry e = freshEntry(key, now);
        if (e != null) return e.sha();
        String loaded = loader.load(project, ref);
        if (loaded == null) {
          // A null sha is a loader failure, not a value: never cache it (it would otherwise be served
          // for the whole TTL). Mirror SourceBundleCache's null-result rejection so a misbehaving
          // direct caller fails fast rather than poisoning the cache.
          throw new IllegalStateException("loader returned null sha for " + key);
        }
        if (loaded.length() > MAX_SHA_CHARS) {
          // Defense-in-depth: a non-null but implausibly long value is a misbehaving loader, not a sha.
          // Reject it (mirroring the null-result and key-length guards) rather than pin it for the TTL.
          throw new IllegalStateException(
              "loader returned an over-long (" + loaded.length() + " char) sha for " + key);
        }
        String sha = normalize(loaded);
        // Stamp the TTL from load COMPLETION, not the pre-load `now` captured above: a slow loader (a
        // resolveSha can run up to GitLabSourceClient's 30s request timeout) would otherwise cache the
        // entry as if it had been loaded when the load STARTED, silently shortening its effective
        // lifetime by the whole load duration and making the ref cache churn far more than configured
        // under sustained slow-GitLab load (adding load to the very GitLab that is already struggling).
        // Re-reading the clock here also matches SourceBundleCache.writeDisk, which stamps its entry
        // mtime after the load. (`now` above remains the correct instant for the in-lock pre-load
        // freshness re-check just above — `freshEntry(key, now)` — only the expiry stamp moves to load
        // completion.)
        // Saturating add: a pathologically large ttlMillis (near Long.MAX_VALUE) would overflow
        // `now + ttlMillis` to a NEGATIVE instant, so the entry would be born already-expired and the
        // cache would silently no-op — the exact "silently disabled cache adds load to GitLab" failure the
        // negative-TTL constructor guard exists to prevent, reached instead via overflow. Saturate to
        // Long.MAX_VALUE ("never expires") rather than wrap. The wrapper already clamps its configured TTL
        // (AdminConfig.MAX_REF_TTL_MILLIS); this keeps the core self-defending against a direct caller.
        long expiresAt = saturatingExpiry(clock.nowMillis(), ttlMillis);
        synchronized (entries) {
          entries.put(key, new Entry(sha, expiresAt));
        }
        return sha;
      } catch (InterruptedException ie) {
        // Caller cancellation / shutdown — NOT a load failure. Restore the interrupt flag and propagate
        // rather than dropping it (a loader that blocks and is interrupted may clear the flag before it
        // throws): mirrors SourceBundleCache.get and CircuitBreaker.call, so a caller up the stack that
        // polls Thread.interrupted() to decide whether to abort still sees the interrupt.
        Thread.currentThread().interrupt();
        throw ie;
      } finally {
        locks.remove(key, lock);
      }
    }
  }

  /** {@code now + ttlMillis}, saturating to {@link Long#MAX_VALUE} on overflow instead of wrapping to a
   *  past instant (which would make the entry instantly stale). {@code ttlMillis} is {@code >= 0} (the
   *  constructor rejects a negative TTL); {@code ttlMillis == 0} deliberately yields {@code now}, so the
   *  strict {@code >} freshness check in {@link #freshEntry} treats such an entry as immediately stale —
   *  i.e. a zero TTL disables ref caching, the documented "disable" knob. */
  private static long saturatingExpiry(long now, long ttlMillis) {
    try {
      return Math.addExact(now, ttlMillis);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private Entry freshEntry(String key, long now) {
    synchronized (entries) {
      Entry e = entries.get(key);
      if (e == null) return null;
      if (e.expiresAt() > now) return e;
      // Proactively evict the expired entry instead of leaving it. The get() above already moved it to
      // the MRU end (access-order map); if the ensuing reload fails, leaving it there would protect a
      // stale entry from LRU eviction. A successful reload overwrites it anyway, so this is free.
      entries.remove(key);
      return null;
    }
  }

  /** Current number of cached entries (introspection / tests). */
  public int size() {
    synchronized (entries) {
      return entries.size();
    }
  }

  /** Lowercase a full-sha result so case variants share a single cache slot; pass others through.
   *  {@code sha} is always non-null here — the sole caller rejects a null loader result before this. */
  private static String normalize(String sha) {
    return FULL_SHA.matcher(sha).matches() ? sha.toLowerCase(Locale.ROOT) : sha;
  }
}
