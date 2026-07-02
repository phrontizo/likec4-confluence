import type { WorkerResponse } from './worker'

export interface PoolWorker {
  postMessage(message: { sources: Record<string, string> }): void
  terminate(): void
  // Structural subsets of the native Worker's MessageEvent<WorkerResponse> / ErrorEvent — enough to catch
  // a WorkerResponse field rename or a typo on `e.message` at the pool boundary, while staying loose
  // enough that a test can still dispatch a plain `{ data }` / `{ message }` fake (createDiagramWorker
  // bridges the real Worker with a double `as unknown as PoolWorker` cast).
  onmessage: ((e: { data: WorkerResponse }) => void) | null
  onerror: ((e: { message?: string }) => void) | null
  // Fired when a posted response cannot be structured-clone DESERIALIZED on the main thread — distinct
  // from both `message` and `error`. Without it such a job would settle only via the timeout (misreported
  // as "Diagram too large"). Optional so a minimal fake/host that omits it still satisfies the type.
  onmessageerror?: ((e?: unknown) => void) | null
}

export interface RunOptions { timeoutMs?: number; signal?: AbortSignal }

export class TimeoutError extends Error {
  constructor(message = 'compute timed out') {
    super(message)
    this.name = 'TimeoutError'
  }
}

interface Job {
  sources: Record<string, string>
  opts: RunOptions
  resolve: (r: WorkerResponse) => void
  reject: (e: unknown) => void
  // Removes the queue-abort listener wired in run(); cleared once assign() takes ownership of abort
  // handling. Lets an abort while the job is still queued prune it immediately instead of leaking.
  detachQueuedAbort?: () => void
}

export class WorkerPool {
  private idle: PoolWorker[] = []
  private created = 0
  private queue: Job[] = []
  // Workers currently running a job (NOT in `idle`). Tracked so dispose() can terminate them — an
  // untracked in-flight worker would otherwise leak its thread and, on a late onmessage, resurrect
  // itself into `idle` with created=0 (a corrupt, never-cleaned-up state). Value = cancel closure
  // that finishes + rejects that worker's job.
  private inflight = new Map<PoolWorker, () => void>()
  private disposed = false
  // Armed only while the pool is FULLY idle; fires to terminate the idle workers so a long-lived pool
  // (the viewer's lives for the whole page) does not hold maxWorkers heavy language-services heaps
  // resident forever. null when disarmed. Disabled entirely when idleTimeoutMs is undefined.
  private idleTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * @param idleTimeoutMs when set, a pool that becomes fully idle (no in-flight jobs, empty queue)
   *   terminates its idle workers after this many ms and recreates them on demand on the next run().
   *   Undefined keeps workers for the process/page lifetime (the original behaviour).
   */
  constructor(
    private readonly createWorker: () => PoolWorker,
    private readonly maxWorkers = 3,
    private readonly idleTimeoutMs?: number,
  ) {}

  private cancelIdleSweep(): void {
    if (this.idleTimer !== null) {
      clearTimeout(this.idleTimer)
      this.idleTimer = null
    }
  }

  /** Arm the idle-sweep timer iff the pool is fully idle and an idle timeout is configured. Idempotent. */
  private scheduleIdleSweep(): void {
    this.cancelIdleSweep()
    if (this.disposed || !this.idleTimeoutMs) return
    if (this.idle.length === 0 || this.inflight.size > 0 || this.queue.length > 0) return
    this.idleTimer = setTimeout(() => {
      this.idleTimer = null
      // Re-check under the timer: only release a pool that is still genuinely idle (a job could have
      // arrived and be in-flight/queued between arming and firing).
      if (this.disposed || this.inflight.size > 0 || this.queue.length > 0) return
      for (const w of this.idle) w.terminate()
      this.created = Math.max(0, this.created - this.idle.length)
      this.idle = []
    }, this.idleTimeoutMs)
  }

  run(sources: Record<string, string>, opts: RunOptions = {}): Promise<WorkerResponse> {
    return new Promise<WorkerResponse>((resolve, reject) => {
      if (this.disposed) {
        // A run() after teardown must settle here. Otherwise the job is push()ed and pump() spins a
        // worker whose eventual onmessage hits the disposed branch (terminate-without-resolve), leaving
        // this promise unsettled forever (a hung caller) and leaking the worker thread.
        reject(new DOMException('Aborted', 'AbortError'))
        return
      }
      if (opts.signal?.aborted) {
        reject(new DOMException('Aborted', 'AbortError'))
        return
      }
      const job: Job = { sources, opts, resolve, reject }
      const signal = opts.signal
      if (signal) {
        // Prune a job that aborts WHILE STILL QUEUED. assign() only wires its abort handling once a worker
        // is free, so without this an aborted job with no free worker would sit in the queue forever —
        // retaining its `sources` (memory) and leaving its promise unsettled (a hung caller). Once assign()
        // takes the job it detaches this listener and owns abort handling, so the two never double-settle.
        const onQueuedAbort = () => {
          const i = this.queue.indexOf(job)
          if (i === -1) return // already assigned: assign()'s own onAbort handles it
          this.queue.splice(i, 1)
          job.detachQueuedAbort = undefined
          signal.removeEventListener('abort', onQueuedAbort)
          job.reject(new DOMException('Aborted', 'AbortError'))
        }
        signal.addEventListener('abort', onQueuedAbort)
        job.detachQueuedAbort = () => signal.removeEventListener('abort', onQueuedAbort)
      }
      this.queue.push(job)
      this.pump()
    })
  }

