// @vitest-environment jsdom
//
// Unit coverage for Diagram.tsx's OWN logic — the fullscreen Escape-key effect (add/remove the window
// keydown listener, with cleanup so a page of many diagrams doesn't leak a listener per mount) and the
// drift-banner selection. The heavy likec4 render deps are stubbed so we can mount the component; the
// actual diagram rendering is covered by the live e2e gate. Uses createElement (this is a .test.ts, so
// no JSX) + React's act to drive the real component + hooks in jsdom.
import { act, createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'

// Capture the props LikeC4Diagram is rendered with so a test can drive its onNavigateTo callback.
const diagram = vi.hoisted(() => ({ props: null as { onNavigateTo?: (to: unknown) => void } | null }))
// The model's findView is controllable per-test: it defaults to the truthy behaviour every existing test
// relies on (a view always exists), but a test can swap it to return undefined to hit the "view not
// found" fallback branch. Restored in afterEach.
const modelMock = vi.hoisted(() => ({
  findView: (id: string): unknown => ({ $view: { id } }),
}))
const defaultFindView = (id: string): unknown => ({ $view: { id } })
vi.mock('likec4/react', () => ({
  LikeC4ModelProvider: ({ children }: { children: unknown }) => children,
  LikeC4Diagram: (props: { onNavigateTo?: (to: unknown) => void }) => {
    diagram.props = props
    return null
  },
  useLikeC4Model: () => ({ findView: (id: string) => modelMock.findView(id) }),
}))
vi.mock('@likec4/core/model', () => ({ LikeC4Model: { create: (d: unknown) => d } }))
vi.mock('likec4-app-style.css', () => ({}))

import Diagram from '../src/Diagram'

let container: HTMLElement
let root: Root

afterEach(() => {
  act(() => root.unmount())
  container.remove()
  modelMock.findView = defaultFindView // restore the truthy default for the next test
  vi.restoreAllMocks()
})

function mount(props: { data: unknown; startView: string; drifts: unknown[] }) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => root.render(createElement(Diagram, props as never)))
}

const viewerClass = () => container.querySelector('[data-testid="likec4-diagram"]')!.className

