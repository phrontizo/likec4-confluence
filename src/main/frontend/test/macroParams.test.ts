import { describe, expect, test } from 'vitest'
import { toMacroParamMap } from '../src/editor/macroEditor'

// The macro editor strips empty/undefined fields before writing the macro back into the page, so a
// blank optional field never emits an empty attribute. This is the pure half of that logic (the DOM
// half — reading the inputs — is exercised by the live editor e2e).
describe('toMacroParamMap', () => {
  test('drops undefined and empty-string params, keeps the rest', () => {
    expect(toMacroParamMap({ project: 'grp/repo', ref: undefined, path: '', view: 'index' }))
      .toEqual({ project: 'grp/repo', view: 'index' })
  })

  test('keeps every present non-empty value', () => {
    const full = { project: 'grp/repo', ref: 'main', path: 'sub', view: 'v', instance: '2', height: '600px' }
    expect(toMacroParamMap(full)).toEqual(full)
  })

  test('returns an empty object when nothing is set', () => {
    expect(toMacroParamMap({})).toEqual({})
  })

  test('does not treat "0" or "false"-ish strings as empty', () => {
    // Only undefined and the empty string are dropped; a literal "0" height must survive.
    expect(toMacroParamMap({ project: 'g/r', height: '0' })).toEqual({ project: 'g/r', height: '0' })
  })
})
