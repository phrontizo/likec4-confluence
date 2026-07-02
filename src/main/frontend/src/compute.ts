import { applyManualLayout, calcDriftsFromSnapshot } from '@likec4/core'
import { fromSources } from '@likec4/language-services/browser'
import JSON5 from 'json5'
import { asViewsMap } from './viewsMap'

export interface ComputeError { message: string; line: number; sourceFsPath: string }
export interface DriftInfo { viewId: string; reasons: string[] }
export interface ComputeResult {
  /** Serializable LayoutedLikeC4ModelData — safe to postMessage and feed to LikeC4Model.create. */
  data: unknown
  errors: ComputeError[]
  drifts: DriftInfo[]
}

const SNAP_SUFFIX = '.likec4.snap'

function viewIdFromSnapKey(key: string): string {
  const base = key.split('/').pop() ?? key
  return base.slice(0, -SNAP_SUFFIX.length)
}

// Keys that, if carried from attacker-influenceable .snap content into the snapshot object that is
// spread into applyManualLayout below, could write through to a prototype (prototype pollution). JSON5
// (like JSON.parse) surfaces a literal `__proto__`/`constructor`/`prototype` as an OWN enumerable key,
// and likec4's layout merge may recursively copy source→target — so they must be excised at EVERY depth,
// not just the top level (a nested one, e.g. inside `nodes[].data`, would otherwise survive).
const UNSAFE_SNAPSHOT_KEYS = new Set(['__proto__', 'constructor', 'prototype'])

function sanitizeSnapshotBody(content: string): Record<string, unknown> | null {
  // Parse with a reviver that DROPS the prototype-polluting keys at every depth: returning undefined
  // from a JSON5/JSON reviver removes the key from its holder, and these keys are materialised as OWN
  // properties (never mutating the real prototype), so this is the clean place to strip them so no
  // downstream consumer — at any nesting level — ever sees one.
  const parsed: unknown = JSON5.parse(content, (key, value) =>
    UNSAFE_SNAPSHOT_KEYS.has(key) ? undefined : value)
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null
  return parsed as Record<string, unknown>
}

/**
 * Recompute a view's `bounds` as the enclosing box of its node rects + edge geometry.
 *
 * `view.bounds` is a DERIVED value (the diagram's content box) that likec4's react-flow uses as the
 * fitView target. But `applyManualLayout` copies `bounds` verbatim from the snapshot, and a curated
 * snapshot can carry positions that no longer match its stored `bounds` (e.g. a node moved after the
 * box was computed). Stale bounds make fitView centre the wrong box — nodes clip off-screen or the
 * zoom collapses. Since bounds is purely derived, recomputing it from the applied geometry is always
 * safe (a no-op for consistent snapshots) and keeps the embedded diagram correctly framed.
 */
// The minimal geometry shape enclosingBounds reads out of a laid-out likec4 view. Narrowing it (vs the
// previous `any`) makes a likec4 field rename a compile error here rather than a silently-blanked frame
// caught only by the e2e gate. Fields are optional/loose because the values are parsed from
// attacker-influenceable .snap content and re-checked at runtime with Number.isFinite.
interface LayoutGeometry {
  nodes?: { x: number; y: number; width?: number; height?: number }[]
  // points are [x, y] pairs; typed as number[][] (not a strict tuple) so the function only depends on
  // index access p[0]/p[1] and accepts both the layouted data and hand-built test fixtures.
  // labelBBox width/height are OPTIONAL for the same reason node width/height are: the value is parsed
  // from attacker-influenceable .snap content, so a missing/partial box must type as such rather than
  // overstate a guarantee. grow() defaults a missing w/h to 0 (contributing the origin only).
  edges?: { points?: number[][]; labelBBox?: { x: number; y: number; width?: number; height?: number } }[]
}

export function enclosingBounds(view: LayoutGeometry): { x: number; y: number; width: number; height: number } | null {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  const grow = (x: number, y: number, w = 0, h = 0) => {
    // Skip a rect/point with a non-finite ORIGIN — it can't be placed. A non-finite width/height
    // contributes its origin only, so ONE broken node (a NaN width parsed from a .snap) cannot poison
    // the whole view's bounds to NaN and blank an otherwise-valid curated frame. (typeof checks let NaN
    // through — it is a number — so use Number.isFinite.) The all-non-finite case still → null below.
    if (!Number.isFinite(x) || !Number.isFinite(y)) return
    // Fold in BOTH corners of the rect. A .snap is attacker-influenceable, so w/h can be finite but
    // NEGATIVE — the node's true left/top edge is then x+w / y+h, BELOW the origin. Taking min/max over
    // {x, x+w} (not just extending max with x+w) keeps that real edge enclosed instead of clipped; for
    // the normal positive-w/h case x+w is simply the far corner and this is identical to before. A
    // non-finite far corner (NaN/Inf width) collapses back to the origin so one broken node can't poison
    // the whole view's bounds.
    const x2 = Number.isFinite(x + w) ? x + w : x
    const y2 = Number.isFinite(y + h) ? y + h : y
    minX = Math.min(minX, x, x2); minY = Math.min(minY, y, y2)
    maxX = Math.max(maxX, x, x2); maxY = Math.max(maxY, y, y2)
  }
  for (const n of view.nodes ?? []) grow(n.x, n.y, n.width, n.height)
  for (const e of view.edges ?? []) {
    for (const p of e.points ?? []) grow(p[0], p[1])
    if (e.labelBBox) grow(e.labelBBox.x, e.labelBBox.y, e.labelBBox.width, e.labelBBox.height)
  }
  // Require ALL four extremes to be finite: a snapshot point of Infinity/NaN (it is parsed from
  // attacker-influenceable .snap content) would otherwise leave minX finite but maxX = Infinity,
  // yielding an Infinity-width box that survives the guard and breaks fitView ("mounts but invisible").
  // Also require a POSITIVE area (maxX > minX && maxY > minY): a degenerate point-/line-only result is
  // finite but collapses fitView just the same, and it is truthy at the call site — so it would clobber
  // the snapshot's own (possibly correct) bounds. Fall back to null so the caller keeps the verbatim
  // layout bounds instead.
  return Number.isFinite(minX) && Number.isFinite(minY) && Number.isFinite(maxX) && Number.isFinite(maxY)
    && maxX > minX && maxY > minY
    ? { x: minX, y: minY, width: maxX - minX, height: maxY - minY }
    : null
}

