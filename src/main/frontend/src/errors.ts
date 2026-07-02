import type { DiagramParams } from './dataAttrs'
import type { LoadResult } from './pipeline'

export interface FailureView {
  title: string
  lines: string[]
  tone: 'error' | 'warning'
}

export type LoadFailure = Exclude<LoadResult, { kind: 'ok' }>

/**
 * A human-readable message for an arbitrary thrown value (mirrors ErrorBoundary's `message || String`).
 * An Error yields its message (or its `String()` form when the message is empty); anything else yields
 * `String()`, except a plain object — whose `String()` is the opaque `"[object Object]"` — which we try
 * to render as JSON so the user-facing panel isn't a useless `[object Object]`.
 */
export function messageOf(err: unknown): string {
  if (err instanceof Error) return err.message || safeString(err)
  // String(err) THROWS for a null-prototype object ("Cannot convert object to primitive value"), so a
  // thrown Object.create(null) would make this error-formatting helper itself throw — blowing up the
  // catch that called it (worker.ts / ErrorBoundary). safeString swallows that, yielding "[object
  // Object]" so we then fall through to JSON.stringify (which handles null-proto objects fine).
  const s = safeString(err)
  if (s !== '[object Object]') return s
  try {
    const json = JSON.stringify(err)
    return json == null ? s : truncate(json)
  } catch {
    return s // circular / non-serialisable — fall back to String()
  }
}

// A likec4-internal crash can throw a large plain object whose JSON would otherwise flood the user-facing
// ErrorPanel <li> as a wall of text. Cap the rendered length, keeping the leading (most useful) part and
// marking the elision so it reads as truncated rather than as a malformed message.
const MAX_JSON_LEN = 500
function truncate(s: string): string {
  if (s.length <= MAX_JSON_LEN) return s
  // Cut on a code-point boundary. If the char at the cut index is a lone HIGH surrogate (its LOW half sits
  // at MAX_JSON_LEN, which we're about to drop), back off one code unit — otherwise the panel would show a
  // lone-surrogate replacement glyph (U+FFFD) right before the ellipsis for a non-BMP char (emoji, astral
  // identifier) that happens to straddle the boundary.
  let end = MAX_JSON_LEN
  const last = s.charCodeAt(end - 1)
  if (last >= 0xd800 && last <= 0xdbff) end -= 1
  return s.slice(0, end) + '…'
}

/** String(v) that never throws (a null-prototype object makes the real String() throw). */
function safeString(v: unknown): string {
  try {
    return String(v)
  } catch {
    return '[object Object]'
  }
}

// A model can legitimately carry many views; listing every id as its own bullet would make an unusably
// tall error panel (the flood messageOf's length cap guards against for JSON blobs). Show at most
// MAX_VIEW_LINES ids — the leading ones in the model's view-declaration order (the caller passes
// viewIdsOf(...) === Object.keys order, NOT sorted) — and summarise the remainder.
const MAX_VIEW_LINES = 50
function cappedViewList(available: readonly string[]): string[] {
  if (available.length <= MAX_VIEW_LINES) return available.map(v => `• ${v}`)
  const shown = available.slice(0, MAX_VIEW_LINES).map(v => `• ${v}`)
  shown.push(`• …and ${available.length - MAX_VIEW_LINES} more`)
  return shown
}

export function describeFailure(result: LoadFailure, params: DiagramParams): FailureView {
  switch (result.kind) {
    case 'parse-error':
      return {
        title: 'LikeC4 source has errors',
        tone: 'error',
        lines: result.errors.map(e => `${e.sourceFsPath || 'source'}:${e.line} — ${e.message}`),
      }
    case 'unknown-view':
      return {
        title: `Unknown view "${result.requested}"`,
        tone: 'error',
        lines: ['Available views:', ...cappedViewList(result.available)],
      }
    case 'not-found':
      return {
        title: 'Diagram source not found',
        tone: 'error',
        lines: [result.detail, `project: ${params.project}`, `ref: ${params.ref ?? '(default)'}`, `path: ${params.path ?? '(root)'}`],
      }
    case 'unreachable':
      return { title: 'Cannot reach source repository', tone: 'error', lines: [result.detail] }
    case 'too-large':
      return { title: 'Diagram too large to render', tone: 'error', lines: [result.detail] }
    case 'error':
      return { title: 'Could not render diagram', tone: 'error', lines: [result.detail] }
    default: {
      // Exhaustiveness guard: adding a LoadResult failure kind without a case above is a COMPILE error
      // (`result` narrows to `never` only when every kind is handled). tsconfig has neither
      // noImplicitReturns nor an exhaustive switch check, so without this a new kind would silently
      // return `undefined` and crash ErrorPanel on `failure.tone`. The runtime fallback is belt-and-
      // braces should an unhandled kind ever reach here at runtime.
      const _exhaustive: never = result
      void _exhaustive
      return { title: 'Could not render diagram', tone: 'error', lines: [] }
    }
  }
}
