import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import JSON5 from 'json5'
import { compute } from '../src/compute'
import { loadSources } from '../src/sources'

const targetDir = fileURLToPath(new URL('../fixtures/target', import.meta.url))
const SHIFT = 500

// Read a CLEAN auto-layout: drop any pre-existing manual-layout snapshots so we
// never bake an already-shifted position back into the snapshot.
const sources = loadSources(targetDir)
for (const key of Object.keys(sources)) {
  if (key.endsWith('.likec4.snap')) delete sources[key]
}

const { data, errors } = await compute(sources)
if (errors.length > 0) {
  console.error('compute reported errors, aborting:', errors)
  process.exit(1)
}

// $data.views.index is a LayoutedView. The on-disk `.likec4.snap` is exactly a
// JSON5-serialised LayoutedView tagged `_layout: 'manual'` (see
// @likec4/language-server DefaultLikeC4ManualLayouts.readManualLayouts, and the
// `ViewManualLayoutSnapshot` consumed by @likec4/core applyManualLayout).
const view = structuredClone((data as any).views.index)
view.nodes[0].x += SHIFT
view._layout = 'manual'

mkdirSync(`${targetDir}/.likec4`, { recursive: true })
writeFileSync(`${targetDir}/.likec4/index.likec4.snap`, JSON5.stringify(view, null, 2), 'utf8')
console.log(`Wrote snapshot; node[0] '${view.nodes[0].id}' x shifted by ${SHIFT}`)
