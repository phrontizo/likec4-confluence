/**
 * Extract the `views` map from a (possibly malformed) computed-model dump.
 *
 * Only a PLAIN object is a valid views map. `Object.keys`/`Object.values` on an ARRAY yields positional
 * indices/elements (a `views: [{id:'x'}]` dump would leak a phantom row) and on a STRING yields its
 * characters — so a shallowly-valid but malformed dump must degrade to `{}` rather than surface bogus
 * "views" that pass a length check, get reported as `ok`, and then crash `LikeC4Model.create`.
 *
 * This is the single source of truth for the guard: the load path (`pipeline.ts`), the editor picker
 * (`listViews.ts`) and the worker compute (`compute.ts`) all funnel through it, so the invariant can no
 * longer drift between them.
 */
export function asViewsMap(data: unknown): Record<string, unknown> {
  const raw = (data as { views?: unknown } | null | undefined)?.views
  return raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : {}
}

/**
 * The prototype-pollution triad. A crafted/poisoned dump can carry these as OWN enumerable keys on
 * `views` — e.g. a directly-tampered IndexedDB row, or `JSON.parse('{"__proto__":…}')` which
 * materialises an own `"__proto__"` property — so `Object.keys(asViewsMap(data))` would otherwise
 * surface them as phantom view ids that flow into `startView` / the editor picker. They are never
 * legitimate view names. Mirrors compute.ts's `UNSAFE_SNAPSHOT_KEYS`, which strips the same triad from
 * parsed layout snapshots.
 */
export const PROTOTYPE_POLLUTING_VIEW_KEYS: ReadonlySet<string> = new Set([
  '__proto__',
  'constructor',
  'prototype',
])

/**
 * The view ids of a (possibly malformed) dump: the plain-object `views` keys with any
 * prototype-polluting own key ({@link PROTOTYPE_POLLUTING_VIEW_KEYS}) stripped. The single source of
 * truth for view-id derivation shared by the load path (`pipeline.ts`) and the picker (`listViews.ts`),
 * so a phantom `__proto__`/`constructor` key can never become a `startView` or a selectable option.
 * Declaration order is preserved (Object.keys is insertion-ordered), so the pipeline's `viewIds[0]`
 * fallback stays deterministic.
 */
export function viewIdsOf(data: unknown): string[] {
  return Object.keys(asViewsMap(data)).filter((k) => !PROTOTYPE_POLLUTING_VIEW_KEYS.has(k))
}
