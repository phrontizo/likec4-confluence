/**
 * One-off scale probe (NOT a unit test): run the plugin's own `compute()` over a real LikeC4 model
 * directory and report counts / per-view node+edge counts / compute time. Usage:
 *   npx tsx test/scripts/probe-bigmodel.ts <model-dir>
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { performance } from 'node:perf_hooks'
import { compute } from '../../src/compute'

const KEEP = (n: string) => n.endsWith('.c4') || n.endsWith('.likec4') || n.endsWith('.likec4.snap')

function walk(base: string): Record<string, string> {
  const out: Record<string, string> = {}
  const rec = (cur: string) => {
    for (const name of readdirSync(cur)) {
      const full = join(cur, name)
      if (statSync(full).isDirectory()) rec(full)
      else if (KEEP(name)) out[relative(base, full).split(sep).join('/')] = readFileSync(full, 'utf8')
    }
  }
  rec(base)
  return out
}

async function main() {
  const dir = process.argv[2]
  if (!dir) throw new Error('usage: probe-bigmodel.ts <model-dir>')
  const sources = walk(dir)
  const keys = Object.keys(sources)
  const snapCount = keys.filter(k => k.endsWith('.likec4.snap')).length
  const srcBytes = keys.reduce((a, k) => a + Buffer.byteLength(sources[k], 'utf8'), 0)
  console.log(`FILES=${keys.length} (snaps=${snapCount}) totalSourceBytes=${srcBytes}`)

  const runs: number[] = []
  let result: Awaited<ReturnType<typeof compute>> | null = null
  for (let i = 0; i < 3; i++) {
    const t0 = performance.now()
    result = await compute(sources)
    runs.push(performance.now() - t0)
    console.log(`compute run ${i + 1}: ${runs[i].toFixed(0)} ms`)
  }
  const res = result!
  console.log(`COMPUTE_MS min=${Math.min(...runs).toFixed(0)} max=${Math.max(...runs).toFixed(0)}`)
  console.log(`ERRORS=${res.errors.length}`, res.errors.slice(0, 10))
  console.log(`DRIFTS=${res.drifts.length}`, JSON.stringify(res.drifts))

  const data = res.data as any
  const model = data.__ ?? data
  const elements = data.elements ? Object.keys(data.elements).length : '?'
  const rels = data.relations ? Object.keys(data.relations).length : (data.relationships ? Object.keys(data.relationships).length : '?')
  const deployments = data.deployments?.elements ? Object.keys(data.deployments.elements).length : (data.deployments ? Object.keys(data.deployments).length : '?')
  console.log(`MODEL elements=${elements} relations=${rels} deploymentElements=${deployments}`)

  const views = data.views ?? {}
  const ids = Object.keys(views)
  console.log(`VIEWS=${ids.length}`)
  let maxNodes = 0, maxNodesView = ''
  const byType: Record<string, number> = {}
  for (const id of ids) {
    const v = views[id]
    const nodes = v.nodes?.length ?? 0
    const edges = v.edges?.length ?? 0
    const type = v._type ?? v.__ ?? 'unknown'
    byType[type] = (byType[type] ?? 0) + 1
    const b = v.bounds ?? {}
    if (nodes > maxNodes) { maxNodes = nodes; maxNodesView = id }
    console.log(
      `  ${id.padEnd(26)} type=${String(type).padEnd(11)} nodes=${String(nodes).padStart(3)} edges=${String(edges).padStart(3)} bounds=${Math.round(b.width ?? 0)}x${Math.round(b.height ?? 0)}`,
    )
  }
  console.log(`VIEW_TYPES=${JSON.stringify(byType)}`)
  console.log(`MAX_NODES view=${maxNodesView} nodes=${maxNodes}`)
}

main().catch(e => { console.error('PROBE FAILED:', e); process.exit(1) })
