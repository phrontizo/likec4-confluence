export interface ResolveResponse { sha: string }
export interface SourceResponse { sha: string; files: Record<string, string> }

export class RestError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
    this.name = 'RestError'
  }
}

export interface RestClient {
  resolve(project: string, ref?: string, signal?: AbortSignal): Promise<ResolveResponse>
  fetchSource(
    project: string,
    ref: string | undefined,
    path: string | undefined,
    signal?: AbortSignal,
  ): Promise<SourceResponse>
}

// Confluence exposes AJS.contextPath() (e.g. '/confluence') so requests hit the right context path.
// Resolve it LAZILY (per client construction), not once at module load: AJS may not be initialised
// when this module first evaluates, and a base captured too early would be wrong for the page
// lifetime. Guard the `window` access so the module also loads under Node (vitest) and the Vite
// dev-server, where there is no AJS / no context path → fall back to ''.
export function defaultBase(): string {
  const hasWindow = typeof window !== 'undefined'
  const hasContextPath = hasWindow && typeof window.AJS?.contextPath === 'function'
  if (hasWindow && !hasContextPath && window.console?.warn) {
    // Parity with boot-loader.js / editor-loader.js, which warn when AJS.contextPath() is unavailable:
    // without it the REST base is built origin-relative, which is WRONG under a non-root Confluence
    // context path (e.g. '/confluence'). Warn rather than degrade silently so a load-order fault is
    // diagnosable. This fires ONLY when AJS itself is missing — a genuinely root-deployed Confluence
    // still HAS AJS.contextPath (it returns ''), so the legitimate root case does not warn. (Absent
    // under vitest/Vite-dev, where there is no AJS by design.)
    window.console.warn(
      'likec4: AJS.contextPath() unavailable; building the REST base origin-relative — this breaks under a non-root context path',
    )
  }
  const raw = (hasContextPath && window.AJS!.contextPath!()) || ''
  // AJS.contextPath() normally has no trailing slash, but a reverse-proxy/config can return one
  // ('/wiki/'), and a root context could return a bare '/'. Strip trailing slashes so the base never
  // has a '//' — rather than depend on the server collapsing it.
  const ctx = raw.replace(/\/+$/, '')
  return `${ctx}/rest/likec4/1.0`
}

/** Network requests that outlive this are aborted and surfaced as `unreachable` (not a hang). */
const DEFAULT_TIMEOUT_MS = 15_000

function qs(params: Record<string, string | undefined>): string {
  const u = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v != null && v !== '') u.set(k, v)
  return u.toString()
}

// Cancel an unconsumed response body so a large or partially-read stream isn't left for GC to reclaim
// over a slow connection; the cancel rejection is irrelevant to the error we're about to throw, and the
// optional chain no-ops when there is no body (e.g. a test double).
function cancelBody(res: Response): void {
  res.body?.cancel().catch(() => {})
}

function validateResolve(body: unknown): ResolveResponse {
  const sha = (body as Record<string, unknown> | null)?.sha
  if (!body || typeof body !== 'object' || typeof sha !== 'string' || sha === '') {
    throw new RestError(0, 'Malformed resolve response (expected { sha })')
  }
  return { sha }
}

// Prototype-polluting keys a crafted/MITM'd GitLab response could carry as OWN keys in `files`
// (JSON.parse surfaces a literal `__proto__`/`constructor`/`prototype` as an own enumerable property).
// The browser is the trust boundary for this untrusted GitLab data, so they are stripped here — parity
// with compute.ts's UNSAFE_SNAPSHOT_KEYS sanitisation of the same source, so no downstream consumer
// (compute()/JSON5) ever sees one.
const UNSAFE_FILE_KEYS = new Set(['__proto__', 'constructor', 'prototype'])

function validateSource(body: unknown): SourceResponse {
  const o = body as Record<string, unknown> | null
  if (
    !o || typeof o !== 'object' ||
    typeof o.sha !== 'string' || o.sha === '' ||
    typeof o.files !== 'object' || o.files === null
  ) {
    throw new RestError(0, 'Malformed source response (expected { sha, files })')
  }
  // Rebuild `files` onto a fresh object, DROPPING the prototype-polluting keys and asserting the
  // retained VALUES are strings (the contract types files as Record<string,string>), so neither a
  // poisoned key nor a non-string content can flow untyped into compute()/JSON5.parse.
  const files: Record<string, string> = {}
  for (const [k, v] of Object.entries(o.files)) {
    if (UNSAFE_FILE_KEYS.has(k)) continue
    if (typeof v !== 'string') {
      throw new RestError(0, 'Malformed source response (file contents must be strings)')
    }
    files[k] = v
  }
  return { sha: o.sha, files }
}

export interface RestClientOptions {
  /** Per-request timeout; an exceeded request aborts and rejects with RestError(0). */
  timeoutMs?: number
}

export function createRestClient(
  base: string = defaultBase(),
  doFetch: typeof fetch = fetch,
  opts: RestClientOptions = {},
): RestClient {
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS

  async function getJson<T>(url: string, validate: (b: unknown) => T, signal?: AbortSignal): Promise<T> {
    // Compose a per-request timeout with any caller-supplied signal (FE-I3 cancellation).
    const ctrl = new AbortController()
    let timedOut = false
    const onExternalAbort = () => ctrl.abort()
    if (signal) {
      if (signal.aborted) ctrl.abort()
      else signal.addEventListener('abort', onExternalAbort)
    }
    const timer = setTimeout(() => { timedOut = true; ctrl.abort() }, timeoutMs)
    try {
      const res = await doFetch(url, { signal: ctrl.signal })
      if (!res.ok) {
        // Cancel the error body so a large error response over a slow connection is not left
        // un-consumed (relying on GC to reclaim the stream); only the status is needed here.
        cancelBody(res)
        throw new RestError(res.status, `${res.status} ${res.statusText}`)
      }
      let body: unknown
      try {
        body = await res.json()
      } catch (parseErr) {
        // A truncated/invalid JSON body is a genuine error — but an abort DURING the body read must
        // stay an abort. Only convert to a RestError when we are not actually aborting.
        if (timedOut || signal?.aborted) {
          // Symmetric with the non-ok path above: defensively cancel the (aborting) body. The fetch
          // abort already tears the stream down, so this is belt-and-braces.
          cancelBody(res)
          throw parseErr
        }
        throw new RestError(0, 'Malformed response body (not valid JSON)')
      }
      return validate(body)
    } catch (e) {
      // A genuine response error (non-ok status, malformed shape/body) means we DID get a response, so
      // it is real regardless of whether the timeout flag also flipped or the caller later aborted in
      // the same window — it must win over both, never be masked as a timeout(0) or an abort. (A body
      // read that was interrupted by the timeout re-throws the raw parse error above, not a RestError,
      // so it correctly falls through to the timeout branch below.)
      if (e instanceof RestError) throw e
      if (timedOut) throw new RestError(0, `Request timed out after ${timeoutMs}ms`)
      // External cancellation (component unmounted): surface as an AbortError so the pipeline maps
      // it to `unreachable` and the (already-unmounted) caller ignores it via its `alive` guard.
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
      throw e
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', onExternalAbort)
    }
  }

  return {
    resolve: (project, ref, signal) =>
      getJson(`${base}/resolve?${qs({ project, ref })}`, validateResolve, signal),
    fetchSource: (project, ref, path, signal) =>
      getJson(`${base}/source?${qs({ project, ref, path })}`, validateSource, signal),
  }
}
