import { type ComputeError, type DriftInfo, compute } from './compute'
import { messageOf } from './errors'

export interface WorkerRequest { sources: Record<string, string> }
export interface WorkerResponse {
  data: unknown
  errors: ComputeError[]
  drifts: DriftInfo[]
  computeMs: number
  /**
   * Set only when the compute itself THREW (an internal/runtime failure), as opposed to the
   * author's `.c4` failing validation (which surfaces via `errors`). The pipeline maps this to a
   * generic "Could not render diagram" error rather than misattributing it to the source DSL.
   */
  computeError?: string
}

self.onmessage = async (e: MessageEvent<WorkerRequest>) => {
  const start = performance.now()
  // Validate the message shape before touching compute(). A malformed/empty payload (a future refactor
  // that posts the wrong key, or a stray message) would otherwise reach compute(undefined) and surface
  // as an opaque "Object.entries(undefined)" — report the real cause instead.
  const sources = e.data?.sources
  // Reject anything that is not a plain Record — including an ARRAY, which is `typeof === 'object'` and
  // truthy and would otherwise reach compute() where Object.entries([...]) yields positional index keys.
  // Mirrors the codebase's !Array.isArray trust-boundary convention (asViewsMap, sanitizeSnapshotBody).
  if (!sources || typeof sources !== 'object' || Array.isArray(sources)) {
    ;(self as unknown as Worker).postMessage({
      data: null,
      errors: [],
      drifts: [],
      computeMs: performance.now() - start,
      computeError: 'worker received no sources',
    } satisfies WorkerResponse)
    return
  }
  try {
    const { data, errors, drifts } = await compute(sources)
    const res: WorkerResponse = { data, errors, drifts, computeMs: performance.now() - start }
    ;(self as unknown as Worker).postMessage(res)
  } catch (err) {
    // Only the message string crosses postMessage (below). Log the full error+stack here so an internal
    // likec4 crash leaves a diagnosable trace in the browser console for support, not just the UI's
    // generic "Could not render diagram".
    console.error('[likec4 worker] compute failed', err)
    ;(self as unknown as Worker).postMessage({
      data: null,
      errors: [],
      drifts: [],
      computeMs: performance.now() - start,
      computeError: messageOf(err),
    } satisfies WorkerResponse)
  }
}
