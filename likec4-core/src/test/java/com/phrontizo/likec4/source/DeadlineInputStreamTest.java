package com.phrontizo.likec4.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DeadlineInputStreamTest {
  @Test
  void passes_bytes_through_while_within_the_deadline() throws IOException {
    byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
    // A deadline comfortably in the future must not interfere with normal reads.
    try (InputStream s = DeadlineInputStream.start(new ByteArrayInputStream(data),
        System.nanoTime() + 60_000_000_000L)) {
      assertEquals("hello world", new String(s.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void rejects_a_read_once_the_deadline_has_passed() {
    byte[] data = new byte[1024];
    // A deadline already in the past: the very first read must throw rather than continue consuming a
    // slow-trickle body indefinitely (which would pin the request thread, the bundle single-flight
    // lock and a circuit-breaker permit while staying under the size caps).
    InputStream s = DeadlineInputStream.start(new ByteArrayInputStream(data), System.nanoTime() - 1L);
    IOException ex = assertThrows(IOException.class, () -> s.read(new byte[8]));
    assertTrue(ex.getMessage().toLowerCase().contains("time"), "was: " + ex.getMessage());
  }

  @Test
  void single_byte_read_also_enforces_the_deadline() {
    InputStream s = DeadlineInputStream.start(new ByteArrayInputStream(new byte[4]), System.nanoTime() - 1L);
    assertThrows(IOException.class, s::read);
  }

  @Test
  void skip_also_enforces_the_deadline() {
    // skip() consumes bytes just like read(), so it must be subject to the same between-calls deadline —
    // otherwise a consumer that skipped a large run (e.g. a future archive format skipping padding) could
    // sidestep the wall-clock budget the class exists to enforce. A deadline already in the past must make
    // skip() throw rather than delegate to the underlying (potentially slow-trickling) stream.
    InputStream s = DeadlineInputStream.start(new ByteArrayInputStream(new byte[1024]), System.nanoTime() - 1L);
    assertThrows(IOException.class, () -> s.skip(8));
  }

  @Test
  void skip_advances_within_the_deadline() throws IOException {
    byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
    try (InputStream s = DeadlineInputStream.start(new ByteArrayInputStream(data),
        System.nanoTime() + 60_000_000_000L)) {
      assertEquals(6L, s.skip(6)); // a comfortably-future deadline must not interfere with a normal skip
      assertEquals("world", new String(s.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void breaks_a_read_that_stalls_inside_a_single_blocking_read() throws Exception {
    // A read that blocks forever (a silent socket) is NOT caught by the between-reads deadline check —
    // it never returns to re-check. The watchdog must close the underlying stream at the deadline so
    // the parked read unblocks (here the fake surfaces the close as an IOException), rather than the
    // request thread / single-flight lock / breaker permit pinning indefinitely.
    BlockingInputStream blocking = new BlockingInputStream();
    long deadline = System.nanoTime() + 150_000_000L; // 150ms out
    InputStream s = DeadlineInputStream.start(blocking, deadline);
    long t0 = System.nanoTime();
    assertThrows(IOException.class, () -> s.read(new byte[16]));
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    assertTrue(elapsedMs < 4000,
        "the watchdog must break the stalled read near the deadline, not block ~5s; was " + elapsedMs + "ms");
    assertTrue(blocking.closed, "the watchdog must have closed the underlying stream");
    s.close();
  }

  /** A stream whose read() parks until the stream is closed (mimics a silent socket). */
  private static final class BlockingInputStream extends InputStream {
    private final CountDownLatch released = new CountDownLatch(1);
    volatile boolean closed = false;

    @Override
    public int read() throws IOException {
      try {
        released.await(5, TimeUnit.SECONDS); // safety net so a broken watchdog can't hang the suite
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
      if (closed) throw new IOException("stream closed");
      return -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      return read();
    }

    @Override
    public void close() {
      closed = true;
      released.countDown();
    }
  }

  @Test
  void the_watchdog_thread_terminates_promptly_on_normal_close_and_is_not_leaked() throws Exception {
    // On normal completion the consumer closes the stream well before the (far-future) deadline. close()
    // counts down the `closed` latch so the watchdog's closed.await() returns at once and the daemon thread
    // exits — otherwise every download would leak one "likec4-download-deadline" thread parked until its
    // deadline elapsed (up to the full download budget), piling up under load. The existing tests pin that
    // the delegate is closed; this pins that the watchdog thread is actually reclaimed.
    String name = "likec4-download-deadline";
    Set<Thread> before = threadsNamed(name);
    long farFuture = System.nanoTime() + 300_000_000_000L; // 300s: the watchdog would park ~forever if unwoken
    InputStream s = DeadlineInputStream.start(new ByteArrayInputStream(new byte[0]), farFuture);
    Thread watchdog = null;
    for (int i = 0; i < 200 && watchdog == null; i++) { // the thread starts ~immediately; poll briefly for it
      for (Thread t : threadsNamed(name)) {
        if (!before.contains(t)) { watchdog = t; break; }
      }
      if (watchdog == null) Thread.sleep(5);
    }
    assertNotNull(watchdog, "start() must have spawned the named watchdog thread");
    s.close();
    watchdog.join(3000); // if close() failed to wake it, it would stay parked ~300s and this would time out
    assertFalse(watchdog.isAlive(),
        "close() must wake the watchdog so its daemon thread terminates promptly, not linger until the deadline");
  }

  private static Set<Thread> threadsNamed(String name) {
    Set<Thread> out = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (name.equals(t.getName())) out.add(t);
    }
    return out;
  }

  @Test
  void closing_propagates_to_the_delegate() throws IOException {
    boolean[] closed = {false};
    InputStream delegate = new ByteArrayInputStream(new byte[0]) {
      @Override
      public void close() throws IOException {
        closed[0] = true;
        super.close();
      }
    };
    DeadlineInputStream.start(delegate, System.nanoTime() + 60_000_000_000L).close();
    assertTrue(closed[0], "close() must reach the wrapped stream so the socket is released");
  }

  @Test
  void the_delegate_is_closed_at_most_once_across_the_watchdog_and_consumer() throws Exception {
    // The watchdog (firing at the deadline) and a consumer close() must never both close the delegate:
    // the closeOnce CAS makes the underlying close single-shot, so correctness never relies on the
    // delegate's close() being idempotent under a concurrent double-close. Force the watchdog to fire
    // (deadline already past), wait for it to close the delegate, THEN close the wrapper from this
    // thread — the delegate must still have seen exactly one close().
    CountingCloseStream delegate = new CountingCloseStream();
    DeadlineInputStream s = DeadlineInputStream.start(delegate, System.nanoTime() - 1L);
    assertTrue(delegate.awaitClosed(2, TimeUnit.SECONDS), "the watchdog must close the delegate at the past deadline");
    s.close(); // consumer close AFTER the watchdog already owns the close
    assertEquals(1, delegate.closeCount.get(), "the delegate must be closed exactly once, not double-closed");
  }

  /** A stream that counts close() invocations and signals the first one, to pin single-shot close. */
  private static final class CountingCloseStream extends InputStream {
    final java.util.concurrent.atomic.AtomicInteger closeCount = new java.util.concurrent.atomic.AtomicInteger();
    private final CountDownLatch closedLatch = new CountDownLatch(1);

    @Override
    public int read() {
      return -1;
    }

    @Override
    public void close() {
      closeCount.incrementAndGet();
      closedLatch.countDown();
    }

    boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
      return closedLatch.await(timeout, unit);
    }
  }
}
