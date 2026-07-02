import { describe, expect, it } from 'vitest'
import { asViewsMap, viewIdsOf } from '../src/viewsMap'

describe('asViewsMap', () => {
  it('returns the views map when it is a plain object', () => {
    const views = { index: { id: 'index' }, d: { id: 'd' } }
    expect(asViewsMap({ views })).toBe(views)
  })

  it('returns {} when views is absent, null, or the dump itself is nullish', () => {
    expect(asViewsMap({})).toEqual({})
    expect(asViewsMap({ views: null })).toEqual({})
    expect(asViewsMap(null)).toEqual({})
    expect(asViewsMap(undefined)).toEqual({})
  })

  it('treats a non-plain-object views (array/string/number) as empty', () => {
    // Object.keys/values on an ARRAY yields positional indices/elements (a `views: [{id:'x'}]` dump would
    // otherwise leak a phantom row) and on a STRING yields its characters — both must degrade to {} so a
    // shallowly-valid but malformed dump never surfaces bogus "views".
    expect(asViewsMap({ views: [{ id: 'x' }] })).toEqual({})
    expect(asViewsMap({ views: 'index' })).toEqual({})
    expect(asViewsMap({ views: 42 })).toEqual({})
  })

  it('is the single source of truth for the guard (pipeline, listViews and compute share it)', () => {
    // A regression here would drift all three call sites at once, which is exactly the coupling we want:
    // the invariant can no longer diverge between the load path and the picker.
    const map = { a: { id: 'a' } }
    expect(asViewsMap({ views: map })).toBe(asViewsMap({ views: map }))
  })
})

describe('viewIdsOf', () => {
  it('strips prototype-polluting own keys and preserves declaration order', () => {
    // A poisoned dump can carry an OWN __proto__/constructor/prototype key — e.g. JSON.parse of a
    // tampered cache row materialises an own "__proto__" property. Object.keys would otherwise surface
    // them as phantom view ids that flow into the pipeline's startView / the editor picker.
    const views = JSON.parse(
      '{"__proto__":{"id":"x"},"index":{"id":"index"},"constructor":{"id":"c"},"prototype":{"id":"p"},"d":{"id":"d"}}',
    )
    expect(Object.keys(views)).toContain('__proto__') // the own key really is present pre-filter
    expect(viewIdsOf({ views })).toEqual(['index', 'd'])
  })

  it('degrades a non-plain-object or nullish views to no ids', () => {
    expect(viewIdsOf({ views: [{ id: 'x' }] })).toEqual([])
    expect(viewIdsOf({ views: 'index' })).toEqual([])
    expect(viewIdsOf(null)).toEqual([])
    expect(viewIdsOf(undefined)).toEqual([])
  })
})
