import { IndexedDbDumpCache } from './cache'
import { createDiagramWorker } from './createDiagramWorker'
import { mountViewPicker } from './editor/mountEditor'
import type { PipelineDeps } from './pipeline'
import { createRestClient } from './restClient'
import { bumpComputeCounter, defaultPoolSize } from './runtime'
import { WorkerPool } from './workerPool'

const deps: PipelineDeps = {
  rest: createRestClient(),
  cache: new IndexedDbDumpCache(),
  // Mirror the SHIPPED editor (editor-confluence.tsx): pool size 2 and a 30s idle sweep, so the dev/e2e
  // harness exercises the same worker-lifecycle profile as production (idle workers are released) rather
  // than an unbounded pool of 4 that never sweeps and diverges from what ships.
  pool: new WorkerPool(createDiagramWorker, defaultPoolSize(2), 30_000),
  // Match the shipped editor/viewer (20s) so the dev/e2e harness can't hang a worker forever on a
  // pathological compute — without it pool.run creates no timer and a runaway job never times out.
  timeoutMs: 20_000,
  onCompute: bumpComputeCounter,
}

mountViewPicker(document.getElementById('editor')!, {
  params: { project: 'acme/architecture', ref: 'main', path: 'ok' },
  deps,
  onSelect: id => { window.__editorSelected = id },
})
