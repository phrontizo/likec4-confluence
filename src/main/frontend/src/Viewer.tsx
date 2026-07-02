import { Suspense, lazy, useEffect, useState } from 'react'
import { ErrorBoundary } from './ErrorBoundary'
import { ErrorPanel } from './ErrorPanel'
import type { DiagramParams } from './dataAttrs'
import { describeFailure, messageOf } from './errors'
import { loadModel, type LoadResult, type PipelineDeps } from './pipeline'

const Diagram = lazy(() => import('./Diagram'))

type OkResult = Extract<LoadResult, { kind: 'ok' }>
type State = LoadResult | { kind: 'loading' }

/**
 * Renders a successfully-loaded model. The reset key sits on the ERROR BOUNDARY (not just Diagram) so a
 * key change remounts the whole subtree, resetting BOTH (a) the boundary's latched render error — a
 * class error boundary does not auto-clear on prop changes, so without this one diagram that throws
 * (LikeC4Model.create / <LikeC4Diagram> run on untyped worker/cache data) would keep showing its
 * ErrorPanel even after the macro params change to a different, valid model — and (b) Diagram's internal
 * navigated viewId (seeded once from startView via useState; it ignores later prop changes). The key is
 * `${sha}:${startView}`: sha changes whenever the model content changes, and startView changes when the
 * `view` param changes for the SAME source (same sha). In-diagram navigation leaves both unchanged, so
 * it does NOT remount and the user's navigation is preserved.
 */
export function OkView({ result }: { result: OkResult }) {
  return (
    <ErrorBoundary key={`${result.sha}:${result.startView}`}>
      <Suspense fallback={<div data-testid="likec4-loading" className="likec4-loading">Rendering…</div>}>
        <Diagram data={result.data} startView={result.startView} drifts={result.drifts} />
      </Suspense>
    </ErrorBoundary>
  )
}

export function Viewer({ params, deps }: { params: DiagramParams; deps: PipelineDeps }) {
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    let alive = true
    // Abort the in-flight fetch + worker job when params change or we unmount, so a stale load stops
    // occupying one of the few workers instead of just having its setState suppressed.
    const controller = new AbortController()
    setState({ kind: 'loading' })
    loadModel(params, deps, controller.signal)
      .then(r => { if (alive) setState(r) })
      .catch(e => { if (alive) setState({ kind: 'error', detail: messageOf(e) }) })
    return () => { alive = false; controller.abort() }
    // NOTE: deps list is intentionally the PRIMITIVE params, not the `params` object identity — a caller
    // that recreates `params` inline on every render must not re-run the resolve+fetch+compute pipeline
    // (it would tie up a worker). `deps` IS in the list, so it MUST be a stable reference too (boot/
    // viewerDeps memoise it); an inline-recreated `deps` would re-run the whole pipeline every render.
    // (The repo ships no ESLint, so this is a human note, not a lint pragma.)
  }, [params.project, params.ref, params.path, params.view, params.instance, deps])

  if (state.kind === 'loading') {
    return <div data-testid="likec4-loading" className="likec4-loading">Loading diagram…</div>
  }
  if (state.kind === 'ok') {
    return <OkView result={state} />
  }
  return <ErrorPanel failure={describeFailure(state, params)} />
}
