// @vitest-environment jsdom
//
// DOM/keyboard/lifecycle coverage for the openMacroEditor dialog — the fiddly branches (blank-project
// guard, backdrop mousedown/mouseup pairing, Escape-defers-to-fullscreen, focus trap, teardown) that
// were previously exercised only by the live e2e gate and would otherwise regress silently. The
// "Load views" picker mount itself stays an e2e concern; these tests avoid it (the only loadViews
// branch touched here is the blank-project early return that never mounts the picker).
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PipelineDeps } from '../src/pipeline'
import { openMacroEditor } from '../src/editor/macroEditor'

// The deps factory is only invoked lazily on a successful "Load views"; these tests never reach that
// path, so a stub whose pool.dispose is a no-op is sufficient (createDepsLifecycle is unit-tested
// separately for the real dispose behaviour).
const fakeCreateDeps = () => ({ pool: { dispose: () => {} } }) as unknown as PipelineDeps

const q = (root: ParentNode, testid: string) =>
  root.querySelector(`[data-testid="${testid}"]`) as HTMLElement

const dialog = () => document.querySelector('[data-testid="likec4-macro-dialog"]')

const press = (key: string) =>
  document.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }))

afterEach(() => {
  document.body.replaceChildren()
})

describe('openMacroEditor dialog', () => {
  it('prefills fields from params and focuses the project field on open', () => {
    openMacroEditor({ createDeps: fakeCreateDeps, params: { project: 'grp/repo', view: 'v1' } })
    expect((q(document, 'likec4-field-project') as HTMLInputElement).value).toBe('grp/repo')
    expect((q(document, 'likec4-field-view') as HTMLInputElement).value).toBe('v1')
    expect(document.activeElement).toBe(q(document, 'likec4-field-project'))
  })

  it('Insert with a valid project fires onInsert with the mapped params and tears the dialog down', () => {
    const onInsert = vi.fn()
    openMacroEditor({ createDeps: fakeCreateDeps, onInsert, params: { project: 'grp/repo', ref: 'main' } })
    // A blank optional field must not emit an empty attribute.
    ;(q(document, 'likec4-field-height') as HTMLInputElement).value = '   '
    q(document, 'likec4-macro-insert').click()
    expect(onInsert).toHaveBeenCalledTimes(1)
    expect(onInsert.mock.calls[0][0]).toEqual({
      name: 'likec4-diagram',
      params: { project: 'grp/repo', ref: 'main' },
    })
    expect(dialog()).toBeNull() // overlay removed
  })

  it('Insert with a blank project is refused: no onInsert, dialog stays, project re-focused', () => {
    const onInsert = vi.fn()
    openMacroEditor({ createDeps: fakeCreateDeps, onInsert })
    q(document, 'likec4-macro-insert').click()
    expect(onInsert).not.toHaveBeenCalled()
    expect(dialog()).not.toBeNull()
    expect(q(document, 'likec4-macro-status').textContent).toContain('Project is required')
    expect(document.activeElement).toBe(q(document, 'likec4-field-project'))
  })

  it('the Cancel button fires onCancel and removes the overlay', () => {
    const onCancel = vi.fn()
    openMacroEditor({ createDeps: fakeCreateDeps, onCancel })
    q(document, 'likec4-macro-cancel').click()
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(dialog()).toBeNull()
  })

  it('Escape cancels the dialog', () => {
    const onCancel = vi.fn()
    openMacroEditor({ createDeps: fakeCreateDeps, onCancel })
    press('Escape')
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(dialog()).toBeNull()
  })

  it('Escape defers to the diagram while a fullscreen preview is open (does not discard the fields)', () => {
    const onCancel = vi.fn()
    openMacroEditor({ createDeps: fakeCreateDeps, onCancel })
    const fs = document.createElement('div')
    fs.className = 'likec4-fullscreen'
    document.body.appendChild(fs)
    press('Escape')
    expect(onCancel).not.toHaveBeenCalled()
    expect(dialog()).not.toBeNull()
    // Once fullscreen is gone, Escape cancels as usual.
    fs.remove()
    press('Escape')
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(dialog()).toBeNull()
  })

  it('a full backdrop click (press AND release on the overlay) cancels', () => {
    const onCancel = vi.fn()
    const h = openMacroEditor({ createDeps: fakeCreateDeps, onCancel })
    h.el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    h.el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(dialog()).toBeNull()
  })

  it('a drag that starts in a field and releases on the backdrop does NOT cancel', () => {
    const onCancel = vi.fn()
    const h = openMacroEditor({ createDeps: fakeCreateDeps, onCancel })
    // mousedown on an input (bubbles to the overlay listener with target=input), mouseup on the overlay.
    q(document, 'likec4-field-project').dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    h.el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
    expect(onCancel).not.toHaveBeenCalled()
    expect(dialog()).not.toBeNull()
  })

  it('a backdrop press that leaves the overlay (drag out of the window) does not later cancel spuriously', () => {
    const onCancel = vi.fn()
    const h = openMacroEditor({ createDeps: fakeCreateDeps, onCancel })
    // Press on the backdrop, then drag the pointer out of the full-viewport overlay (mouseleave = out of
    // the window) and release outside, so no mouseup reaches the overlay — leaving the press-latch stale.
    h.el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    h.el.dispatchEvent(new MouseEvent('mouseleave'))
    // A later mouseup that happens to land on the overlay must NOT be treated as a completed backdrop
    // click from that stale press (which would discard the author's typed fields).
    h.el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }))
    expect(onCancel).not.toHaveBeenCalled()
    expect(dialog()).not.toBeNull()
  })

  it('close() removes the overlay, is idempotent, and detaches the keydown listener', () => {
    const onCancel = vi.fn()
    const h = openMacroEditor({ createDeps: fakeCreateDeps, onCancel })
    h.close()
    expect(dialog()).toBeNull()
    expect(() => h.close()).not.toThrow() // second close is a no-op, not a double-remove crash
    // The document keydown listener was removed, so a later Escape cannot re-fire cancel.
    press('Escape')
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('Tab from the last focusable wraps to the first, and Shift+Tab from the first wraps to the last', () => {
    openMacroEditor({ createDeps: fakeCreateDeps, params: { project: 'grp/repo' } })
    const first = q(document, 'likec4-field-project')
    const last = q(document, 'likec4-macro-cancel')
    last.focus()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }))
    expect(document.activeElement).toBe(first)
    first.focus()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true }))
    expect(document.activeElement).toBe(last)
  })

  it('Insert with an invalid height is refused with an inline message, height re-focused', () => {
    const onInsert = vi.fn()
    openMacroEditor({ createDeps: fakeCreateDeps, onInsert, params: { project: 'grp/repo' } })
    ;(q(document, 'likec4-field-height') as HTMLInputElement).value = 'tall'
    q(document, 'likec4-macro-insert').click()
    expect(onInsert).not.toHaveBeenCalled()
    expect(dialog()).not.toBeNull()
    expect(q(document, 'likec4-macro-status').textContent?.toLowerCase()).toContain('height')
    expect(document.activeElement).toBe(q(document, 'likec4-field-height'))
  })

  it('Insert with a valid CSS-length height passes it through', () => {
    const onInsert = vi.fn()
    openMacroEditor({ createDeps: fakeCreateDeps, onInsert, params: { project: 'grp/repo' } })
    ;(q(document, 'likec4-field-height') as HTMLInputElement).value = '80vh'
    q(document, 'likec4-macro-insert').click()
    expect(onInsert).toHaveBeenCalledTimes(1)
    expect(onInsert.mock.calls[0][0].params).toEqual({ project: 'grp/repo', height: '80vh' })
  })

  it('restores focus to the previously-focused element on close (WCAG 2.4.3)', () => {
    const trigger = document.createElement('button')
    document.body.appendChild(trigger)
    trigger.focus()
    expect(document.activeElement).toBe(trigger)
    const h = openMacroEditor({ createDeps: fakeCreateDeps })
    expect(document.activeElement).toBe(q(document, 'likec4-field-project')) // focus moved into the dialog
    h.close()
    expect(document.activeElement).toBe(trigger) // and returned to the opener on teardown
  })

  it('a second open while a dialog is already open does not stack a second overlay', () => {
    const h1 = openMacroEditor({ createDeps: fakeCreateDeps })
    const h2 = openMacroEditor({ createDeps: fakeCreateDeps })
    // The second call is bounced by the single-instance guard: exactly one overlay exists (no leaked
    // second document keydown listener / WorkerPool), and it returns the existing dialog's handle.
    expect(document.querySelectorAll('.likec4-macro-overlay').length).toBe(1)
    expect(h2.el).toBe(h1.el)
    // Closing via the returned handle tears down the single dialog cleanly.
    h2.close()
    expect(dialog()).toBeNull()
  })

  it('a second open in a DIFFERENT container is still bounced (one dialog per document)', () => {
    const a = document.createElement('div')
    const b = document.createElement('div')
    document.body.append(a, b)
    const h1 = openMacroEditor({ createDeps: fakeCreateDeps, container: a })
    const h2 = openMacroEditor({ createDeps: fakeCreateDeps, container: b })
    // The document-level keydown listener + WorkerPool are shared per DOCUMENT, so the guard must be
    // document-global: opening in a second container must NOT stack a second overlay (which would leak
    // the first's listener/pool). Exactly one overlay exists and the second call returns the first handle.
    expect(document.querySelectorAll('.likec4-macro-overlay').length).toBe(1)
    expect(h2.el).toBe(h1.el)
  })

  it('loadViews with a blank project shows a message and never mounts the picker', () => {
    openMacroEditor({ createDeps: fakeCreateDeps })
    q(document, 'likec4-load-views').click()
    expect(q(document, 'likec4-macro-status').textContent).toContain('Project is required')
    expect(q(document, 'likec4-macro-mount').childElementCount).toBe(0)
  })
})
