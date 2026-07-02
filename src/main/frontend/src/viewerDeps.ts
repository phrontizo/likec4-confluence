import { IndexedDbDumpCache } from './cache'
import { createDiagramWorker } from './createDiagramWorker'
import type { PipelineDeps } from './pipeline'
import { createRestClient } from './restClient'
import { bumpComputeCounter, defaultPoolSize } from './runtime'
import { WorkerPool } from './workerPool'

let shared: PipelineDeps | null = null

// The viewer pool is never disposed (it lives for the page), so release its workers after a spell of
// inactivity rather than holding maxWorkers language-services heaps resident forever after the last
// diagram renders. Generous enough not to thrash during a burst of macros on one page; the pool
// recreates a worker on demand for the next (rare, post-idle) recompute.
const VIEWER_WORKER_IDLE_MS = 60_000

/**
 * The viewer's shared pipeline deps (REST client, IndexedDB cache, worker pool) for the whole page.
 *
 * MEMOISED: `boot()` can run more than once on a page — Confluence re-executes a macro's
 * web-resource when it injects content dynamically (inline edit, AJAX content load), and React
 * StrictMode double-invokes effects in dev. Building a fresh `WorkerPool` each time would leak the
 * previous pool's Worker threads (the viewer never disposes the pool — it lives for the page). Reusing
 * one set of deps keeps a single pool no matter how many times `boot()` is called.
 */
export function viewerDeps(): PipelineDeps {
  return (shared ??= {
    rest: createRestClient(),
    cache: new IndexedDbDumpCache(),
    pool: new WorkerPool(createDiagramWorker, defaultPoolSize(4), VIEWER_WORKER_IDLE_MS),
    timeoutMs: 20_000,
    onCompute: bumpComputeCounter,
  })
}
