import { describe, expect, it } from 'vitest'
import { describeFailure, messageOf } from '../src/errors'

const params = { project: 'grp/repo', ref: 'main', path: 'sub' }

describe('describeFailure', () => {
  it('formats parse errors as file:line — message', () => {
    const f = describeFailure({ kind: 'parse-error', errors: [{ message: 'oops', line: 3, sourceFsPath: 'm.likec4' }] }, params)
    expect(f.title).toMatch(/errors/i)
    expect(f.lines).toContain('m.likec4:3 — oops')
  })

  it('lists available views for an unknown view', () => {
    const f = describeFailure({ kind: 'unknown-view', requested: 'x', available: ['index', 'sys_detail'] }, params)
    expect(f.title).toContain('x')
    expect(f.lines.join('\n')).toContain('sys_detail')
  })

  it('renders a sensible unknown-view panel when the model has an empty views map (available: [])', () => {
    // A reachable degenerate state: a model whose views map is empty still reports the requested view as
    // unknown, with available: []. The panel must stay coherent — a real title naming the requested view,
    // the "Available views:" header kept, and NO dangling empty bullet row (the empty list maps to zero
    // "• " lines, so lines is exactly the header) — rather than crashing or emitting a stray bullet.
    const f = describeFailure({ kind: 'unknown-view', requested: 'ghost', available: [] }, params)
    expect(f.tone).toBe('error')
    expect(f.title).toContain('ghost')
    expect(f.lines).toEqual(['Available views:']) // header only; no `• ` line for the empty list
    expect(f.lines.some(l => l.trim() === '•' || l.trim() === '')).toBe(false) // no dangling empty bullet
  })

  it('caps a very large available-views list so it cannot flood the error panel', () => {
    // A model with hundreds of views would otherwise dump every id as its own bullet, making an unusably
    // tall panel (the same flood messageOf's length cap guards against for JSON blobs). Cap the listed
    // ids and summarise the remainder as an overflow line, keeping the leading (alphabetically-first) ids.
    const available = Array.from({ length: 200 }, (_, i) => `view_${String(i).padStart(3, '0')}`)
    const f = describeFailure({ kind: 'unknown-view', requested: 'x', available }, params)
    const bulletLines = f.lines.filter(l => l.startsWith('• '))
    expect(bulletLines.length).toBeLessThan(available.length) // not one bullet per view
    expect(f.lines.some(l => /\d+ more/.test(l))).toBe(true) // an "…and N more" overflow summary
    expect(f.lines.join('\n')).toContain('view_000') // the leading, useful ids are still shown
  })

  it('echoes params for not-found', () => {
    const f = describeFailure({ kind: 'not-found', detail: 'Project or ref not found' }, params)
    expect(f.lines.join('\n')).toContain('grp/repo')
    expect(f.lines.join('\n')).toContain('main')
  })

  it('shows (default)/(root) placeholders for a not-found with no ref or path', () => {
    // The not-found panel echoes ref/path; when the macro omits them they fall back to '(default)' /
    // '(root)' rather than the literal 'undefined'. Only-project params exercise those two branches
    // (the existing test always passes a concrete ref and path, so the fallbacks were untested).
    const f = describeFailure({ kind: 'not-found', detail: 'Project or ref not found' }, { project: 'grp/repo' })
    const joined = f.lines.join('\n')
    expect(joined).toContain('ref: (default)')
    expect(joined).toContain('path: (root)')
    expect(joined).not.toContain('undefined')
    expect(joined).toContain(f.lines[0]) // the detail line is still present as the first line
  })

  it('has a message for unreachable, too-large, and error', () => {
    for (const kind of ['unreachable', 'too-large', 'error'] as const) {
      const f = describeFailure({ kind, detail: 'd' } as any, params)
      expect(f.title.length).toBeGreaterThan(0)
      expect(f.tone).toBe('error')
    }
  })

  it('returns a defined fallback FailureView for an unhandled kind instead of undefined', () => {
    // The compile-time `never` guard makes a new kind a build error, but if one ever reaches here at
    // runtime the fallback must be a real FailureView (title/tone/lines) — never undefined, which would
    // crash ErrorPanel on `failure.tone`.
    const f = describeFailure({ kind: 'some-future-kind', detail: 'd' } as any, params)
    expect(f).toBeDefined()
    expect(f.title.length).toBeGreaterThan(0)
    expect(f.tone).toBe('error')
    expect(Array.isArray(f.lines)).toBe(true)
  })
})

