import { describe, expect, it } from 'vitest'
import { listViews } from '../src/editor/listViews'

describe('listViews', () => {
  it('maps model views to id+title, falling back to id', () => {
    const data = { views: { index: { id: 'index', title: 'Landscape' }, d: { id: 'd' } } }
    expect(listViews(data)).toEqual([{ id: 'index', title: 'Landscape' }, { id: 'd', title: 'd' }])
  })

  it('returns [] for empty/absent views', () => {
    expect(listViews({})).toEqual([])
    expect(listViews(null)).toEqual([])
  })

  it('falls back to the map key when an entry lacks a string id, and drops null/non-object values', () => {
    // A view object without a string `id` must NOT be dropped: the pipeline derives its view ids (and its
    // startView) from the MAP KEYS (Object.keys(asViewsMap(data))), so dropping the entry here would leave
    // ViewPicker's <select value={startView}> with no matching <option> (React warns; nothing selectable).
    // Fall back to the map key so both consumers agree. Null/non-object values are still dropped (they
    // can't back a real view). The key is unique, so options stay uniquely-keyed and selectable.
    const data = { views: { ok: { id: 'ok', title: 'OK' }, bad: { title: 'no id' }, nul: null } }
    expect(listViews(data)).toEqual([{ id: 'ok', title: 'OK' }, { id: 'bad', title: 'no id' }])
  })

  it('keys an id-less view on its map key so the picker agrees with the pipeline startView', () => {
    // Exact reviewer scenario: {views:{index:{title:'x'}}} -> pipeline startView 'index' (a map key), so
    // the picker must offer an option whose value is 'index' or it renders an option-less <select>.
    expect(listViews({ views: { index: { title: 'x' } } })).toEqual([{ id: 'index', title: 'x' }])
  })

  it('keys the option on the MAP KEY even when a view.id differs, matching the pipeline viewIds', () => {
    // The divergence case: a (malformed) dump whose view.id differs from its map key. The pipeline
    // derives viewIds AND startView from the MAP KEYS (viewIdsOf = Object.keys(asViewsMap)), so the
    // picker MUST key its <option value> on the map key too — else <select value={startView='landscape'}>
    // has only an <option value='index'> (option-less select + React warning), and choosing it writes
    // 'index' back to params.view which the pipeline's viewIds (map keys) rejects as an unknown-view.
    // The title still honours v.title (display only); identity is the map key.
    expect(listViews({ views: { landscape: { id: 'index', title: 'Landscape' } } }))
      .toEqual([{ id: 'landscape', title: 'Landscape' }])
  })

  it('falls back to id when title is not a string', () => {
    expect(listViews({ views: { a: { id: 'a', title: 123 } } })).toEqual([{ id: 'a', title: 'a' }])
  })

  it('drops prototype-polluting own keys so a poisoned dump surfaces no phantom option', () => {
    // A tampered cache row can carry an OWN __proto__/constructor/prototype key on `views` (JSON.parse of
    // such a row materialises them as own enumerable properties). They must never become picker options —
    // parity with the pipeline's viewIdsOf, which strips the same triad from its view ids.
    const views = JSON.parse('{"__proto__":{"id":"x","title":"X"},"index":{"id":"index","title":"Home"}}')
    expect(Object.keys(views)).toContain('__proto__') // the own key really is present pre-filter
    expect(listViews({ views })).toEqual([{ id: 'index', title: 'Home' }])
  })

  it('treats a non-plain-object views (array/string) as empty, matching the pipeline guard', () => {
    // pipeline.ts rejects a `views` that is an array or string before deriving ids; listViews reads the
    // same dump and must apply the same guard. Object.values on an ARRAY yields its elements (an entry
    // like [{id:'x'}] would otherwise leak through) and on a STRING yields its characters — so a
    // shallowly-valid but malformed dump must degrade to [] here rather than surfacing phantom rows.
    expect(listViews({ views: [{ id: 'x', title: 'X' }] })).toEqual([])
    expect(listViews({ views: 'index' })).toEqual([])
  })
})
