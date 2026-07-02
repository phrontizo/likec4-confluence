// Small shared helpers for the bundle entry points (boot / editor), so the worker-pool sizing and the
// e2e compute-counter wiring live in one place instead of being copy-pasted across three entries.

/**
 * Default worker-pool size: {@code min(cores, cap)} — scale DOWN toward the device's CPU-core count but
 * never above {@code cap}, so a page with many diagram macros does not spawn an unbounded number of
 * workers (on any machine with at least {@code cap} cores the size is simply {@code cap}). Falls back to
 * {@code cap} when `navigator.hardwareConcurrency` is unavailable.
 */
export function defaultPoolSize(cap = 4): number {
  const hc = typeof navigator !== 'undefined' ? navigator.hardwareConcurrency : undefined
  // Math.floor so a browser reporting a fractional hardwareConcurrency yields a whole worker count
  // (the pool's `created >= maxWorkers` check would otherwise implicitly ceil a fractional size).
  return Math.max(1, Math.floor(Math.min(hc && hc > 0 ? hc : cap, cap)))
}

/**
 * Bump the window-scoped counter the e2e harness reads — incremented once per real (cache-miss) compute.
 * BOTH entry points wire it (`viewerDeps` for rendered macros and the editor for its live preview), so on
 * a page with both a rendered macro and an open macro-editor the counter is the SUM of viewer and editor
 * computes, not viewer-only. The e2e specs that assert on it drive one surface at a time for that reason.
 */
export function bumpComputeCounter(): void {
  window.__likec4Computes = (window.__likec4Computes ?? 0) + 1
}
