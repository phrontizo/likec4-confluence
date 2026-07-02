import { applyManualLayout } from '@likec4/core'
import { fromSources } from '@likec4/language-services/browser'
import JSON5 from 'json5'

export interface ComputeResult {
  /** Serializable LayoutedLikeC4ModelData — safe to postMessage and feed to LikeC4Model.create. */
  data: unknown
  errors: Array<{ message: string; line: number; sourceFsPath: string }>
}

const SNAP_SUFFIX = '.likec4.snap'

/** viewId for a `.likec4.snap` source key (basename minus the `.likec4.snap` suffix). */
function viewIdFromSnapKey(key: string): string {
  const base = key.split('/').pop() ?? key
  return base.slice(0, -SNAP_SUFFIX.length)
}

/**
 * Compute a fully laid-out model from a path→content record.
 *
 * Manual-layout snapshots (`.likec4/<viewId>.likec4.snap`, JSON5-serialised
 * `LayoutedView` tagged `_layout: 'manual'`) are applied here explicitly:
 *
 * The browser `fromSources` wires `NoopLikeC4ManualLayouts` + `NoopFileSystemProvider`,
 * so it never reads `.snap` files from the virtual workspace (and passing them
 * through to `fromSources` would only produce parse errors, since `.snap` is not a
 * LikeC4 file extension). Instead we strip the snapshots out, compute the auto
 * layout, then reconcile each snapshot onto its view with the public, browser-safe
 * `applyManualLayout` from `@likec4/core` — which also records `drifts` when the
 * model has changed since the snapshot was taken.
 */
export async function compute(sources: Record<string, string>): Promise<ComputeResult> {
  const likec4Sources: Record<string, string> = {}
  const snapshots: Array<{ viewId: string; view: any }> = []
  for (const [key, content] of Object.entries(sources)) {
    if (key.endsWith(SNAP_SUFFIX)) {
      try {
        snapshots.push({ viewId: viewIdFromSnapKey(key), view: JSON5.parse(content) })
      } catch (e) {
        // Ignore unparseable snapshots; auto layout still renders.
        console.warn(`Failed to parse manual-layout snapshot ${key}:`, e)
      }
    } else {
      likec4Sources[key] = content
    }
  }

  const likec4 = await fromSources(likec4Sources)
  const errors = likec4.getErrors().map(e => ({
    message: e.message,
    line: e.line,
    sourceFsPath: e.sourceFsPath,
  }))
  const model = await likec4.layoutedModel()
  const data = model.$data as any

  for (const { viewId, view } of snapshots) {
    const auto = data.views?.[viewId]
    if (!auto) {
      console.warn(`Manual-layout snapshot references unknown view '${viewId}'`)
      continue
    }
    // applyManualLayout requires a layouted snapshot with a matching id.
    data.views[viewId] = applyManualLayout(auto, { ...view, id: viewId, _stage: 'layouted' })
  }

  return { data, errors }
}
