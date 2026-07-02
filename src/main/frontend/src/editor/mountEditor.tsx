import { StrictMode, useEffect, useRef, useState } from 'react'
import { type Root, createRoot } from 'react-dom/client'
import '../styles.css'
import { ErrorBoundary } from '../ErrorBoundary'
import { ErrorPanel } from '../ErrorPanel'
import type { DiagramParams } from '../dataAttrs'
import { describeFailure, messageOf } from '../errors'
import { type LoadResult, type PipelineDeps, loadModel } from '../pipeline'
import { ViewPicker } from './ViewPicker'
import { listViews } from './listViews'

export interface EditorHandle { destroy(): void }

export interface MountOptions {
  params: DiagramParams
  deps: PipelineDeps
  /** Fired with the chosen view id on load and on each change (Plan 3 writes it back to the macro param). */
  onSelect?: (id: string) => void
}

function Editor({ params, deps, onSelect }: MountOptions) {
  const [state, setState] = useState<LoadResult | { kind: 'loading' }>({ kind: 'loading' })
  const [selected, setSelected] = useState<string | null>(null)
  // Keep the latest onSelect in a ref so the load effect (whose deps are the primitive params, NOT
  // onSelect) always fires the CURRENT callback rather than the one captured when the effect last ran —
  // a parent can hand us a new onSelect identity without changing the params.
  const onSelectRef = useRef(onSelect)
  useEffect(() => { onSelectRef.current = onSelect })

  useEffect(() => {
    let alive = true
    const controller = new AbortController()
    // Reset to loading when the primitive params change (parity with Viewer.tsx). Without this, a live
    // Editor re-rendered with new params would keep showing the previously-loaded picker/preview until the
    // new load resolves. Latent today (loadViews destroys + recreates the picker root rather than changing
    // params in place), but the reset keeps the two mounts consistent and forestalls a stale-model foot-gun.
    setState({ kind: 'loading' })
    loadModel(params, deps, controller.signal)
      .then(r => {
        if (!alive) return
        setState(r)
        if (r.kind === 'ok') {
          setSelected(r.startView)
          onSelectRef.current?.(r.startView)
        }
      })
      // loadModel is designed never to reject (it maps every failure to a LoadResult), but mirror
      // Viewer.tsx's defensive .catch so an unforeseen synchronous throw surfaces as an ErrorPanel
      // rather than an unhandled rejection that leaves the picker stuck on "Loading…" forever.
      .catch(e => { if (alive) setState({ kind: 'error', detail: messageOf(e) }) })
    return () => { alive = false; controller.abort() }
    // NOTE: deps list is intentionally the primitive params, not object identity — see Viewer.tsx for
    // the rationale. `deps` must likewise be a stable reference (the dialog passes depsLifecycle.get(),
    // which memoises one instance). (The repo ships no ESLint, so this is a human note, not a lint pragma.)
  }, [params.project, params.ref, params.path, params.view, params.instance, deps])

  if (state.kind === 'loading') return <div data-testid="likec4-loading">Loading…</div>
  if (state.kind !== 'ok') return <ErrorPanel failure={describeFailure(state, params)} />

  const choose = (id: string) => {
    setSelected(id)
    // Route through the ref (kept current each render) for parity with the load effect above, so a
    // changed onSelect identity is always honoured even if this root were ever reused across props.
    onSelectRef.current?.(id)
  }
  return (
    <ViewPicker
      views={listViews(state.data)}
      selected={selected ?? state.startView}
      data={state.data}
      drifts={state.drifts}
      onChange={choose}
    />
  )
}

export function mountViewPicker(container: HTMLElement, opts: MountOptions): EditorHandle {
  const root: Root = createRoot(container)
  // Wrap in an ErrorBoundary so a synchronous throw in Editor's OWN render path (e.g. listViews on a
  // future likec4 shape change) degrades to an inline ErrorPanel rather than blanking the whole picker
  // mount. ViewPicker already boundaries the lazy <Diagram>; this guards everything above it too.
  root.render(
    <StrictMode>
      <ErrorBoundary title="View picker failed">
        <Editor {...opts} />
      </ErrorBoundary>
    </StrictMode>,
  )
  return { destroy: () => root.unmount() }
}
