import { describe, expect, it, vi } from 'vitest'
import { loadModel, type PipelineDeps } from '../src/pipeline'
import { RestError } from '../src/restClient'
import { TimeoutError } from '../src/workerPool'

const okData = { views: { index: { id: 'index' }, sys_detail: { id: 'sys_detail' } } }
const okResponse = { data: okData, errors: [], drifts: [], computeMs: 5 }

function deps(over: Partial<{
  resolve: any; fetchSource: any; get: any; put: any; run: any; onCompute: any
}> = {}): { deps: PipelineDeps; spies: any } {
  const spies = {
    resolve: vi.fn(over.resolve ?? (async () => ({ sha: 'sha1' }))),
    fetchSource: vi.fn(over.fetchSource ?? (async () => ({ sha: 'sha1', files: { 'm.likec4': 'x' } }))),
    get: vi.fn(over.get ?? (async () => null)),
    put: vi.fn(over.put ?? (async () => {})),
    run: vi.fn(over.run ?? (async () => okResponse)),
    onCompute: vi.fn(over.onCompute ?? (() => {})),
  }
  return {
    spies,
    deps: {
      rest: { resolve: spies.resolve, fetchSource: spies.fetchSource } as any,
      cache: { get: spies.get, put: spies.put } as any,
      pool: { run: spies.run } as any,
      onCompute: spies.onCompute,
      timeoutMs: 1000,
    },
  }
}

