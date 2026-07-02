export interface DiagramParams {
  project: string
  ref?: string
  path?: string
  view?: string
  instance?: string
}

export interface AttrSource {
  getAttribute(name: string): string | null
}

export function parseDataAttrs(el: AttrSource): DiagramParams {
  const project = el.getAttribute('data-project')?.trim()
  if (!project) throw new Error('likec4-diagram: missing required "data-project" attribute')
  const opt = (name: string) => {
    const v = el.getAttribute(name)?.trim()
    return v ? v : undefined
  }
  return {
    project,
    ref: opt('data-ref'),
    path: opt('data-path'),
    view: opt('data-view'),
    instance: opt('data-instance'),
  }
}

// A bare number (px) or a number with one of the allowed CSS length units. Mirrors the macro's
// server-side sanitizeHeight so a value that bypassed the macro (a hand-edited page, the dev path)
// still can't inject arbitrary CSS into the container's style.height.
const HEIGHT_RE = /^\d{1,5}(px|em|rem|vh|vw|%)?$/

/**
 * Normalise an author-supplied diagram height: a bare number becomes px (`600` → `600px`), a valid
 * CSS length passes through (`80vh`), and anything else returns `undefined` (the CSS default applies).
 * The single place the boot-time height contract lives, so it is testable and consistent.
 */
export function parseHeight(raw: string | null | undefined): string | undefined {
  const h = raw?.trim()
  if (!h || !HEIGHT_RE.test(h)) return undefined
  // Reject a percentage: a `.likec4-diagram` in a Confluence page body has no explicit-height ancestor,
  // so a `%` height resolves against an auto/0 parent and collapses the container to an invisible
  // diagram — the same failure the zero-guard below prevents. Fall back to the CSS default instead.
  // (HEIGHT_RE deliberately still ACCEPTS `%` so it stays a faithful mirror of the macro's server-side
  // CSS-injection-safety regex; this collapse-guard is the frontend's extra safeguard, exactly like the
  // macro accepting `0px` while this function rejects it.)
  if (h.endsWith('%')) return undefined
  // Reject a zero / all-zero numeric value (`0`, `0px`, `00000`, `0vh`): it passes the regex but
  // would set style.height to 0 and collapse the container to an invisible diagram — fall back to the
  // CSS default instead.
  if (parseInt(h, 10) <= 0) return undefined
  return /^\d+$/.test(h) ? `${h}px` : h
}
