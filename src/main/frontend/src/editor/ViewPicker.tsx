import { Suspense, lazy } from 'react'
import { ErrorBoundary } from '../ErrorBoundary'
import type { DriftInfo } from '../compute'
import type { ViewInfo } from './listViews'

const Diagram = lazy(() => import('../Diagram'))

export function ViewPicker({
  views,
  selected,
  onChange,
  data,
  drifts,
}: {
  views: ViewInfo[]
  selected: string
  onChange: (id: string) => void
  data: unknown
  drifts: DriftInfo[]
}) {
  return (
    <div className="likec4-editor" data-testid="likec4-editor">
      <select
        data-testid="likec4-view-picker"
        aria-label="View"
        value={selected}
        onChange={e => onChange(e.target.value)}
      >
        {views.map(v => (
          <option key={v.id} value={v.id}>{v.title} ({v.id})</option>
        ))}
      </select>
      <div className="likec4-editor-preview" style={{ width: '100%', height: '400px' }}>
        {/* key={selected} sits on the BOUNDARY so switching views remounts the whole preview (boundary +
            Diagram): it clears a latched render error from a previous (e.g. throwing) selection AND makes
            the picker — not in-diagram clicks — drive which view shows. */}
        <ErrorBoundary key={selected}>
          <Suspense fallback={<div data-testid="likec4-loading">Rendering…</div>}>
            <Diagram data={data} startView={selected} drifts={drifts} />
          </Suspense>
        </ErrorBoundary>
      </div>
    </div>
  )
}