describe('loadModel', () => {
  it('renders from cache without fetching or computing', async () => {
    const { deps: d, spies } = deps({ get: async () => ({ key: '4:sha1:', data: okData, errors: [], drifts: [], accessedAt: 1 }) })
    const r = await loadModel({ project: 'p' }, d)
    expect(r).toMatchObject({ kind: 'ok', fromCache: true, startView: 'index' })
    expect(spies.fetchSource).not.toHaveBeenCalled()
    expect(spies.run).not.toHaveBeenCalled()
    expect(spies.onCompute).not.toHaveBeenCalled()
  })

  it('fetches, computes, caches on a miss', async () => {
    const { deps: d, spies } = deps()
    const r = await loadModel({ project: 'p', path: 'sub' }, d)
    expect(r).toMatchObject({ kind: 'ok', fromCache: false })
    expect(spies.onCompute).toHaveBeenCalledTimes(1)
    expect(spies.put).toHaveBeenCalledWith('4:sha1:sub', { data: okData, errors: [], drifts: [] })
  })

  it('maps resolve 404 to not-found', async () => {
    const { deps: d } = deps({ resolve: async () => { throw new RestError(404, 'nf') } })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'not-found' })
  })

  it('maps a network failure to unreachable', async () => {
    const { deps: d } = deps({ resolve: async () => { throw new TypeError('network') } })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'unreachable' })
  })

  it('maps a source-stage 404 to not-found with the source-specific detail', async () => {
    // The resolve-stage 404 is covered above; the SOURCE-stage fetch (a cache miss whose fetchSource
    // 404s) goes through the same mapFetchError but with stage='source', which yields a DIFFERENT detail
    // ('Source path not found' vs 'Project or ref not found'). That branch was previously untested.
    const { deps: d, spies } = deps({ fetchSource: async () => { throw new RestError(404, 'nf') } })
    expect(await loadModel({ project: 'p', path: 'sub' }, d))
      .toMatchObject({ kind: 'not-found', detail: 'Source path not found' })
    expect(spies.run).not.toHaveBeenCalled() // never computed on a source fetch failure
  })

  it('maps a source-stage non-404 RestError to unreachable with the status', async () => {
    const { deps: d } = deps({ fetchSource: async () => { throw new RestError(502, 'bad gateway') } })
    expect(await loadModel({ project: 'p', path: 'sub' }, d))
      .toMatchObject({ kind: 'unreachable', detail: 'Source repository error (502)' })
  })

  it('maps a source-stage network failure to unreachable', async () => {
    const { deps: d } = deps({ fetchSource: async () => { throw new TypeError('network') } })
    expect(await loadModel({ project: 'p', path: 'sub' }, d))
      .toMatchObject({ kind: 'unreachable', detail: 'Cannot reach source repository' })
  })

  it('surfaces parse errors', async () => {
    const errors = [{ message: 'bad', line: 2, sourceFsPath: 'm.likec4' }]
    const { deps: d } = deps({ run: async () => ({ data: okData, errors, drifts: [], computeMs: 1 }) })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'parse-error', errors })
  })

  it('does not count a compute that produced only parse errors — onCompute fires only for a usable model', async () => {
    // onCompute's contract is "once per compute that yields a model". A run that completed but produced
    // validation errors (parse-error) yields no usable model, so it must NOT increment the counter — the
    // same exclusion applied to a crashed (computeError) or timed-out run above.
    const errors = [{ message: 'bad', line: 2, sourceFsPath: 'm.likec4' }]
    const { deps: d, spies } = deps({ run: async () => ({ data: okData, errors, drifts: [], computeMs: 1 }) })
    await loadModel({ project: 'p' }, d)
    expect(spies.onCompute).not.toHaveBeenCalled()
  })

  it('a throwing onCompute callback does not turn a successful compute into an error', async () => {
    // onCompute is a diagnostic side-effect (boot wires a window counter for e2e). It must never be able
    // to fail the render: a throwing callback on an otherwise-successful compute must still return `ok`,
    // not be caught by the compute try/catch and reported as `kind: 'error'` (which would also discard the
    // produced model). Pin that the throw is swallowed and the model is still returned + cached.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { deps: d, spies } = deps({ onCompute: () => { throw new Error('counter exploded') } })
    const r = await loadModel({ project: 'p', path: 'sub' }, d)
    expect(r).toMatchObject({ kind: 'ok', fromCache: false, startView: 'index' })
    expect(spies.put).toHaveBeenCalledWith('4:sha1:sub', { data: okData, errors: [], drifts: [] })
    warn.mockRestore()
  })

  it('reports an unknown requested view with the available list', async () => {
    const { deps: d } = deps()
    expect(await loadModel({ project: 'p', view: 'nope' }, d))
      .toMatchObject({ kind: 'unknown-view', requested: 'nope', available: ['index', 'sys_detail'] })
  })

  it('maps a compute timeout to too-large', async () => {
    const { deps: d } = deps({ run: async () => { throw new TimeoutError() } })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'too-large' })
  })

  it('does not count a compute that times out — onCompute fires only after a completed run', async () => {
    // onCompute is the e2e compute counter. A timed-out/aborted run (and, under StrictMode's dev
    // double-invoke, the aborted first attempt) must NOT increment it; only a completed compute counts.
    const { deps: d, spies } = deps({ run: async () => { throw new TimeoutError() } })
    await loadModel({ project: 'p' }, d)
    expect(spies.onCompute).not.toHaveBeenCalled()
  })

  it('degrades to recompute when the cache get/put reject — never fails the render (FE-C1)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const { deps: d, spies } = deps({
      get: async () => { throw new Error('idb get exploded (private mode / quota)') },
      put: async () => { throw new Error('idb put exploded') },
    })
    const r = await loadModel({ project: 'p' }, d)
    expect(r).toMatchObject({ kind: 'ok', fromCache: false })
    expect(spies.onCompute).toHaveBeenCalledTimes(1) // compute still ran
    warn.mockRestore()
  })

  it('threads the abort signal into rest + pool (FE-I3)', async () => {
    const controller = new AbortController()
    const { deps: d, spies } = deps()
    await loadModel({ project: 'p', path: 'sub' }, d, controller.signal)
    expect(spies.resolve).toHaveBeenCalledWith('p', undefined, controller.signal)
    // fetchSource is pinned to the sha /resolve returned ('sha1'), not the request's mutable ref.
    expect(spies.fetchSource).toHaveBeenCalledWith('p', 'sha1', 'sub', controller.signal)
    const runSignal = spies.run.mock.calls[0][1].signal as AbortSignal
    expect(runSignal).toBe(controller.signal)
    controller.abort()
    expect(runSignal.aborted).toBe(true)
  })

  it('pins /source to the sha resolved from /resolve, not the mutable ref (resolve->source TOCTOU)', async () => {
    // /resolve turns the mutable ref ('main') into an immutable sha; /source must be fetched by that
    // sha so a push landing between the two legs cannot serve new content keyed under the old sha (the
    // cache is content-addressed by sha, and `ok.sha` is the React remount key). A full sha is itself a
    // valid ref, so the server resolves it to itself.
    const { deps: d, spies } = deps({ resolve: async () => ({ sha: 'deadbeefcafe' }) })
    await loadModel({ project: 'p', ref: 'main', path: 'sub' }, d)
    expect(spies.resolve).toHaveBeenCalledWith('p', 'main', undefined)
    expect(spies.fetchSource).toHaveBeenCalledWith('p', 'deadbeefcafe', 'sub', undefined)
  })

  it('maps a thrown compute (computeError) to error, not parse-error (FE-M4)', async () => {
    const { deps: d } = deps({
      run: async () => ({ data: null, errors: [], drifts: [], computeMs: 1, computeError: 'TypeError: boom' }),
    })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'error', detail: 'TypeError: boom' })
  })

  it('coerces a malformed worker response (missing errors/drifts) instead of throwing (FE-M3)', async () => {
    // worker.ts always posts the full {data,errors,drifts} shape, but pool.run() casts the message
    // `as WorkerResponse` without validating it. A non-conforming response (a future worker change that
    // drops `errors`) must degrade gracefully — `errors.length` runs OUTSIDE the compute try, so an
    // undefined `errors` would otherwise throw an uncaught TypeError out of loadModel.
    const { deps: d } = deps({ run: async () => ({ data: okData, computeMs: 1 }) as any })
    const r = await loadModel({ project: 'p' }, d)
    expect(r).toMatchObject({ kind: 'ok', startView: 'index' })
  })

  it('maps a valid model that declares no views to a clear error', async () => {
    // A model that parses cleanly but has zero views must surface a clear error, not an out-of-bounds
    // startView (viewIds[0] === undefined). Covers pipeline's viewIds.length === 0 branch.
    const { deps: d } = deps({ run: async () => ({ data: { views: {} }, errors: [], drifts: [], computeMs: 1 }) })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'error', detail: 'Model has no views' })
  })

  it('maps a poisoned dump whose views is not a plain object to the empty-views error, not fake index ids', async () => {
    // A corrupt cache row (or a malformed worker response) whose `data.views` is a string/array would
    // make Object.keys() return positional index keys ('0','1',…) — spurious "view ids" that pass the
    // length check, get reported as `ok`, then crash LikeC4Model.create. Guard `views` to a plain
    // object so a non-object degrades to the same clean "Model has no views" error as `views: {}`.
    const asString = deps({ run: async () => ({ data: { views: 'ab' }, errors: [], drifts: [], computeMs: 1 }) })
    expect(await loadModel({ project: 'p' }, asString.deps)).toMatchObject({ kind: 'error', detail: 'Model has no views' })
    const asArray = deps({ run: async () => ({ data: { views: ['a', 'b'] }, errors: [], drifts: [], computeMs: 1 }) })
    expect(await loadModel({ project: 'p' }, asArray.deps)).toMatchObject({ kind: 'error', detail: 'Model has no views' })
  })

  it('surfaces a thrown compute error via its clean message, not "Error: ..." (messageOf)', async () => {
    // A pool.run() that THROWS a generic Error (not a TimeoutError, not a computeError RESULT) must
    // surface the Error's message via messageOf — String(e) would prefix a redundant "Error:".
    const { deps: d } = deps({ run: async () => { throw new Error('compute exploded') } })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'error', detail: 'compute exploded' })
  })

  it('does not count a crashed compute (computeError) — onCompute fires only for a produced model', async () => {
    // A computeError run RAN in the worker but produced no usable model. It must not increment the e2e
    // compute counter (which is meant to count real, successful computes), just as a timeout does not.
    const { deps: d, spies } = deps({
      run: async () => ({ data: null, errors: [], drifts: [], computeMs: 1, computeError: 'TypeError: boom' }),
    })
    await loadModel({ project: 'p' }, d)
    expect(spies.onCompute).not.toHaveBeenCalled()
  })
})
