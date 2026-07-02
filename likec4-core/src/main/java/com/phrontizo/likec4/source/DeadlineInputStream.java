package com.phrontizo.likec4.source;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a streamed response body with an overall wall-clock deadline, enforced two ways.
 *
 * <p>The GitLab archive body is consumed as a live stream (so the extractor's size caps bound memory
 * during the download). The JDK {@code HttpClient} request timeout governs the response-headers phase,
 * not the consumption of the body, so a hostile/compromised GitLab can send a valid gzip header and
 * then either <em>trickle</em> bytes indefinitely or <em>stall completely inside a single read</em>
 * while staying under the uncompressed-size caps — pinning the request thread, the bundle single-flight
 * lock and a circuit-breaker permit without ever failing.
 *
 * <ol>
 *   <li><b>Between reads</b> ({@link #checkDeadline()}): once the deadline passes, the next read throws.
 *       This catches a slow trickle (reads keep returning a few bytes, re-checked between them) without
 *       any background thread.</li>
 *   <li><b>Inside a single blocking read</b>: a daemon watchdog closes the underlying stream at the
 *       deadline, so a thread parked in {@code read()} on a socket that has gone silent is unblocked
 *       (the close surfaces as an {@link java.nio.channels.AsynchronousCloseException}/IOException)
 *       rather than waiting forever. The watchdog is cancelled the instant the consumer closes this
 *       stream (normal completion), so it adds at most one short-lived daemon thread per download.</li>
 * </ol>
 *
 * <p>No shared/static executor is used on purpose: a static thread pool in this (plugin-bundle-loaded)
 * class would pin the bundle classloader across enable/disable, which the wrapper explicitly avoids.
 */
final class DeadlineInputStream extends FilterInputStream {
  private final long deadlineNanos;
  // Counted down by close(); lets the watchdog wake immediately on normal completion instead of
  // lingering until the deadline.
  private final CountDownLatch closed = new CountDownLatch(1);
  // Makes the underlying-stream close single-shot: exactly one of {watchdog, consumer close()} ever
  // closes the delegate. Without it, a watchdog firing at the same instant the consumer closes could
  // both call in.close(), relying on the delegate's close() being idempotent under a concurrent
  // double-close — true for the JDK HttpClient body but an undocumented assumption. The CAS removes it.
  private final AtomicBoolean closedOnce = new AtomicBoolean(false);
  private final Thread watchdog;

  /**
   * Wrap {@code in} with a wall-clock deadline and start its watchdog. Use this in preference to the
   * constructor: the watchdog thread is started only AFTER construction fully completes, so {@code this}
   * never escapes a partially-constructed object (the watchdog references {@code this}; starting it inside
   * the constructor would publish {@code this} before the object is fully built — safe today because
   * every field {@code watch()} reads is already assigned, but a latent data race for any field added
   * after the start line).
   */
  static DeadlineInputStream start(InputStream in, long deadlineNanos) {
    DeadlineInputStream s = new DeadlineInputStream(in, deadlineNanos);
    try {
      s.watchdog.start();
    } catch (Throwable t) {
      // Thread creation failed (thread-count exhaustion / OutOfMemoryError under load). `s` is never
      // returned, so the caller's try-with-resources never runs and would otherwise leak the wrapped
      // live HTTP body/socket — precisely when the JVM is already under resource pressure. Close the
      // delegate here before rethrowing so the connection is released, not stranded.
      try {
        in.close();
      } catch (IOException ignored) {
        // best-effort: nothing more we can do while unwinding a thread-start failure
      }
      throw t;
    }
    return s;
  }

  private DeadlineInputStream(InputStream in, long deadlineNanos) {
    super(in);
    this.deadlineNanos = deadlineNanos;
    this.watchdog = new Thread(this::watch, "likec4-download-deadline");
    this.watchdog.setDaemon(true);
    // NB: watchdog.start() is deliberately in start(), AFTER construction — see that factory's javadoc.
  }

  /** Wait until the consumer closes us OR the deadline passes; if the latter, close {@code in} to
   *  break a thread stalled inside a single blocking read. */
  private void watch() {
    try {
      long remaining = deadlineNanos - System.nanoTime();
      // closed.await returns true if close() fired first (nothing to do); false on timeout.
      if (remaining > 0 && closed.await(remaining, TimeUnit.NANOSECONDS)) {
        return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    // Deadline reached while still open: close the underlying stream to unblock an in-progress read.
    // The closeOnce CAS makes this mutually exclusive with a concurrent consumer close() (see close()).
    if (closed.getCount() > 0 && closedOnce.compareAndSet(false, true)) {
      try {
        in.close();
      } catch (IOException ignored) {
        // best-effort: the consuming read will surface the close as its own IOException
      }
    }
  }

  // nanoTime() - deadline >= 0 (not nanoTime() >= deadline) so the comparison is correct across a
  // System.nanoTime() wraparound; do not "simplify" it into the naive form.
  private void checkDeadline() throws IOException {
    if (System.nanoTime() - deadlineNanos >= 0) {
      throw new IOException("archive download exceeded the time budget");
    }
  }

  @Override
  public int read() throws IOException {
    checkDeadline();
    return super.read();
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    checkDeadline();
    return super.read(b, off, len);
  }

  @Override
  public long skip(long n) throws IOException {
    // skip() consumes bytes just like read(), so it is subject to the same between-calls deadline: no
    // current consumer skips (the gzip/tar path reads exclusively via read(byte[])), but guarding it keeps
    // the wall-clock budget airtight if a future consumer ever skips a large run.
    checkDeadline();
    return super.skip(n);
  }

  @Override
  public void close() throws IOException {
    // Wake the watchdog first so its thread exits promptly rather than parking until the deadline. The
    // closeOnce CAS makes the delegate close single-shot: if the watchdog already fired at the deadline
    // it owns the close and this skips super.close() (the delegate is already closed) — so the watchdog
    // and the consumer can never concurrently double-close the underlying stream.
    closed.countDown();
    if (closedOnce.compareAndSet(false, true)) {
      super.close();
    }
  }
}
