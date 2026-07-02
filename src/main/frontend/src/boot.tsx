import './reactCompat' // install the useEffectEvent shim before any diagram renders
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'
import { parseDataAttrs, parseHeight } from './dataAttrs'
import { ErrorBoundary } from './ErrorBoundary'
import { messageOf } from './errors'
import { remountElement } from './mountRegistry'
import { Viewer } from './Viewer'
import { viewerDeps } from './viewerDeps'

export function boot(root: ParentNode = document): void {
  // Shared, page-lifetime deps — one WorkerPool reused across every diagram root AND across repeated
  // boot() calls (see viewerDeps), so a re-run can't leak a second pool's worker threads.
  const deps = viewerDeps()
  root.querySelectorAll<HTMLElement>('.likec4-diagram').forEach(el => {
    try {
      const params = parseDataAttrs(el)
      // Author-configurable height overrides the CSS default (.likec4-diagram { height: 480px }).
      // parseHeight validates it (bare number -> px, valid CSS length passes, garbage ignored) so a
      // hand-edited/dev data-height can't inject arbitrary CSS into style.height.
      const h = parseHeight(el.dataset.height)
      if (h) el.style.height = h
      // Unmount any prior root on this element before re-mounting (a re-run of boot() would otherwise
      // call createRoot() on a container that already has one — React 19 warns and leaks the old root).
      remountElement(el, () => {
        el.replaceChildren()
        // Named reactRoot (not `root`) so it never shadows the `root: ParentNode` boot() parameter — a
        // future edit referencing the param inside this callback would otherwise silently pick up the
        // React root instead.
        const reactRoot = createRoot(el)
        // Parity with the editor mount: an ErrorBoundary so a throw in Viewer's own render path (not just
        // the lazy diagram, which OkView already boundaries) degrades to an inline ErrorPanel rather than
        // blanking this diagram's container.
        reactRoot.render(
          <StrictMode>
            <ErrorBoundary>
              <Viewer params={params} deps={deps} />
            </ErrorBoundary>
          </StrictMode>,
        )
        return reactRoot
      })
    } catch (e) {
      // Match the rest of the bundle's error surfaces: log for support AND style the inline message,
      // rather than leaving an unstyled bare-text error with no console trace.
      console.error('likec4: failed to boot diagram element', e)
      el.classList.add('likec4-error')
      el.textContent = messageOf(e)
    }
  })
}

boot()
