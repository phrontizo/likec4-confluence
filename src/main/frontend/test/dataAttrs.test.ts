import { describe, expect, it } from 'vitest'
import { parseDataAttrs, parseHeight } from '../src/dataAttrs'

const el = (attrs: Record<string, string>) => ({
  getAttribute: (n: string) => (n in attrs ? attrs[n] : null),
})

describe('parseDataAttrs', () => {
  it('reads all attributes, trimming values', () => {
    expect(parseDataAttrs(el({
      'data-project': ' grp/repo ',
      'data-ref': 'main',
      'data-path': 'sub',
      'data-view': 'index',
      'data-instance': '7',
    }))).toEqual({ project: 'grp/repo', ref: 'main', path: 'sub', view: 'index', instance: '7' })
  })

  it('treats blank optionals as undefined', () => {
    expect(parseDataAttrs(el({ 'data-project': 'grp/repo', 'data-ref': '  ' })))
      .toEqual({ project: 'grp/repo', ref: undefined, path: undefined, view: undefined, instance: undefined })
  })

  it('throws when project is missing', () => {
    expect(() => parseDataAttrs(el({ 'data-ref': 'main' }))).toThrow(/data-project/)
  })

  it('throws when project is present but whitespace-only', () => {
    // A whitespace-only data-project trims to '' (falsy) and must be rejected exactly like an absent one,
    // not passed through as an empty project id. Only the fully-absent case was pinned before.
    expect(() => parseDataAttrs(el({ 'data-project': '   ' }))).toThrow(/data-project/)
  })
})

describe('parseHeight', () => {
  it('turns a bare number into px', () => {
    expect(parseHeight('600')).toBe('600px')
    expect(parseHeight(' 480 ')).toBe('480px')
  })

  it('passes a valid absolute / viewport CSS length through', () => {
    expect(parseHeight('80vh')).toBe('80vh')
    expect(parseHeight('600px')).toBe('600px')
    expect(parseHeight('40rem')).toBe('40rem')
    expect(parseHeight('50em')).toBe('50em')
    expect(parseHeight('90vw')).toBe('90vw')
  })

  it('rejects a percentage height that would collapse against an unsized parent', () => {
    // A `.likec4-diagram` in a Confluence page body has no explicit-height ancestor, so a `%` height
    // resolves against an auto/0 parent and collapses the container to ~0 — the same invisible-diagram
    // failure the zero-guard prevents. Fall back to the CSS default (visible) rather than emit it.
    expect(parseHeight('50%')).toBeUndefined()
    expect(parseHeight('100%')).toBeUndefined()
  })

  it('ignores blank and unset values', () => {
    expect(parseHeight(undefined)).toBeUndefined()
    expect(parseHeight(null)).toBeUndefined()
    expect(parseHeight('   ')).toBeUndefined()
  })

  it('rejects garbage / CSS-injection attempts rather than passing them to style.height', () => {
    expect(parseHeight('480; position:fixed')).toBeUndefined()
    expect(parseHeight('100vw)} body{display:none')).toBeUndefined()
    expect(parseHeight('calc(100% - 4px)')).toBeUndefined()
    expect(parseHeight('-10px')).toBeUndefined()
    expect(parseHeight('999999')).toBeUndefined() // > 5 digits
  })

  it('rejects a zero / all-zero height that would collapse the diagram to invisible', () => {
    expect(parseHeight('0')).toBeUndefined()
    expect(parseHeight('0px')).toBeUndefined()
    expect(parseHeight('00000')).toBeUndefined()
    expect(parseHeight('0vh')).toBeUndefined()
  })
})
