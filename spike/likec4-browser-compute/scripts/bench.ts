import { compute } from '../src/compute'

function genModel(nSystems: number): Record<string, string> {
  const spec = `specification {\n  element system\n  element container\n}\n`
  let model = 'model {\n'
  for (let i = 0; i < nSystems; i++) {
    model += `  system s${i} {\n    title 'System ${i}'\n    container c${i}a 'A'\n    container c${i}b 'B'\n    c${i}a -> c${i}b 'uses'\n  }\n`
  }
  // a few cross-system relations
  for (let i = 0; i + 1 < nSystems; i += 1) model += `  s${i} -> s${i + 1} 'calls'\n`
  model += '}\n'
  const views = `views {\n  view index {\n    title 'All'\n    include *\n  }\n}\n`
  return { 'spec.likec4': spec, 'model.likec4': model, 'views.likec4': views }
}

for (const n of [5, 25, 100, 300]) {
  const sources = genModel(n)
  const t0 = performance.now()
  const { data, errors } = await compute(sources)
  const ms = performance.now() - t0
  const views = (data as any)?.views ?? {}
  const nodes = (views.index?.nodes ?? []).length
  console.log(`systems=${n} elements≈${n * 3} errors=${errors.length} indexNodes=${nodes} computeMs=${Math.round(ms)}`)
}