describe('messageOf', () => {
  it('uses an Error message, falling back to String for an empty one', () => {
    expect(messageOf(new Error('boom'))).toBe('boom')
    expect(messageOf(new TypeError(''))).toBe('TypeError') // String(new TypeError('')) === 'TypeError'
  })

  it('passes strings, numbers and null/undefined through String', () => {
    expect(messageOf('plain string')).toBe('plain string')
    expect(messageOf(42)).toBe('42')
    expect(messageOf(null)).toBe('null')
    expect(messageOf(undefined)).toBe('undefined')
  })

  it('renders a plain object as JSON instead of the opaque "[object Object]"', () => {
    expect(messageOf({ code: 5, why: 'bad' })).toBe('{"code":5,"why":"bad"}')
  })

  it('truncates a very large object so a crash payload cannot flood the error panel', () => {
    // A likec4-internal crash can throw a large plain object; its JSON would otherwise be dumped whole
    // into the user-facing ErrorPanel <li> as a wall of text. Cap the rendered length with an ellipsis.
    const big = { blob: 'x'.repeat(5000) }
    const msg = messageOf(big)
    expect(msg.length).toBeLessThanOrEqual(600)
    expect(msg.endsWith('…')).toBe(true)
    expect(msg.startsWith('{"blob":"xxx')).toBe(true) // still shows the leading, useful part
  })

  it('does not truncate an already-short object (no spurious ellipsis)', () => {
    expect(messageOf({ code: 5, why: 'bad' })).toBe('{"code":5,"why":"bad"}')
  })

  it('does not split a UTF-16 surrogate pair at the truncation boundary', () => {
    // A non-BMP char (emoji, an astral identifier from a .c4 source) straddling the 500-unit cap must not
    // be cut between its surrogate halves — that would leave a lone surrogate (the ? replacement glyph)
    // just before the ellipsis. The JSON prefix {"blob":" is 9 chars, so 490 x's put the first emoji's
    // HIGH surrogate exactly at index 499 and its LOW half at 500, which a naive slice(0,500) would drop.
    const big = { blob: 'x'.repeat(490) + '😀'.repeat(20) }
    const msg = messageOf(big)
    expect(msg.endsWith('…')).toBe(true)
    expect(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(msg)).toBe(false) // no lone high surrogate
    expect(/(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(msg)).toBe(false) // no lone low surrogate
  })

  it('falls back to String for a non-serialisable (circular) object', () => {
    const circular: Record<string, unknown> = {}
    circular.self = circular
    expect(messageOf(circular)).toBe('[object Object]')
  })

  it('does not throw for a null-prototype object (String() would) and renders it as JSON', () => {
    // A thrown null-prototype value (Object.create(null)) makes String(err) throw
    // ("Cannot convert object to primitive value"). messageOf is the error-formatting helper — it must
    // NEVER itself throw, or the catch that called it (worker.ts / ErrorBoundary) blows up. It should
    // fall through to JSON.stringify, which handles null-proto objects fine.
    expect(() => messageOf(Object.create(null))).not.toThrow()
    expect(messageOf(Object.create(null))).toBe('{}')
    const withProps = Object.create(null) as Record<string, unknown>
    withProps.code = 5
    expect(messageOf(withProps)).toBe('{"code":5}')
  })
})
