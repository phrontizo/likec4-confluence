# Spike Findings — LikeC4 Browser Compute (upstream 1.58.0)

> **Historical spike — superseded.** This directory is the original proof-of-concept; its `GO`
> verdict was acted on and the production implementation now lives in `src/main/frontend/` (with the
> live render proven by `docker/e2e/`). Kept for reference (notably the manual-layout notes); it is
> not built or tested by CI and should not be edited as if it were live code.

## Verdict: GO-WITH-CAVEATS

The core compute path — parsing LikeC4 DSL, running ELK layout, applying manual-layout snapshots,
and exposing the result as a serialisable `LayoutedLikeC4ModelData` — is fully proven in a browser
build. The Web Worker integration, error API, and `.snap` reconciliation mechanism all work as
needed for Plan 2. The only unresolved item is browser pixel-render: `likec4/react` + ReactFlow
renders correctly per API and data contract (types, bundle), but an actual Chrome/Firefox launch was
not possible in this environment (missing `libnss3.so` and other Chromium system libs; no sudo
access to install them). That step is GO-pending-CI: `test/render.spec.ts` is written and
committed; it needs to pass in the Plan 4 containerised environment before Plan 2 ships a UI.
Nothing in the compute/layout/snapshot path blocks Plan 2's implementation.

## What was proven

- [x] `fromSources` computes a layouted model in the browser (no Monaco) — builds for the browser;
  Node-verified semantics under Node v20.19.2 (the `engines >=22.22.3` constraint is conservative)
- [x] Errors reported with line numbers — `likec4.getErrors()` returns
  `[{ message, line, sourceFsPath }]`; `sourceFsPath` is a virtual `/workspace/…` path
- [x] Manual-layout `.snap` applied — mechanism: split `.likec4.snap` keys from `sources`,
  compute auto layout via `fromSources` on LikeC4-only keys, then call
  `applyManualLayout(autoView, { ...snap, id: viewId, _stage: 'layouted' })` from `@likec4/core`
  (browser-safe, no `node:*` deps). Proven rigorous: removing the snap shifts `sys` node to x=0
  instead of x=500; sibling `ext` keeps x=0. Drift gracefully flagged via `view.drifts` /
  node `drifts`, never throws. Do NOT feed `.snap` files into `fromSources` (causes parse errors).
- [x] Off-main-thread compute via Web Worker — `src/worker.ts` posts `{ data, errors, computeMs }`;
  `src/main.tsx` spawns it, embeds fixtures via `?raw`, stashes result at `window.__spike`
- [x] `likec4/react` render — API + data contract confirmed; pixel-render PENDING (see Caveats)

## Measurements

### Bundle sizes (fresh build, Vite 5.4.10, 2026-06-08)

| Chunk | Raw | Gzip |
|-------|-----|------|
| `worker-*.js` (language-services + ELK) | 2,751 kB | 1,122 kB |
| `index-*.js` (main + renderer) | 2,367 kB | 671 kB |

Worker gzip measured with `gzip -c dist/assets/worker-*.js | wc -c` (Vite does not print worker
gzip size in the build log).

### Node-measured compute time by model size (proxy; browser-worker timings pending CI)

> These timings were recorded under Node v20.19.2, not inside a browser Worker. Real browser
> timings will differ, but order-of-magnitude is representative. Each run is a fresh `fromSources`
> instance (no warm-up / caching). The n=5 run is elevated due to one-time JIT warm-up (first
> run in process).

| systems | elements (approx) | indexNodes (rendered) | errors | computeMs (Node) |
|---------|-------------------|-----------------------|--------|-----------------|
| 5       | 15                | 5                     | 0      | 592             |
| 25      | 75                | 25                    | 0      | 345             |
| 100     | 300               | 100                   | 0      | 477             |
| 300     | 900               | 300                   | 0      | 734             |

For real Confluence deployments the relevant model size is typically tens of systems; 300 elements
computes in ~0.7 s Node / likely 1–2 s in a browser Worker. IndexedDB caching (see Risks) will
make subsequent renders near-instant.

## Exact APIs confirmed (carry into Plan 2)

**Compute:**
```ts
import { fromSources } from '@likec4/language-services/browser'

const likec4 = await fromSources(likec4OnlySources)      // no .snap keys
const errors = likec4.getErrors()                         // [{ message, line, sourceFsPath }]
const model  = await likec4.layoutedModel()
const data   = model.$data                               // LayoutedLikeC4ModelData — serialisable
```

**Node position fields** (each entry in `data.views.<id>.nodes[i]`):
`id`, `kind`, `title`, `shape`, `color`, `style`, `x`, `y`, `width`, `height`, `labelBBox`,
`parent`, `level`, `children`, `inEdges`, `outEdges`, `modelRef`, `tags`

