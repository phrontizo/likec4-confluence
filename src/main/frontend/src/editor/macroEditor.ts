import { parseHeight } from '../dataAttrs'
import type { PipelineDeps } from '../pipeline'
import { type EditorHandle, mountViewPicker } from './mountEditor'

/** The subset of macro params the editor knows how to read/write. */
export interface MacroEditorParams {
  project?: string
  ref?: string
  path?: string
  view?: string
  instance?: string
  /** Diagram container height: a bare number (px), or any CSS length (e.g. "600px", "80vh"). */
  height?: string
}

export interface MacroEditorOptions {
  /** Current macro params (empty for a fresh insert). */
  params?: MacroEditorParams
  /** Factory for the pipeline deps; the picker reuses the SAME browser pipeline as the viewer. */
  createDeps: () => PipelineDeps
  /** Fired with the macro object when the author clicks Insert. */
  onInsert?: (macro: { name: string; params: Record<string, string> }) => void
  /** Fired when the author cancels / dismisses the dialog. */
  onCancel?: () => void
  /** Where to attach the dialog (default document.body). */
  container?: HTMLElement
  /** Macro name written back (default 'likec4-diagram'). */
  macroName?: string
}

export interface MacroEditorHandle {
  close(): void
  /** The dialog root — exposed for testing/automation. */
  readonly el: HTMLElement
}

export interface DepsLifecycle {
  /** Lazily creates the deps once, then returns the SAME instance for the dialog's lifetime. */
  get(): PipelineDeps
  /** Terminates the pool's Workers and forgets the deps (re-created on the next `get`). */
  dispose(): void
}

/**
 * Owns a single set of pipeline deps for a dialog. Each "Load views" must REUSE the one WorkerPool
 * rather than building a fresh one — `picker.destroy()` only unmounts React, so without this every
 * reload (and every closed dialog) leaked the old pool's Worker threads (`pool.dispose()` was never
 * called anywhere). The dialog disposes this on teardown.
 */
export function createDepsLifecycle(factory: () => PipelineDeps): DepsLifecycle {
  let deps: PipelineDeps | null = null
  return {
    get: () => (deps ??= factory()),
    dispose: () => {
      deps?.pool.dispose()
      deps = null
    },
  }
}

/**
 * Serialise editor params to the macro param map written back into the page, dropping undefined and
 * empty-string values so a blank optional field never emits an empty attribute. Pure + exported so the
 * empty-stripping rule is unit-testable without a DOM (the rest of the dialog is covered by e2e).
 */
export function toMacroParamMap(p: MacroEditorParams): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(p)) if (v != null && v !== '') out[k] = String(v)
  return out
}

const FIELDS: { key: keyof MacroEditorParams; label: string; required?: boolean; placeholder?: string }[] = [
  { key: 'project', label: 'Project', required: true, placeholder: 'group/repository' },
  { key: 'ref', label: 'Ref', placeholder: 'branch, tag or sha (default: main)' },
  { key: 'path', label: 'Path', placeholder: 'sub-path within the repo (optional)' },
  { key: 'view', label: 'View', placeholder: 'view id (use "Load views" or type it)' },
  { key: 'height', label: 'Height', placeholder: 'e.g. 600, 600px or 80vh (default 480)' },
]

/**
 * The macro-editor "Load views" experience (spec §6).
 *
 * Renders a small custom editor: the standard project/ref/path/view fields plus a "Load views"
 * action that fetches+computes the model once (via the SAME browser pipeline as the viewer) and
 * mounts the view picker — a view dropdown (id + title) with a live preview. Selecting a view from
 * the dropdown writes it back into the View field. If the author skips "Load views" they just type
 * the view id. Load/parse/unknown-* errors surface inline inside the picker (ErrorPanel).
 *
 * Confluence-runtime concerns (registering the macro JS override, inserting the macro back into the
 * editor via tinymce) live in the classic editor-loader.js; this module is pure DOM + the pipeline,
 * so it stays typechecked and free of editor globals.
 */
