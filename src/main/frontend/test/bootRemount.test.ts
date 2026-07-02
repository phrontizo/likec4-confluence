// @vitest-environment jsdom
//
// Integration coverage that boot() actually TEARS DOWN a prior React root when it re-runs on the SAME
// element. Confluence re-executes a macro's web-resource when it injects content dynamically (inline
// edit, AJAX content load), so boot() can run more than once per page; a second createRoot() on a
// container that already has one makes React 19 warn and leaks the old root (its effect cleanups never
// run). boot.test.ts stubs remountElement out entirely and mountRegistry.test.ts tests it in isolation —
// neither proves the wiring, so "a real second boot() replaces rather than duplicates the mount" was
// only covered by the live e2e gate. Uses the REAL mountRegistry with a fake createRoot so it stays a
// fast DOM-only unit test.
import { afterEach, describe, expect, it, vi } from 'vitest'

// Record every root the (faked) createRoot hands out, with spies for unmount/render.
const roots = vi.hoisted(() => ({
  created: [] as { unmount: ReturnType<typeof vi.fn>; render: ReturnType<typeof vi.fn> }[],
}))
vi.mock('react-dom/client', () => ({
  createRoot: vi.fn(() => {
    const r = { unmount: vi.fn(), render: vi.fn() }
    roots.created.push(r)
    return r
  }),
}))
// Keep the heavy leaves out; the REAL mountRegistry is deliberately NOT mocked (it is under test).
vi.mock('../src/viewerDeps', () => ({ viewerDeps: () => ({}) }))
vi.mock('../src/Viewer', () => ({ Viewer: () => null }))

import { boot } from '../src/boot'

afterEach(() => {
  document.body.innerHTML = ''
  roots.created.length = 0
  vi.restoreAllMocks()
})

describe('boot() re-mount teardown', () => {
  it('unmounts the prior root when boot() re-runs on the same element (no leaked root)', () => {
    const el = document.createElement('div')
    el.className = 'likec4-diagram'
    el.setAttribute('data-project', 'grp/repo')
    document.body.appendChild(el)

    boot()
    expect(roots.created).toHaveLength(1) // first mount created one root...
    expect(roots.created[0].unmount).not.toHaveBeenCalled() // ...with nothing to tear down yet

    boot() // Confluence re-runs the web-resource on the SAME live element
    expect(roots.created).toHaveLength(2) // a new root was created...
    expect(roots.created[0].unmount).toHaveBeenCalledTimes(1) // ...and the FIRST was unmounted, not leaked
    expect(roots.created[1].unmount).not.toHaveBeenCalled() // the current root stays mounted
  })
})
