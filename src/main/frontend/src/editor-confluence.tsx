// Confluence macro-editor bundle entry (spec §6).
//
// Atlassian injects a classic <script> (editor-loader.js); that loader injects THIS file as an ESM
// <script type="module"> and waits for `window.LikeC4Editor` to appear, then drives the macro-editor
// override. This module just publishes the mount API onto the global.

// The live preview lazy-loads the same Diagram chunk as the viewer, so install the same
// useEffectEvent shim (see reactCompat) before it renders.
import './reactCompat'

import { IndexedDbDumpCache } from './cache'
import { createDiagramWorker } from './createDiagramWorker'
import { openMacroEditor } from './editor/macroEditor'
import type { PipelineDeps } from './pipeline'
import { createRestClient } from './restClient'
import { bumpComputeCounter, defaultPoolSize } from './runtime'
import { WorkerPool } from './workerPool'

// The pool is disposed when the dialog closes, but a dialog left open after a "Load views" would
// otherwise keep the (up to 2) language-services worker heaps resident with no sweep. Release idle
// workers after a spell of inactivity — shorter than the viewer's 60s since a macro-editor dialog is
// transient — and recreate on demand for the next (view-switch) recompute. Matches the viewer's rationale.
const EDITOR_WORKER_IDLE_MS = 30_000

function createDeps(): PipelineDeps {
  return {
    rest: createRestClient(),
    cache: new IndexedDbDumpCache(),
    // Smaller pool than the viewer's (4): the editor preview computes ONE selected view at a time and
    // the pool is disposed when the dialog closes, so a leaner pool keeps the editor page light while
    // still parallelising the resolve/compute of that single view.
    pool: new WorkerPool(createDiagramWorker, defaultPoolSize(2), EDITOR_WORKER_IDLE_MS),
    timeoutMs: 20_000,
    onCompute: bumpComputeCounter,
  }
}

// Only the two members the classic editor-loader.js consumes are published on the global. mountViewPicker
// is imported directly where it's used (macroEditor.ts, editor-dev.tsx) and was never read off the global,
// so exposing it here was dead public surface.
const api = { openMacroEditor, createDeps }
window.LikeC4Editor = api
export type LikeC4EditorApi = typeof api