  private pump(): void {
    // Any pump means work is arriving or a worker just freed — disarm the idle sweep; scheduleIdleSweep()
    // below re-arms it only if the pool ends up fully idle.
    this.cancelIdleSweep()
    while (this.queue.length > 0) {
      let worker = this.idle.pop()
      if (!worker) {
        if (this.created >= this.maxWorkers) return
        try {
          worker = this.createWorker()
        } catch (e) {
          // createWorker() can throw — `new Worker(...)` fails when a host CSP blocks worker-src or the
          // worker chunk 404s. Reject the job we were about to assign and move on, rather than let the
          // throw propagate out of run()'s executor while the job stays stranded in `queue` (already
          // push()ed): a later pump() would then assign a worker to that dead, already-settled job.
          const job = this.queue.shift()!
          job.detachQueuedAbort?.()
          job.reject(e)
          continue
        }
        this.created++
      }
      this.assign(worker, this.queue.shift()!)
    }
    // Queue drained. If the pool is now fully idle, arm the sweep so idle workers are eventually released.
    this.scheduleIdleSweep()
  }

  private assign(worker: PoolWorker, job: Job): void {
    // assign() now owns this job's abort handling (via onAbort below); drop the queue-abort listener so
    // the two paths can't both fire on the same signal.
    job.detachQueuedAbort?.()
    job.detachQueuedAbort = undefined
    // The job may have been aborted while it sat in the queue waiting for a free worker. Re-check
    // before posting so we never start (and occupy a scarce worker with) cancelled work.
    if (job.opts.signal?.aborted) {
      this.idle.push(worker)
      job.reject(new DOMException('Aborted', 'AbortError'))
      return
    }
    let done = false
    const finish = (action: () => void) => {
      if (done) return
      done = true
      if (timer !== null) clearTimeout(timer)
      job.opts.signal?.removeEventListener('abort', onAbort)
      worker.onmessage = null
      worker.onerror = null
      worker.onmessageerror = null
      this.inflight.delete(worker)
      action()
    }
    const onAbort = () => finish(() => {
      this.discard(worker)
      job.reject(new DOMException('Aborted', 'AbortError'))
    })
    const timer = job.opts.timeoutMs
      ? setTimeout(() => finish(() => {
          this.discard(worker)
          job.reject(new TimeoutError())
        }), job.opts.timeoutMs)
      : null

    worker.onmessage = (e) => finish(() => {
      if (this.disposed) { worker.terminate(); return } // pool torn down: don't resurrect into idle
      this.idle.push(worker)
      job.resolve(e.data as WorkerResponse)
      this.pump()
    })
    worker.onerror = (e) => finish(() => {
      this.discard(worker)
      // Prefer a non-EMPTY message, then fall back. An ErrorEvent can carry message === '' (e.g. a
      // cross-origin worker error); `?? ` would have kept that empty string (only null/undefined are
      // nullish), rejecting with new Error('') and losing the 'worker error' fallback. `||` treats the
      // empty string as absent so the fallback is reached.
      const msg = (e && typeof e.message === 'string' && e.message) || 'worker error'
      job.reject(new Error(msg))
    })
    // A response that fails to deserialize on the main thread: terminate the worker and reject with a
    // clear cause rather than letting the job hang until the timeout fires its misleading message.
    worker.onmessageerror = () => finish(() => {
      this.discard(worker)
      job.reject(new Error('worker message deserialization failed'))
    })
    // Register a cancel closure so dispose() can terminate this still-running worker and reject its
    // job (otherwise an in-flight job would hang forever once the pool is torn down).
    this.inflight.set(worker, () => finish(() => {
      worker.terminate()
      job.reject(new DOMException('Aborted', 'AbortError'))
    }))
    job.opts.signal?.addEventListener('abort', onAbort)
    worker.postMessage({ sources: job.sources })
  }

  /** Terminate a worker (e.g. on timeout/abort/error) and free its slot so the queue can refill. */
  private discard(worker: PoolWorker): void {
    worker.terminate()
    // After dispose() the slot bookkeeping is already reset (created=0, queue drained); don't decrement
    // below zero or re-pump a torn-down pool. clamp() guards against any future caller ordering too.
    if (this.disposed) return
    this.created = Math.max(0, this.created - 1)
    this.pump()
  }

  dispose(): void {
    this.disposed = true
    this.cancelIdleSweep()
    for (const w of this.idle) w.terminate()
    this.idle = []
    // Terminate every still-running worker and reject its job (in-flight jobs would otherwise hang
    // forever, and the worker threads would leak). Iterate a snapshot — each cancel() mutates the map.
    for (const cancel of [...this.inflight.values()]) cancel()
    this.inflight.clear()
    this.created = 0
    // Reject anything still queued so callers awaiting a never-to-run job don't hang forever.
    const queued = this.queue
    this.queue = []
    for (const job of queued) {
      job.detachQueuedAbort?.() // drop the queue-abort listener so a later abort can't re-settle the job
      job.reject(new DOMException('Aborted', 'AbortError'))
    }
  }
}
