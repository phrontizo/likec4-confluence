// @vitest-environment jsdom
//
// Unit coverage for mountEditor.tsx's internal `Editor` component — the load→select→write-back state
// machine: it fires onSelect with the start view on a successful load, routes ViewPicker onChange through
// onSelect while updating the selection, honours the LATEST onSelect via the onSelectRef freshness
// indirection (a re-render with a new onSelect identity, NOT a param change, must not be a stale closure),
// aborts its in-flight AbortController on unmount, and degrades a load rejection to the error branch via
// its defensive .catch. loadModel and ViewPicker are stubbed so this stays a fast DOM-only test; the real
// picker + render are covered by the live e2e gate. Mirrors viewer.test.ts (createElement + act, no JSX).
//
// `Editor` is module-private (only mountViewPicker is exported). To drive re-renders with a changing
// onSelect prop (the freshness case) we render the SAME `Editor` component in our OWN controllable root;
// its reference is recovered once (beforeAll) from the element tree mountViewPicker renders, captured by
// stubbing createRoot's render. Every case then renders that recovered component directly.
import { act, createElement, isValidElement, type ReactNode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { DiagramParams } from '../src/dataAttrs'
import type { PipelineDeps } from '../src/pipeline'

// Capture the very first React element tree mountViewPicker renders, so we can recover the module-private
// `Editor` from it. ESM namespaces aren't spyable, so wrap createRoot via a vi.mock that delegates to the
// real react-dom/client and records the first-rendered tree. `mount()` below uses the same (real) root, so
// the wrapper is transparent to the actual test mounts.
const rootCapture = vi.hoisted(() => ({ firstTree: undefined as unknown }))
vi.mock('react-dom/client', async (importActual) => {
  const actual = await importActual<typeof import('react-dom/client')>()
  return {
    ...actual,
    createRoot(container: Element | DocumentFragment, options?: unknown) {
      const root = actual.createRoot(container, options as never)
      const origRender = root.render.bind(root)
      return {
        ...root,
        render(node: ReactNode) {
          if (rootCapture.firstTree === undefined) rootCapture.firstTree = node
          origRender(node)
        },
        unmount: () => root.unmount(),
      } as Root
    },
  }
})

// A controllable loadModel that records each call's AbortSignal and returns a promise we settle from the
// test, so the load transitions and abort-on-unmount are fully deterministic (same shape as viewer.test).
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
// Stub ViewPicker so we can read the props Editor passes (selected) and drive its onChange. It renders a
// marker carrying the current selection so a test can assert the selection updated.
const picker = vi.hoisted(() => ({ props: null as { selected: string; onChange: (id: string) => void } | null }))
vi.mock('../src/editor/ViewPicker', () => ({
  ViewPicker: (props: { selected: string; onChange: (id: string) => void }) => {
    picker.props = props
    return createElement('div', { 'data-testid': 'stub-picker', 'data-selected': props.selected })
  },
}))

import { mountViewPicker, type MountOptions } from '../src/editor/mountEditor'

const deps = {} as unknown as PipelineDeps
const params: DiagramParams = { project: 'grp/a', ref: 'main', path: '', view: undefined, instance: undefined }

// A successful LoadResult whose data carries two views so listViews (real) feeds the stub picker options.
const ok = (startView: string) => ({
  kind: 'ok' as const,
  sha: 'sha-1',
  data: { views: { index: { id: 'index', title: 'Index' }, detail: { id: 'detail', title: 'Detail' } } },
  viewIds: ['index', 'detail'],
  startView,
  drifts: [],
  fromCache: false,
})

// Walk the element tree mountViewPicker renders (StrictMode > ErrorBoundary > Editor) down to the Editor
// function component — identified as the function-typed element carrying our onSelect prop.
function findEditor(node: unknown): ((opts: MountOptions) => ReactNode) | null {
  if (!isValidElement(node)) return null
  const el = node as { type: unknown; props: { children?: unknown } }
  if (typeof el.type === 'function' && 'onSelect' in (el.props as object)) {
    return el.type as (opts: MountOptions) => ReactNode
  }
  return findEditor((el.props as { children?: unknown }).children)
}

// Recover Editor once, before any test: mount+immediately destroy a throwaway picker and pull Editor out
// of the first captured tree.
let Editor: (opts: MountOptions) => ReactNode
beforeAll(() => {
  const throwaway = document.createElement('div')
  document.body.appendChild(throwaway)
  let handle: { destroy(): void } | undefined
  act(() => { handle = mountViewPicker(throwaway, { params, deps, onSelect: () => {} }) })
  act(() => handle!.destroy())
  throwaway.remove()
  // Drain the throwaway mount's in-flight load so its (unresolved) settler/signal don't leak into a test.
  pipeline.signals.length = 0
  pipeline.settlers.length = 0
  const found = findEditor(rootCapture.firstTree)
  if (!found) throw new Error('could not recover the Editor component from the mountViewPicker tree')
  Editor = found
})

let container: HTMLElement
let root: Root

function mount(node: ReactNode) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => root.render(node))
}
const flush = () => act(async () => {})
const qs = (testid: string) => container.querySelector(`[data-testid="${testid}"]`)

