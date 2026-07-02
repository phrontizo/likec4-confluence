import { LikeC4Model } from '@likec4/core/model'
import { LikeC4Diagram, LikeC4ModelProvider, useLikeC4Model } from 'likec4/react'
import { useMemo } from 'react'

// NOTE: likec4/react self-injects its stylesheet (Mantine + xyflow layers) at
// runtime via adoptedStyleSheets/injectStyles inside LikeC4Diagram, so no manual
// CSS import (e.g. `likec4/react/styles.css`) is required — and the package ships
// no such CSS export anyway. Confirmed against react/index.mjs + index.d.mts.

export function Diagram({ data, viewId }: { data: unknown; viewId: string }) {
  // LikeC4Model.create accepts the serializable LayoutedLikeC4ModelData ($data).
  const model = useMemo(() => LikeC4Model.create(data as any), [data])
  return (
    <LikeC4ModelProvider likec4model={model}>
      <ViewRenderer viewId={viewId} />
    </LikeC4ModelProvider>
  )
}

function ViewRenderer({ viewId }: { viewId: string }) {
  // findView returns a LikeC4ViewModel | null; LikeC4Diagram wants the underlying
  // LayoutedView, exposed as viewModel.$view.
  const view = useLikeC4Model().findView(viewId)
  if (!view) return <div data-testid="error">view not found: {viewId}</div>
  return (
    <div data-testid="diagram" style={{ width: '100%', height: '100%' }}>
      <LikeC4Diagram view={(view as any).$view} zoomable pannable />
    </div>
  )
}
