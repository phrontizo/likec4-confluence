package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.phrontizo.likec4.source.cache.ManualClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {
  private static final CircuitBreaker.ThrowingSupplier<String> BOOM =
      () -> { throw new RuntimeException("boom"); };

  @Test
  void opens_after_threshold_consecutive_failures_and_fails_fast_without_calling_supplier() {
    CircuitBreaker b = new CircuitBreaker(new ManualClock(0), 3, 1000);
    for (int i = 0; i < 3; i++) {
      assertThrows(RuntimeException.class, () -> b.call(BOOM));
    }
    // OPEN now: the supplier must NOT run and a CircuitOpenException is thrown.
    AtomicInteger calls = new AtomicInteger();
    assertThrows(CircuitBreaker.CircuitOpenException.class,
        () -> b.call(() -> { calls.incrementAndGet(); return "x"; }));
    assertEquals(0, calls.get());
  }

  @Test
  void a_zero_open_window_degrades_to_retry_immediately() throws Exception {
    // The constructor explicitly permits openMillis == 0 (>= 0). With no cooldown, tripping OPEN must not
    // wedge the breaker: the very next call — even at the same clock instant — is admitted as the single
    // half-open trial (0 - openedAt >= 0) rather than fast-failing, so a zero window degrades to "retry on
    // the next call". A success then closes the breaker.
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 2, 0);
    assertThrows(RuntimeException.class, () -> b.call(BOOM));
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN at t=0
    AtomicInteger calls = new AtomicInteger();
    assertEquals("ok", b.call(() -> { calls.incrementAndGet(); return "ok"; }));
    assertEquals(1, calls.get(), "the trial supplier must actually run (not fast-fail) with a zero window");
    assertEquals("again", b.call(() -> "again")); // trial success closed the breaker
  }

  @Test
  void a_success_resets_the_consecutive_failure_count() throws Exception {
    CircuitBreaker b = new CircuitBreaker(new ManualClock(0), 3, 1000);
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // 1
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // 2
    assertEquals("ok", b.call(() -> "ok"));                  // resets to 0
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // 1
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // 2 - still CLOSED
    AtomicInteger calls = new AtomicInteger();
    assertEquals("still-closed", b.call(() -> { calls.incrementAndGet(); return "still-closed"; }));
    assertEquals(1, calls.get());
  }

  @Test
  void half_open_trial_after_openMillis_closes_on_success() throws Exception {
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 2, 1000);
    assertThrows(RuntimeException.class, () -> b.call(BOOM));
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN at t=0
    // before openMillis elapses: still fail-fast.
    clock.advance(999);
    assertThrows(CircuitBreaker.CircuitOpenException.class, () -> b.call(() -> "x"));
    // at/after openMillis: one trial runs; success -> CLOSED.
    clock.advance(1);
    AtomicInteger calls = new AtomicInteger();
    assertEquals("ok", b.call(() -> { calls.incrementAndGet(); return "ok"; }));
    assertEquals(1, calls.get());
    // CLOSED again: normal calls flow through.
    assertEquals("ok2", b.call(() -> "ok2"));
  }

  @Test
  void an_interrupt_does_not_count_as_a_failure_and_keeps_the_breaker_closed() throws Exception {
    // A thread interrupt is a caller-side cancellation/shutdown, NOT a GitLab failure — it must not
    // trip the breaker (else a JVM shutdown or cancelled request would falsely mark GitLab as down).
    CircuitBreaker b = new CircuitBreaker(new ManualClock(0), 2, 1000);
    CircuitBreaker.ThrowingSupplier<String> interrupt =
        () -> { throw new InterruptedException("cancelled"); };
    assertThrows(InterruptedException.class, () -> b.call(interrupt));
    assertThrows(InterruptedException.class, () -> b.call(interrupt));
    assertThrows(InterruptedException.class, () -> b.call(interrupt)); // 3 interrupts > threshold(2)
    // Still CLOSED: a real call runs rather than fail-fast with CircuitOpenException.
    AtomicInteger calls = new AtomicInteger();
    assertEquals("ok", b.call(() -> { calls.incrementAndGet(); return "ok"; }));
    assertEquals(1, calls.get());
  }

  @Test
  void an_interrupt_during_the_half_open_trial_frees_the_trial_slot_without_reopening() throws Exception {
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 2, 1000);
    assertThrows(RuntimeException.class, () -> b.call(BOOM));
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN at t=0
    clock.advance(1000); // half-open eligible
    assertThrows(InterruptedException.class,
        () -> b.call(() -> { throw new InterruptedException("cancelled mid-trial"); }));
    // The interrupted trial neither closed nor re-opened the breaker; a fresh trial may run.
    AtomicInteger trial = new AtomicInteger();
    assertEquals("recovered", b.call(() -> { trial.incrementAndGet(); return "recovered"; }));
    assertEquals(1, trial.get());
  }

  @Test
  void a_hung_half_open_trial_is_reclaimed_after_openMillis_so_the_breaker_cannot_wedge_forever()
      throws Exception {
    // Safety valve: if the single half-open trial's supplier never returns (hangs), the breaker must
    // not reject every subsequent call forever. After openMillis the abandoned trial is superseded.
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 2, 1000);
    assertThrows(RuntimeException.class, () -> b.call(BOOM));
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN at t=0
    clock.advance(1000); // half-open eligible

    CountDownLatch inTrial = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread hung = new Thread(() -> {
      try {
        b.call(() -> { inTrial.countDown(); release.await(5, TimeUnit.SECONDS); return "late"; });
      } catch (Exception ignored) {
        // the test releases the supplier at the end; any outcome here is irrelevant
      }
    });
    hung.start();
    assertTrue(inTrial.await(2, TimeUnit.SECONDS), "the half-open trial should be in flight");

    // While the trial is still within its deadline, the rest are still rejected (single-trial holds).
    assertThrows(CircuitBreaker.CircuitOpenException.class, () -> b.call(() -> "x"));

    // Once openMillis elapses with the trial still hung, a fresh trial is permitted and can recover.
    clock.advance(1000);
    AtomicInteger trial = new AtomicInteger();
    assertEquals("recovered", b.call(() -> { trial.incrementAndGet(); return "recovered"; }));
    assertEquals(1, trial.get());

    release.countDown();
    hung.join(2000);
  }

  @Test
  void a_late_failure_while_already_open_does_not_push_back_half_open_eligibility() throws Exception {
    // A call that acquired its permit while CLOSED, then fails AFTER another failure already tripped the
    // breaker OPEN, must NOT re-stamp openedAt — that would delay the OPEN->HALF_OPEN trial by up to one
    // in-flight call's duration. Drive the interleaving deterministically with latches.
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 1, 1000); // threshold 1: a single failure opens it

    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread slow = new Thread(() -> {
      try {
        b.call(() -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); throw new RuntimeException("late"); });
      } catch (Exception ignored) {
        // expected: this call fails (and, importantly, fails while the breaker is already OPEN)
      }
    });
    slow.start();
    assertTrue(entered.await(2, TimeUnit.SECONDS), "the slow call should hold a CLOSED-era permit");

    // Trip the breaker OPEN at t=0 with a separate failure while the slow call is still in flight.
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN, openedAt=0

    clock.advance(1000);  // openMillis elapses while the slow call is still running
    release.countDown();  // the slow call now fails -> onFailure runs while state is already OPEN
    slow.join(2000);

    // openedAt must still be 0, so a trial is allowed now (t=1000 >= openMillis). Had the late failure
    // re-stamped openedAt to 1000, this would fail-fast with CircuitOpenException instead.
    AtomicInteger trial = new AtomicInteger();
    assertEquals("recovered", b.call(() -> { trial.incrementAndGet(); return "recovered"; }));
    assertEquals(1, trial.get());
  }

  @Test
  void a_closed_era_straggler_failure_during_a_live_half_open_trial_does_not_delay_recovery()
      throws Exception {
    // A call that acquired its permit while CLOSED, then fails AFTER the breaker has since tripped OPEN,
    // cooled down, and started a FRESH half-open trial, must NOT re-OPEN the breaker or re-stamp openedAt.
    // Doing so pushes the next trial's eligibility forward by up to one straggler's duration, delaying
    // recovery. This is the failure mirror of a_late_success_while_already_open (a CLOSED-era straggler
    // must only affect state while STILL CLOSED). Drive the interleaving deterministically with latches.
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 1, 1000); // threshold 1: a single failure opens it

    // A: hold a CLOSED-era permit (myTrial == 0), blocked inside the supplier; it will FAIL when released.
    CountDownLatch aEntered = new CountDownLatch(1);
    CountDownLatch aRelease = new CountDownLatch(1);
    Thread a = new Thread(() -> {
      try {
        b.call(() -> { aEntered.countDown(); aRelease.await(5, TimeUnit.SECONDS); throw new RuntimeException("straggler"); });
      } catch (Exception ignored) { /* its effect is asserted via the breaker state */ }
    });
    a.start();
    assertTrue(aEntered.await(2, TimeUnit.SECONDS), "A should hold a CLOSED-era permit");

    // Trip OPEN at t=0 with a separate failure while A is still in flight.
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN, openedAt=0

    // C: after openMillis, start the single half-open trial and block inside it (trialInFlight=true).
    clock.advance(1000);
    CountDownLatch cEntered = new CountDownLatch(1);
    CountDownLatch cRelease = new CountDownLatch(1);
    Thread c = new Thread(() -> {
      try {
        b.call(() -> { cEntered.countDown(); cRelease.await(5, TimeUnit.SECONDS); return "c"; });
      } catch (Exception ignored) { /* released at the end */ }
    });
    c.start();
    assertTrue(cEntered.await(2, TimeUnit.SECONDS), "C should be the in-flight half-open trial (trialStartedAt=1000)");

    // Release A at t=1500 (LATER than C's trial start): its stale CLOSED-era failure runs while the
    // breaker is HALF_OPEN with C's fresh trial in flight. On the buggy code this re-OPENs and re-stamps
    // openedAt to 1500, pushing the next trial's eligibility out to 2500 instead of C's 2000.
    clock.advance(500); // t=1500
    aRelease.countDown();
    a.join(2000);

    // At t=2000 the (hung) trial C started is superseded (2000 - 1000 >= openMillis), so a fresh trial is
    // eligible and must run. On the buggy code the straggler's re-stamped openedAt=1500 fast-fails this
    // until t=2500.
    clock.advance(500); // t=2000
    AtomicInteger recovered = new AtomicInteger();
    assertEquals("recovered", b.call(() -> { recovered.incrementAndGet(); return "recovered"; }),
        "a CLOSED-era straggler failure must not delay the half-open trial's reclaim");
    assertEquals(1, recovered.get());

    cRelease.countDown();
    c.join(2000);
  }

  @Test
  void a_late_success_while_already_open_does_not_close_the_breaker() throws Exception {
    // A call that acquired its permit while CLOSED, then SUCCEEDS AFTER another failure already tripped the
    // breaker OPEN, must NOT reset the breaker to CLOSED — a straggler success that began before the trip
    // says nothing about GitLab's health after it, and must not un-trip a breaker the threshold just opened
    // (the mirror image of the late-failure case). Drive the interleaving deterministically with latches.
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 1, 1000); // threshold 1: a single failure opens it

    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread slow = new Thread(() -> {
      try {
        b.call(() -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return "late-ok"; });
      } catch (Exception ignored) {
        // its success is asserted via the breaker state, not its return value
      }
    });
    slow.start();
    assertTrue(entered.await(2, TimeUnit.SECONDS), "the slow call should hold a CLOSED-era permit");

    // Trip the breaker OPEN at t=0 with a separate failure while the slow call is still in flight.
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN, openedAt=0

    release.countDown(); // the slow CLOSED-era call now SUCCEEDS -> onSuccess(myTrial=0) runs while OPEN
    slow.join(2000);

    // The breaker must still be OPEN (openMillis has not elapsed): a fresh call fails fast WITHOUT invoking
    // the supplier. On the buggy code the straggler success reset state to CLOSED and this call would run.
    AtomicInteger calls = new AtomicInteger();
    assertThrows(CircuitBreaker.CircuitOpenException.class,
        () -> b.call(() -> { calls.incrementAndGet(); return "x"; }));
    assertEquals(0, calls.get(), "a CLOSED-era straggler success must not un-trip a freshly-OPENed breaker");
  }

  @Test
  void an_interrupted_non_trial_call_does_not_free_another_threads_half_open_trial_slot() throws Exception {
    // A call that acquired its permit while CLOSED, then is INTERRUPTED after another thread started the
    // single half-open trial, must NOT clear the trial slot — otherwise a SECOND probe could start
    // against a known-degraded GitLab, breaking the single-trial invariant. Drive it with latches.
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 1, 1000); // threshold 1: a single failure opens it

    // A: hold a CLOSED-era permit, blocked inside the supplier (state still CLOSED, owns no trial).
    CountDownLatch aEntered = new CountDownLatch(1);
    CountDownLatch aRelease = new CountDownLatch(1);
    Thread a = new Thread(() -> {
      try {
        b.call(() -> { aEntered.countDown(); aRelease.await(5, TimeUnit.SECONDS); return "a"; });
      } catch (Exception ignored) { /* interrupted below */ }
    });
    a.start();
    assertTrue(aEntered.await(2, TimeUnit.SECONDS), "A should hold a CLOSED-era permit");

    // Trip OPEN at t=0 with a separate failure while A is still in flight.
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN, openedAt=0

    // C: after openMillis, start the single half-open trial and block inside it (trialInFlight=true).
    clock.advance(1000);
    CountDownLatch cEntered = new CountDownLatch(1);
    CountDownLatch cRelease = new CountDownLatch(1);
    Thread c = new Thread(() -> {
      try {
        b.call(() -> { cEntered.countDown(); cRelease.await(5, TimeUnit.SECONDS); return "c"; });
      } catch (Exception ignored) { /* released at the end */ }
    });
    c.start();
    assertTrue(cEntered.await(2, TimeUnit.SECONDS), "C should be the in-flight half-open trial");

    // Interrupt A: its await() throws InterruptedException -> call()'s releasePermit(myTrial=0) runs.
    a.interrupt();
    a.join(2000);

    // C's trial slot must STILL be held (within openMillis), so a fresh call is rejected. On the buggy
    // code A's releasePermit cleared trialInFlight, letting this call start a SECOND concurrent trial.
    AtomicInteger second = new AtomicInteger();
    assertThrows(CircuitBreaker.CircuitOpenException.class,
        () -> b.call(() -> { second.incrementAndGet(); return "x"; }));
    assertEquals(0, second.get(), "no second concurrent trial may run while C's trial is in flight");

    cRelease.countDown();
    c.join(2000);
  }

  @Test
  void is_thread_safe_under_concurrent_failures_and_converges_to_open() throws Exception {
    // The state machine is guarded by `synchronized`; verify it stays consistent under contention and
    // converges to OPEN. (The supplier runs OUTSIDE the lock, so more than `threshold` calls can run
    // before the breaker trips — the invariant is convergence + no corrupted state, not an exact count.)
    final int threshold = 5;
    final int threads = 8;
    final int perThread = 25;
    CircuitBreaker b = new CircuitBreaker(new ManualClock(0), threshold, 60_000); // clock fixed -> stays OPEN
    AtomicInteger supplierRuns = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int t = 0; t < threads; t++) {
        pool.submit(() -> {
          try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
          for (int i = 0; i < perThread; i++) {
            try {
              b.call(() -> { supplierRuns.incrementAndGet(); throw new RuntimeException("down"); });
            } catch (RuntimeException expected) {
              // CircuitOpenException (fast-rejected) OR the supplier's failure — both are fine.
            } catch (Throwable other) {
              unexpected.incrementAndGet(); // any checked/other exception means a race corrupted state
            }
          }
        });
      }
      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "all workers must finish");
    } finally {
      pool.shutdownNow();
    }
    assertEquals(0, unexpected.get(), "no unexpected exception / corrupted state under contention");
    assertTrue(supplierRuns.get() >= threshold, "the breaker must have seen at least the threshold failures");
    assertTrue(supplierRuns.get() <= threads * perThread, "the supplier cannot run more than the calls made");
    // Converged to OPEN: a fresh call fails fast WITHOUT invoking the supplier.
    AtomicInteger after = new AtomicInteger();
    assertThrows(CircuitBreaker.CircuitOpenException.class,
        () -> b.call(() -> { after.incrementAndGet(); return "x"; }));
    assertEquals(0, after.get());
  }

  @Test
  void a_superseded_trials_late_failure_does_not_reopen_a_recovered_breaker() throws Exception {
    // A half-open trial that overran openMillis is ABANDONED and superseded by a fresh trial. If that
    // stale trial later FAILS, its outcome must not mutate breaker state — in particular it must not
    // re-OPEN (or pollute the consecutive-failure count of) a breaker a NEWER trial has since CLOSED.
    // Drive the interleaving deterministically with latches.
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 1, 1000); // threshold 1: a single failure opens it

    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN at t=0
    clock.advance(1000); // half-open eligible

    // C1: take the single half-open trial and block inside it so it overruns openMillis (abandoned).
    CountDownLatch c1In = new CountDownLatch(1);
    CountDownLatch c1Fail = new CountDownLatch(1);
    Thread c1 = new Thread(() -> {
      try {
        b.call(() -> { c1In.countDown(); c1Fail.await(5, TimeUnit.SECONDS); throw new RuntimeException("stale"); });
      } catch (Exception ignored) { /* abandoned trial; its effect is asserted via the breaker state */ }
    });
    c1.start();
    assertTrue(c1In.await(2, TimeUnit.SECONDS), "C1 should hold the in-flight half-open trial");

    // Abandon C1 (it overran openMillis) and run a FRESH trial that SUCCEEDS -> the breaker CLOSES.
    clock.advance(1000); // now - trialStartedAt = 1000 >= openMillis: C1's trial is superseded
    assertEquals("recovered", b.call(() -> "recovered")); // fresh trial succeeds -> CLOSED

    // Release C1: its stale trial now fails. On the buggy code this re-OPENs the just-recovered breaker.
    c1Fail.countDown();
    c1.join(2000);

    // The breaker must still be CLOSED: a normal call runs rather than fail-fast with CircuitOpenException.
    AtomicInteger calls = new AtomicInteger();
    assertEquals("ok", b.call(() -> { calls.incrementAndGet(); return "ok"; }));
    assertEquals(1, calls.get());
  }

  @Test
  void a_superseded_trials_late_success_does_not_reclose_a_reopened_breaker() throws Exception {
    // Symmetric to the superseded-late-FAILURE case above: a half-open trial that overran openMillis is
    // ABANDONED and superseded. If that stale trial later SUCCEEDS, its outcome must be ignored (the
    // onSuccess `myTrial == trialId` guard) — it must not re-CLOSE a breaker that a NEWER trial has since
    // (correctly) re-OPENed. Drive the interleaving deterministically with latches.
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 1, 1000); // threshold 1: a single failure opens it

    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN at t=0
    clock.advance(1000); // half-open eligible

    // C1: take the single half-open trial and block inside it so it overruns openMillis (abandoned),
    // then eventually SUCCEED.
    CountDownLatch c1In = new CountDownLatch(1);
    CountDownLatch c1Succeed = new CountDownLatch(1);
    Thread c1 = new Thread(() -> {
      try {
        b.call(() -> { c1In.countDown(); c1Succeed.await(5, TimeUnit.SECONDS); return "stale-ok"; });
      } catch (Exception ignored) { /* abandoned trial; its effect is asserted via the breaker state */ }
    });
    c1.start();
    assertTrue(c1In.await(2, TimeUnit.SECONDS), "C1 should hold the in-flight half-open trial");

    // Abandon C1 (it overran openMillis) and run a FRESH trial that FAILS -> the breaker RE-OPENs.
    clock.advance(1000); // now - trialStartedAt = 1000 >= openMillis: C1's trial is superseded
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // fresh trial fails -> OPEN again

    // Release C1: its stale trial now SUCCEEDS. On buggy code this re-CLOSEs the just-reopened breaker.
    c1Succeed.countDown();
    c1.join(2000);

    // The breaker must still be OPEN: a call fast-fails with CircuitOpenException (supplier not invoked).
    AtomicInteger calls = new AtomicInteger();
    assertThrows(CircuitBreaker.CircuitOpenException.class,
        () -> b.call(() -> { calls.incrementAndGet(); return "should-not-run"; }));
    assertEquals(0, calls.get());
  }

  @Test
  void a_non_counting_failure_does_not_trip_the_breaker() throws Exception {
    // A countsAsFailure classifier can mark certain exceptions as "not an outage" (e.g. a GitLab 4xx
    // client error). Such an exception must propagate but NOT count toward the threshold, so a run of
    // them — well beyond failureThreshold — never trips the breaker: a subsequent healthy call still
    // runs rather than fast-failing with CircuitOpenException.
    CircuitBreaker b = new CircuitBreaker(new ManualClock(0), 2, 1000,
        ex -> !(ex instanceof IllegalStateException)); // an IllegalStateException does NOT count
    CircuitBreaker.ThrowingSupplier<String> clientError =
        () -> { throw new IllegalStateException("client 404"); };
    for (int i = 0; i < 5; i++) {
      assertThrows(IllegalStateException.class, () -> b.call(clientError)); // 5 >> threshold(2)
    }
    AtomicInteger calls = new AtomicInteger();
    assertEquals("ok", b.call(() -> { calls.incrementAndGet(); return "ok"; }),
        "a run of non-counting failures must leave the breaker CLOSED");
    assertEquals(1, calls.get());
  }

  @Test
  void a_counting_failure_still_trips_the_breaker_with_a_classifier() {
    // The classifier only exempts what it rejects; a counting failure (here a RuntimeException) still
    // trips the breaker exactly as with the default "count everything" classifier.
    CircuitBreaker b = new CircuitBreaker(new ManualClock(0), 2, 1000,
        ex -> !(ex instanceof IllegalStateException)); // RuntimeException("boom") DOES count
    assertThrows(RuntimeException.class, () -> b.call(BOOM));
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN
    AtomicInteger calls = new AtomicInteger();
    assertThrows(CircuitBreaker.CircuitOpenException.class,
        () -> b.call(() -> { calls.incrementAndGet(); return "x"; }));
    assertEquals(0, calls.get());
  }

  @Test
  void a_non_counting_failure_during_the_half_open_trial_frees_the_slot_without_reopening()
      throws Exception {
    // Mirror of an_interrupt_during_the_half_open_trial_…: a non-counting (client) error thrown by the
    // single half-open trial neither closes nor re-opens the breaker — it just frees the trial slot so a
    // fresh trial may run.
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 2, 1000,
        ex -> !(ex instanceof IllegalStateException));
    assertThrows(RuntimeException.class, () -> b.call(BOOM));
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN at t=0
    clock.advance(1000); // half-open eligible
    assertThrows(IllegalStateException.class,
        () -> b.call(() -> { throw new IllegalStateException("client error mid-trial"); }));
    AtomicInteger trial = new AtomicInteger();
    assertEquals("recovered", b.call(() -> { trial.incrementAndGet(); return "recovered"; }));
    assertEquals(1, trial.get());
  }

  @Test
  void rejects_a_null_classifier() {
    assertThrows(NullPointerException.class,
        () -> new CircuitBreaker(new ManualClock(0), 1, 1000, null));
  }

  @Test
  void rejects_a_negative_openMillis() {
    assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(new ManualClock(0), 1, -1));
  }

  @Test
  void rejects_a_failureThreshold_below_one() {
    // A breaker with threshold 0 would be OPEN before any real failure (or, depending on the counting,
    // never trip cleanly) — the ctor rejects it. Pins the sibling guard to rejects_a_negative_openMillis.
    assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(new ManualClock(0), 0, 1000));
    assertThrows(IllegalArgumentException.class, () -> new CircuitBreaker(new ManualClock(0), -1, 1000));
  }

  @Test
  void half_open_trial_failure_reopens_the_breaker() {
    ManualClock clock = new ManualClock(0);
    CircuitBreaker b = new CircuitBreaker(clock, 2, 1000);
    assertThrows(RuntimeException.class, () -> b.call(BOOM));
    assertThrows(RuntimeException.class, () -> b.call(BOOM)); // OPEN at t=0
    clock.advance(1000); // half-open eligible
    AtomicInteger trial = new AtomicInteger();
    assertThrows(RuntimeException.class,
        () -> b.call(() -> { trial.incrementAndGet(); throw new RuntimeException("still down"); }));
    assertEquals(1, trial.get()); // the trial DID run
    // re-OPEN at t=1000: immediately fail-fast without invoking the supplier.
    AtomicInteger calls = new AtomicInteger();
    assertThrows(CircuitBreaker.CircuitOpenException.class,
        () -> b.call(() -> { calls.incrementAndGet(); return "x"; }));
    assertEquals(0, calls.get());
  }
}