/**
 * Compute a fully laid-out model from a path→content record.
 *
 * `.likec4/<viewId>.likec4.snap` (JSON5 `LayoutedView`, `_layout: 'manual'`) must NOT reach
 * `fromSources` (the browser build wires Noop manual-layouts + Noop FS and `.snap` is not a
 * LikeC4 extension, so it would only emit a parse error). We split them out, compute auto
 * layout, read drift reasons with `calcDriftsFromSnapshot`, then reconcile positions with
 * `applyManualLayout` — both public, browser-safe (`@likec4/core`, no `node:*`).
 */
export async function compute(sources: Record<string, string>): Promise<ComputeResult> {
  const likec4Sources: Record<string, string> = {}
  // `view` is `any` (not the Record<string,unknown> sanitizeSnapshotBody returns) ONLY so the
  // `{ ...view, id, _stage }` snapshot below flows into likec4's applyManualLayout/calcDriftsFromSnapshot
  // (which want an un-exported ViewManualLayoutSnapshot) without a cast to an internal type. Runtime
  // safety is unaffected: only a non-null plain object is ever pushed (see the sanitize guard).
  const snapshots: Array<{ viewId: string; view: any }> = []
  for (const [key, content] of Object.entries(sources)) {
    // Trust-boundary guard: `sources` arrives over the worker's structured-clone boundary (worker.ts
    // validates only the object shape, not element types) and could also be handed to compute() directly
    // by a caller that bypassed restClient's per-value string check. A non-string content would otherwise
    // reach fromSources (which throws "Cannot use 'in' operator … in 42") or JSON5.parse — an opaque crash
    // that blanks the whole diagram. Skip it with a warning so one bad entry never sinks a valid model.
    if (typeof content !== 'string') {
      console.warn(`Ignoring non-string source entry ${key}: ${typeof content}`)
      continue
    }
    if (key.endsWith(SNAP_SUFFIX)) {
      try {
        const view = sanitizeSnapshotBody(content)
        if (view) {
          snapshots.push({ viewId: viewIdFromSnapKey(key), view })
        } else {
          console.warn(`Ignoring manual-layout snapshot ${key}: not a JSON object`)
        }
      } catch (e) {
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
  // Shallow-clone $data (and its views map) rather than mutating the model's internal object in place:
  // a future likec4 could freeze $data, which would make the data.views[viewId] assignment below throw.
  // asViewsMap applies the same plain-object guard the load path uses, so a future likec4 emitting `views`
  // as a non-plain-object can't have its indices spread in as phantom view ids.
  const src = model.$data as any
  const data = { ...src, views: { ...asViewsMap(src) } }

  const drifts: DriftInfo[] = []
  for (const { viewId, view } of snapshots) {
    // Own-property check (not a truthy lookup): a snapshot basename that collides with a prototype key
    // such as `__proto__` or `constructor` must not resolve to the inherited Object.prototype member
    // and bypass this guard — that would feed a bogus "auto layout" into applyManualLayout and risk a
    // `data.views.__proto__ = …` prototype write below.
    if (!Object.hasOwn(data.views, viewId)) {
      console.warn(`Manual-layout snapshot references unknown view '${viewId}'`)
      continue
    }
    const auto = data.views[viewId]
    // A single broken snapshot (malformed shape, non-finite geometry, an internal likec4 throw) must
    // only cost ITS view its manual layout — never reject the whole compute and blank the entire
    // diagram. Isolate each snapshot: on failure, fall back to the view's auto-layout.
    try {
      const snapshot = { ...view, id: viewId, _stage: 'layouted' as const }
      const drifted = calcDriftsFromSnapshot(auto, snapshot)
      if (drifted.drifts && drifted.drifts.length > 0) {
        drifts.push({ viewId, reasons: [...drifted.drifts] })
      }
      // applyManualLayout returns a frozen (immer) view that copies snapshot.bounds verbatim; spread
      // into a fresh object with the re-derived bounds (must not mutate the frozen result in place).
      const laidOut = applyManualLayout(auto, snapshot)
      const bounds = enclosingBounds(laidOut)
      data.views[viewId] = bounds ? { ...laidOut, bounds } : laidOut
    } catch (e) {
      console.warn(`Failed to apply manual-layout snapshot for view '${viewId}'; using auto-layout:`, e)
    }
  }

  return { data, errors, drifts }
}
