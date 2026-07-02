import { LikeC4Model } from '@likec4/core/model'
import { LikeC4Diagram, LikeC4ModelProvider, useLikeC4Model } from 'likec4/react'
import { useEffect, useMemo, useState } from 'react'
// likec4/react's panda-CSS stylesheet (aliased to likec4's prebuilt bundle in vite.config.ts).
// Without it the `.likec4-root` container has no `height` rule and collapses (~110px), react-flow
// gets ~20px and fitView zooms the diagram to ~scale(0.05) — mounted but invisible. It also supplies
// all node/edge styling. The stylesheet is fully `@layer`-scoped, so the host Confluence page's
// (unlayered) CSS still wins. It is imported HERE (the lazy-loaded Diagram chunk) rather than in the
// entry so Vite emits it as async-chunk CSS that the runtime auto-injects as a <link> — the same
// mechanism that already serves our styles.css; the entry's own CSS is never injected by boot-loader.js.
import 'likec4-app-style.css'
import type { DriftInfo } from './compute'

// likec4's top-left navigation panel (logo + back/forward + the view-id button) is absolutely
// positioned OVER the top of the diagram. Its bottom sits ~78px below the container top (measured
// live on the cloud-system model). likec4's own default fit-view padding is only `top: 58px`, so on
// TALL views the topmost node is fit to 58px from the top and ends up half-hidden UNDER the panel
// (e.g. the green "interacts with the system" actor on `big-cloud`). Bias the TOP padding so the
// fit clears the panel with breathing room, keeping modest padding on the other three sides. Small
// 2-node views are unaffected: their fit zoom is capped at maxZoom, so the extra padding only
// re-centres them slightly rather than shrinking them. Numbers are pixels (likec4's ViewPaddings).
// This is fit-padding only — no absolute CSS offset that would break the fullscreen layout.
const FIT_VIEW_PADDING = { top: 100, right: 24, bottom: 24, left: 24 }

// Module-level ref-count for the host-page body scroll-lock. A page can carry MANY diagrams, each with
// its own fullscreen toggle and a `position: fixed; inset: 0` overlay, so two can be fullscreen at once.
// A per-effect save/restore would let diagram A's exit restore the overflow while diagram B is still a
// fullscreen overlay — the host page would then scroll behind B. Ref-count instead: capture the host's
// original inline overflow on the FIRST acquire and restore it only on the LAST release, so the page
// stays locked until the last fullscreen diagram exits. For a single diagram this is identical to a
// plain save/restore.
//
// This lock is a property of the ONE shared page document, not of a JS module instance, so the
// ref-count + saved overflow live in a Symbol-keyed slot on `document` rather than in module-level
// variables. That keeps a SINGLE counter even if a future rollup change (e.g. per-entry manualChunks)
// ever duplicated this `Diagram-*.js` chunk across the `main` viewer and `editor-confluence` entries —
// two module-level copies would otherwise each keep their own counter and clobber each other's saved
// overflow. `Symbol.for` resolves to the same symbol across duplicated modules, and `document` is the
// genuine singleton they all converge on. (Today Vite emits one shared `isDynamicEntry` chunk for both,
// so this is defence against a future build change, not a live bug.)
const SCROLL_LOCK = Symbol.for('likec4.bodyScrollLock')
type ScrollLockState = { count: number; savedOverflow: string }
function scrollLockState(): ScrollLockState {
  const host = document as unknown as Record<symbol, ScrollLockState | undefined>
  return (host[SCROLL_LOCK] ??= { count: 0, savedOverflow: '' })
}
function acquireBodyScrollLock() {
  const s = scrollLockState()
  if (s.count === 0) {
    s.savedOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  }
  s.count++
}
function releaseBodyScrollLock() {
  const s = scrollLockState()
  s.count--
  if (s.count <= 0) {
    s.count = 0
    document.body.style.overflow = s.savedOverflow
  }
}

export default function Diagram({ data, startView, drifts }: { data: unknown; startView: string; drifts: DriftInfo[] }) {
  const model = useMemo(() => LikeC4Model.create(data as any), [data])
  const [viewId, setViewId] = useState(startView)
  const [fullscreen, setFullscreen] = useState(false)

  useEffect(() => {
    if (!fullscreen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setFullscreen(false)
    }
    window.addEventListener('keydown', onKey)
    // Lock the host page's scroll while the fixed-position (`inset: 0`) overlay is up, so the Confluence
    // page behind the fullscreen diagram can't scroll through it. Ref-counted (see acquire/release above)
    // so the cleanup — which runs both on exit-fullscreen and on unmount — only unlocks once the LAST
    // fullscreen diagram exits, and the host's prior overflow is never clobbered.
    acquireBodyScrollLock()
    return () => {
      window.removeEventListener('keydown', onKey)
      releaseBodyScrollLock()
    }
  }, [fullscreen])

  const drift = useMemo(() => drifts.find(d => d.viewId === viewId), [drifts, viewId])

  return (
    <div
      className={`likec4-viewer${fullscreen ? ' likec4-fullscreen' : ''}`}
      data-testid="likec4-diagram"
      data-current-view={viewId}
      style={{ width: '100%', height: '100%' }}
    >
      {drift && (
        <div data-testid="likec4-drift-banner" className="likec4-drift" role="status">
          Curated layout may be stale ({drift.reasons.join(', ')}). Ask an architect to re-export the snapshot.
        </div>
      )}
      <button
        type="button"
        data-testid="likec4-fullscreen-toggle"
        className="likec4-fullscreen-toggle"
        aria-pressed={fullscreen}
        onClick={() => setFullscreen(f => !f)}
      >
        {fullscreen ? 'Exit full screen' : 'Full screen'}
      </button>
      <LikeC4ModelProvider likec4model={model}>
        <ViewRenderer viewId={viewId} onNavigate={setViewId} />
      </LikeC4ModelProvider>
    </div>
  )
}

function ViewRenderer({ viewId, onNavigate }: { viewId: string; onNavigate: (id: string) => void }) {
  const view = useLikeC4Model().findView(viewId)
  if (!view) return <div data-testid="likec4-error">view not found: {viewId}</div>
  return (
    <LikeC4Diagram
      view={(view as any).$view}
      // Guard the boundary: onNavigateTo's argument type is owned by the pinned likec4 version. A
      // non-string (a future shape change) would otherwise flow into setViewId and render
      // "view not found: [object Object]"; drop it silently instead of masking it with `as string`.
      onNavigateTo={(to) => { if (typeof to === 'string') onNavigate(to) }}
      zoomable
      pannable
      fitViewPadding={FIT_VIEW_PADDING}
      showNavigationButtons
      // Dynamic views are walkable: the walkthrough surfaces each step's `notes` (markdown). likec4
      // renders that markdown via dangerouslySetInnerHTML, so it is a real XSS surface — proven inert
      // by docker/e2e/xss-notes.spec.ts (no execution, no live <img>/<script>, zero CSP violations).
      enableDynamicViewWalkthrough
    />
  )
}
