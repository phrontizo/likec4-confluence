import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { compute } from '../src/compute'
import { loadSources } from './support/sources'

const dir = (name: string) => fileURLToPath(new URL(`./fixtures/likec4/${name}`, import.meta.url))

// likec4 routes an edge endpoint to a node's border handle and leaves a small visual gap; allow a
// little slack so an endpoint sitting just outside its node border still counts as "connected".
const EDGE_ENDPOINT_TOL = 24

/** True when point [x,y] lies within node n's box (grown by tol on every side). */
function pointTouchesNode(p: [number, number], n: any, tol = EDGE_ENDPOINT_TOL): boolean {
  return p[0] >= n.x - tol && p[0] <= n.x + n.width + tol
    && p[1] >= n.y - tol && p[1] <= n.y + n.height + tol
}

describe('compute', () => {
  it('computes a layouted model with the index view', async () => {
    const { data, errors, drifts } = await compute(loadSources(dir('target')))
    expect(errors).toEqual([])
    const d = data as any
    expect(d._stage).toBe('layouted')
    expect(Object.keys(d.views)).toEqual(expect.arrayContaining(['index', 'sys_detail']))
    const index = d.views.index
    expect(index.nodes.length).toBeGreaterThan(0)
    expect(typeof index.nodes[0].x).toBe('number')
    expect(drifts).toEqual([])
  })

  it('surfaces parse/validation errors with line numbers', async () => {
    const { errors } = await compute(loadSources(dir('broken')))
    expect(errors.length).toBeGreaterThan(0)
    expect(errors[0]).toMatchObject({ message: expect.any(String), line: expect.any(Number) })
    expect(errors[0].sourceFsPath).toContain('model.likec4')
  })

  it('applies a manual-layout snapshot and proves it overrides auto-layout', async () => {
    const sources = loadSources(dir('target'))
    expect(Object.keys(sources)).toContain('.likec4/index.likec4.snap')

    const autoOnly = Object.fromEntries(
      Object.entries(sources).filter(([k]) => !k.endsWith('.likec4.snap')),
    )
    const auto = (await compute(autoOnly)).data as any
    const autoSys = auto.views.index.nodes.find((n: any) => n.id === 'sys')

    const { data, errors } = await compute(sources)
    expect(errors).toEqual([])
    const index = (data as any).views.index
    expect(index._layout).toBe('manual')
    const sys = index.nodes.find((n: any) => n.id === 'sys')
    expect(sys.x).toBe(autoSys.x + 500)
    const ext = index.nodes.find((n: any) => n.id === 'ext')
    const autoExt = auto.views.index.nodes.find((n: any) => n.id === 'ext')
    expect(ext.x).toBe(autoExt.x)

    // REGRESSION: the snapshot shifts a node without updating its (stale) `bounds`, and
    // applyManualLayout copies bounds verbatim. We must re-derive bounds so they ENCLOSE every
    // node — otherwise likec4's fitView centres a too-small box and the node clips off-screen
    // (the "mounts but invisible" class of bug). Bounds must cover the shifted sys node.
    for (const n of index.nodes) {
      expect(n.x, `${n.id} left within bounds`).toBeGreaterThanOrEqual(index.bounds.x)
      expect(n.y, `${n.id} top within bounds`).toBeGreaterThanOrEqual(index.bounds.y)
      expect(n.x + n.width, `${n.id} right within bounds`).toBeLessThanOrEqual(index.bounds.x + index.bounds.width)
      expect(n.y + n.height, `${n.id} bottom within bounds`).toBeLessThanOrEqual(index.bounds.y + index.bounds.height)
    }
  })

  it('renders a COHERENT manual-layout snapshot (each edge connects its source and target nodes)', async () => {
    // REGRESSION: a manual-layout snapshot must stay internally coherent. The original fixture
    // shifted the `sys` node by +500 but left edge `dh52re`'s pinned points at the node's OLD
    // position, so the "calls" edge dangled in empty space and `sys` floated away disconnected.
    // applyManualLayout renders pinned geometry verbatim, so an incoherent snapshot renders broken.
    // Each edge's first point must touch its SOURCE node and its last point its TARGET node.
    const { data, errors } = await compute(loadSources(dir('target')))
    expect(errors).toEqual([])
    const index = (data as any).views.index
    const byId = (id: string) => index.nodes.find((n: any) => n.id === id)
    expect(index.edges.length).toBeGreaterThan(0)
    for (const e of index.edges) {
      const src = byId(e.source)
      const tgt = byId(e.target)
      expect(src, `edge ${e.id} source node`).toBeTruthy()
      expect(tgt, `edge ${e.id} target node`).toBeTruthy()
      expect(e.points.length).toBeGreaterThanOrEqual(2)
      const first = e.points[0]
      const last = e.points[e.points.length - 1]
      expect(pointTouchesNode(first, src), `edge ${e.id} source endpoint ${JSON.stringify(first)} must touch '${e.source}' box [${src.x},${src.x + src.width}]x[${src.y},${src.y + src.height}]`).toBe(true)
      expect(pointTouchesNode(last, tgt), `edge ${e.id} target endpoint ${JSON.stringify(last)} must touch '${e.target}' box [${tgt.x},${tgt.x + tgt.width}]x[${tgt.y},${tgt.y + tgt.height}]`).toBe(true)
    }
  })

  it('a malformed snapshot degrades gracefully — it does not fail the whole model or yield non-finite bounds', async () => {
    // A .snap is attacker-influenceable content from GitLab. A structurally-broken or non-finite
    // snapshot must NOT reject the entire compute (which would blank an otherwise-valid diagram) nor
    // produce an Infinity-width `bounds` that breaks fitView ("mounts but invisible"). The affected
    // view falls back to auto-layout; the model and its other views still render.
    const sources = loadSources(dir('target'))
    sources['.likec4/index.likec4.snap'] =
      '{ _layout: "manual", nodes: [{ id: "sys", x: Infinity, y: 0, width: 10, height: 10 }] }'
    const { data, errors } = await compute(sources)
    expect(errors).toEqual([]) // the model itself is valid; only the snapshot was bad
    const index = (data as any).views.index
    expect(index.nodes.length).toBeGreaterThan(0)
    expect(Number.isFinite(index.bounds.width), 'bounds.width must be finite').toBe(true)
    expect(Number.isFinite(index.bounds.height), 'bounds.height must be finite').toBe(true)
  })

  it('ignores a non-string source value rather than crashing the compute (trust-boundary guard)', async () => {
    // `sources` crosses the worker's structured-clone boundary and worker.ts validates only the OBJECT
    // shape, not that every value is a string. A non-string content (a future refactor posting the wrong
    // type, or a caller bypassing restClient's per-value validation) must be skipped with a warning — not
    // passed to fromSources (a non-.likec4 entry) or JSON5.parse (a .snap entry) where it surfaces as an
    // opaque "content.endsWith is not a function" / parse crash that blanks an otherwise-valid diagram.
    const warns: string[] = []
    const orig = console.warn
    console.warn = (...a: unknown[]) => { warns.push(a.map(String).join(' ')) }
    try {
      const polluted: Record<string, string> = {
        ...loadSources(dir('target')),
        'junk.likec4': 42 as unknown as string, // non-.snap, non-string
        'bad.likec4.snap': { nodes: [] } as unknown as string, // .snap, non-string
      }
      const { data, errors } = await compute(polluted)
      const d = data as any
      expect(d._stage).toBe('layouted') // the valid sources still computed
      expect(d.views.index.nodes.length).toBeGreaterThan(0)
      expect(errors).toEqual([]) // the numeric entry did NOT become a phantom parse-error line
      expect(warns.some(w => w.includes('junk.likec4'))).toBe(true)
      expect(warns.some(w => w.includes('bad.likec4.snap'))).toBe(true)
    } finally {
      console.warn = orig
    }
  })

  it('ignores a snapshot that references an unknown view without failing the model', async () => {
    // A .snap whose viewId has no matching model view must warn-and-continue, not throw or inject a
    // bogus view — the rest of the model still computes normally.
    const sources = loadSources(dir('target'))
    sources['.likec4/ghost.likec4.snap'] = '{ _layout: "manual", nodes: [] }'
    const { data, errors, drifts } = await compute(sources)
    expect(errors).toEqual([])
    const d = data as any
    expect(d.views.ghost).toBeUndefined()
    expect(d.views.index.nodes.length).toBeGreaterThan(0)
    expect(drifts.find(x => x.viewId === 'ghost')).toBeUndefined()
  })

  it('treats a snapshot named after a prototype key (__proto__) as an unknown view', async () => {
    // A .snap basename that collides with an Object.prototype key (e.g. `__proto__`, `constructor`)
    // must be handled exactly like any other unknown view: a truthy `data.views['__proto__']` lookup
    // would resolve to the inherited Object.prototype and bypass the unknown-view guard, sending a
    // bogus "auto layout" into applyManualLayout (and risking a `data.views.__proto__ = …` prototype
    // write). The lookup must use own-property semantics.
    const warns: string[] = []
    const orig = console.warn
    console.warn = (...a: unknown[]) => { warns.push(a.map(String).join(' ')) }
    try {
      const sources = loadSources(dir('target'))
      sources['.likec4/__proto__.likec4.snap'] = '{ _layout: "manual", nodes: [] }'
      const { data, errors, drifts } = await compute(sources)
      expect(errors).toEqual([])
      const d = data as any
      expect(d.views.index.nodes.length).toBeGreaterThan(0) // real view unaffected
      expect(Object.getPrototypeOf(d.views)).toBe(Object.prototype) // not prototype-polluted
      expect(drifts.find(x => x.viewId === '__proto__')).toBeUndefined()
      // It is reported as an UNKNOWN view, not as a failed-to-apply snapshot.
      expect(warns.some(w => w.includes('unknown view') && w.includes('__proto__'))).toBe(true)
      expect(warns.some(w => w.includes('Failed to apply'))).toBe(false)
    } finally {
      console.warn = orig
    }
  })

  it('treats a snapshot whose basename is exactly the suffix (empty view id) as an unknown view', async () => {
    // A file literally named `.likec4.snap` yields an EMPTY view id — viewIdFromSnapKey slices the whole
    // basename off. `Object.hasOwn(data.views, '')` is false, so it must warn-and-continue like any other
    // unknown view: never throw, never inject a bogus `views[''] = …` entry.
    const warns: string[] = []
    const orig = console.warn
    console.warn = (...a: unknown[]) => { warns.push(a.map(String).join(' ')) }
    try {
      const sources = loadSources(dir('target'))
      sources['.likec4/.likec4.snap'] = '{ _layout: "manual", nodes: [] }'
      const { data, errors, drifts } = await compute(sources)
      expect(errors).toEqual([])
      const d = data as any
      expect(d.views.index.nodes.length).toBeGreaterThan(0) // real view unaffected
      expect(Object.hasOwn(d.views, '')).toBe(false) // no empty-id view injected
      expect(drifts.find(x => x.viewId === '')).toBeUndefined()
      expect(warns.some(w => w.includes('unknown view'))).toBe(true) // reported unknown, not applied
      expect(warns.some(w => w.includes('Failed to apply'))).toBe(false)
    } finally {
      console.warn = orig
    }
  })

  it('strips prototype-polluting keys from a snapshot BODY for a valid view', async () => {
    // A .snap is attacker-influenceable content from GitLab. JSON5 surfaces a literal `__proto__`/
    // `constructor` as an OWN enumerable key, which `{ ...view }` would copy straight into the object
    // handed to applyManualLayout. Strip them at the parse boundary so no prototype-polluting key ever
    // reaches likec4's layout merge, and Object.prototype stays clean — while the valid `nodes`/`_layout`
    // body still applies (here a no-op empty layout, falling back without error).
    const before = ({} as Record<string, unknown>).polluted
    const sources = loadSources(dir('target'))
    sources['.likec4/index.likec4.snap'] =
      '{ "__proto__": { "polluted": "yes" }, "constructor": { "x": 1 }, "_layout": "manual", "nodes": [] }'
    const { data, errors } = await compute(sources)
    expect(errors).toEqual([])
    expect(({} as Record<string, unknown>).polluted, 'Object.prototype must not be polluted').toBe(before)
    const index = (data as any).views.index
    expect(index.nodes.length).toBeGreaterThan(0) // real view still computed
    expect(Object.getPrototypeOf(index)).toBe(Object.prototype) // not prototype-corrupted
  })

  it('strips prototype-polluting keys NESTED inside a snapshot body, not just the top level', async () => {
    // Defense in depth: stripping only the top level would leave a nested `__proto__` (buried inside a
    // deeper field) to reach likec4's layout merge, which may recursively copy source→target. The parse
    // reviver drops the unsafe keys at EVERY depth, so Object.prototype stays clean regardless of how
    // deep the attacker buries the key — while the valid `nodes`/`_layout` body still applies.
    const before = ({} as Record<string, unknown>).deepPolluted
    const sources = loadSources(dir('target'))
    sources['.likec4/index.likec4.snap'] =
      '{ "_layout": "manual", "nodes": [], "meta": { "x": { "__proto__": { "deepPolluted": "yes" } } } }'
    const { data, errors } = await compute(sources)
    expect(errors).toEqual([])
    expect(({} as Record<string, unknown>).deepPolluted, 'Object.prototype must not be polluted from a nested key').toBe(before)
    const index = (data as any).views.index
    expect(index.nodes.length).toBeGreaterThan(0) // real view still computed
  })

  it('ignores an unparseable snapshot (invalid JSON5) and still computes the model', async () => {
    // A corrupt .snap must not abort compute; the affected view falls back to auto-layout.
    const sources = loadSources(dir('target'))
    sources['.likec4/index.likec4.snap'] = '{ this : is : not : valid json5 :::'
    const { data, errors } = await compute(sources)
    expect(errors).toEqual([])
    const index = (data as any).views.index
    expect(index.nodes.length).toBeGreaterThan(0)
    expect(index._layout).not.toBe('manual') // the bad snapshot was dropped, so auto-layout stands
  })

  it('reports drift when the model changed since the snapshot', async () => {
    const { drifts, errors } = await compute(loadSources(dir('drifted')))
    expect(errors).toEqual([])
    const indexDrift = drifts.find(d => d.viewId === 'index')
    expect(indexDrift, 'expected index view to be flagged as drifted').toBeTruthy()
    expect(indexDrift!.reasons).toContain('nodes-added')
  })
})
