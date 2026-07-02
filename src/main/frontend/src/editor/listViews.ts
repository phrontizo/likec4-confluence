import { asViewsMap, PROTOTYPE_POLLUTING_VIEW_KEYS } from '../viewsMap'

export interface ViewInfo {
  id: string
  title: string
}

export function listViews(data: unknown): ViewInfo[] {
  // asViewsMap applies the same "PLAIN object only" guard as the pipeline load path: an array/string
  // `views` degrades to {} here (Object.values on an array leaks its elements, on a string its chars),
  // so a malformed-but-shallowly-valid dump never surfaces bogus options. The per-entry filter below is
  // a second line of defence for the plain-object case.
  const views = asViewsMap(data)
  // Mirror the pipeline's view IDENTITY exactly: it derives view ids AND the startView from the MAP KEYS
  // (viewIdsOf = Object.keys(asViewsMap(data))), so key each option on its MAP KEY — never on the view
  // object's own `id`. If a (malformed) dump carried a view whose `id` differed from its map key, keying
  // on `v.id` would leave ViewPicker's <select value={startView}> (a map key) option-less AND, worse,
  // make choosing that option write the divergent `v.id` back to params.view, which the pipeline's
  // viewIds (map keys) then reject as an unknown-view. The map key is the single source of truth. `title`
  // is display-only: honour a string `v.title`, else fall back to the key. Null/non-object values drop
  // out (they can't back a real view); the map key is unique, so every option stays uniquely-keyed.
  return Object.entries<any>(views)
    // Drop prototype-polluting own keys (__proto__/constructor/prototype) so a poisoned dump can't
    // surface a phantom picker option — parity with the pipeline's viewIdsOf (see PROTOTYPE_POLLUTING_VIEW_KEYS).
    .filter(([key, v]) => !PROTOTYPE_POLLUTING_VIEW_KEYS.has(key) && v != null && typeof v === 'object')
    .map(([key, v]) => ({ id: key, title: typeof v.title === 'string' ? v.title : key }))
}
