import { cacheKey, type CachedDump, type DumpCache } from './cache'
import type { ComputeError, DriftInfo } from './compute'
import type { DiagramParams } from './dataAttrs'
import { messageOf } from './errors'
import { RestError, type RestClient } from './restClient'
import { viewIdsOf } from './viewsMap'
import { TimeoutError, type WorkerPool } from './workerPool'

export type LoadResult =
  | { kind: 'ok'; sha: string; data: unknown; viewIds: string[]; startView: string; drifts: DriftInfo[]; fromCache: boolean }
  | { kind: 'parse-error'; errors: ComputeError[] }
  | { kind: 'unknown-view'; requested: string; available: string[] }
  | { kind: 'not-found'; detail: string }
  | { kind: 'unreachable'; detail: string }
  | { kind: 'too-large'; detail: string }
  | { kind: 'error'; detail: string }

export interface PipelineDeps {
  rest: RestClient
  cache: DumpCache
  pool: WorkerPool
  /** Bounds the worker COMPUTE only. The resolve+source REST legs have their own per-request timeout
   *  (see restClient); the cache get/put legs between them are best-effort and UNtimed — they settle on
   *  IndexedDB's own oncomplete/onerror/onabort (and onblocked) and degrade to recompute rather than fail
   *  the render (see loadModel), so in practice they add only a negligible settle. Worst-case total
   *  wall-clock is therefore roughly 2×restTimeout + timeoutMs plus that IndexedDB settle. */
  timeoutMs?: number
  /** Called once per compute that COMPLETES and yields a usable model (cache miss) — boot wires a window
   *  counter for e2e. A crashed (computeError), timed-out, aborted, OR validation-failed (parse-error)
   *  run does NOT count: none of them yields a renderable model (see loadModel). */
  onCompute?: () => void
}

export async function loadModel(
  params: DiagramParams,
  deps: PipelineDeps,
  signal?: AbortSignal,
): Promise<LoadResult> {
  const { rest, cache, pool } = deps

  let sha: string
  try {
    sha = (await rest.resolve(params.project, params.ref, signal)).sha
  } catch (e) {
    return mapFetchError(e, 'resolve')
  }

  const key = cacheKey(sha, params.path)
  // The cache is strictly best-effort: a read that rejects (private mode, disabled storage, quota,
  // a corrupt DB) must degrade to "recompute", never fail the whole render.
  let cached: CachedDump | null = null
  try {
    cached = await cache.get(key)
  } catch (e) {
    console.warn('likec4: cache read failed, recomputing', e)
  }
  const fromCache = cached !== null
  let data: unknown
  let errors: ComputeError[]
  let drifts: DriftInfo[]

  if (cached) {
    // `errors`/`drifts` are read straight off the cached row without the Array.isArray coercion the
    // compute path applies below — safe because cache.get only ever returns a row that passed
    // isValidDump(), which already asserts both are arrays. Keep that invariant if isValidDump changes.
    ;({ data, errors, drifts } = cached)
  } else {
    let files: Record<string, string>
    try {
      // Fetch /source by the SHA that /resolve just returned, not by the request's mutable ref. A push
      // landing on `ref` in the window between the two legs would otherwise let /source re-resolve to a
      // NEWER commit whose files get cached under this OLDER `sha` key (and returned as `ok.sha`, the
      // React remount key) — breaking the "cache is content-addressed by sha" invariant. A full 40-hex
      // sha is itself a valid ref, so the server resolves it to itself (RefShaCache bypasses it), pinning
      // both legs to one immutable commit.
      files = (await rest.fetchSource(params.project, sha, params.path, signal)).files
    } catch (e) {
      return mapFetchError(e, 'source')
    }
    try {
      const res = await pool.run(files, { timeoutMs: deps.timeoutMs, signal })
      // A THROWN compute (internal/runtime crash) is reported via `computeError`, distinct from the
      // author's `.c4` failing validation (which arrives via `errors` → parse-error below).
      if (res.computeError) return { kind: 'error', detail: res.computeError }
      // worker.ts always posts the full {data,errors,drifts} shape, but pool.run() returns the message
      // `as WorkerResponse` without validating it. Coerce errors/drifts to arrays defensively: the
      // `errors.length` check below runs OUTSIDE this try, so a non-conforming response (e.g. a future
      // worker change dropping `errors`) would otherwise throw an uncaught TypeError out of loadModel.
      data = res.data
      errors = Array.isArray(res.errors) ? res.errors : []
      drifts = Array.isArray(res.drifts) ? res.drifts : []
      // Count the compute only once it has COMPLETED and produced a USABLE model — not on every cache-miss
      // attempt, not for a crashed (computeError, returned above), timed-out or aborted run (those throw
      // and never reach here), and not for a validation-failed run (errors present -> parse-error below,
      // no renderable model). Under React StrictMode's dev double-invoke the aborted first attempt is
      // likewise excluded — so the counter reflects real successful computes, not attempts. Fires before
      // the best-effort cache write so a failed persist still counts the compute that ran.
      // Isolated in its own try/catch: onCompute is a diagnostic side-effect (a window counter for e2e),
      // so a throwing callback must NOT be caught by the surrounding compute try below and misreported as
      // a compute `error` (which would also discard this otherwise-successful model).
      if (errors.length === 0) {
        try {
          deps.onCompute?.()
        } catch (e) {
          console.warn('likec4: onCompute callback threw (ignored)', e)
        }
      }
    } catch (e) {
      if (e instanceof TimeoutError) return { kind: 'too-large', detail: 'Diagram computation timed out' }
      return { kind: 'error', detail: messageOf(e) }
    }
    // Best-effort write: a failed persist just means we recompute next time.
    try {
      await cache.put(key, { data, errors, drifts })
    } catch (e) {
      console.warn('likec4: cache write failed', e)
    }
  }

  if (errors.length > 0) return { kind: 'parse-error', errors }

  // Guard against a poisoned/malformed dump whose `views` is not a plain object: viewIdsOf's asViewsMap
  // degrades a string ('ab' -> ['0','1']) or array to {} so positional index "view ids" can't pass the
  // length check, get reported as `ok`, and then crash LikeC4Model.create. viewIdsOf ALSO strips any
  // prototype-polluting own key (__proto__/constructor/prototype) so a poisoned dump can't smuggle one
  // into `startView` below. (Shared with listViews/compute via viewsMap.)
  const viewIds = viewIdsOf(data)
  if (viewIds.length === 0) return { kind: 'error', detail: 'Model has no views' }
  if (params.view && !viewIds.includes(params.view)) {
    return { kind: 'unknown-view', requested: params.view, available: viewIds }
  }
  // Default view: the explicit param wins; else the conventional 'index' view; else the FIRST declared
  // view. viewIds is Object.keys(data.views), whose order is the model's view-declaration order (stable
  // for a given dump), so this is deterministic — though which view is "first" is a likec4 emission
  // detail, hence 'index' is the documented convention for authors who want a specific landing view.
  const startView = params.view ?? (viewIds.includes('index') ? 'index' : viewIds[0])
  return { kind: 'ok', sha, data, viewIds, startView, drifts, fromCache }
}

function mapFetchError(e: unknown, stage: 'resolve' | 'source'): LoadResult {
  if (e instanceof RestError) {
    if (e.status === 404) {
      return { kind: 'not-found', detail: stage === 'resolve' ? 'Project or ref not found' : 'Source path not found' }
    }
    return { kind: 'unreachable', detail: `Source repository error (${e.status})` }
  }
  return { kind: 'unreachable', detail: 'Cannot reach source repository' }
}
