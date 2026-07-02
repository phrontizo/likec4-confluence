package com.phrontizo.likec4.source.cache;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Test clock with controllable time. {@code now} is an {@link AtomicLong} so that {@link #advance} is a
 * lock-free atomic read-modify-write (a plain {@code now += ms} on a {@code volatile} is NOT atomic —
 * two threads advancing concurrently could lose an update and make time non-monotonic). The value
 * written by one thread (e.g. a test driver advancing the clock) is visible to a breaker/cache call
 * running on another — the concurrency tests read it across threads.
 */
public final class ManualClock implements Clock {
  private final AtomicLong now;
  public ManualClock(long start) { this.now = new AtomicLong(start); }
  public void advance(long ms) { now.addAndGet(ms); }
  @Override public long nowMillis() { return now.get(); }
}
