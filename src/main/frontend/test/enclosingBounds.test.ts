import { describe, expect, it } from 'vitest'
import { enclosingBounds } from '../src/compute'

describe('enclosingBounds', () => {
  it('encloses node rects and edge points', () => {
    const view = {
      nodes: [{ x: 0, y: 0, width: 100, height: 50 }, { x: 200, y: 100, width: 40, height: 40 }],
      edges: [{ points: [[10, 10], [260, 150]] }],
    }
    expect(enclosingBounds(view)).toEqual({ x: 0, y: 0, width: 260, height: 150 })
  })

  it('does not let one node with a non-finite size poison the whole view bounds', () => {
    // A .snap is parsed from attacker-influenceable content. Previously a NaN width made maxX = NaN,
    // failing the all-finite guard and discarding the entire view's curated bounds even though every
    // other node was fine. The broken node now contributes its ORIGIN only; the good nodes still frame.
    const view = {
      nodes: [
        { x: 0, y: 0, width: 100, height: 50 },
        { x: 300, y: 200, width: Number.NaN, height: 40 }, // broken width
      ],
      edges: [],
    }
    const b = enclosingBounds(view)
    expect(b).not.toBeNull()
    expect(Number.isFinite(b!.width)).toBe(true)
    expect(Number.isFinite(b!.height)).toBe(true)
    // bounds span the good node and the broken node's origin (300,200), but not its NaN extent.
    expect(b).toEqual({ x: 0, y: 0, width: 300, height: 240 })
  })

  it('drops an Infinity coordinate rather than producing an Infinity-width box', () => {
    const view = {
      nodes: [{ x: 0, y: 0, width: 100, height: 50 }, { x: Infinity, y: 10, width: 10, height: 10 }],
      edges: [],
    }
    // The Infinity-origin node is skipped entirely; the finite node defines the box.
    expect(enclosingBounds(view)).toEqual({ x: 0, y: 0, width: 100, height: 50 })
  })

  it('grows the box to include an edge labelBBox', () => {
    // enclosingBounds reads edges[].labelBBox as a distinct grow-with-w/h branch — a moved edge label can
    // sit outside every node rect and every routing point. Pin it so a likec4 rename of labelBBox is a
    // failure here, not a silently-clipped label caught only by the e2e gate.
    const view = {
      nodes: [{ x: 0, y: 0, width: 100, height: 50 }],
      edges: [{ points: [[10, 10]], labelBBox: { x: 120, y: 60, width: 40, height: 20 } }],
    }
    // The label at (120,60)+(40,20) extends the box beyond the single node.
    expect(enclosingBounds(view)).toEqual({ x: 0, y: 0, width: 160, height: 80 })
  })

  it('lets a non-finite labelBBox contribute its origin only, not poison the bounds', () => {
    const view = {
      nodes: [{ x: 0, y: 0, width: 100, height: 50 }],
      edges: [{ points: [], labelBBox: { x: 200, y: 100, width: Number.NaN, height: 20 } }],
    }
    const b = enclosingBounds(view)
    expect(b).not.toBeNull()
    // The label's NaN width is dropped (its origin 200,100 still counts); the box spans node + origin.
    expect(b).toEqual({ x: 0, y: 0, width: 200, height: 120 })
  })

  it('skips malformed edge points (empty / short / non-finite) without poisoning the bounds', () => {
    // .snap edge points are parsed from attacker-influenceable content; a point array can be [] or [x]
    // (missing y) or carry a NaN. enclosingBounds only index-accesses p[0]/p[1], so a missing/NaN
    // coordinate is undefined/NaN → Number.isFinite is false → the point is skipped, and the good
    // geometry still frames the view (rather than collapsing it to null or NaN).
    const view = {
      nodes: [{ x: 0, y: 0, width: 100, height: 50 }],
      edges: [{ points: [[], [5], [Number.NaN, 5], [10, 80]] as number[][] }],
    }
    const b = enclosingBounds(view)
    expect(b).not.toBeNull()
    // Only the well-formed point (10,80) counts alongside the node rect; the malformed three are dropped.
    expect(b).toEqual({ x: 0, y: 0, width: 100, height: 80 })
  })

  it('encloses a node whose width/height is negative (its true left/top edge, not just the origin)', () => {
    // A .snap is parsed from attacker-influenceable content, so a node can carry a finite but NEGATIVE
    // width/height — meaning its real left/top edge is x+w / y+h, which sits BELOW the origin. The box
    // must fold in BOTH corners (min uses x+w, max uses x) so that true edge is enclosed rather than
    // clipped; otherwise minX/minY use only the origin and the node's left/top spills outside the frame.
    const view = {
      nodes: [
        { x: 200, y: 0, width: 50, height: 50 }, // a normal node anchoring the right/top extent
        { x: 100, y: 100, width: -40, height: -30 }, // true edges (60,70)→(100,100)
      ],
      edges: [] as { points?: number[][] }[],
    }
    // Left edge 60 (=100-40) and bottom edge 100 must both be enclosed; before folding, minX used the
    // origin 100 and clipped the node's left side, and maxY stopped at 70 instead of the origin 100.
    expect(enclosingBounds(view)).toEqual({ x: 60, y: 0, width: 190, height: 100 })
  })

  it('returns null when no geometry is finite (caller falls back to the verbatim layout)', () => {
    expect(enclosingBounds({ nodes: [{ x: Number.NaN, y: Number.NaN }], edges: [] })).toBeNull()
    expect(enclosingBounds({ nodes: [], edges: [] })).toBeNull()
  })

  it('returns null for a degenerate zero-area box (caller keeps the verbatim layout bounds)', () => {
    // A finite but point-/line-only result collapses fitView to an invisible frame exactly like a
    // non-finite one, and being truthy it would clobber the snapshot's own bounds at the call site.
    // Treat it as null so compute() keeps the verbatim laid-out bounds instead.
    expect(enclosingBounds({ nodes: [{ x: 5, y: 5, width: 0, height: 0 }], edges: [] })).toBeNull()
    expect(enclosingBounds({ nodes: [], edges: [{ points: [[7, 7]] }] })).toBeNull() // a single point
    // Zero height (a horizontal line of points) is degenerate too.
    expect(enclosingBounds({ nodes: [], edges: [{ points: [[0, 3], [10, 3]] }] })).toBeNull()
  })
})
