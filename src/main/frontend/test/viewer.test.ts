// @vitest-environment jsdom
//
// Unit coverage for Viewer.tsx's OWN effect logic — the loading->ok/error state machine and, most
// importantly, the abort/alive/re-fire cleanup: a stale load (unmount or a param change) must abort its
// AbortController so it frees one of the few compute workers, AND its late result must be suppressed
// (the `alive` guard) so it never wins over the fresh load or setState()s after unmount. loadModel and
// the lazily-imported Diagram are stubbed so this stays a fast DOM-only test; the real render is covered
// by the live e2e gate. Uses createElement + act (this is a .test.ts, no JSX) — mirrors diagram.test.ts.
import { act, createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DiagramParams } from '../src/dataAttrs'
import type { PipelineDeps } from '../src/pipeline'

// A controllable loadModel: it records each call's AbortSignal and returns a promise we settle from the
// test, so the loading->ok/error transitions and the abort-on-change behaviour are fully deterministic.
const pipeline = vi.hoisted(() => ({
  signals: [] as AbortSignal[],
  settlers: [] as Array<{ resolve: (r: unknown) => void; reject: (e: unknown) => void }>,
  loadModel(_params: unknown, _deps: unknown, signal: AbortSignal) {
    this.signals.push(signal)
    return new Promise((resolve, reject) => this.settlers.push({ resolve, reject }))
  },
}))
vi.mock('../src/pipeline', () => ({
  loadModel: (p: unknown, d: unknown, s: AbortSignal) => pipeline.loadModel(p, d, s),
}))
// Stub the lazily-imported Diagram so OkView's <Suspense> resolves without the heavy likec4 render deps.
vi.mock('../src/Diagram', () => ({
  default: (props: { startView: string }) =>
    createElement('div', { 'data-testid': 'stub-diagram', 'data-view': props.startView }),
}))

import { Viewer } from '../src/Viewer'

const deps = {} as unknown as PipelineDeps
const paramsA: DiagramParams = { project: 'grp/a', ref: 'main', path: '', view: 'index', instance: undefined }
const paramsB: DiagramParams = { project: 'grp/b', ref: 'main', path: '', view: 'index', instance: undefined }

const ok = (sha: string, startView: string) =>
  ({ kind: 'ok' as const, sha, data: {}, viewIds: [startView], startView, drifts: [], fromCache: false })

let container: HTMLElement
let root: Root

function render(p: DiagramParams) {
  act(() => root.render(createElement(Viewer, { params: p, deps })))
}
function mount(p: DiagramParams = paramsA) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  render(p)
}
const flush = () => act(async () => {}) // drain microtasks (the lazy import + effect .then callbacks)
const qs = (testid: string) => container.querySelector(`[data-testid="${testid}"]`)

afterEach(() => {
  try {
    act(() => root.unmount())
  } catch {
    /* a test that already unmounted — fine */
  }
  document.body.innerHTML = ''
  pipeline.signals.length = 0
  pipeline.settlers.length = 0
  vi.restoreAllMocks()
})

describe('Viewer', () => {
  it('shows the loading state, then the ok subtree when the model resolves', async () => {
    mount()
    expect(qs('likec4-loading')).not.toBeNull()
    expect(qs('stub-diagram')).toBeNull()

    await act(async () => pipeline.settlers[0].resolve(ok('sha-1', 'index')))
    await flush() // let the lazy Diagram import settle out of the Suspense fallback

    expect(qs('stub-diagram')).not.toBeNull()
    expect(qs('stub-diagram')!.getAttribute('data-view')).toBe('index')
  })

  it('shows the error panel when the load rejects', async () => {
    mount()
    await act(async () => pipeline.settlers[0].reject(new Error('boom')))
    const panel = qs('likec4-error')
    expect(panel).not.toBeNull()
    expect(panel!.textContent).toContain('boom')
    expect(qs('likec4-loading')).toBeNull()
  })

  it('aborts the in-flight load on unmount so it frees its worker', () => {
    mount()
    const signal = pipeline.signals[0]
    expect(signal.aborted).toBe(false)
    act(() => root.unmount())
    expect(signal.aborted).toBe(true)
  })

  it('aborts the prior load and re-fires on a param change, suppressing the stale result', async () => {
    mount(paramsA) // load #1 in flight
    expect(pipeline.signals).toHaveLength(1)

    render(paramsB) // a different project re-runs the effect
    expect(pipeline.signals).toHaveLength(2)
    expect(pipeline.signals[0].aborted).toBe(true) // prior controller aborted -> its worker is freed
    expect(pipeline.signals[1].aborted).toBe(false)

    // The `alive` guard: resolving the STALE first load must NOT win over the fresh in-flight one.
    await act(async () => pipeline.settlers[0].resolve(ok('stale', 'index')))
    await flush()
    expect(qs('stub-diagram')).toBeNull() // stale result suppressed — still loading
    expect(qs('likec4-loading')).not.toBeNull()

    // The fresh load resolves and renders.
    await act(async () => pipeline.settlers[1].resolve(ok('fresh', 'detail')))
    await flush()
    expect(qs('stub-diagram')!.getAttribute('data-view')).toBe('detail')
  })
})
