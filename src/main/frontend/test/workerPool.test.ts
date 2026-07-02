import { afterEach, describe, expect, it, vi } from 'vitest'
import { TimeoutError, WorkerPool, type PoolWorker } from '../src/workerPool'

class FakeWorker implements PoolWorker {
  onmessage: ((e: { data: any }) => void) | null = null
  onerror: ((e: any) => void) | null = null
  onmessageerror: ((e: any) => void) | null = null
  posted: any[] = []
  terminated = false
  autoRespond: any | null
  constructor(autoRespond: any | null) { this.autoRespond = autoRespond }
  postMessage(message: any) {
    this.posted.push(message)
    if (this.autoRespond !== null) queueMicrotask(() => this.onmessage?.({ data: this.autoRespond }))
  }
  terminate() { this.terminated = true }
  respond(data: any) { this.onmessage?.({ data }) }
  fail(message: string) { this.onerror?.({ message }) }
  messageError() { this.onmessageerror?.({}) }
}

afterEach(() => vi.useRealTimers())

describe('WorkerPool', () => {
  it('runs a job and resolves with the worker response', async () => {
    const resp = { data: { ok: 1 }, errors: [], drifts: [], computeMs: 1 }
    const pool = new WorkerPool(() => new FakeWorker(resp), 2)
    expect(await pool.run({ 'a.likec4': 'x' })).toEqual(resp)
  })

  it('caps concurrent workers and queues the overflow', async () => {
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => { const w = new FakeWorker(null); made.push(w); return w }, 2)
    const p0 = pool.run({ a: '0' })
    const p1 = pool.run({ a: '1' })
    const p2 = pool.run({ a: '2' }) // queued: only 2 workers allowed
    expect(made.length).toBe(2)
    made[0].respond({ data: 0, errors: [], drifts: [], computeMs: 1 })
    await p0
    expect(made.length).toBe(2) // worker 0 reused for the queued job, not a 3rd
    expect(made[0].posted.length).toBe(2)
    made[0].respond({ data: 2, errors: [], drifts: [], computeMs: 1 })
    made[1].respond({ data: 1, errors: [], drifts: [], computeMs: 1 })
    expect((await p2).data).toBe(2)
    expect((await p1).data).toBe(1)
  })

  it('times out, terminates the stuck worker, and rejects with TimeoutError', async () => {
    vi.useFakeTimers()
    const w = new FakeWorker(null)
    const pool = new WorkerPool(() => w, 1)
    const p = pool.run({ a: 'x' }, { timeoutMs: 100 })
    const assertion = expect(p).rejects.toBeInstanceOf(TimeoutError)
    await vi.advanceTimersByTimeAsync(100)
    await assertion
    expect(w.terminated).toBe(true)
  })

  it('rejects a job aborted while queued and never posts it (FE-M1)', async () => {
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => { const w = new FakeWorker(null); made.push(w); return w }, 1)
    const busy = pool.run({ a: 'busy' }) // occupies the single worker
    const ctrl = new AbortController()
    const queued = pool.run({ a: 'queued' }, { signal: ctrl.signal }) // sits in the queue
    ctrl.abort() // abort BEFORE a worker frees up (no assign-time abort listener yet)
    made[0].respond({ data: 0, errors: [], drifts: [], computeMs: 1 }) // free worker → pump
    await expect(queued).rejects.toMatchObject({ name: 'AbortError' })
    await busy
    expect(made.length).toBe(1) // no extra worker spun up for the aborted job
    expect(made[0].posted.length).toBe(1) // the aborted job was never posted
  })

  it('eagerly prunes and rejects a queued job when its signal aborts, without waiting for a worker (FE-M1b)', async () => {
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => { const w = new FakeWorker(null); made.push(w); return w }, 1)
    const busy = pool.run({ a: 'busy' }) // occupies the only worker; never responds (stays in flight)
    const ctrl = new AbortController()
    const queued = pool.run({ a: 'queued' }, { signal: ctrl.signal }) // sits in the queue
    ctrl.abort()
    // The single worker is still busy (no response), yet the aborted queued job must settle IMMEDIATELY
    // — pruned from the queue and rejected — rather than hang pending until a worker happens to free.
    await expect(queued).rejects.toMatchObject({ name: 'AbortError' })
    expect(made.length).toBe(1) // no extra worker spun up for the aborted job
    expect(made[0].posted.length).toBe(1) // only the busy job was posted; the aborted one was pruned
    // The busy job is still in flight; dispose to settle it (and prove the queue no longer holds the dead job).
    busy.catch(() => {})
    pool.dispose()
  })

  it('terminates the worker and rejects when the worker errors (distinct from timeout/abort)', async () => {
    const w = new FakeWorker(null)
    const pool = new WorkerPool(() => w, 1)
    const p = pool.run({ a: 'x' }) // assign() wires w.onerror synchronously
    w.fail('worker crashed')
    await expect(p).rejects.toThrow(/worker crashed/)
    expect(w.terminated).toBe(true) // the broken worker is discarded, not returned to the pool
  })

  it('rejects with a non-empty fallback message when the worker error carries an empty message', async () => {
    // A cross-origin/opaque worker error can arrive as ErrorEvent{ message: '' }. `?? ` kept that empty
    // string (only null/undefined are nullish), so the job rejected with new Error('') — a blank, useless
    // failure. It must fall back to a meaningful 'worker error' message instead.
    const w = new FakeWorker(null)
    const pool = new WorkerPool(() => w, 1)
    const p = pool.run({ a: 'x' })
    w.fail('')
    await expect(p).rejects.toThrow(/worker error/)
    expect(w.terminated).toBe(true)
  })

  it('terminates the worker and rejects on a messageerror instead of hanging until timeout', async () => {
    // A worker can post a response whose structured-clone DESERIALIZATION fails on the main thread; the
    // platform fires `messageerror`, distinct from both `message` and `error`. Unhandled, the job would
    // settle only via the 20s timeout (misreported as "Diagram too large"). It must reject promptly with
    // a deserialization error and the broken worker must be discarded, not returned to the pool.
    const w = new FakeWorker(null)
    const pool = new WorkerPool(() => w, 1)
    const p = pool.run({ a: 'x' }) // assign() wires w.onmessageerror synchronously
    w.messageError()
    await expect(p).rejects.toThrow(/deserial/i)
    expect(w.terminated).toBe(true)
  })

  it('rejects still-queued jobs on dispose instead of leaving them pending forever (FE-M2)', async () => {
    const pool = new WorkerPool(() => new FakeWorker(null), 1)
    const busy = pool.run({ a: 'busy' }) // occupies the worker (also rejected on dispose now)
    busy.catch(() => {}) // the in-flight job rejects on dispose too — handle it
    const queued = pool.run({ a: 'queued' }) // queued behind it
    pool.dispose()
    await expect(queued).rejects.toMatchObject({ name: 'AbortError' })
    await expect(busy).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('rejects run() after dispose instead of stranding a never-settled job (FE-M2b)', async () => {
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => { const w = new FakeWorker(null); made.push(w); return w }, 2)
    pool.dispose()
    // A run() after teardown must settle (reject), not push a job onto a disposed pool: pump() would
    // spin a worker whose eventual onmessage hits the disposed branch (terminate-without-resolve),
    // hanging the caller forever. Guard run() so it rejects up front and never creates a worker.
    await expect(pool.run({ a: 'x' })).rejects.toMatchObject({ name: 'AbortError' })
    expect(made.length).toBe(0)
  }, 2000)

  it('rejects the job (and does not strand the queue) when createWorker throws', async () => {
    // new Worker(...) can throw — a host CSP blocking worker-src, or the worker chunk 404ing. The job
    // must reject with that error, and the pool must stay consistent: the failed job is removed from the
    // queue (not left settled-but-stranded for a later pump to assign a worker to), so a later run()
    // once worker creation recovers is served normally and spins a worker up for IT.
    let mode: 'throw' | 'ok' = 'throw'
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => {
      if (mode === 'throw') throw new Error('worker-src blocked')
      const w = new FakeWorker({ data: { ok: 1 }, errors: [], drifts: [], computeMs: 1 }); made.push(w); return w
    }, 2)
    await expect(pool.run({ a: 'x' })).rejects.toThrow(/worker-src blocked/)
    expect(made.length).toBe(0) // nothing was created
    mode = 'ok'
    expect(await pool.run({ a: 'y' })).toEqual({ data: { ok: 1 }, errors: [], drifts: [], computeMs: 1 })
    expect(made.length).toBe(1) // a worker was spun up for the recovered job, not wasted on the dead one
  })

  it('terminates idle workers after the idle timeout and lazily recreates on the next run (FE mem)', async () => {
    // The viewer pool lives for the whole page; without an idle sweep it holds up to maxWorkers threads
    // (each with the full language-services heap) resident forever after the last diagram renders. With
    // an idleTimeoutMs, a fully-idle pool releases its workers and recreates them on demand.
    vi.useFakeTimers()
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => { const w = new FakeWorker(null); made.push(w); return w }, 2, 1000)
    const p = pool.run({ a: 'x' })
    made[0].respond({ data: 1, errors: [], drifts: [], computeMs: 1 })
    await p
    expect(made[0].terminated).toBe(false) // idle, not yet swept
    await vi.advanceTimersByTimeAsync(1000)
    expect(made[0].terminated).toBe(true) // swept once idle past the timeout
    const p2 = pool.run({ a: 'y' })
    expect(made.length).toBe(2) // a FRESH worker was created, not the terminated one resurrected
    made[1].respond({ data: 2, errors: [], drifts: [], computeMs: 1 })
    expect((await p2).data).toBe(2)
  })

  it('a run() before the idle timeout cancels the sweep and reuses the idle worker (FE mem)', async () => {
    vi.useFakeTimers()
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => { const w = new FakeWorker(null); made.push(w); return w }, 2, 1000)
    const p = pool.run({ a: 'x' })
    made[0].respond({ data: 1, errors: [], drifts: [], computeMs: 1 })
    await p
    await vi.advanceTimersByTimeAsync(500) // partway to the sweep
    const p2 = pool.run({ a: 'y' }) // arrives first → cancels the pending sweep, reuses the idle worker
    expect(made.length).toBe(1) // reused, not recreated
    expect(made[0].terminated).toBe(false)
    made[0].respond({ data: 2, errors: [], drifts: [], computeMs: 1 })
    expect((await p2).data).toBe(2)
    await vi.advanceTimersByTimeAsync(1000) // the original sweep must not fire late and kill the reused worker
    expect(made[0].terminated).toBe(true) // it IS swept, but only after the NEW idle period elapsed
  })

  it('an idle sweep after a discarded (aborted) job resets created so the pool still serves fresh work', async () => {
    // Interleave the idle-sweep with a discard: an aborted in-flight job discards its worker (created--)
    // and, finding the pool now fully idle, arms the sweep; when the sweep then fires it does
    // `created = Math.max(0, created - idle.length)`. Pin that the two bookkeeping paths compose — created
    // is left neither negative nor stale — so the pool can still spin fresh workers afterwards.
    vi.useFakeTimers()
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => { const w = new FakeWorker(null); made.push(w); return w }, 2, 1000)
    const ac = new AbortController()
    const pA = pool.run({ a: 'x' })                        // worker0 in-flight
    const pB = pool.run({ b: 'y' }, { signal: ac.signal }) // worker1 in-flight (maxWorkers=2)
    expect(made.length).toBe(2)
    made[0].respond({ data: 1, errors: [], drifts: [], computeMs: 1 })
    await pA                                               // worker0 now idle (worker1 still in-flight)
    ac.abort()                                             // discard worker1: created 2 -> 1, sweep armed
    await expect(pB).rejects.toMatchObject({ name: 'AbortError' })
    await vi.advanceTimersByTimeAsync(1000)                // sweep fires: worker0 terminated, created -> 0
    expect(made[0].terminated).toBe(true)
    // created was reset to 0 (not stuck at a stale 1), so BOTH fresh slots are available again.
    const pC = pool.run({ c: 'z' })
    const pD = pool.run({ d: 'w' })
    expect(made.length).toBe(4)                            // two FRESH workers, proving created == 0 post-sweep
    made[2].respond({ data: 3, errors: [], drifts: [], computeMs: 1 })
    made[3].respond({ data: 4, errors: [], drifts: [], computeMs: 1 })
    expect((await pC).data).toBe(3)
    expect((await pD).data).toBe(4)
  })

  it('never sweeps idle workers when no idleTimeoutMs is configured (default is page-lifetime)', async () => {
    vi.useFakeTimers()
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => { const w = new FakeWorker(null); made.push(w); return w }, 2) // no idle timeout
    const p = pool.run({ a: 'x' })
    made[0].respond({ data: 1, errors: [], drifts: [], computeMs: 1 })
    await p
    await vi.advanceTimersByTimeAsync(10 * 60_000)
    expect(made[0].terminated).toBe(false) // held for the page lifetime, as before
  })

  it('terminates an IN-FLIGHT worker on dispose and rejects its job (no thread leak/resurrection)', async () => {
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => { const w = new FakeWorker(null); made.push(w); return w }, 2)
    const inflight = pool.run({ a: 'x' }) // occupies worker 0; no autoRespond → stays in flight
    expect(made.length).toBe(1)
    pool.dispose()
    expect(made[0].terminated).toBe(true) // the running worker is terminated, not leaked
    await expect(inflight).rejects.toMatchObject({ name: 'AbortError' })
    // A late message from the now-terminated worker must not resurrect it or resolve the dead job.
    let resolved = false
    inflight.then(() => { resolved = true }, () => {})
    made[0].respond({ data: 9, errors: [], drifts: [], computeMs: 1 })
    await Promise.resolve()
    expect(resolved).toBe(false)
  })
})
