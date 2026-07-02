/** Minimal interface satisfied by a React 19 `Root` (and trivially fakeable in tests). */
export interface Unmountable {
  unmount(): void
}

// Page-lifetime registry of the live mount per diagram element. WeakMap so a removed element's mount
// is collected without manual bookkeeping.
const mounted = new WeakMap<Element, Unmountable>()

/**
 * Mount into {@code el}, tearing down any prior mount for the SAME element first.
 *
 * `boot()` can run more than once on a page (Confluence re-executes a macro's web-resource when it
 * injects content dynamically — inline edit, AJAX content load). Calling `createRoot()` again on a
 * container that already has a root makes React 19 warn and leaks the previous root (its effect
 * cleanups never run). This unmounts the previous root before {@code create} builds the new one, so a
 * re-boot replaces rather than duplicates the mount.
 *
 * <p><b>Known limitation (deliberate):</b> teardown is driven by a re-`boot()` that re-selects the SAME
 * element. If Confluence instead DELETES a diagram element without re-invoking boot() (an AJAX swap that
 * removes rather than replaces, an inline-edit teardown), that element's `unmount()` never fires, so the
 * Viewer effect's `controller.abort()` and the Diagram scroll-lock release don't run — a leaked React
 * root (and, briefly, an in-flight compute occupying a shared worker) until navigation. This is not
 * closed with a MutationObserver on removed nodes on purpose: the leak is bounded (the pool is shared
 * and idle-swept, aborts are best-effort) and an observer over Confluence-owned DOM risks interfering
 * with the platform's own content lifecycle for a cost that never accumulates unbounded in practice.
 */
export function remountElement<T extends Unmountable>(el: Element, create: () => T): T {
  mounted.get(el)?.unmount()
  const m = create()
  mounted.set(el, m)
  return m
}