afterEach(() => {
  try {
    act(() => root.unmount())
  } catch {
    /* already unmounted */
  }
  document.body.innerHTML = ''
  pipeline.signals.length = 0
  pipeline.settlers.length = 0
  picker.props = null
  vi.restoreAllMocks()
})

describe('mountEditor Editor', () => {
  it('recovered the module-private Editor component', () => {
    expect(typeof Editor).toBe('function')
  })

  it('fires onSelect once with the start view on a successful load', async () => {
    const onSelect = vi.fn()
    mount(createElement(Editor, { params, deps, onSelect }))
    expect(qs('likec4-loading')).not.toBeNull()
    expect(onSelect).not.toHaveBeenCalled() // not until the model actually resolves

    await act(async () => pipeline.settlers[0].resolve(ok('index')))
    await flush()

    expect(qs('stub-picker')).not.toBeNull()
    expect(qs('stub-picker')!.getAttribute('data-selected')).toBe('index')
    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(onSelect).toHaveBeenLastCalledWith('index')
  })

  it('a ViewPicker onChange fires onSelect and updates the shown selection', async () => {
    const onSelect = vi.fn()
    mount(createElement(Editor, { params, deps, onSelect }))
    await act(async () => pipeline.settlers[0].resolve(ok('index')))
    await flush()
    expect(onSelect).toHaveBeenCalledTimes(1) // the start-view call

    // Drive the picker's onChange as a user selecting a different view.
    await act(async () => picker.props!.onChange('detail'))
    expect(onSelect).toHaveBeenLastCalledWith('detail')
    expect(onSelect).toHaveBeenCalledTimes(2)
    expect(qs('stub-picker')!.getAttribute('data-selected')).toBe('detail') // selection state advanced
  })

  it('honours the LATEST onSelect via the ref (a changed onSelect identity is not a stale closure)', async () => {
    const first = vi.fn()
    const second = vi.fn()
    // Render with `first`, then re-render the SAME root with `second` BEFORE the load resolves. The load
    // effect keyed on primitive params does not re-run, but the ref-updating effect swaps in `second`.
    mount(createElement(Editor, { params, deps, onSelect: first }))
    act(() => root.render(createElement(Editor, { params, deps, onSelect: second })))

    // The load resolves after the identity swap: the start-view callback must go to `second`, not `first`.
    await act(async () => pipeline.settlers[0].resolve(ok('index')))
    await flush()
    expect(first).not.toHaveBeenCalled() // a stale closure would have called this
    expect(second).toHaveBeenCalledTimes(1)
    expect(second).toHaveBeenLastCalledWith('index')

    // And a later selection also honours the latest onSelect.
    await act(async () => picker.props!.onChange('detail'))
    expect(first).not.toHaveBeenCalled()
    expect(second).toHaveBeenLastCalledWith('detail')
  })

  it('resets to loading when the primitive params change so a stale model is not shown (parity with Viewer)', async () => {
    const onSelect = vi.fn()
    mount(createElement(Editor, { params, deps, onSelect }))
    await act(async () => pipeline.settlers[0].resolve(ok('index')))
    await flush()
    expect(qs('stub-picker')).not.toBeNull() // the first model is shown

    // Re-render the SAME root with a CHANGED primitive param (a different path). The load effect re-runs;
    // without the loading reset the previously-loaded picker would linger until the new load resolves.
    const changed = { ...params, path: 'other' }
    act(() => root.render(createElement(Editor, { params: changed, deps, onSelect })))
    expect(qs('likec4-loading')).not.toBeNull() // reset to loading immediately...
    expect(qs('stub-picker')).toBeNull() // ...not the stale picker

    // The second load resolves to the fresh model.
    await act(async () => pipeline.settlers[1].resolve(ok('detail')))
    await flush()
    expect(qs('stub-picker')).not.toBeNull()
    expect(qs('stub-picker')!.getAttribute('data-selected')).toBe('detail')
  })

  it('aborts the in-flight load controller on unmount so it frees its worker', () => {
    mount(createElement(Editor, { params, deps, onSelect: () => {} }))
    const signal = pipeline.signals[0]
    expect(signal.aborted).toBe(false)
    act(() => root.unmount())
    expect(signal.aborted).toBe(true)
  })

  it('renders the error branch when the load rejects (the defensive .catch)', async () => {
    const onSelect = vi.fn()
    mount(createElement(Editor, { params, deps, onSelect }))
    await act(async () => pipeline.settlers[0].reject(new Error('boom')))
    await flush()
    const err = qs('likec4-error')
    expect(err).not.toBeNull()
    expect(err!.textContent).toContain('boom')
    expect(qs('stub-picker')).toBeNull() // no picker on the error path
    expect(onSelect).not.toHaveBeenCalled() // a rejected load never selects
  })
})
