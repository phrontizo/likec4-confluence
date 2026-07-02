// @vitest-environment jsdom
//
// Unit coverage for boot.tsx's per-element composition — specifically the two DOM side-effects the rest
// of the suite doesn't exercise: the height-override branch (parseHeight -> el.style.height) and the
// catch that styles a failed element inline (likec4-error class + textContent + console.error). The
// heavy leaf deps (the WorkerPool from viewerDeps and the real Viewer mount via mountRegistry) are
// stubbed so this stays a fast DOM-only test; the actual mount/render is covered by the live e2e gate.
import { afterEach, describe, expect, it, vi } from 'vitest'

// vi.hoisted so the mock factory can reference remountElement (vi.mock is hoisted above imports).
const { remountElement } = vi.hoisted(() => ({ remountElement: vi.fn() }))
vi.mock('../src/viewerDeps', () => ({ viewerDeps: () => ({}) }))
vi.mock('../src/mountRegistry', () => ({ remountElement }))

// boot.tsx runs boot() at module top-level against the (empty) document on import — harmless with the
// stubs above. Import once here, then drive boot() explicitly per test.
import { boot } from '../src/boot'

function diagramEl(attrs: Record<string, string>): HTMLElement {
  const el = document.createElement('div')
  el.className = 'likec4-diagram'
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v)
  document.body.appendChild(el)
  return el
}

afterEach(() => {
  document.body.innerHTML = ''
  remountElement.mockClear()
  vi.restoreAllMocks()
})

describe('boot() per-element composition', () => {
  it('styles a failed element inline and logs when parsing throws (missing data-project)', () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {})
    const el = diagramEl({}) // no data-project -> parseDataAttrs throws before any mount
    boot()
    expect(el.classList.contains('likec4-error')).toBe(true)
    expect(el.textContent).toMatch(/data-project/)
    expect(remountElement).not.toHaveBeenCalled() // the failure short-circuits before the mount
    expect(err).toHaveBeenCalled()
  })

  it('applies a valid data-height to style.height before mounting', () => {
    const el = diagramEl({ 'data-project': 'grp/repo', 'data-height': '600' })
    boot()
    expect(el.style.height).toBe('600px') // bare number -> px, per parseHeight
    expect(remountElement).toHaveBeenCalledWith(el, expect.any(Function))
  })

  it('ignores a garbage data-height (CSS default applies) but still mounts', () => {
    const el = diagramEl({ 'data-project': 'grp/repo', 'data-height': 'url(javascript:alert(1))' })
    boot()
    expect(el.style.height).toBe('') // rejected by parseHeight -> no inline height written
    expect(remountElement).toHaveBeenCalledWith(el, expect.any(Function))
  })
})
