package com.phrontizo.likec4.source;

import com.phrontizo.likec4.source.cache.Clock;
import java.util.function.Predicate;

/**
 * Trips after a run of consecutive failures so a down/slow GitLab isn't hammered (spec §7).
 *
 * <p>CLOSED &rarr; (≥{@code failureThreshold} consecutive failures) &rarr; OPEN (calls fail fast
 * with {@link CircuitOpenException}, supplier not invoked) &rarr; after {@code openMillis}
 * &rarr; HALF_OPEN (a single trial call is allowed; success closes the breaker, failure re-opens
 * it). Time comes from {@link Clock} so the state machine is unit-testable.
 *
 * <p>By default every non-{@link InterruptedException} thrown by the supplier counts toward the
 * threshold. A caller may instead supply a {@code countsAsFailure} predicate to classify which
 * exceptions represent an actual outage: an exception the predicate rejects propagates to the caller
 * but does NOT trip (or re-open) the breaker and merely frees any half-open trial slot it held —
 * exactly like an interrupt. This lets the source layer treat a GitLab 4xx client error (a typo'd ref
 * &rarr; 404, an auth failure) as "GitLab is up and rejecting THIS request" rather than an outage, so a
 * single misconfigured macro can't trip the shared breaker for every diagram (see
 * {@link SourceService#COUNTS_AS_OUTAGE}).
 */
public final class CircuitBreaker {
  /** Thrown when the breaker is OPEN and a call is rejected without invoking the supplier. */
  public static final class CircuitOpenException extends RuntimeException {
    public CircuitOpenException() {
      super("circuit breaker is open");
    }
  }

  @FunctionalInterface
  public interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  private enum State { CLOSED, OPEN, HALF_OPEN }

  private final Clock clock;
  private final int failureThreshold;
  private final long openMillis;
  // Returns true if a thrown exception counts as an outage (toward the threshold); false means it
  // propagates without tripping/re-opening the breaker. Defaults to "count everything".
  private final Predicate<Exception> countsAsFailure;

  private State state = State.CLOSED;
  private int consecutiveFailures = 0;
  private long openedAt = 0L;
  private boolean trialInFlight = false;
  private long trialStartedAt = 0L;
  // Monotonic id of the most recently started half-open trial. A call captures the id it owns (0 if it
  // did not start a trial — i.e. a CLOSED-acquired call), and only frees/resolves the trial slot when
  // its id still matches the current trial. This stops a DIFFERENT in-flight call (e.g. a CLOSED-era
  // call that is then interrupted) from clearing trialInFlight and letting a SECOND concurrent trial
  // start, which would put two probes onto a known-degraded GitLab and break the single-trial invariant.
  private long trialId = 0L;

  public CircuitBreaker(Clock clock, int failureThreshold, long openMillis) {
    // Default classifier: every supplier failure counts toward the threshold.
    this(clock, failureThreshold, openMillis, ex -> true);
  }

  public CircuitBreaker(Clock clock, int failureThreshold, long openMillis,
                        Predicate<Exception> countsAsFailure) {
    if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
    if (openMillis < 0) throw new IllegalArgumentException("openMillis must be >= 0");
    this.clock = clock;
    this.failureThreshold = failureThreshold;
    this.openMillis = openMillis;
    this.countsAsFailure = java.util.Objects.requireNonNull(countsAsFailure, "countsAsFailure");
  }

  public <T> T call(ThrowingSupplier<T> supplier) throws Exception {
    long myTrial = acquirePermit(); // throws CircuitOpenException if rejecting; 0 unless this IS the trial
    try {
      T result = supplier.get();
      onSuccess(myTrial);
      return result;
    } catch (InterruptedException ie) {
      // A thread interrupt is caller-side cancellation/shutdown, not a GitLab failure: do NOT count
      // it toward the threshold (that would falsely trip the breaker). Free this call's OWN half-open
      // trial slot (if it held one) so a later call can retry, then propagate the interrupt unchanged.
      releasePermit(myTrial);
      throw ie;
    } catch (Exception ex) {
      if (!countsAsFailure.test(ex)) {
        // A non-outage failure (e.g. a GitLab 4xx client error — see SourceService.COUNTS_AS_OUTAGE):
        // GitLab is up and rejecting THIS request, so it must not count toward the threshold or one
        // misconfigured macro would trip the shared breaker for every diagram. Treat it exactly like an
        // interrupt — free this call's OWN half-open trial slot (if any) without recording a failure, so
        // it neither trips a CLOSED breaker nor re-opens/closes one mid-trial — then propagate unchanged.
        releasePermit(myTrial);
        throw ex;
      }
      onFailure(myTrial);
      throw ex;
    }
  }