describe('Diagram', () => {
  it('toggles fullscreen and exits on Escape, removing the window keydown listener on cleanup', () => {
    const add = vi.spyOn(window, 'addEventListener')
    const remove = vi.spyOn(window, 'removeEventListener')
    mount({ data: {}, startView: 'index', drifts: [] })
    expect(viewerClass()).not.toContain('likec4-fullscreen')

    const toggle = container.querySelector('[data-testid="likec4-fullscreen-toggle"]') as HTMLButtonElement
    act(() => toggle.click())
    expect(viewerClass()).toContain('likec4-fullscreen')
    expect(add).toHaveBeenCalledWith('keydown', expect.any(Function)) // listener wired only while fullscreen

    act(() => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })))
    expect(viewerClass()).not.toContain('likec4-fullscreen') // Escape exits -> the listener really fires
    expect(remove).toHaveBeenCalledWith('keydown', expect.any(Function)) // effect cleanup removed it (no leak)
  })

  it('exposes the fullscreen toggle state via aria-pressed for assistive tech', () => {
    mount({ data: {}, startView: 'index', drifts: [] })
    const toggle = container.querySelector('[data-testid="likec4-fullscreen-toggle"]') as HTMLButtonElement
    expect(toggle.getAttribute('aria-pressed')).toBe('false') // a two-state control, not pressed yet
    act(() => toggle.click())
    expect(toggle.getAttribute('aria-pressed')).toBe('true') // now fullscreen -> pressed
    act(() => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })))
    expect(toggle.getAttribute('aria-pressed')).toBe('false') // Escape exits -> back to not pressed
  })

  it('locks body scroll while fullscreen and restores the host\'s prior overflow on exit', () => {
    document.body.style.overflow = 'auto' // a value the host page set; the effect must restore it verbatim
    mount({ data: {}, startView: 'index', drifts: [] })
    expect(document.body.style.overflow).toBe('auto') // not touched until fullscreen

    const toggle = container.querySelector('[data-testid="likec4-fullscreen-toggle"]') as HTMLButtonElement
    act(() => toggle.click())
    expect(document.body.style.overflow).toBe('hidden') // locked while the fixed overlay is up

    // Exit runs the SAME effect cleanup an unmount would, so this also proves unmount restoration: the
    // host's prior value is restored verbatim ('auto'), NOT blanked to '' (which would drop a host style).
    act(() => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })))
    expect(document.body.style.overflow).toBe('auto')
    document.body.style.overflow = '' // reset shared jsdom state so it can't leak into the next test
  })

  it('keeps the body scroll-lock until the LAST of two fullscreen diagrams exits', () => {
    // A page can hold many diagrams, each with its own `position: fixed; inset: 0` fullscreen overlay, so
    // two can be fullscreen at once. The body scroll-lock is ref-counted: exiting ONE while another is
    // still fullscreen must NOT unlock the host page (which would let it scroll behind the other overlay),
    // and the host's original overflow is restored only when the LAST one exits. This would FAIL with a
    // per-effect save/restore (A's exit would restore the overflow while B is still an overlay).
    document.body.style.overflow = 'auto' // a host-set value, restored only at the very end
    mount({ data: {}, startView: 'index', drifts: [] }) // diagram A -> module root/container (afterEach cleans it)
    const containerA = container
    const containerB = document.createElement('div')
    document.body.appendChild(containerB)
    const rootB = createRoot(containerB)
    act(() => rootB.render(createElement(Diagram, { data: {}, startView: 'index', drifts: [] } as never)))
    const toggleOf = (c: HTMLElement) =>
      c.querySelector('[data-testid="likec4-fullscreen-toggle"]') as HTMLButtonElement

    act(() => toggleOf(containerA).click()) // A -> fullscreen: host 'auto' saved, body 'hidden'
    expect(document.body.style.overflow).toBe('hidden')
    act(() => toggleOf(containerB).click()) // B -> fullscreen: still locked
    expect(document.body.style.overflow).toBe('hidden')

    act(() => toggleOf(containerA).click()) // A exits, B still fullscreen -> must STAY locked
    expect(document.body.style.overflow).toBe('hidden')

    act(() => toggleOf(containerB).click()) // B exits (last one out) -> restore the host's original 'auto'
    expect(document.body.style.overflow).toBe('auto')

    act(() => rootB.unmount()) // A is unmounted by afterEach
    containerB.remove()
    document.body.style.overflow = '' // reset shared jsdom state for the next test
  })

  it('does not exit fullscreen on a non-Escape key', () => {
    mount({ data: {}, startView: 'index', drifts: [] })
    act(() => (container.querySelector('[data-testid="likec4-fullscreen-toggle"]') as HTMLButtonElement).click())
    act(() => window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' })))
    expect(viewerClass()).toContain('likec4-fullscreen')
  })

  it('shows the drift banner only when a drift matches the current view', () => {
    mount({ data: {}, startView: 'index', drifts: [{ viewId: 'index', reasons: ['nodes moved'] }] })
    expect(container.querySelector('[data-testid="likec4-drift-banner"]')).not.toBeNull()
  })

  it('shows no drift banner when no drift matches the current view', () => {
    mount({ data: {}, startView: 'index', drifts: [{ viewId: 'other', reasons: ['x'] }] })
    expect(container.querySelector('[data-testid="likec4-drift-banner"]')).toBeNull()
  })

  it('renders the "view not found" fallback (not <LikeC4Diagram>) when findView returns undefined', () => {
    // ViewRenderer guards on `findView(viewId)`: a missing view (e.g. a stale startView the pinned likec4
    // no longer resolves) must render the inline error panel instead of passing undefined.$view into
    // <LikeC4Diagram>. Make the model resolve nothing for this view id and assert the fallback shows and
    // the real diagram does NOT mount.
    diagram.props = null
    modelMock.findView = () => undefined
    mount({ data: {}, startView: 'ghost', drifts: [] })
    const err = container.querySelector('[data-testid="likec4-error"]')
    expect(err).not.toBeNull()
    expect(err!.textContent).toContain('view not found: ghost')
    expect(diagram.props).toBeNull() // <LikeC4Diagram> was never rendered (no props captured)
  })

  it('navigates on a string target but ignores a non-string onNavigateTo argument', () => {
    mount({ data: {}, startView: 'index', drifts: [] })
    const currentView = () =>
      container.querySelector('[data-testid="likec4-diagram"]')!.getAttribute('data-current-view')
    expect(currentView()).toBe('index')
    // A non-string target (a shape change in a future pinned likec4) must NOT flow into the view id —
    // it would render "view not found: [object Object]". The boundary guard drops it silently instead.
    act(() => diagram.props!.onNavigateTo!({ id: 'evil' }))
    expect(currentView()).toBe('index')
    // A genuine string target still navigates.
    act(() => diagram.props!.onNavigateTo!('detail'))
    expect(currentView()).toBe('detail')
  })
})
