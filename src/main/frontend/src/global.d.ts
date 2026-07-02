// Ambient typings for the few window globals this bundle reads or pokes, so the accesses are typed
// instead of scattered `(window as any)` casts. Kept intentionally narrow — only the members actually
// used are declared.
export {}

declare global {
  interface Window {
    /** Confluence's AJS global. Only contextPath() is used (for the REST base); absent under Node/dev. */
    AJS?: { contextPath?: () => string }
    /** Set by editor-confluence so the classic editor-loader.js can drive the picker. */
    LikeC4Editor?: unknown
    /** Debug counter incremented per compute (asserted by e2e); absent unless the bundle bumped it. */
    __likec4Computes?: number
    /** Dev-harness hook: the id the dev editor last selected. */
    __editorSelected?: string
  }
}