  /** Decides whether this call may run, returning the id of the half-open trial it owns (0 if it is not
   *  a trial — a CLOSED-acquired call). The supplier is only invoked if this returns normally. */
  private synchronized long acquirePermit() {
    switch (state) {
      case CLOSED:
        return 0L;
      case OPEN:
        // The subtraction form (rather than nowMillis() >= openedAt + openMillis) mirrors
        // DeadlineInputStream.checkDeadline, but here it is chosen for readability, not wrap-safety:
        // Clock.SYSTEM is epoch millis (System.currentTimeMillis), which does not wrap within ~292M
        // years, and a ManualClock never rewinds — so, unlike nanoTime, there is no wraparound to guard.
        if (clock.nowMillis() - openedAt >= openMillis) {
          return startTrial(); // this call is the single half-open trial
        }
        throw new CircuitOpenException();
      case HALF_OPEN:
        // A trial that has been in flight for less than openMillis still owns the single slot; reject
        // the rest. But a trial that has exceeded openMillis is treated as ABANDONED (its supplier is
        // hung/never returned) and a fresh trial is allowed — otherwise a single non-terminating call
        // would wedge the breaker OPEN forever, rejecting every request for the life of the process.
        if (trialInFlight && clock.nowMillis() - trialStartedAt < openMillis) {
          throw new CircuitOpenException();
        }
        return startTrial();
      default:
        throw new IllegalStateException("unreachable circuit state: " + state);
    }
  }

  /** Begin the single half-open trial (or reclaim an abandoned one): record its start so a hung trial
   *  can be superseded after {@code openMillis}, and return its unique id so only the owning call can
   *  later resolve it. {@code synchronized} for defence even though every caller already holds the lock. */
  private synchronized long startTrial() {
    state = State.HALF_OPEN;
    trialInFlight = true;
    trialStartedAt = clock.nowMillis();
    return ++trialId;
  }

  private synchronized void onSuccess(long myTrial) {
    if (myTrial == 0L) {
      // A CLOSED-era call (acquired its permit while CLOSED, owns no half-open trial). Its success only
      // clears the failure count while the breaker is STILL CLOSED. A straggler that finishes AFTER other
      // failures have since tripped the breaker OPEN must NOT un-trip it — it ran against a GitLab that was
      // healthy when it STARTED and says nothing about health after the trip (mirror of the stale failure).
      if (state == State.CLOSED) consecutiveFailures = 0;
      return;
    }
    // A half-open trial's success. Only the CURRENT trial's owner closes the breaker; a SUPERSEDED
    // (abandoned-then-replaced) trial's stale success is ignored, exactly as onFailure ignores a stale
    // failure — otherwise it could re-close a breaker a newer trial has since (correctly) re-OPENed.
    if (myTrial == trialId) {
      state = State.CLOSED;
      consecutiveFailures = 0;
      trialInFlight = false; // only the current trial's owner frees the slot
    }
  }

  /** Caller-side cancellation: release THIS call's half-open trial slot (if it owns the current trial)
   *  without recording success or failure. A non-owning call (e.g. a CLOSED-era call) must not clear it. */
  private synchronized void releasePermit(long myTrial) {
    if (myTrial == trialId) trialInFlight = false;
  }

  private synchronized void onFailure(long myTrial) {
    // A SUPERSEDED half-open trial — one that owned a trial slot but overran openMillis, so a fresh
    // trial has since replaced it (trialId moved on) — must not mutate breaker state with its stale
    // outcome: it neither frees the current slot nor counts toward the threshold, and crucially must not
    // re-OPEN (or push back the eligibility of) a breaker a newer trial may have since recovered. A
    // CLOSED-era call carries myTrial == 0 and ALWAYS counts (that is how the breaker trips from CLOSED);
    // the current trial's owner carries myTrial == trialId. (A stale SUCCESS is filtered symmetrically in
    // onSuccess: a CLOSED-era straggler only clears state while still CLOSED, and a superseded trial's
    // success is ignored — neither may un-trip a breaker the failures correctly OPENed.)
    if (myTrial != 0 && myTrial != trialId) return;
    // A CLOSED-era straggler (myTrial == 0) only counts while the breaker is STILL CLOSED — exactly as its
    // success counterpart in onSuccess only clears the count while CLOSED. Once the breaker has left CLOSED
    // (tripped OPEN, cooled to HALF_OPEN, possibly with a fresh trial in flight), a stale failure that began
    // against a healthy GitLab must be a no-op: letting it re-OPEN would re-stamp openedAt and push the next
    // half-open trial's eligibility forward by up to one straggler's duration, needlessly delaying recovery.
    if (myTrial == 0 && state != State.CLOSED) return;
    if (myTrial == trialId) trialInFlight = false; // only the current trial's owner frees the slot
    consecutiveFailures++;
    if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
      // Only (re)stamp openedAt when actually transitioning INTO open. A late concurrent failure (a call
      // that acquired its permit before another thread tripped the breaker) re-entering here while the
      // state is already OPEN must not push openedAt forward — that would delay the OPEN->HALF_OPEN trial.
      if (state != State.OPEN) {
        openedAt = clock.nowMillis();
        // The counter has served its purpose once the breaker trips: OPEN/HALF_OPEN never consult it
        // (HALF_OPEN re-opens on the state clause above, not the count), and CLOSED is only ever
        // re-entered via onSuccess, which also zeroes it. Reset on the transition so a breaker that
        // re-fails its half-open trials for the life of the process can't grow the count unboundedly
        // (a purely theoretical int overflow), and so the field cleanly means "failures since CLOSED".
        consecutiveFailures = 0;
      }
      state = State.OPEN;
    }
  }
}