export function openMacroEditor(opts: MacroEditorOptions): MacroEditorHandle {
  const host = opts.container ?? document.body
  const doc = host.ownerDocument
  const macroName = opts.macroName ?? 'likec4-diagram'
  const params = opts.params ?? {}

  // Single-instance guard: a rapid double-open (a double-click on the placeholder, or the Macro-Browser
  // override's opener firing twice) must NOT stack a second overlay — each carries its own document-level
  // keydown listener AND, once "Load views" runs, its own WorkerPool (via depsLifecycle), and tearing one
  // down would leave the other's listener + pool leaked. The guard is scoped to the DOCUMENT (not just the
  // passed host) to match those shared per-document resources: a second open in a DIFFERENT container must
  // also be bounced, else its listener/pool would stack. If a dialog is already open, focus it and return
  // ITS handle rather than building a second.
  const openOverlay = doc.querySelector('.likec4-macro-overlay') as HTMLElement | null
  if (openOverlay) {
    ;(openOverlay.querySelector('[data-testid="likec4-field-project"]') as HTMLElement | null)?.focus()
    const existingClose = (openOverlay as unknown as { __likec4Destroy?: () => void }).__likec4Destroy
    // __likec4Destroy is assigned BEFORE the overlay is appended to the document (see below), so any
    // overlay this querySelector can find already carries its full destroy() — the `??` fallback is never
    // taken in practice. It stays as a safe degradation: if that ordering ever regressed, removing the
    // node is better than returning an undefined close (which would throw when the caller invokes it).
    return { close: existingClose ?? (() => openOverlay.remove()), el: openOverlay }
  }

  // Remember who had focus before we steal it into the dialog, so destroy() can hand it back (WCAG
  // 2.4.3): after Insert/Cancel/Escape, focus would otherwise be lost to <body>, stranding keyboard and
  // assistive-tech users.
  const previouslyFocused = doc.activeElement as HTMLElement | null

  const overlay = doc.createElement('div')
  overlay.className = 'likec4-macro-overlay'
  overlay.setAttribute('data-testid', 'likec4-macro-dialog')
  Object.assign(overlay.style, {
    position: 'fixed',
    inset: '0',
    background: 'rgba(9, 30, 66, 0.54)',
    zIndex: '9999',
    display: 'flex',
    alignItems: 'flex-start',
    justifyContent: 'center',
    overflow: 'auto',
    padding: '40px 16px',
  } as CSSStyleDeclaration)

  const panel = doc.createElement('div')
  panel.className = 'likec4-macro-panel aui-dialog2 aui-dialog2-large'
  Object.assign(panel.style, {
    background: '#fff',
    borderRadius: '3px',
    boxShadow: '0 0 0 1px rgba(9,30,66,0.08), 0 8px 16px -4px rgba(9,30,66,0.25)',
    width: 'min(900px, 96vw)',
    maxWidth: '96vw',
    font: '14px/1.4 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    color: '#172b4d',
    boxSizing: 'border-box',
  } as CSSStyleDeclaration)
  // Mark the panel as a modal dialog so assistive tech announces it and names it from the <h2>.
  panel.setAttribute('role', 'dialog')
  panel.setAttribute('aria-modal', 'true')
  panel.setAttribute('aria-labelledby', 'likec4-macro-title')
  overlay.appendChild(panel)

  panel.innerHTML = `
    <div class="aui-dialog2-header" style="padding:16px 20px;border-bottom:1px solid #dfe1e6">
      <h2 id="likec4-macro-title" class="aui-dialog2-header-main" style="margin:0;font-size:20px">LikeC4 Diagram</h2>
    </div>
    <div class="aui-dialog2-content" style="padding:16px 20px">
      <div class="likec4-macro-fields"></div>
      <div style="margin:12px 0">
        <button type="button" class="aui-button" data-testid="likec4-load-views">Load views</button>
        <span class="likec4-macro-status" data-testid="likec4-macro-status" role="status" aria-live="polite"
              style="margin-left:10px;color:#5e6c84"></span>
      </div>
      <div class="likec4-macro-mount" data-testid="likec4-macro-mount"
           style="min-height:120px"></div>
    </div>
    <div class="aui-dialog2-footer" style="padding:12px 20px;border-top:1px solid #dfe1e6;text-align:right">
      <button type="button" class="aui-button aui-button-primary" data-testid="likec4-macro-insert">Insert</button>
      <button type="button" class="aui-button aui-button-link" data-testid="likec4-macro-cancel">Cancel</button>
    </div>`

  const fieldsEl = panel.querySelector('.likec4-macro-fields') as HTMLElement
  const statusEl = panel.querySelector('.likec4-macro-status') as HTMLElement
  const mountEl = panel.querySelector('.likec4-macro-mount') as HTMLElement
  const loadBtn = panel.querySelector('[data-testid="likec4-load-views"]') as HTMLButtonElement
  const insertBtn = panel.querySelector('[data-testid="likec4-macro-insert"]') as HTMLButtonElement
  const cancelBtn = panel.querySelector('[data-testid="likec4-macro-cancel"]') as HTMLButtonElement

  const inputs: Record<string, HTMLInputElement> = {}
  for (const f of FIELDS) {
    const row = doc.createElement('div')
    row.style.margin = '0 0 10px'
    const label = doc.createElement('label')
    label.textContent = f.required ? `${f.label} *` : f.label
    label.style.cssText = 'display:block;font-weight:600;margin-bottom:4px;font-size:12px;color:#5e6c84'
    const input = doc.createElement('input')
    input.type = 'text'
    input.className = 'text'
    input.setAttribute('data-testid', `likec4-field-${f.key}`)
    input.value = (params[f.key] as string) ?? ''
    if (f.placeholder) input.placeholder = f.placeholder
    input.style.cssText =
      'width:100%;box-sizing:border-box;padding:6px 8px;border:1px solid #dfe1e6;border-radius:3px'
    label.htmlFor = `likec4-field-${f.key}`
    input.id = `likec4-field-${f.key}`
    row.appendChild(label)
    row.appendChild(input)
    fieldsEl.appendChild(row)
    inputs[f.key] = input
  }

  let picker: EditorHandle | null = null
  // One pool per dialog, disposed on teardown (FE-I4).
  const depsLifecycle = createDepsLifecycle(opts.createDeps)
  // Background elements we mark `inert` while the modal is open (see host.appendChild below); destroy()
  // restores exactly these, never clearing an inert an author already set.
  const inerted: HTMLElement[] = []

  const setStatus = (msg: string) => {
    statusEl.textContent = msg
  }

  const readParams = (): MacroEditorParams => ({
    project: inputs.project.value.trim() || undefined,
    ref: inputs.ref.value.trim() || undefined,
    path: inputs.path.value.trim() || undefined,
    view: inputs.view.value.trim() || undefined,
    height: inputs.height.value.trim() || undefined,
    instance: params.instance,
  })

  const loadViews = () => {
    const p = readParams()
    if (!p.project) {
      setStatus('Project is required to load views.')
      return
    }
    setStatus('Loading model…')
    picker?.destroy()
    mountEl.replaceChildren()
    picker = mountViewPicker(mountEl, {
      params: { project: p.project, ref: p.ref, path: p.path, view: p.view },
      deps: depsLifecycle.get(),
      onSelect: id => {
        inputs.view.value = id
        setStatus(`Selected view: ${id}`)
      },
    })
  }

  const destroy = () => {
    picker?.destroy()
    picker = null
    depsLifecycle.dispose()
    // Un-inert the background BEFORE the focus restore below — previouslyFocused lives in it, and focus()
    // is a no-op on an element inside an inert subtree.
    for (const el of inerted) el.inert = false
    inerted.length = 0
    if (overlay.parentNode) overlay.parentNode.removeChild(overlay)
    doc.removeEventListener('keydown', onKey)
    // Return focus to whatever held it before the dialog opened, if that element is still in the document
    // and focusable — so keyboard/AT focus is not stranded on <body> after teardown (WCAG 2.4.3).
    if (previouslyFocused && typeof previouslyFocused.focus === 'function' && doc.contains(previouslyFocused)) {
      previouslyFocused.focus()
    }
  }

  const cancel = () => {
    destroy()
    opts.onCancel?.()
  }

  const insert = () => {
    const p = readParams()
    if (!p.project) {
      setStatus('Project is required.')
      inputs.project.focus()
      return
    }
    // Validate height at authoring time (reusing the shared parseHeight contract, which mirrors the
    // macro's server-side sanitizeHeight). Without this an invalid height (e.g. "tall") Inserted
    // successfully and only failed at render as a "macro parameter is invalid" box, with no editor-time
    // feedback. A blank height is optional (readParams already maps it to undefined) and skips this.
    if (p.height && parseHeight(p.height) === undefined) {
      setStatus('Height must be a positive number (px) or a CSS length like 600px or 80vh.')
      inputs.height.focus()
      return
    }
    const out = toMacroParamMap(p)
    destroy()
    opts.onInsert?.({ name: macroName, params: out })
  }

  function onKey(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      // If the diagram PREVIEW is in fullscreen, this Escape is meant to exit fullscreen (handled by
      // the shared Diagram's own window keydown listener), NOT to cancel the whole macro-edit dialog
      // and discard the author's typed fields. This handler is on `document`, which sits BELOW `window`
      // in the bubble path, so it would otherwise fire first and tear the dialog down before the
      // diagram ever saw the key. Defer to the diagram while its `.likec4-fullscreen` marker is present.
      if (doc.querySelector('.likec4-fullscreen')) return
      cancel()
      return
    }
    if (e.key === 'Tab') trapTab(e)
  }

  // Keep keyboard focus inside the modal: wrap Tab/Shift+Tab at the ends, and pull focus back in if it
  // has escaped to the underlying Confluence editor.
  function trapTab(e: KeyboardEvent) {
    const focusables = panel.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]),'
      + ' textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    )
    if (focusables.length === 0) return
    const first = focusables[0]
    const last = focusables[focusables.length - 1]
    const active = doc.activeElement
    if (e.shiftKey && (active === first || !panel.contains(active))) {
      e.preventDefault()
      last.focus()
    } else if (!e.shiftKey && (active === last || !panel.contains(active))) {
      e.preventDefault()
      first.focus()
    }
  }

  loadBtn.addEventListener('click', loadViews)
  insertBtn.addEventListener('click', insert)
  cancelBtn.addEventListener('click', cancel)
  // Backdrop click-to-cancel requires BOTH the press AND the release to land on the overlay itself.
  // A text selection that starts inside a field and drags onto the dim backdrop (mouseup on overlay,
  // but mousedown was on the input) must NOT discard the author's typed values.
  let pressedOnOverlay = false
  overlay.addEventListener('mousedown', e => { pressedOnOverlay = e.target === overlay })
  overlay.addEventListener('mouseup', e => {
    const onOverlay = pressedOnOverlay && e.target === overlay
    pressedOnOverlay = false
    if (onOverlay) cancel()
  })
  // Clear the press-latch if the pointer leaves the overlay while held. The overlay is a full-viewport
  // fixed element, so mouseleave fires when the drag exits the window (releasing outside never delivers a
  // mouseup here). Without this a stale latch could let a LATER, unrelated mouseup that lands on the
  // overlay be misread as a completed backdrop click and discard the author's typed fields. mouseleave
  // does NOT fire when moving onto the panel (a descendant), so a legitimate press→panel drag is
  // unaffected; the listener lives on the overlay and is GC'd when destroy() removes it (no leak).
  overlay.addEventListener('mouseleave', () => { pressedOnOverlay = false })
  doc.addEventListener('keydown', onKey)

  // Publish the teardown on the overlay so a subsequent open (bounced by the single-instance guard above)
  // can return a handle whose close() tears down THIS dialog — pool and listener included.
  ;(overlay as unknown as { __likec4Destroy?: () => void }).__likec4Destroy = destroy

  host.appendChild(overlay)
  // Make the background inert while the modal is open (WCAG dialog pattern): `aria-modal` alone does not
  // remove background content from the AT reading order in every screen reader, and the Tab trap only
  // catches Tab — a screen-reader virtual cursor or a programmatic focus from the host editor could still
  // land behind the dialog. Mark every sibling of the overlay under `host` inert, remembering only the
  // ones we actually changed so destroy() can restore their prior state.
  for (const child of Array.from(host.children)) {
    if (child !== overlay && child instanceof HTMLElement && !child.inert) {
      child.inert = true
      inerted.push(child)
    }
  }
  inputs.project.focus()

  return { close: destroy, el: overlay }
}