**Manual-layout mechanism** (browser-safe — no `node:*` deps):
```ts
import { applyManualLayout, calcDriftsFromSnapshot } from '@likec4/core'

// split sources:
const snapshots: Array<{ viewId: string; view: any }> = []
const likec4Sources: Record<string, string> = {}
for (const [key, content] of Object.entries(sources)) {
  if (key.endsWith('.likec4.snap')) {
    snapshots.push({ viewId: basename(key).replace('.likec4.snap', ''), view: JSON5.parse(content) })
  } else {
    likec4Sources[key] = content
  }
}
// after layoutedModel():
data.views[viewId] = applyManualLayout(auto, { ...snap, id: viewId, _stage: 'layouted' })
```
Do NOT feed `.snap` keys into `fromSources` — they trigger a parse error
(`Expecting token of type 'EOF' but found '{'`) because `.snap` is not a registered LikeC4
extension and Langium appends `.c4`, emitting a spurious `getErrors()` entry.

**`.snap` schema:** JSON5-serialised `LayoutedView` (`_stage: 'layouted'`, `_layout: 'manual'`),
same shape as `$data.views.<id>`. Top-level keys: `_type`, `tags`, `links`, `_stage`,
`sourcePath`, `description`, `title`, `id`, `autoLayout`, `hash`, `bounds`, `nodes`, `edges`,
`_layout`.

**Render:**
```ts
import { LikeC4Model }                                    from '@likec4/core/model'
import { LikeC4Diagram, LikeC4ModelProvider, useLikeC4Model } from 'likec4/react'

const model    = LikeC4Model.create(data)
const viewModel = model.findView('index')
// inside a component under <LikeC4ModelProvider likec4model={model}>:  // prop is `likec4model`, not `model`
const v = useLikeC4Model().findView('index')
// <LikeC4Diagram view={v.$view} zoomable pannable />
```
No CSS import needed — `likec4/react` self-injects styles at runtime (it ships no `.css` export).

**Vite bundling — required resolve alias:**
```ts
// vite.config.ts
resolve: {
  alias: {
    'vscode-languageserver/browser': 'vscode-languageserver/browser.js',
  }
}
```

## Caveats / not proven in this environment

- **Browser pixel-render + zoom:** not executed. Host lacks Chromium system libraries (`libnss3.so`
  etc.) and has no sudo access to install them. `test/render.spec.ts` is written and committed;
  it must pass in the Plan 4 Docker / CI environment to confirm visible nodes and zoom
  interactivity. ReactFlow selectors (`.react-flow__node`, `.react-flow__viewport`) are
  bundle-grep-confirmed but not live-DOM-confirmed.

## Risks / recommendations for Plan 2

- **Worker bundle is large (~1.1 MB gz):** lazy-load the Worker on first diagram request (not on
  page load). Pair with an IndexedDB dump cache keyed on the source hash to skip recompute on
  subsequent page loads — compute cost then only applies on first view or after DSL change.
- **Code-split the renderer:** the index chunk (2.4 MB raw / 671 kB gz) bundles language-services
  AND the React renderer together; in Plan 2 split them so the renderer is only loaded when a
  diagram is about to be shown.
- **Worker must own `.snap` splitting + `applyManualLayout`:** do not pass `.snap` keys to
  `fromSources`; do not apply manual layout on the main thread. The current `src/compute.ts`
  implements the correct pattern — carry it forward unchanged.
- **Surface `view.drifts` / node `drifts` to the UI:** when a curated (manual-layout) diagram's
  underlying model changes, `applyManualLayout` records drift metadata rather than throwing.
  Plan 2 should inspect `data.views[id].drifts` and warn users that the curated layout is stale
  so an architect can re-export the `.snap`.
- **Node v20 compatibility:** the `engines >=22.22.3` constraint in `@likec4/language-services`
  is conservative; all tests passed on Node v20.19.2. CI should still run Node 22 for correctness,
  but the library is not observably broken on v20.
- **Multiple views:** the benchmark only includes a single `index` view per model. Real diagrams
  will have multiple views; ELK layout time scales roughly linearly per view. If a model has
  many views, consider computing only the requested view(s) on demand rather than the full
  `layoutedModel()`.
- **Strengthen the manual-layout regression test:** the spike's committed test trips on its
  precondition (`expect(keys).toContain('.likec4/index.likec4.snap')`) first when the `.snap` is
  removed, so it does not isolate the *coordinate* logic on its own. The semantics are genuinely
  correct (independently verified: auto x=0 → snapshot x=500, sibling unchanged), but Plan 2's
  port of this test should add a direct assertion comparing against the auto-layout coordinate
  (e.g. assert the node moved *because* of the snapshot) so a future regression can't be masked.
- **gitignore `test-results/`:** Playwright writes an untracked `test-results/` dir; the spike
  `.gitignore` now covers it — carry the same into Plan 2's front-end package.
