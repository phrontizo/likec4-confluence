import { afterEach, describe, expect, it, vi } from 'vitest'
import { RestError, createRestClient, defaultBase } from '../src/restClient'

function fakeFetch(status: number, body: unknown) {
  return vi.fn(async () => ({
    ok: status >= 200 && status < 300,
    status,
    statusText: `S${status}`,
    json: async () => body,
  })) as unknown as typeof fetch
}

// A fetch that resolves with a response only after `delayMs` (fake-timer driven), ignoring its abort
// signal — models a response whose bytes were already in flight when the client's timeout flipped.
function delayedFetch(status: number, body: unknown, delayMs: number) {
  return vi.fn(
    () =>
      new Promise((resolve) => {
        setTimeout(
          () =>
            resolve({
              ok: status >= 200 && status < 300,
              status,
              statusText: `S${status}`,
              json: async () => body,
            }),
          delayMs,
        )
      }),
  ) as unknown as typeof fetch
}

// A fetch that never resolves on its own but rejects (like the platform) when its signal aborts.
function hangingFetch() {
  return vi.fn((_url: any, init: any) => new Promise((_res, reject) => {
    const sig = init?.signal as AbortSignal | undefined
    sig?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
  })) as unknown as typeof fetch
}

// A fetch that resolves an ok response immediately but whose body read (json()) REJECTS after
// `delayMs` — models bytes still in flight when the client's timeout aborts the read.
function fetchWhoseJsonRejectsAfter(delayMs: number, err: unknown) {
  return vi.fn(async () => ({
    ok: true,
    status: 200,
    statusText: 'S200',
    json: () => new Promise((_res, reject) => setTimeout(() => reject(err), delayMs)),
  })) as unknown as typeof fetch
}

afterEach(() => vi.useRealTimers())

