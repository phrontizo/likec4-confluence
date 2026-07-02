import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import JSON5 from 'json5'
import { compute } from '../../src/compute'
import { loadSources } from '../support/sources'

const targetDir = fileURLToPath(new URL('../fixtures/likec4/target', import.meta.url))
const SHIFT = 500

// Compute the AUTO layout only: drop any existing `.likec4.snap` from the sources, otherwise compute
// would apply the previous manual snapshot and re-running this script would compound the shift
// (sys.x: 0 -> 500 -> 1000 ...). Regeneration must be idempotent and start from a clean auto layout.
const sources = loadSources(targetDir)
const autoOnly = Object.fromEntries(
  Object.entries(sources).filter(([k]) => !k.endsWith('.likec4.snap')),
)
const { data } = await compute(autoOnly)
const view = structuredClone((data as any).views.index)

// Curate a MANUAL layout that visibly differs from auto-layout BUT stays internally COHERENT:
// shift node[0] (`sys`) to the right by SHIFT, then carry everything that is anchored to it along —
// its label, and every edge that starts on it must be re-routed to the node's new border handle.
// applyManualLayout renders pinned geometry verbatim, so a snapshot that moved a node without moving
// its connected edge would render a dangling "calls" edge with the node floating disconnected.
const sys = view.nodes[0]
sys.x += SHIFT
if (sys.labelBBox) sys.labelBBox.x += SHIFT

// Re-route each edge leaving `sys` from the node's new bottom-centre handle to its existing target
// endpoint with a simple orthogonal (down / across / down) path, and recentre the edge's label.
const bottomCentre = (n: any): [number, number] => [n.x + n.width / 2, n.y + n.height]
for (const e of view.edges) {
  if (e.source !== sys.id || !Array.isArray(e.points) || e.points.length === 0) continue
  const start = bottomCentre(sys)
  const end = e.points[e.points.length - 1] as [number, number]
  const midY = (start[1] + end[1]) / 2
  e.points = [start, [start[0], midY], [end[0], midY], end]
  if (e.labelBBox) {
    e.labelBBox.x = (start[0] + end[0]) / 2 - e.labelBBox.width / 2
    e.labelBBox.y = midY - e.labelBBox.height / 2
  }
}

// `bounds` is the derived content box react-flow fits to; recompute it so it encloses the shifted
// geometry (compute.ts re-derives this at render time too, but keep the on-disk snapshot honest).
let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
const grow = (x: number, y: number, w = 0, h = 0) => {
  minX = Math.min(minX, x); minY = Math.min(minY, y)
  maxX = Math.max(maxX, x + w); maxY = Math.max(maxY, y + h)
}
for (const n of view.nodes) grow(n.x, n.y, n.width, n.height)
for (const e of view.edges) {
  for (const p of e.points ?? []) grow(p[0], p[1])
  if (e.labelBBox) grow(e.labelBBox.x, e.labelBBox.y, e.labelBBox.width, e.labelBBox.height)
}
view.bounds = { x: minX, y: minY, width: maxX - minX, height: maxY - minY }

view._layout = 'manual'

mkdirSync(`${targetDir}/.likec4`, { recursive: true })
writeFileSync(`${targetDir}/.likec4/index.likec4.snap`, JSON5.stringify(view, null, 2), 'utf8')
console.log(
  `Wrote coherent target snapshot; node[0] '${sys.id}' x shifted by ${SHIFT}, `
  + `${view.edges.length} edge(s) re-routed, bounds=${JSON5.stringify(view.bounds)}`,
)
