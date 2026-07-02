/**
 * Generate a synthetic LikeC4 model (cloud-system DSL style) of a chosen size and run the plugin's
 * own compute() over it to characterise compute-time scaling and the 20s worker timeout.
 *   npx tsx test/scripts/gen-scale-model.ts <systems> <containersPerSystem> <componentsPerContainer>
 * Prints element/relation/view counts, biggest-view node count, and compute time (3 runs).
 */
import { performance } from 'node:perf_hooks'
import { compute } from '../../src/compute'

function buildModel(G: number, C: number, K: number) {
  const spec = `specification {
  element system
  element container
  element component
  tag core
}`
  let model = 'model {\n'
  // an actor that talks to every system
  model += `  user = system 'User'\n`
  const compFqns: string[] = []
  for (let g = 0; g < G; g++) {
    model += `  system sys${g} 'System ${g}' {\n`
    for (let c = 0; c < C; c++) {
      model += `    container c${g}_${c} 'Container ${g}.${c}' {\n`
      for (let k = 0; k < K; k++) {
        const fqn = `sys${g}.c${g}_${c}.k${g}_${c}_${k}`
        compFqns.push(fqn)
        model += `      component k${g}_${c}_${k} 'Comp ${g}.${c}.${k}'\n`
      }
      // intra-container edges
      for (let k = 1; k < K; k++) model += `      k${g}_${c}_${k - 1} -> k${g}_${c}_${k} 'calls'\n`
      model += `    }\n`
    }
    // intra-system edges between containers
    for (let c = 1; c < C; c++) model += `    c${g}_${c - 1} -> c${g}_${c} 'uses'\n`
    model += `  }\n`
    model += `  user -> sys${g} 'uses'\n`
  }
  // cross-system edges
  for (let g = 1; g < G; g++) model += `  sys${g - 1} -> sys${g} 'integrates'\n`
  model += '}\n'

  // views: a landscape, one per system, and 3 "kitchen-sink" big views that include everything.
  let views = 'views {\n  view index { title \'Landscape\'\n include *\n }\n'
  for (let g = 0; g < G; g++) views += `  view sys${g}_v of sys${g} { include * }\n`
  // a mega view that fully expands EVERY system subtree (worst-case single-view layout)
  views += `  view mega { title 'Mega'\n include *\n`
  for (let g = 0; g < G; g++) views += `   include sys${g}.**\n`
  views += `  }\n`
  views += '}\n'
  return { '_spec.likec4': spec, 'model.likec4': model, 'views.likec4': views }
}

async function main() {
  const G = parseInt(process.argv[2] ?? '6', 10)
  const C = parseInt(process.argv[3] ?? '4', 10)
  const K = parseInt(process.argv[4] ?? '4', 10)
  const sources = buildModel(G, C, K)
  const bytes = Object.values(sources).reduce((a, s) => a + Buffer.byteLength(s, 'utf8'), 0)
  const elements = 1 + G + G * C + G * C * K
  console.log(`PARAMS systems=${G} containers/sys=${C} comps/container=${K} -> ~${elements} elements, sourceBytes=${bytes}`)

  const runs: number[] = []
  let res: Awaited<ReturnType<typeof compute>> | null = null
  for (let i = 0; i < 3; i++) {
    const t0 = performance.now()
    res = await compute(sources)
    runs.push(performance.now() - t0)
  }
  const data = res!.data as any
  const views = data.views ?? {}
  let maxNodes = 0, maxView = ''
  for (const id of Object.keys(views)) {
    const n = views[id].nodes?.length ?? 0
    if (n > maxNodes) { maxNodes = n; maxView = id }
  }
  console.log(`ERRORS=${res!.errors.length} VIEWS=${Object.keys(views).length} maxView=${maxView} maxNodes=${maxNodes}`)
  console.log(`COMPUTE_MS runs=[${runs.map(r => r.toFixed(0)).join(', ')}] min=${Math.min(...runs).toFixed(0)} max=${Math.max(...runs).toFixed(0)}`)
}
main().catch(e => { console.error('FAILED:', e); process.exit(1) })