describe('restClient', () => {
  it('resolve builds the URL and returns the sha', async () => {
    const f = fakeFetch(200, { sha: 'abc' })
    const c = createRestClient('/rest/likec4/1.0', f)
    expect(await c.resolve('grp/repo', 'main')).toEqual({ sha: 'abc' })
    expect((f as any).mock.calls[0][0]).toBe('/rest/likec4/1.0/resolve?project=grp%2Frepo&ref=main')
  })

  it('fetchSource omits empty params and returns files', async () => {
    const f = fakeFetch(200, { sha: 'abc', files: { 'a.likec4': 'x' } })
    const c = createRestClient('/rest/likec4/1.0', f)
    const res = await c.fetchSource('grp/repo', undefined, 'sub')
    expect(res.files).toEqual({ 'a.likec4': 'x' })
    expect((f as any).mock.calls[0][0]).toBe('/rest/likec4/1.0/source?project=grp%2Frepo&path=sub')
  })

  it('percent-encodes query-injection characters in project/ref (guards a manual-concat regression)', async () => {
    // A future refactor of qs() away from URLSearchParams to string concatenation would reintroduce
    // query injection: a project/ref containing &, ?, #, = or spaces could smuggle extra query params or
    // truncate the URL server-side. Round-trip the built URL through URL/searchParams: the embedded
    // separators must survive as VALUES, proving they were encoded rather than left structural.
    const f = fakeFetch(200, { sha: 'abc' })
    const c = createRestClient('/rest/likec4/1.0', f)
    await c.resolve('grp/repo?x=1&y=2', 'feat/#frag zone')
    const built = new URL((f as any).mock.calls[0][0], 'http://x')
    expect(built.searchParams.get('project')).toBe('grp/repo?x=1&y=2')
    expect(built.searchParams.get('ref')).toBe('feat/#frag zone')
    // Exactly the two intended params — no smuggled third key from an unencoded '&'.
    expect([...built.searchParams.keys()]).toEqual(['project', 'ref'])
  })

  it('throws RestError carrying the status on non-2xx', async () => {
    const c = createRestClient('/rest/likec4/1.0', fakeFetch(404, {}))
    await expect(c.resolve('grp/repo')).rejects.toMatchObject({ status: 404 })
    await expect(c.resolve('grp/repo')).rejects.toBeInstanceOf(RestError)
  })

  it('times out a hung request and rejects with RestError(0) (FE-I1)', async () => {
    vi.useFakeTimers()
    const c = createRestClient('/rest/likec4/1.0', hangingFetch(), { timeoutMs: 5000 })
    const p = c.resolve('grp/repo')
    const assertion = expect(p).rejects.toMatchObject({ status: 0 })
    await vi.advanceTimersByTimeAsync(5000)
    await assertion
    await expect(p).rejects.toBeInstanceOf(RestError)
  })

  it('cancels the error response body on a non-ok status so a slow stream is not left un-drained', async () => {
    const cancel = vi.fn(async () => {})
    const f = vi.fn(async () => ({
      ok: false,
      status: 502,
      statusText: 'Bad Gateway',
      body: { cancel },
      json: async () => ({}),
    })) as unknown as typeof fetch
    const c = createRestClient('/rest/likec4/1.0', f)
    await expect(c.resolve('grp/repo')).rejects.toMatchObject({ status: 502 })
    expect(cancel).toHaveBeenCalledTimes(1)
  })

  it('does NOT cancel the body on a successful 200 (it is consumed by json())', async () => {
    // Complements the non-ok / abort-mid-read cancel tests: on the happy path the body IS read via
    // res.json(), so cancelling it too would be wrong (a double-consume). Pin that the belt-and-braces
    // cancel is scoped to the error/abort paths only and never fires on a clean success.
    const cancel = vi.fn(async () => {})
    const f = vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'S200',
      body: { cancel },
      json: async () => ({ sha: 'abc' }),
    })) as unknown as typeof fetch
    const c = createRestClient('/rest/likec4/1.0', f)
    expect(await c.resolve('grp/repo')).toEqual({ sha: 'abc' })
    expect(cancel).not.toHaveBeenCalled()
  })

  it('reports a genuine non-ok status arriving in the timeout window, not a masked timeout', async () => {
    vi.useFakeTimers()
    // The response (a real 404) arrives AFTER the client's timeout already flipped `timedOut`. A genuine
    // HTTP status means we DID get a response, so it must win over the timeout flag — never be reported
    // back as RestError(0) "timed out", which the pipeline would mislabel as unreachable instead of a
    // not-found. Guards against the catch-order regression where `if (timedOut)` preceded the RestError.
    const c = createRestClient('/rest/likec4/1.0', delayedFetch(404, {}, 10_000), { timeoutMs: 5000 })
    const p = c.resolve('grp/repo')
    const assertion = expect(p).rejects.toMatchObject({ status: 404 })
    await vi.advanceTimersByTimeAsync(10_000)
    await assertion
  })

  it('reports a body read interrupted by the timeout as a timeout, not malformed JSON', async () => {
    vi.useFakeTimers()
    // res.json() rejects only AFTER the client's timeout has already flipped `timedOut` and aborted the
    // read. That parse rejection must be re-thrown raw and fall through to the timeout branch —
    // RestError(0, "timed out") — NOT be mislabelled as a malformed body. Pins the
    // `if (timedOut || signal?.aborted) throw parseErr` guard against a regression that dropped it.
    const c = createRestClient('/rest/likec4/1.0',
      fetchWhoseJsonRejectsAfter(10_000, new Error('read aborted mid-body')), { timeoutMs: 5000 })
    const p = c.resolve('grp/repo')
    const status0 = expect(p).rejects.toMatchObject({ status: 0 })
    const timedOut = expect(p).rejects.toThrow(/timed out/)
    await vi.advanceTimersByTimeAsync(10_000)
    await status0
    await timedOut
  })

  it('surfaces an external abort DURING the body read as an AbortError, not a malformed-body error', async () => {
    vi.useFakeTimers()
    // The response arrived ok, but the caller (an unmounting component) aborts WHILE res.json() is still
    // reading. The parse then rejects; with signal.aborted true and timedOut false it must be re-thrown
    // raw and surface as an AbortError (-> pipeline `unreachable`, dropped by the caller's `alive` guard)
    // — NOT mislabelled as a malformed-body RestError(0). Complements the timeout-mid-body test, which
    // pins the timedOut side of the same `if (timedOut || signal?.aborted) throw parseErr` guard.
    const ctrl = new AbortController()
    const c = createRestClient('/rest/likec4/1.0',
      fetchWhoseJsonRejectsAfter(10_000, new Error('read aborted mid-body')), { timeoutMs: 60_000 })
    const p = c.resolve('grp/repo', 'main', ctrl.signal)
    const isAbort = expect(p).rejects.toMatchObject({ name: 'AbortError' })
    const notRest = expect(p).rejects.not.toBeInstanceOf(RestError)
    ctrl.abort() // external cancellation while the body read is still pending
    await vi.advanceTimersByTimeAsync(10_000) // now json() rejects, well before the 60s timeout
    await isAbort
    await notRest
  })

  it('cancels the response body when an external abort interrupts the body read', async () => {
    vi.useFakeTimers()
    // Symmetric with the non-ok body-cancel above: when the caller aborts mid-json(), the (aborting) body
    // stream is defensively cancelled so a partially-read stream is not left for GC to reclaim. The abort
    // still surfaces as an AbortError. Before the fix the abort path re-threw without cancelling the body.
    const cancel = vi.fn(async () => {})
    const f = vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'S200',
      body: { cancel },
      json: () => new Promise((_res, reject) => setTimeout(() => reject(new Error('read aborted mid-body')), 10_000)),
    })) as unknown as typeof fetch
    const ctrl = new AbortController()
    const c = createRestClient('/rest/likec4/1.0', f, { timeoutMs: 60_000 })
    const p = c.resolve('grp/repo', 'main', ctrl.signal)
    const isAbort = expect(p).rejects.toMatchObject({ name: 'AbortError' })
    ctrl.abort()
    await vi.advanceTimersByTimeAsync(10_000)
    await isAbort
    expect(cancel).toHaveBeenCalledTimes(1)
  })

  it('reports a genuine JSON parse failure (no abort) as a malformed-body RestError', async () => {
    // res.json() throws with NO timeout and NO external abort — a real corrupt/HTML body. It must become
    // RestError(0, "Malformed response body …"), distinct from the timeout case above so a slow server is
    // never confused with a corrupt one.
    const f = vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'S200',
      json: async () => { throw new SyntaxError('Unexpected token < in JSON at position 0') },
    })) as unknown as typeof fetch
    const c = createRestClient('/rest/likec4/1.0', f)
    await expect(c.resolve('grp/repo')).rejects.toBeInstanceOf(RestError)
    await expect(c.resolve('grp/repo')).rejects.toThrow(/not valid JSON/)
  })

  it('aborts when the external signal fires (FE-I3)', async () => {
    const ctrl = new AbortController()
    const c = createRestClient('/rest/likec4/1.0', hangingFetch())
    const p = c.fetchSource('grp/repo', 'main', undefined, ctrl.signal)
    ctrl.abort()
    await expect(p).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('rejects a malformed resolve body instead of returning sha:undefined (FE-I2)', async () => {
    const c = createRestClient('/rest/likec4/1.0', fakeFetch(200, {}))
    await expect(c.resolve('grp/repo')).rejects.toBeInstanceOf(RestError)
  })

  it('rejects a non-object (HTML error page) source body (FE-I2)', async () => {
    const c = createRestClient('/rest/likec4/1.0', fakeFetch(200, '<html>503</html>'))
    await expect(c.fetchSource('grp/repo', 'main', undefined)).rejects.toBeInstanceOf(RestError)
  })

  it('rejects a source body missing files (FE-I2)', async () => {
    const c = createRestClient('/rest/likec4/1.0', fakeFetch(200, { sha: 'abc' }))
    await expect(c.fetchSource('grp/repo', 'main', undefined)).rejects.toBeInstanceOf(RestError)
  })

  it('rejects a source body whose file contents are not strings', async () => {
    // files is typed Record<string,string>; a non-string value must be rejected at the boundary rather
    // than flowing untyped into compute()/JSON5.parse.
    const c = createRestClient('/rest/likec4/1.0', fakeFetch(200, { sha: 'abc', files: { 'a.likec4': 42 } }))
    await expect(c.fetchSource('grp/repo', 'main', undefined)).rejects.toBeInstanceOf(RestError)
  })

  it('strips prototype-polluting keys from the files map (parity with the .snap sanitisation)', async () => {
    // A crafted / MITM'd GitLab response could carry __proto__/constructor/prototype as OWN keys in
    // files. JSON.parse surfaces a literal "__proto__" as an own enumerable property, exactly as the
    // real res.json() path would; constructor/prototype are ordinary own keys. All three must be
    // dropped, the legitimate file kept, and no prototype polluted — mirroring compute.ts's .snap
    // sanitisation for the same untrusted GitLab source.
    const poisoned = JSON.parse(
      '{"sha":"abc","files":{"__proto__":"p","constructor":"c","prototype":"y","index.c4":"real"}}',
    )
    const c = createRestClient('/rest/likec4/1.0', fakeFetch(200, poisoned))
    const res = await c.fetchSource('grp/repo', 'main', undefined)
    expect(res.files).toEqual({ 'index.c4': 'real' })
    expect(Object.keys(res.files)).not.toContain('constructor')
    expect(Object.keys(res.files)).not.toContain('prototype')
    expect(({} as Record<string, unknown>).p).toBeUndefined() // no prototype write occurred
  })

  it('aborts immediately when the caller signal is ALREADY aborted before the call (no request survives)', async () => {
    // React double-invoke / unmount-before-request: the effect's controller aborted before getJson even
    // ran. getJson's `if (signal.aborted) ctrl.abort()` fast-path aborts the composed controller up front,
    // so the platform fetch (given an already-aborted signal) rejects at once and never yields a body. The
    // rejection must surface as an AbortError — not a RestError — so the pipeline maps it to `unreachable`
    // and the unmounted caller's `alive` guard drops it, rather than a spurious network/malformed error.
    // A real `fetch` rejects synchronously when handed an already-aborted signal (the abort EVENT never
    // re-fires for a signal aborted before the listener attaches — the shared hangingFetch would hang);
    // model that faithfully here by inspecting init.signal.aborted at call time.
    const abortingFetch = vi.fn((_url: any, init: any) => {
      const sig = init?.signal as AbortSignal | undefined
      if (sig?.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'))
      return new Promise((_res, reject) =>
        sig?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError'))),
      )
    }) as unknown as typeof fetch
    const ctrl = new AbortController()
    ctrl.abort()
    const c = createRestClient('/rest/likec4/1.0', abortingFetch)
    await expect(c.fetchSource('grp/repo', 'main', undefined, ctrl.signal)).rejects.toMatchObject({
      name: 'AbortError',
    })
    // And specifically NOT a RestError (would mislabel an unmount as a network fault).
    await expect(c.fetchSource('grp/repo', 'main', undefined, ctrl.signal)).rejects.not.toBeInstanceOf(RestError)
  })

  it('surfaces a genuine malformed-body RestError even when the caller signal is already aborted', async () => {
    // The body arrived and is malformed (a real server fault); the caller having since unmounted must
    // not mask that as an AbortError — the error is real regardless of the abort.
    const ctrl = new AbortController()
    ctrl.abort()
    const c = createRestClient('/rest/likec4/1.0', fakeFetch(200, {}))
    await expect(c.resolve('grp/repo', 'main', ctrl.signal)).rejects.toBeInstanceOf(RestError)
  })

  it('resolves the context path lazily so a late-initialised AJS is reflected (FE-M3)', () => {
    const g = globalThis as any
    const had = 'window' in g
    const prev = g.window
    try {
      g.window = {}
      expect(defaultBase()).toBe('/rest/likec4/1.0')
      g.window = { AJS: { contextPath: () => '/wiki' } }
      expect(defaultBase()).toBe('/wiki/rest/likec4/1.0')
    } finally {
      if (had) g.window = prev
      else delete g.window
    }
  })

  it('strips a trailing slash from the context path so the REST base has no double slash', () => {
    // AJS.contextPath() normally returns no trailing slash, but a reverse-proxy / config can hand back
    // one ('/wiki/'), and a root context could return a bare '/'. The base must stay well-formed (no '//')
    // rather than relying on the server to collapse double slashes.
    const g = globalThis as any
    const had = 'window' in g
    const prev = g.window
    try {
      g.window = { AJS: { contextPath: () => '/wiki/' } }
      expect(defaultBase()).toBe('/wiki/rest/likec4/1.0')
      g.window = { AJS: { contextPath: () => '/' } }
      expect(defaultBase()).toBe('/rest/likec4/1.0')
    } finally {
      if (had) g.window = prev
      else delete g.window
    }
  })

  it('warns when AJS.contextPath is unavailable so an origin-relative base is diagnosable, not silent', () => {
    // Parity with boot-loader.js / editor-loader.js, which warn when AJS.contextPath() is unavailable:
    // without it the base is built origin-relative, which is WRONG under a non-root context path. Warn
    // rather than degrade silently. A genuinely root-deployed Confluence still HAS AJS.contextPath (it
    // returns '') so the warn must NOT fire for that legitimate case — only when AJS itself is missing.
    const g = globalThis as any
    const had = 'window' in g
    const prev = g.window
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      // AJS entirely absent (a load-order fault) -> origin-relative base AND a warning.
      g.window = { console }
      expect(defaultBase()).toBe('/rest/likec4/1.0')
      expect(warn).toHaveBeenCalledTimes(1)
      expect(warn.mock.calls[0][0]).toMatch(/AJS\.contextPath/)

      // A legitimate root deploy (contextPath present, returns '') must NOT warn.
      warn.mockClear()
      g.window = { console, AJS: { contextPath: () => '' } }
      expect(defaultBase()).toBe('/rest/likec4/1.0')
      expect(warn).not.toHaveBeenCalled()

      // A non-root deploy: no warn either.
      g.window = { console, AJS: { contextPath: () => '/wiki' } }
      expect(defaultBase()).toBe('/wiki/rest/likec4/1.0')
      expect(warn).not.toHaveBeenCalled()
    } finally {
      warn.mockRestore()
      if (had) g.window = prev
      else delete g.window
    }
  })
})
