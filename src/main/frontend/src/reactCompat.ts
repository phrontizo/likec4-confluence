// Polyfill React.useEffectEvent (experimental in React 18 canaries, dropped from React 19 stable)
// which @xyflow/react (bundled in likec4@1.58.0) still calls — needed for the diagram to render.
//
// Patch the CJS default-export object: this IS the same object the lazily-loaded Diagram chunk uses,
// because Vite maps the CJS `module.exports` to the default export. The frozen ES-module namespace
// (`import * as React`) cannot be mutated; the default import gives the underlying mutable CJS exports
// object. Importing this module for its side-effect installs the shim exactly once, before any render.
import ReactDefault from 'react'

const R = ReactDefault as any
if (!R.useEffectEvent) {
  R.useEffectEvent = function useEffectEvent(fn: (...a: any[]) => any) {
    const ref = R.useRef(fn) as { current: typeof fn }
    // Update the ref in an insertion effect, not a layout effect: child layout-effects run BEFORE
    // parent layout-effects, so a consumer (e.g. @xyflow/react) that fires the event from its own
    // layout-effect during the same commit would otherwise observe the PREVIOUS render's fn. Insertion
    // effects run before all layout effects, so the latest fn is always seen — matching React's own
    // useEffectEvent reference implementation.
    R.useInsertionEffect(() => {
      ref.current = fn
    })
    return R.useCallback((...args: any[]) => ref.current(...args), [])
  }
}
