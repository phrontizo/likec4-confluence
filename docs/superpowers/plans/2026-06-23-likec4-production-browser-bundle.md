# LikeC4 Production Browser Bundle Implementation Plan

> **Superseded-platform / as-planned note (post-2026-06-29):** this is a historical planning record. The
> browser bundle itself is platform-agnostic, but a few dependency details below are plan-era, not
> as-built: it names the layout engine as **`elkjs`**, whereas the shipped bundle uses **graphviz**
> (`@hpcc-js/wasm-graphviz` via `@likec4/layouts`) — no `elkjs` dependency exists — and it references the
> Confluence **9.2.20** (`amps maven-confluence-plugin`) era before the retarget to Confluence
> **10.2.13 / Jakarta EE / Java 21**. Version numbers here (e.g. TypeScript 5.6) reflect that earlier era.
> For the current facts see **README.md**, **NOTICE**, and **CLAUDE.md**. Kept as the original plan record.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the production browser bundle (spec §4 component 6) that, given a Confluence macro `<div data-…>`, resolves a source SHA, fetches `.c4`/`.likec4` + `.likec4/*.likec4.snap` via the plugin REST API, computes a fully laid-out LikeC4 model off-main-thread in a shared worker pool, caches the dump in IndexedDB, and renders it interactively with in-place navigation, drift warnings, and a macro-editor view-picker.

**Architecture:** A TypeScript/Vite front-end nested at `src/main/frontend/` (building into `src/main/resources/likec4-web/` as the eventual web-resource; a stub root `pom.xml` marks the Java module Plan 3 will complete). The pure pipeline — `dataAttrs → restClient → IndexedDB cache → WorkerPool(compute) → React render` — is unit-tested in Node (vitest); React + interactivity is covered by Playwright against a Vite middleware that mocks the two REST endpoints from on-disk fixture repos. Carries forward the proven spike compute (`fromSources` + `.snap` split + `applyManualLayout`), adding drift surfacing via `calcDriftsFromSnapshot`.

**Tech Stack:** TypeScript 5.6, Vite 5, vitest 2 (Node env), `fake-indexeddb`, `@playwright/test`, React 19, JSON5, pinned upstream LikeC4 **1.58.0** (`@likec4/language-services`, `@likec4/core`, `likec4` [`likec4/react`], `elkjs`).

**Not a spike — this is production code.** Two areas are explicitly runtime-confirm (authored + committed here, browser-pixel-verified in the Plan 4 container, mirroring the spike's honest GO-pending-CI handling): the Playwright specs in Task 12, and the exact react-flow navigation affordance. Every such step gives concrete code and a named fallback — that is intentional, not a placeholder. The Node unit + compute tests and the Vite build are the local green gates.

---

## Scope

**In scope (this plan):** the entire browser bundle — boot/orchestration, data-attr parsing, REST client, IndexedDB dump cache, shared Web Worker pool, browser compute (carry-forward + drift), React viewer (interactive, in-place nav, drift banner, fullscreen), failure rendering, the full macro-editor view-picker UI with live preview + an editor-agnostic mount API, the Vite mock-REST middleware, and the test pyramid's fast + e2e layers for the front-end.

**Out of scope (later plans):** Java macro / REST resource / `GitLabSourceClient` / `CacheLayer` / `AdminConfig` (Plan 3); the Docker Confluence+Postgres+mock-GitLab stack, UPM install/upgrade, performance, and licence harness (Plan 4). The root `pom.xml` here is a documented stub; Maven is **not** invoked in Plan 2.

**Spec refinement (flagged):** spec §7 keys the client dump cache on `sha`. Because §6 supports multiple LikeC4 projects in one repo addressed by `path` (same commit → same `sha`, different model), `sha` alone collides. This plan keys the IndexedDB dump on `${sha}:${path}`. (Server bundle key stays `(project, sha, path)` per §7 — Plan 3.)

---

## File Structure

Root:
- Create: `pom.xml` — **stub** Atlassian-SDK plugin POM marking the module + frontend build hook Plan 3 completes. Not built in Plan 2.
- Modify: `.gitignore` — ignore `node_modules/`, `src/main/resources/likec4-web/`, `test-results/`.

Frontend root `src/main/frontend/`:
- Create: `package.json`, `tsconfig.json`, `vite.config.ts`, `vite-plugin-mock-rest.ts`, `playwright.config.ts`, `index.html`, `editor.html`, `README.md`.

`src/main/frontend/src/` (each file one responsibility):
- `types.ts` — shared cross-module types not owned by a single module.
- `compute.ts` — carry-forward: `fromSources` + `.snap` split + `applyManualLayout` + drift via `calcDriftsFromSnapshot`.
- `sources.ts` — Node fs fixture loader (tests/scripts only).
- `worker.ts` — Web Worker entry: `compute` → post `{ data, errors, drifts, computeMs }`.
- `createDiagramWorker.ts` — production worker factory (the Vite `new Worker(new URL(...))` construct; isolated so the pool stays Node-safe).
- `workerPool.ts` — shared pool: lazy spawn ≤N workers, queue, timeout/abort, terminate-on-cancel.
- `restClient.ts` — typed `/resolve` + `/source` client; `RestError`.
- `cache.ts` — `IndexedDbDumpCache` keyed on `${sha}:${path}`, bounded LRU.
- `dataAttrs.ts` — parse macro `<div>` data-attributes → `DiagramParams`.
- `pipeline.ts` — `loadModel(params, deps): LoadResult` — the pure orchestration core.
- `errors.ts` — `describeFailure(result, params): FailureView` (pure).
- `ErrorPanel.tsx` — render a `FailureView`.
- `Diagram.tsx` — **default export**, lazy-loaded: LikeC4 render + in-place nav + drift banner + fullscreen.
- `Viewer.tsx` — per-diagram lifecycle: loading → ok(`<Diagram>`) | failure(`<ErrorPanel>`).
- `boot.tsx` — entry: build shared deps, find `.likec4-diagram` divs, mount a `<Viewer>` each.
- `styles.css` — viewer/fullscreen/drift/error CSS.
- `editor/listViews.ts` — `listViews(data): ViewInfo[]` (pure).
- `editor/ViewPicker.tsx` — dropdown + live `<Diagram>` preview.
- `editor/mountEditor.tsx` — editor-agnostic `mountViewPicker(container, opts): EditorHandle`.
- `editor-dev.tsx` — e2e host wiring for the editor page.

`src/main/frontend/test/`:
- `fixtures/likec4/target/{spec,model,views}.likec4` + `.likec4/index.likec4.snap` — compute fixture (navigable `sys`).
- `fixtures/likec4/broken/model.likec4` — parse-error fixture.
- `fixtures/likec4/drifted/{spec,model,views}.likec4` + `.likec4/index.likec4.snap` — drift fixture.
- `fixtures/repos/acme/architecture/{ok,drifted,broken}/…` — mock-REST repo subtrees.
- `*.test.ts` — Node unit + compute tests (compute, restClient, cache, dataAttrs, workerPool, pipeline, errors, listViews).
- `render.spec.ts`, `editor.spec.ts` — Playwright e2e.
- `scripts/make-snapshot.ts` — regenerate the target `.snap` from the computed view.

---

## Task 1: Scaffold the nested frontend module pinned to 1.58.0

**Files:**
- Create: `pom.xml`
- Modify: `.gitignore`
- Create: `src/main/frontend/package.json`
- Create: `src/main/frontend/tsconfig.json`

- [ ] **Step 1: Create the stub `pom.xml`** (documents the module; not built in Plan 2)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.phrontizo.confluence</groupId>
  <artifactId>likec4-confluence</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>atlassian-plugin</packaging>
  <name>LikeC4 Confluence Diagram Plugin</name>

  <!--
    STUB (Plan 2). The browser bundle lives under src/main/frontend and builds into
    src/main/resources/likec4-web (Vite). Plan 3 completes this POM:
      - amps maven-confluence-plugin (Confluence 9.2.20) + dependencies
      - a frontend build hook (e.g. frontend-maven-plugin or exec) running
        `npm ci && npm run build` in src/main/frontend before package
      - atlassian-plugin.xml referencing the built web-resource from the Vite manifest
    Plan 2 does NOT invoke Maven; the frontend is built/tested with npm.
  -->
</project>
```

- [ ] **Step 2: Update `.gitignore`** (append; keep existing lines)

```
node_modules/
src/main/resources/likec4-web/
test-results/
```

- [ ] **Step 3: Create `src/main/frontend/package.json`**

```json
{
  "name": "likec4-confluence-frontend",
  "private": true,
  "type": "module",
  "scripts": {
    "test": "vitest run",
    "make-snapshot": "tsx test/scripts/make-snapshot.ts",
    "build": "vite build",
    "preview": "vite preview --port 4317 --strictPort",
    "test:e2e": "playwright test",
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@likec4/core": "1.58.0",
    "@likec4/language-services": "1.58.0",
    "elkjs": "0.9.3",
    "json5": "2.2.3",
    "likec4": "1.58.0",
    "react": "19.0.0",
    "react-dom": "19.0.0"
  },
  "devDependencies": {
    "@playwright/test": "1.48.0",
    "@types/node": "^22.19.20",
    "@types/react": "19.0.0",
    "@types/react-dom": "19.0.0",
    "@vitejs/plugin-react": "4.3.3",
    "fake-indexeddb": "6.0.0",
    "tsx": "4.19.2",
    "typescript": "5.6.3",
    "vite": "5.4.10",
    "vitest": "2.1.4"
  }
}
```

- [ ] **Step 4: Create `src/main/frontend/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "lib": ["ES2022", "DOM", "DOM.Iterable", "WebWorker"],
    "jsx": "react-jsx",
    "strict": true,
    "skipLibCheck": true,
    "noEmit": true,
    "types": ["vite/client", "node"]
  },
  "include": ["src", "test", "vite.config.ts", "vite-plugin-mock-rest.ts", "playwright.config.ts"]
}
```

- [ ] **Step 5: Install and verify the pins**

Run: `cd src/main/frontend && npm install && npm ls likec4 @likec4/language-services @likec4/core`
Expected: `likec4`, `@likec4/language-services`, `@likec4/core` all resolve to `1.58.0`, no `UNMET`/`invalid`. Engine + renderer drift is a known failure mode (spec §10) — fix any mismatch before continuing.

- [ ] **Step 6: Commit**

```bash
git add pom.xml .gitignore src/main/frontend/package.json src/main/frontend/tsconfig.json src/main/frontend/package-lock.json
git commit -m "plan2: scaffold nested frontend module pinned to likec4 1.58.0"
```

---

## Task 2: Carry-forward compute (with drift) + fixtures + worker

**Files:**
- Create: `src/main/frontend/src/compute.ts`
- Create: `src/main/frontend/src/sources.ts`
- Create: `src/main/frontend/src/worker.ts`
- Create: `src/main/frontend/vite.config.ts` (test-only form now; build/mock added in Task 10)
- Create: fixtures under `src/main/frontend/test/fixtures/likec4/{target,broken,drifted}/…`
- Create: `src/main/frontend/test/scripts/make-snapshot.ts`
- Test: `src/main/frontend/test/compute.test.ts`

- [ ] **Step 1: Create `vite.config.ts`** (vitest in Node; alias required by language-services)

```ts
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  worker: { format: 'es' },
  resolve: {
    alias: { 'vscode-languageserver/browser': 'vscode-languageserver/browser.js' },
  },
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
    server: { deps: { inline: [/@likec4\//, /langium/] } },
  },
})
```

- [ ] **Step 2: Create `src/sources.ts`** (Node fixture loader — tests/scripts only)

```ts
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'

/** Recursively read every file under `dir` into a record keyed by POSIX relative path. */
export function loadSources(dir: string): Record<string, string> {
  const out: Record<string, string> = {}
  const walk = (current: string) => {
    for (const name of readdirSync(current)) {
      const full = join(current, name)
      if (statSync(full).isDirectory()) walk(full)
      else out[relative(dir, full).split(sep).join('/')] = readFileSync(full, 'utf8')
    }
  }
  walk(dir)
  return out
}
```

- [ ] **Step 3: Create `src/compute.ts`** (carry-forward + drift; uses both manual-layout APIs for their documented purpose)

```ts
import { applyManualLayout, calcDriftsFromSnapshot } from '@likec4/core'
import { fromSources } from '@likec4/language-services/browser'
import JSON5 from 'json5'

export interface ComputeError { message: string; line: number; sourceFsPath: string }
export interface DriftInfo { viewId: string; reasons: string[] }
export interface ComputeResult {
  /** Serializable LayoutedLikeC4ModelData — safe to postMessage and feed to LikeC4Model.create. */
  data: unknown
  errors: ComputeError[]
  drifts: DriftInfo[]
}

const SNAP_SUFFIX = '.likec4.snap'

function viewIdFromSnapKey(key: string): string {
  const base = key.split('/').pop() ?? key
  return base.slice(0, -SNAP_SUFFIX.length)
}

/**
 * Compute a fully laid-out model from a path→content record.
 *
 * `.likec4/<viewId>.likec4.snap` (JSON5 `LayoutedView`, `_layout: 'manual'`) must NOT reach
 * `fromSources` (the browser build wires Noop manual-layouts + Noop FS and `.snap` is not a
 * LikeC4 extension, so it would only emit a parse error). We split them out, compute auto
 * layout, read drift reasons with `calcDriftsFromSnapshot`, then reconcile positions with
 * `applyManualLayout` — both public, browser-safe (`@likec4/core`, no `node:*`).
 */
export async function compute(sources: Record<string, string>): Promise<ComputeResult> {
  const likec4Sources: Record<string, string> = {}
  const snapshots: Array<{ viewId: string; view: any }> = []
  for (const [key, content] of Object.entries(sources)) {
    if (key.endsWith(SNAP_SUFFIX)) {
      try {
        snapshots.push({ viewId: viewIdFromSnapKey(key), view: JSON5.parse(content) })
      } catch (e) {
        console.warn(`Failed to parse manual-layout snapshot ${key}:`, e)
      }
    } else {
      likec4Sources[key] = content
    }
  }

  const likec4 = await fromSources(likec4Sources)
  const errors = likec4.getErrors().map(e => ({
    message: e.message,
    line: e.line,
    sourceFsPath: e.sourceFsPath,
  }))
  const model = await likec4.layoutedModel()
  const data = model.$data as any

  const drifts: DriftInfo[] = []
  for (const { viewId, view } of snapshots) {
    const auto = data.views?.[viewId]
    if (!auto) {
      console.warn(`Manual-layout snapshot references unknown view '${viewId}'`)
      continue
    }
    const snapshot = { ...view, id: viewId, _stage: 'layouted' as const }
    const drifted = calcDriftsFromSnapshot(auto, snapshot)
    if (drifted.drifts && drifted.drifts.length > 0) {
      drifts.push({ viewId, reasons: [...drifted.drifts] })
    }
    data.views[viewId] = applyManualLayout(auto, snapshot)
  }

  return { data, errors, drifts }
}
```

- [ ] **Step 4: Create the `target` fixture** (navigable `sys`; `index` + `sys_detail` views)

`test/fixtures/likec4/target/spec.likec4`:
```
specification {
  element system
  element container
}
```

`test/fixtures/likec4/target/model.likec4`:
```
model {
  system sys {
    title 'My System'
    container web 'Web App'
    container db 'Database'
    web -> db 'reads/writes'
  }
  system ext {
    title 'External System'
  }
  sys -> ext 'calls'
}
```

`test/fixtures/likec4/target/views.likec4`:
```
views {
  view index {
    title 'Landscape'
    include *
  }
  view sys_detail of sys {
    title 'My System — detail'
    include *
  }
}
```

- [ ] **Step 5: Create the `broken` fixture** — `test/fixtures/likec4/broken/model.likec4`

```
model {
  system sys {
    container web 'Web App'
    web -> nonexistent 'broken relation'
  }
}
```

- [ ] **Step 6: Create `test/scripts/make-snapshot.ts`** (regenerate the target `.snap`)

```ts
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import JSON5 from 'json5'
import { compute } from '../../src/compute'
import { loadSources } from '../../src/sources'

const targetDir = fileURLToPath(new URL('../fixtures/likec4/target', import.meta.url))
const SHIFT = 500

const { data } = await compute(loadSources(targetDir))
const view = structuredClone((data as any).views.index)
view.nodes[0].x += SHIFT
view._layout = 'manual'

mkdirSync(`${targetDir}/.likec4`, { recursive: true })
writeFileSync(`${targetDir}/.likec4/index.likec4.snap`, JSON5.stringify(view, null, 2), 'utf8')
console.log(`Wrote target snapshot; node[0] '${view.nodes[0].id}' x shifted by ${SHIFT}`)
```

- [ ] **Step 7: Generate the target snapshot**

Run: `cd src/main/frontend && npm run make-snapshot`
Expected: prints the shifted node id (`sys`) and writes `test/fixtures/likec4/target/.likec4/index.likec4.snap`.

- [ ] **Step 8: Create the `drifted` fixture** — copy target, add a NEW top-level system so the (pre-existing) snapshot is stale

Copy `target/spec.likec4` and `target/views.likec4` verbatim to `drifted/`. Then write `drifted/model.likec4` with an extra top-level `mon` system the snapshot predates:

```
model {
  system sys {
    title 'My System'
    container web 'Web App'
    container db 'Database'
    web -> db 'reads/writes'
  }
  system ext {
    title 'External System'
  }
  system mon {
    title 'Monitoring'
  }
  sys -> ext 'calls'
  sys -> mon 'reports to'
}
```

Then copy the just-generated snapshot (which knows nothing of `mon`) into the drifted project:

Run:
```bash
cd src/main/frontend
mkdir -p test/fixtures/likec4/drifted/.likec4
cp test/fixtures/likec4/target/.likec4/index.likec4.snap test/fixtures/likec4/drifted/.likec4/index.likec4.snap
```
Because `index` (`include *`) now contains a `mon` node absent from the snapshot, `calcDriftsFromSnapshot` reports `'nodes-added'`.

- [ ] **Step 9: Create `src/worker.ts`**

```ts
import { type ComputeError, type DriftInfo, compute } from './compute'

export interface WorkerRequest { sources: Record<string, string> }
export interface WorkerResponse {
  data: unknown
  errors: ComputeError[]
  drifts: DriftInfo[]
  computeMs: number
}

self.onmessage = async (e: MessageEvent<WorkerRequest>) => {
  const start = performance.now()
  try {
    const { data, errors, drifts } = await compute(e.data.sources)
    const res: WorkerResponse = { data, errors, drifts, computeMs: performance.now() - start }
    ;(self as unknown as Worker).postMessage(res)
  } catch (err) {
    ;(self as unknown as Worker).postMessage({
      data: null,
      errors: [{ message: String(err), line: 0, sourceFsPath: '' }],
      drifts: [],
      computeMs: performance.now() - start,
    } satisfies WorkerResponse)
  }
}
```

- [ ] **Step 10: Write `test/compute.test.ts`** (ports + strengthens spike tests; adds drift)

```ts
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { compute } from '../src/compute'
import { loadSources } from '../src/sources'

const dir = (name: string) => fileURLToPath(new URL(`./fixtures/likec4/${name}`, import.meta.url))

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

    // Auto-layout baseline: compute WITHOUT the snapshot.
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
    // The snapshot shifted sys by +500 relative to its auto x — a per-node override, not a global offset.
    expect(sys.x).toBe(autoSys.x + 500)
    const ext = index.nodes.find((n: any) => n.id === 'ext')
    const autoExt = auto.views.index.nodes.find((n: any) => n.id === 'ext')
    expect(ext.x).toBe(autoExt.x)
  })

  it('reports drift when the model changed since the snapshot', async () => {
    const { drifts, errors } = await compute(loadSources(dir('drifted')))
    expect(errors).toEqual([])
    const indexDrift = drifts.find(d => d.viewId === 'index')
    expect(indexDrift, 'expected index view to be flagged as drifted').toBeTruthy()
    expect(indexDrift!.reasons).toContain('nodes-added')
  })
})
```

- [ ] **Step 11: Run the compute tests**

Run: `cd src/main/frontend && npm test -- compute`
Expected: 4 passing. If the manual-layout drift reason differs from `'nodes-added'`, log `console.log((await compute(loadSources(dir('drifted')))).drifts)` and assert the actual `LayoutedViewDriftReason` emitted (the union is `'not-exists' | 'type-changed' | 'nodes-added' | 'nodes-removed' | 'nodes-drift' | 'edges-added' | 'edges-removed' | 'edges-drift'`); update the assertion and note it. The `+500` override and empty-drift-on-target assertions are exact and must pass as written.

- [ ] **Step 12: Commit**

```bash
git add src/main/frontend/vite.config.ts src/main/frontend/src/compute.ts src/main/frontend/src/sources.ts src/main/frontend/src/worker.ts src/main/frontend/test
git commit -m "plan2: carry-forward compute with drift surfacing + fixtures + worker"
```

---

## Task 3: REST client

**Files:**
- Create: `src/main/frontend/src/restClient.ts`
- Test: `src/main/frontend/test/restClient.test.ts`

- [ ] **Step 1: Write the failing test — `test/restClient.test.ts`**

```ts
import { describe, expect, it, vi } from 'vitest'
import { RestError, createRestClient } from '../src/restClient'

function fakeFetch(status: number, body: unknown) {
  return vi.fn(async () => ({
    ok: status >= 200 && status < 300,
    status,
    statusText: `S${status}`,
    json: async () => body,
  })) as unknown as typeof fetch
}

describe('restClient', () => {
  it('resolve builds the URL and returns the sha', async () => {
    const f = fakeFetch(200, { sha: 'abc' })
    const c = createRestClient('/rest/likec4/1.0', f)
    expect(await c.resolve('grp/repo', 'main')).toEqual({ sha: 'abc' })
    expect((f as any).mock.calls[0][0]).toBe('/rest/likec4/1.0/resolve?project=grp%2Frepo&ref=main')
  })

  it('fetchSource omits empty params and returns files', async () => {
    const f = fakeFetch(200, { sha: 'abc', files: { 'a.likec4': 'x' } })
    const c = createRestClient('/rest/likec4/1.0', f)
    const res = await c.fetchSource('grp/repo', undefined, 'sub')
    expect(res.files).toEqual({ 'a.likec4': 'x' })
    expect((f as any).mock.calls[0][0]).toBe('/rest/likec4/1.0/source?project=grp%2Frepo&path=sub')
  })

  it('throws RestError carrying the status on non-2xx', async () => {
    const c = createRestClient('/rest/likec4/1.0', fakeFetch(404, {}))
    await expect(c.resolve('grp/repo')).rejects.toMatchObject({ status: 404 })
    await expect(c.resolve('grp/repo')).rejects.toBeInstanceOf(RestError)
  })
})
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd src/main/frontend && npm test -- restClient`
Expected: FAIL — `createRestClient` not found.

- [ ] **Step 3: Create `src/restClient.ts`**

```ts
export interface ResolveResponse { sha: string }
export interface SourceResponse { sha: string; files: Record<string, string> }

export class RestError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
    this.name = 'RestError'
  }
}

export interface RestClient {
  resolve(project: string, ref?: string): Promise<ResolveResponse>
  fetchSource(project: string, ref: string | undefined, path: string | undefined): Promise<SourceResponse>
}

const DEFAULT_BASE = '/rest/likec4/1.0'

function qs(params: Record<string, string | undefined>): string {
  const u = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v != null && v !== '') u.set(k, v)
  return u.toString()
}

export function createRestClient(base = DEFAULT_BASE, doFetch: typeof fetch = fetch): RestClient {
  async function getJson<T>(url: string): Promise<T> {
    const res = await doFetch(url)
    if (!res.ok) throw new RestError(res.status, `${res.status} ${res.statusText}`)
    return res.json() as Promise<T>
  }
  return {
    resolve: (project, ref) => getJson(`${base}/resolve?${qs({ project, ref })}`),
    fetchSource: (project, ref, path) => getJson(`${base}/source?${qs({ project, ref, path })}`),
  }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd src/main/frontend && npm test -- restClient`
Expected: 3 passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/frontend/src/restClient.ts src/main/frontend/test/restClient.test.ts
git commit -m "plan2: typed REST client for /resolve and /source"
```

---

## Task 4: IndexedDB dump cache

**Files:**
- Create: `src/main/frontend/src/cache.ts`
- Test: `src/main/frontend/test/cache.test.ts`

- [ ] **Step 1: Write the failing test — `test/cache.test.ts`**

```ts
import 'fake-indexeddb/auto'
import { IDBFactory } from 'fake-indexeddb'
import { beforeEach, describe, expect, it } from 'vitest'
import { IndexedDbDumpCache } from '../src/cache'

beforeEach(() => {
  // Fresh database per test.
  globalThis.indexedDB = new IDBFactory()
})

const dump = (tag: string) => ({ data: { tag }, errors: [], drifts: [] })

describe('IndexedDbDumpCache', () => {
  it('returns null for a missing key', async () => {
    const c = new IndexedDbDumpCache()
    expect(await c.get('nope')).toBeNull()
  })

  it('round-trips a stored dump', async () => {
    const c = new IndexedDbDumpCache()
    await c.put('sha1:path', dump('a'))
    const got = await c.get('sha1:path')
    expect(got?.data).toEqual({ tag: 'a' })
    expect(got?.key).toBe('sha1:path')
  })

  it('evicts the least-recently-used entry beyond the bound', async () => {
    const c = new IndexedDbDumpCache(3)
    await c.put('k0', dump('0'))
    await c.put('k1', dump('1'))
    await c.put('k2', dump('2'))
    await c.get('k0') // touch k0 so k1 becomes LRU
    await c.put('k3', dump('3')) // count 4 > 3 → evict 1
    expect(await c.get('k1')).toBeNull()
    expect(await c.get('k0')).not.toBeNull()
    expect(await c.get('k3')).not.toBeNull()
  })
})
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd src/main/frontend && npm test -- cache`
Expected: FAIL — `IndexedDbDumpCache` not found.

- [ ] **Step 3: Create `src/cache.ts`** (single transaction per op — no awaits spanning a tx; monotonic LRU stamp, no `Date`)

```ts
import type { ComputeError, DriftInfo } from './compute'

export interface CachedDump {
  key: string
  data: unknown
  errors: ComputeError[]
  drifts: DriftInfo[]
  accessedAt: number
}

export type DumpValue = Pick<CachedDump, 'data' | 'errors' | 'drifts'>

export interface DumpCache {
  get(key: string): Promise<CachedDump | null>
  put(key: string, value: DumpValue): Promise<void>
}

const DB_NAME = 'likec4-diagram'
const STORE = 'dumps'
const DB_VERSION = 1

let seq = 0
const stamp = () => ++seq

export class IndexedDbDumpCache implements DumpCache {
  private dbp: Promise<IDBDatabase> | null = null

  constructor(private readonly maxEntries = 50) {}

  private db(): Promise<IDBDatabase> {
    return (this.dbp ??= new Promise<IDBDatabase>((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION)
      req.onupgradeneeded = () => {
        const db = req.result
        if (!db.objectStoreNames.contains(STORE)) {
          const store = db.createObjectStore(STORE, { keyPath: 'key' })
          store.createIndex('accessedAt', 'accessedAt')
        }
      }
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    }))
  }

  async get(key: string): Promise<CachedDump | null> {
    const db = await this.db()
    return new Promise<CachedDump | null>((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite')
      const store = tx.objectStore(STORE)
      const getReq = store.get(key)
      getReq.onsuccess = () => {
        const row = getReq.result as CachedDump | undefined
        if (row) {
          row.accessedAt = stamp()
          store.put(row)
        }
      }
      tx.oncomplete = () => resolve((getReq.result as CachedDump | undefined) ?? null)
      tx.onerror = () => reject(tx.error)
    })
  }

  async put(key: string, value: DumpValue): Promise<void> {
    const db = await this.db()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite')
      const store = tx.objectStore(STORE)
      store.put({ key, accessedAt: stamp(), ...value })
      const countReq = store.count()
      countReq.onsuccess = () => {
        const over = countReq.result - this.maxEntries
        if (over > 0) {
          const cursorReq = store.index('accessedAt').openCursor()
          let removed = 0
          cursorReq.onsuccess = () => {
            const cur = cursorReq.result
            if (cur && removed < over) {
              cur.delete()
              removed++
              cur.continue()
            }
          }
        }
      }
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd src/main/frontend && npm test -- cache`
Expected: 3 passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/frontend/src/cache.ts src/main/frontend/test/cache.test.ts
git commit -m "plan2: IndexedDB dump cache keyed on sha:path with LRU bound"
```

---

## Task 5: Data-attribute parsing

**Files:**
- Create: `src/main/frontend/src/dataAttrs.ts`
- Test: `src/main/frontend/test/dataAttrs.test.ts`

- [ ] **Step 1: Write the failing test — `test/dataAttrs.test.ts`**

```ts
import { describe, expect, it } from 'vitest'
import { parseDataAttrs } from '../src/dataAttrs'

const el = (attrs: Record<string, string>) => ({
  getAttribute: (n: string) => (n in attrs ? attrs[n] : null),
})

describe('parseDataAttrs', () => {
  it('reads all attributes, trimming values', () => {
    expect(parseDataAttrs(el({
      'data-project': ' grp/repo ',
      'data-ref': 'main',
      'data-path': 'sub',
      'data-view': 'index',
      'data-instance': '7',
    }))).toEqual({ project: 'grp/repo', ref: 'main', path: 'sub', view: 'index', instance: '7' })
  })

  it('treats blank optionals as undefined', () => {
    expect(parseDataAttrs(el({ 'data-project': 'grp/repo', 'data-ref': '  ' })))
      .toEqual({ project: 'grp/repo', ref: undefined, path: undefined, view: undefined, instance: undefined })
  })

  it('throws when project is missing', () => {
    expect(() => parseDataAttrs(el({ 'data-ref': 'main' }))).toThrow(/data-project/)
  })
})
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd src/main/frontend && npm test -- dataAttrs`
Expected: FAIL — `parseDataAttrs` not found.

- [ ] **Step 3: Create `src/dataAttrs.ts`** (DOM-agnostic: needs only `getAttribute`)

```ts
export interface DiagramParams {
  project: string
  ref?: string
  path?: string
  view?: string
  instance?: string
}

export interface AttrSource {
  getAttribute(name: string): string | null
}

export function parseDataAttrs(el: AttrSource): DiagramParams {
  const project = el.getAttribute('data-project')?.trim()
  if (!project) throw new Error('likec4-diagram: missing required "data-project" attribute')
  const opt = (name: string) => {
    const v = el.getAttribute(name)?.trim()
    return v ? v : undefined
  }
  return {
    project,
    ref: opt('data-ref'),
    path: opt('data-path'),
    view: opt('data-view'),
    instance: opt('data-instance'),
  }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd src/main/frontend && npm test -- dataAttrs`
Expected: 3 passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/frontend/src/dataAttrs.ts src/main/frontend/test/dataAttrs.test.ts
git commit -m "plan2: parse macro div data-attributes into DiagramParams"
```

---

## Task 6: Shared Web Worker pool

**Files:**
- Create: `src/main/frontend/src/workerPool.ts`
- Create: `src/main/frontend/src/createDiagramWorker.ts`
- Test: `src/main/frontend/test/workerPool.test.ts`

The pool takes a `createWorker` factory so it is Node-testable with a fake worker; the real Vite `new Worker(new URL(...))` lives in `createDiagramWorker.ts` (imported only by `boot`).

- [ ] **Step 1: Write the failing test — `test/workerPool.test.ts`**

```ts
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TimeoutError, WorkerPool, type PoolWorker } from '../src/workerPool'

class FakeWorker implements PoolWorker {
  onmessage: ((e: { data: any }) => void) | null = null
  onerror: ((e: any) => void) | null = null
  posted: any[] = []
  terminated = false
  autoRespond: any | null
  constructor(autoRespond: any | null) { this.autoRespond = autoRespond }
  postMessage(message: any) {
    this.posted.push(message)
    if (this.autoRespond !== null) queueMicrotask(() => this.onmessage?.({ data: this.autoRespond }))
  }
  terminate() { this.terminated = true }
  respond(data: any) { this.onmessage?.({ data }) }
}

afterEach(() => vi.useRealTimers())

describe('WorkerPool', () => {
  it('runs a job and resolves with the worker response', async () => {
    const resp = { data: { ok: 1 }, errors: [], drifts: [], computeMs: 1 }
    const pool = new WorkerPool(() => new FakeWorker(resp), 2)
    expect(await pool.run({ 'a.likec4': 'x' })).toEqual(resp)
  })

  it('caps concurrent workers and queues the overflow', async () => {
    const made: FakeWorker[] = []
    const pool = new WorkerPool(() => { const w = new FakeWorker(null); made.push(w); return w }, 2)
    const p0 = pool.run({ a: '0' })
    const p1 = pool.run({ a: '1' })
    const p2 = pool.run({ a: '2' }) // queued: only 2 workers allowed
    expect(made.length).toBe(2)
    made[0].respond({ data: 0, errors: [], drifts: [], computeMs: 1 })
    await p0
    expect(made.length).toBe(2) // worker 0 reused for the queued job, not a 3rd
    expect(made[0].posted.length).toBe(2)
    made[0].respond({ data: 2, errors: [], drifts: [], computeMs: 1 })
    made[1].respond({ data: 1, errors: [], drifts: [], computeMs: 1 })
    expect((await p2).data).toBe(2)
    expect((await p1).data).toBe(1)
  })

  it('times out, terminates the stuck worker, and rejects with TimeoutError', async () => {
    vi.useFakeTimers()
    const w = new FakeWorker(null)
    const pool = new WorkerPool(() => w, 1)
    const p = pool.run({ a: 'x' }, { timeoutMs: 100 })
    const assertion = expect(p).rejects.toBeInstanceOf(TimeoutError)
    await vi.advanceTimersByTimeAsync(100)
    await assertion
    expect(w.terminated).toBe(true)
  })
})
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd src/main/frontend && npm test -- workerPool`
Expected: FAIL — `WorkerPool` not found.

- [ ] **Step 3: Create `src/workerPool.ts`**

```ts
import type { WorkerResponse } from './worker'

export interface PoolWorker {
  postMessage(message: { sources: Record<string, string> }): void
  terminate(): void
  onmessage: ((e: { data: any }) => void) | null
  onerror: ((e: any) => void) | null
}

export interface RunOptions { timeoutMs?: number; signal?: AbortSignal }

export class TimeoutError extends Error {
  constructor(message = 'compute timed out') {
    super(message)
    this.name = 'TimeoutError'
  }
}

interface Job {
  sources: Record<string, string>
  opts: RunOptions
  resolve: (r: WorkerResponse) => void
  reject: (e: unknown) => void
}

export class WorkerPool {
  private idle: PoolWorker[] = []
  private created = 0
  private queue: Job[] = []

  constructor(private readonly createWorker: () => PoolWorker, private readonly maxWorkers = 3) {}

  run(sources: Record<string, string>, opts: RunOptions = {}): Promise<WorkerResponse> {
    return new Promise<WorkerResponse>((resolve, reject) => {
      if (opts.signal?.aborted) {
        reject(new DOMException('Aborted', 'AbortError'))
        return
      }
      this.queue.push({ sources, opts, resolve, reject })
      this.pump()
    })
  }

  private pump(): void {
    while (this.queue.length > 0) {
      let worker = this.idle.pop()
      if (!worker) {
        if (this.created >= this.maxWorkers) return
        worker = this.createWorker()
        this.created++
      }
      this.assign(worker, this.queue.shift()!)
    }
  }

  private assign(worker: PoolWorker, job: Job): void {
    let done = false
    const finish = (action: () => void) => {
      if (done) return
      done = true
      if (timer !== null) clearTimeout(timer)
      job.opts.signal?.removeEventListener('abort', onAbort)
      worker.onmessage = null
      worker.onerror = null
      action()
    }
    const onAbort = () => finish(() => {
      this.discard(worker)
      job.reject(new DOMException('Aborted', 'AbortError'))
    })
    const timer = job.opts.timeoutMs
      ? setTimeout(() => finish(() => {
          this.discard(worker)
          job.reject(new TimeoutError())
        }), job.opts.timeoutMs)
      : null

    worker.onmessage = (e) => finish(() => {
      this.idle.push(worker)
      job.resolve(e.data as WorkerResponse)
      this.pump()
    })
    worker.onerror = (e) => finish(() => {
      this.discard(worker)
      job.reject(new Error(String((e && (e.message ?? e)) ?? 'worker error')))
    })
    job.opts.signal?.addEventListener('abort', onAbort)
    worker.postMessage({ sources: job.sources })
  }

  /** Terminate a worker (e.g. on timeout/abort/error) and free its slot so the queue can refill. */
  private discard(worker: PoolWorker): void {
    worker.terminate()
    this.created--
    this.pump()
  }

  dispose(): void {
    for (const w of this.idle) w.terminate()
    this.idle = []
    this.created = 0
  }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd src/main/frontend && npm test -- workerPool`
Expected: 3 passing.

- [ ] **Step 5: Create `src/createDiagramWorker.ts`** (production factory — Vite worker construct, isolated from the Node-tested pool)

```ts
import type { PoolWorker } from './workerPool'

/** Vite bundles `./worker.ts` as a separate ES-module worker chunk (lazy: created on first job). */
export function createDiagramWorker(): PoolWorker {
  return new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' }) as unknown as PoolWorker
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/frontend/src/workerPool.ts src/main/frontend/src/createDiagramWorker.ts src/main/frontend/test/workerPool.test.ts
git commit -m "plan2: shared worker pool with queue, timeout, and terminate-on-cancel"
```

---

## Task 7: Pipeline — `loadModel` orchestration core

**Files:**
- Create: `src/main/frontend/src/pipeline.ts`
- Test: `src/main/frontend/test/pipeline.test.ts`

- [ ] **Step 1: Write the failing test — `test/pipeline.test.ts`**

```ts
import { describe, expect, it, vi } from 'vitest'
import { loadModel, type PipelineDeps } from '../src/pipeline'
import { RestError } from '../src/restClient'
import { TimeoutError } from '../src/workerPool'

const okData = { views: { index: { id: 'index' }, sys_detail: { id: 'sys_detail' } } }
const okResponse = { data: okData, errors: [], drifts: [], computeMs: 5 }

function deps(over: Partial<{
  resolve: any; fetchSource: any; get: any; put: any; run: any; onCompute: any
}> = {}): { deps: PipelineDeps; spies: any } {
  const spies = {
    resolve: vi.fn(over.resolve ?? (async () => ({ sha: 'sha1' }))),
    fetchSource: vi.fn(over.fetchSource ?? (async () => ({ sha: 'sha1', files: { 'm.likec4': 'x' } }))),
    get: vi.fn(over.get ?? (async () => null)),
    put: vi.fn(over.put ?? (async () => {})),
    run: vi.fn(over.run ?? (async () => okResponse)),
    onCompute: vi.fn(over.onCompute ?? (() => {})),
  }
  return {
    spies,
    deps: {
      rest: { resolve: spies.resolve, fetchSource: spies.fetchSource } as any,
      cache: { get: spies.get, put: spies.put } as any,
      pool: { run: spies.run } as any,
      onCompute: spies.onCompute,
      timeoutMs: 1000,
    },
  }
}

describe('loadModel', () => {
  it('renders from cache without fetching or computing', async () => {
    const { deps: d, spies } = deps({ get: async () => ({ key: 'sha1:', data: okData, errors: [], drifts: [], accessedAt: 1 }) })
    const r = await loadModel({ project: 'p' }, d)
    expect(r).toMatchObject({ kind: 'ok', fromCache: true, startView: 'index' })
    expect(spies.fetchSource).not.toHaveBeenCalled()
    expect(spies.run).not.toHaveBeenCalled()
    expect(spies.onCompute).not.toHaveBeenCalled()
  })

  it('fetches, computes, caches on a miss', async () => {
    const { deps: d, spies } = deps()
    const r = await loadModel({ project: 'p', path: 'sub' }, d)
    expect(r).toMatchObject({ kind: 'ok', fromCache: false })
    expect(spies.onCompute).toHaveBeenCalledTimes(1)
    expect(spies.put).toHaveBeenCalledWith('sha1:sub', { data: okData, errors: [], drifts: [] })
  })

  it('maps resolve 404 to not-found', async () => {
    const { deps: d } = deps({ resolve: async () => { throw new RestError(404, 'nf') } })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'not-found' })
  })

  it('maps a network failure to unreachable', async () => {
    const { deps: d } = deps({ resolve: async () => { throw new TypeError('network') } })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'unreachable' })
  })

  it('surfaces parse errors', async () => {
    const errors = [{ message: 'bad', line: 2, sourceFsPath: 'm.likec4' }]
    const { deps: d } = deps({ run: async () => ({ data: okData, errors, drifts: [], computeMs: 1 }) })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'parse-error', errors })
  })

  it('reports an unknown requested view with the available list', async () => {
    const { deps: d } = deps()
    expect(await loadModel({ project: 'p', view: 'nope' }, d))
      .toMatchObject({ kind: 'unknown-view', requested: 'nope', available: ['index', 'sys_detail'] })
  })

  it('maps a compute timeout to too-large', async () => {
    const { deps: d } = deps({ run: async () => { throw new TimeoutError() } })
    expect(await loadModel({ project: 'p' }, d)).toMatchObject({ kind: 'too-large' })
  })
})
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd src/main/frontend && npm test -- pipeline`
Expected: FAIL — `loadModel` not found.

- [ ] **Step 3: Create `src/pipeline.ts`**

```ts
import type { DumpCache } from './cache'
import type { ComputeError, DriftInfo } from './compute'
import type { DiagramParams } from './dataAttrs'
import { RestError, type RestClient } from './restClient'
import { TimeoutError, type WorkerPool } from './workerPool'

export type LoadResult =
  | { kind: 'ok'; sha: string; data: unknown; viewIds: string[]; startView: string; drifts: DriftInfo[]; fromCache: boolean }
  | { kind: 'parse-error'; errors: ComputeError[] }
  | { kind: 'unknown-view'; requested: string; available: string[] }
  | { kind: 'not-found'; detail: string }
  | { kind: 'unreachable'; detail: string }
  | { kind: 'too-large'; detail: string }
  | { kind: 'error'; detail: string }

export interface PipelineDeps {
  rest: RestClient
  cache: DumpCache
  pool: WorkerPool
  timeoutMs?: number
  /** Called once per real compute (cache miss) — boot wires a window counter for e2e. */
  onCompute?: () => void
}

export async function loadModel(params: DiagramParams, deps: PipelineDeps): Promise<LoadResult> {
  const { rest, cache, pool } = deps

  let sha: string
  try {
    sha = (await rest.resolve(params.project, params.ref)).sha
  } catch (e) {
    return mapFetchError(e, 'resolve')
  }

  const cacheKey = `${sha}:${params.path ?? ''}`
  let cached = await cache.get(cacheKey)
  const fromCache = cached !== null
  let data: unknown
  let errors: ComputeError[]
  let drifts: DriftInfo[]

  if (cached) {
    ;({ data, errors, drifts } = cached)
  } else {
    let files: Record<string, string>
    try {
      files = (await rest.fetchSource(params.project, params.ref, params.path)).files
    } catch (e) {
      return mapFetchError(e, 'source')
    }
    deps.onCompute?.()
    try {
      const res = await pool.run(files, { timeoutMs: deps.timeoutMs })
      ;({ data, errors, drifts } = res)
    } catch (e) {
      if (e instanceof TimeoutError) return { kind: 'too-large', detail: 'Diagram computation timed out' }
      return { kind: 'error', detail: String(e) }
    }
    await cache.put(cacheKey, { data, errors, drifts })
  }

  if (errors.length > 0) return { kind: 'parse-error', errors }

  const views = (data as any)?.views ?? {}
  const viewIds = Object.keys(views)
  if (viewIds.length === 0) return { kind: 'error', detail: 'Model has no views' }
  if (params.view && !viewIds.includes(params.view)) {
    return { kind: 'unknown-view', requested: params.view, available: viewIds }
  }
  const startView = params.view ?? (viewIds.includes('index') ? 'index' : viewIds[0])
  return { kind: 'ok', sha, data, viewIds, startView, drifts, fromCache }
}

function mapFetchError(e: unknown, stage: 'resolve' | 'source'): LoadResult {
  if (e instanceof RestError) {
    if (e.status === 404) {
      return { kind: 'not-found', detail: stage === 'resolve' ? 'Project or ref not found' : 'Source path not found' }
    }
    return { kind: 'unreachable', detail: `Source repository error (${e.status})` }
  }
  return { kind: 'unreachable', detail: 'Cannot reach source repository' }
}
```

- [ ] **Step 4: Run it to confirm it passes**

Run: `cd src/main/frontend && npm test -- pipeline`
Expected: 7 passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/frontend/src/pipeline.ts src/main/frontend/test/pipeline.test.ts
git commit -m "plan2: loadModel orchestration (resolve, cache, compute, error mapping)"
```

---

## Task 8: Failure descriptions + view listing (pure)

**Files:**
- Create: `src/main/frontend/src/errors.ts`
- Create: `src/main/frontend/src/editor/listViews.ts`
- Test: `src/main/frontend/test/errors.test.ts`
- Test: `src/main/frontend/test/listViews.test.ts`

- [ ] **Step 1: Write the failing test — `test/errors.test.ts`**

```ts
import { describe, expect, it } from 'vitest'
import { describeFailure } from '../src/errors'

const params = { project: 'grp/repo', ref: 'main', path: 'sub' }

describe('describeFailure', () => {
  it('formats parse errors as file:line — message', () => {
    const f = describeFailure({ kind: 'parse-error', errors: [{ message: 'oops', line: 3, sourceFsPath: 'm.likec4' }] }, params)
    expect(f.title).toMatch(/errors/i)
    expect(f.lines).toContain('m.likec4:3 — oops')
  })

  it('lists available views for an unknown view', () => {
    const f = describeFailure({ kind: 'unknown-view', requested: 'x', available: ['index', 'sys_detail'] }, params)
    expect(f.title).toContain('x')
    expect(f.lines.join('\n')).toContain('sys_detail')
  })

  it('echoes params for not-found', () => {
    const f = describeFailure({ kind: 'not-found', detail: 'Project or ref not found' }, params)
    expect(f.lines.join('\n')).toContain('grp/repo')
    expect(f.lines.join('\n')).toContain('main')
  })

  it('has a message for unreachable, too-large, and error', () => {
    for (const kind of ['unreachable', 'too-large', 'error'] as const) {
      const f = describeFailure({ kind, detail: 'd' } as any, params)
      expect(f.title.length).toBeGreaterThan(0)
      expect(f.tone).toBe('error')
    }
  })
})
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd src/main/frontend && npm test -- errors`
Expected: FAIL — `describeFailure` not found.

- [ ] **Step 3: Create `src/errors.ts`**

```ts
import type { DiagramParams } from './dataAttrs'
import type { LoadResult } from './pipeline'

export interface FailureView {
  title: string
  lines: string[]
  tone: 'error' | 'warning'
}

export type LoadFailure = Exclude<LoadResult, { kind: 'ok' }>

export function describeFailure(result: LoadFailure, params: DiagramParams): FailureView {
  switch (result.kind) {
    case 'parse-error':
      return {
        title: 'LikeC4 source has errors',
        tone: 'error',
        lines: result.errors.map(e => `${e.sourceFsPath || 'source'}:${e.line} — ${e.message}`),
      }
    case 'unknown-view':
      return {
        title: `Unknown view "${result.requested}"`,
        tone: 'error',
        lines: ['Available views:', ...result.available.map(v => `• ${v}`)],
      }
    case 'not-found':
      return {
        title: 'Diagram source not found',
        tone: 'error',
        lines: [result.detail, `project: ${params.project}`, `ref: ${params.ref ?? '(default)'}`, `path: ${params.path ?? '(root)'}`],
      }
    case 'unreachable':
      return { title: 'Cannot reach source repository', tone: 'error', lines: [result.detail] }
    case 'too-large':
      return { title: 'Diagram too large to render', tone: 'error', lines: [result.detail] }
    case 'error':
      return { title: 'Could not render diagram', tone: 'error', lines: [result.detail] }
  }
}
```

- [ ] **Step 4: Write the failing test — `test/listViews.test.ts`**

```ts
import { describe, expect, it } from 'vitest'
import { listViews } from '../src/editor/listViews'

describe('listViews', () => {
  it('maps model views to id+title, falling back to id', () => {
    const data = { views: { index: { id: 'index', title: 'Landscape' }, d: { id: 'd' } } }
    expect(listViews(data)).toEqual([{ id: 'index', title: 'Landscape' }, { id: 'd', title: 'd' }])
  })

  it('returns [] for empty/absent views', () => {
    expect(listViews({})).toEqual([])
    expect(listViews(null)).toEqual([])
  })
})
```

- [ ] **Step 5: Create `src/editor/listViews.ts`**

```ts
export interface ViewInfo {
  id: string
  title: string
}

export function listViews(data: unknown): ViewInfo[] {
  const views = (data as any)?.views ?? {}
  return Object.values<any>(views).map(v => ({ id: v.id, title: v.title ?? v.id }))
}
```

- [ ] **Step 6: Run both tests**

Run: `cd src/main/frontend && npm test -- errors listViews`
Expected: errors (4) + listViews (2) passing.

- [ ] **Step 7: Commit**

```bash
git add src/main/frontend/src/errors.ts src/main/frontend/src/editor/listViews.ts src/main/frontend/test/errors.test.ts src/main/frontend/test/listViews.test.ts
git commit -m "plan2: pure failure descriptions and view listing"
```

---

## Task 9: React viewer — Diagram, ErrorPanel, Viewer

**Files:**
- Create: `src/main/frontend/src/ErrorPanel.tsx`
- Create: `src/main/frontend/src/Diagram.tsx`
- Create: `src/main/frontend/src/Viewer.tsx`
- Create: `src/main/frontend/src/styles.css`

These are React components verified live by Playwright (Task 12). There is no Node DOM test layer (the renderer needs a real browser); the pure logic they rest on is already covered (Tasks 7, 8). Type-correctness is gated by `npm run typecheck` in Task 13.

- [ ] **Step 1: Create `src/ErrorPanel.tsx`**

```tsx
import type { FailureView } from './errors'

export function ErrorPanel({ failure }: { failure: FailureView }) {
  return (
    <div data-testid="likec4-error" role="alert" className={`likec4-error likec4-${failure.tone}`}>
      <strong>{failure.title}</strong>
      <ul>
        {failure.lines.map((line, i) => (
          <li key={i}>{line}</li>
        ))}
      </ul>
    </div>
  )
}
```

- [ ] **Step 2: Create `src/Diagram.tsx`** (default export — lazy chunk; in-place nav, drift banner, fullscreen)

```tsx
import { LikeC4Model } from '@likec4/core/model'
import { LikeC4Diagram, LikeC4ModelProvider, useLikeC4Model } from 'likec4/react'
import { useEffect, useMemo, useState } from 'react'
import type { DriftInfo } from './compute'

export default function Diagram({ data, startView, drifts }: { data: unknown; startView: string; drifts: DriftInfo[] }) {
  const model = useMemo(() => LikeC4Model.create(data as any), [data])
  const [viewId, setViewId] = useState(startView)
  const [fullscreen, setFullscreen] = useState(false)

  useEffect(() => {
    if (!fullscreen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setFullscreen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [fullscreen])

  const drift = drifts.find(d => d.viewId === viewId)

  return (
    <div
      className={`likec4-viewer${fullscreen ? ' likec4-fullscreen' : ''}`}
      data-testid="likec4-diagram"
      data-current-view={viewId}
      style={{ width: '100%', height: '100%' }}
    >
      {drift && (
        <div data-testid="likec4-drift-banner" className="likec4-drift" role="status">
          Curated layout may be stale ({drift.reasons.join(', ')}). Ask an architect to re-export the snapshot.
        </div>
      )}
      <button
        type="button"
        data-testid="likec4-fullscreen-toggle"
        className="likec4-fullscreen-toggle"
        onClick={() => setFullscreen(f => !f)}
      >
        {fullscreen ? 'Exit full screen' : 'Full screen'}
      </button>
      <LikeC4ModelProvider likec4model={model}>
        <ViewRenderer viewId={viewId} onNavigate={setViewId} />
      </LikeC4ModelProvider>
    </div>
  )
}

function ViewRenderer({ viewId, onNavigate }: { viewId: string; onNavigate: (id: string) => void }) {
  const view = useLikeC4Model().findView(viewId)
  if (!view) return <div data-testid="likec4-error">view not found: {viewId}</div>
  return (
    <LikeC4Diagram
      view={(view as any).$view}
      onNavigateTo={(to) => onNavigate(to as string)}
      zoomable
      pannable
      showNavigationButtons
    />
  )
}
```

- [ ] **Step 3: Create `src/Viewer.tsx`** (per-diagram lifecycle; lazy-loads the renderer chunk)

```tsx
import { Suspense, lazy, useEffect, useState } from 'react'
import { ErrorPanel } from './ErrorPanel'
import type { DiagramParams } from './dataAttrs'
import { describeFailure } from './errors'
import { loadModel, type LoadResult, type PipelineDeps } from './pipeline'

const Diagram = lazy(() => import('./Diagram'))

type State = LoadResult | { kind: 'loading' }

export function Viewer({ params, deps }: { params: DiagramParams; deps: PipelineDeps }) {
  const [state, setState] = useState<State>({ kind: 'loading' })

  useEffect(() => {
    let alive = true
    setState({ kind: 'loading' })
    loadModel(params, deps)
      .then(r => { if (alive) setState(r) })
      .catch(e => { if (alive) setState({ kind: 'error', detail: String(e) }) })
    return () => { alive = false }
  }, [params, deps])

  if (state.kind === 'loading') {
    return <div data-testid="likec4-loading" className="likec4-loading">Loading diagram…</div>
  }
  if (state.kind === 'ok') {
    return (
      <Suspense fallback={<div data-testid="likec4-loading" className="likec4-loading">Rendering…</div>}>
        <Diagram data={state.data} startView={state.startView} drifts={state.drifts} />
      </Suspense>
    )
  }
  return <ErrorPanel failure={describeFailure(state, params)} />
}
```

- [ ] **Step 4: Create `src/styles.css`**

```css
.likec4-viewer { position: relative; width: 100%; height: 100%; }
.likec4-fullscreen { position: fixed; inset: 0; z-index: 9999; background: #fff; width: 100vw !important; height: 100vh !important; }
.likec4-fullscreen-toggle { position: absolute; top: 8px; right: 8px; z-index: 10000; font: 12px sans-serif; }
.likec4-drift { position: absolute; top: 8px; left: 8px; z-index: 10000; max-width: 60%; background: #fff3cd; color: #664d03; padding: 4px 8px; border-radius: 4px; font: 12px/1.3 sans-serif; }
.likec4-error { padding: 12px; font: 13px/1.4 sans-serif; color: #842029; background: #f8d7da; border-radius: 4px; }
.likec4-error ul { margin: 6px 0 0; padding-left: 18px; }
.likec4-loading { padding: 12px; font: 13px sans-serif; color: #555; }
```

- [ ] **Step 5: Commit**

```bash
git add src/main/frontend/src/ErrorPanel.tsx src/main/frontend/src/Diagram.tsx src/main/frontend/src/Viewer.tsx src/main/frontend/src/styles.css
git commit -m "plan2: React viewer with in-place nav, drift banner, fullscreen, error panel"
```

---

## Task 10: Boot entry, mock-REST middleware, build config, REST fixtures

**Files:**
- Create: `src/main/frontend/src/boot.tsx`
- Create: `src/main/frontend/vite-plugin-mock-rest.ts`
- Modify: `src/main/frontend/vite.config.ts` (add mock plugin + build config)
- Create: `src/main/frontend/index.html`
- Create: REST fixtures under `src/main/frontend/test/fixtures/repos/acme/architecture/{ok,drifted,broken}/…`

- [ ] **Step 1: Create `src/boot.tsx`** (builds shared deps once; mounts a Viewer per macro div)

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'
import { IndexedDbDumpCache } from './cache'
import { createDiagramWorker } from './createDiagramWorker'
import { parseDataAttrs } from './dataAttrs'
import type { PipelineDeps } from './pipeline'
import { createRestClient } from './restClient'
import { Viewer } from './Viewer'
import { WorkerPool } from './workerPool'

export function boot(root: ParentNode = document): void {
  const deps: PipelineDeps = {
    rest: createRestClient(),
    cache: new IndexedDbDumpCache(),
    pool: new WorkerPool(createDiagramWorker, 3),
    timeoutMs: 20_000,
    onCompute: () => {
      ;(window as any).__likec4Computes = ((window as any).__likec4Computes ?? 0) + 1
    },
  }
  root.querySelectorAll<HTMLElement>('.likec4-diagram').forEach(el => {
    try {
      const params = parseDataAttrs(el)
      el.replaceChildren()
      createRoot(el).render(
        <StrictMode>
          <Viewer params={params} deps={deps} />
        </StrictMode>,
      )
    } catch (e) {
      el.textContent = e instanceof Error ? e.message : String(e)
    }
  })
}

boot()
```

- [ ] **Step 2: Create `vite-plugin-mock-rest.ts`** (serves `/resolve` + `/source` from fixture repos, dev AND preview)

```ts
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import type { Connect, Plugin } from 'vite'

const KEEP = (name: string) => name.endsWith('.c4') || name.endsWith('.likec4') || name.endsWith('.likec4.snap')

/** Deterministic 40-char hex from a string. NOT cryptographic — fixture use only. */
function fakeSha(input: string): string {
  let h = 0x811c9dc5
  let out = ''
  for (let i = 0; i < 40; i++) {
    for (let j = 0; j < input.length; j++) {
      h ^= input.charCodeAt(j) + i
      h = Math.imul(h, 0x01000193)
    }
    out += ((h >>> 28) & 0xf).toString(16)
  }
  return out
}

function loadSubtree(reposDir: string, project: string, path: string): Record<string, string> {
  const base = join(reposDir, project, path)
  const out: Record<string, string> = {}
  if (!existsSync(base)) return out
  const walk = (cur: string) => {
    for (const name of readdirSync(cur)) {
      const full = join(cur, name)
      if (statSync(full).isDirectory()) walk(full)
      else if (KEEP(name)) out[relative(base, full).split(sep).join('/')] = readFileSync(full, 'utf8')
    }
  }
  walk(base)
  return out
}

export function mockRest(reposDir: string): Plugin {
  const handler: Connect.NextHandleFunction = (req, res, next) => {
    const url = new URL(req.url ?? '', 'http://localhost')
    if (!url.pathname.startsWith('/rest/likec4/1.0/')) return next()
    const project = url.searchParams.get('project') ?? ''
    const ref = url.searchParams.get('ref') ?? 'main'
    const path = url.searchParams.get('path') ?? ''
    res.setHeader('content-type', 'application/json')
    if (url.pathname.endsWith('/resolve')) {
      res.end(JSON.stringify({ sha: fakeSha(`${project}@${ref}`) }))
      return
    }
    if (url.pathname.endsWith('/source')) {
      const files = loadSubtree(reposDir, project, path)
      if (Object.keys(files).length === 0) {
        res.statusCode = 404
        res.end(JSON.stringify({ error: 'not found' }))
        return
      }
      res.end(JSON.stringify({ sha: fakeSha(`${project}@${ref}`), files }))
      return
    }
    next()
  }
  return {
    name: 'mock-likec4-rest',
    configureServer(server) { server.middlewares.use(handler) },
    configurePreviewServer(server) { server.middlewares.use(handler) },
  }
}
```

- [ ] **Step 3: Replace `vite.config.ts`** with the full build + test config

```ts
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { mockRest } from './vite-plugin-mock-rest'

const reposDir = fileURLToPath(new URL('./test/fixtures/repos', import.meta.url))

export default defineConfig({
  plugins: [react(), mockRest(reposDir)],
  worker: { format: 'es' },
  resolve: {
    alias: { 'vscode-languageserver/browser': 'vscode-languageserver/browser.js' },
  },
  build: {
    outDir: '../resources/likec4-web',
    emptyOutDir: true,
    manifest: true,
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        editor: fileURLToPath(new URL('./editor.html', import.meta.url)),
      },
    },
  },
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
    server: { deps: { inline: [/@likec4\//, /langium/] } },
  },
})
```

- [ ] **Step 4: Create `index.html`** (three macro divs → shared pool; ok / drifted / broken)

```html
<!doctype html>
<html>
  <head><meta charset="utf-8" /><title>LikeC4 viewer</title></head>
  <body>
    <div class="likec4-diagram" data-project="acme/architecture" data-ref="main" data-path="ok" data-view="index" data-instance="1" style="width: 100vw; height: 70vh;">
      LikeC4 diagram (requires JavaScript)
    </div>
    <div class="likec4-diagram" data-project="acme/architecture" data-ref="main" data-path="drifted" data-instance="2" style="width: 100vw; height: 40vh;"></div>
    <div class="likec4-diagram" data-project="acme/architecture" data-ref="main" data-path="broken" data-instance="3" style="width: 100vw; height: 20vh;"></div>
    <script type="module" src="/src/boot.tsx"></script>
  </body>
</html>
```

- [ ] **Step 5: Create the REST fixture repos** (reuse the compute fixtures' content)

Create three project subtrees under `test/fixtures/repos/acme/architecture/`:
- `ok/` — copy of `test/fixtures/likec4/target/` (the three `.likec4` files **and** `.likec4/index.likec4.snap`).
- `drifted/` — copy of `test/fixtures/likec4/drifted/` (three `.likec4` files + `.likec4/index.likec4.snap`).
- `broken/` — copy of `test/fixtures/likec4/broken/` (just `model.likec4`).

Run:
```bash
cd src/main/frontend
mkdir -p test/fixtures/repos/acme/architecture
cp -r test/fixtures/likec4/target  test/fixtures/repos/acme/architecture/ok
cp -r test/fixtures/likec4/drifted test/fixtures/repos/acme/architecture/drifted
cp -r test/fixtures/likec4/broken  test/fixtures/repos/acme/architecture/broken
```

- [ ] **Step 6: Verify the build emits split chunks (renderer + worker separate from boot)**

Run: `cd src/main/frontend && npm run build`
Expected: build succeeds; `dist`/`../resources/likec4-web/` contains a **worker** chunk (language-services + elk, ~1.1 MB gz), a separate **Diagram/likec4-react** chunk (lazy renderer), and a small **boot** entry. Confirm the renderer split worked:

```bash
cd src/main/frontend
# Reliable on minified output: assert a worker chunk exists (language-services off the main thread)
# and the entry chunk is small (a leaked renderer/language-services would be megabytes).
node -e "const fs=require('fs');const dir='../resources/likec4-web/assets';const js=fs.readdirSync(dir).filter(n=>n.endsWith('.js')).map(n=>({n,kb:Math.round(fs.statSync(dir+'/'+n).size/1024)}));js.sort((a,b)=>b.kb-a.kb);console.table(js);const worker=js.find(x=>/worker/i.test(x.n));if(!worker){console.error('FAIL: no worker chunk — language-services not split off-main-thread');process.exit(1)}const entry=js.find(x=>/^(main|boot|index)/.test(x.n));if(entry&&entry.kb>900){console.error('FAIL: entry chunk '+entry.n+' is '+entry.kb+'KB — renderer/language-services likely leaked');process.exit(1)}console.log('OK: worker chunk '+worker.n+' present; entry split looks correct')"
```
Expected: a table of chunks, then `OK: worker chunk …`. The worker chunk should dominate (~2.7 MB raw / ~1.1 MB gz); a separate renderer chunk holds `likec4/react`; the entry chunk stays small. If it FAILS, something in the main import graph pulls `compute.ts`/`@likec4/language-services` — ensure only `worker.ts` imports `compute`, only `createDiagramWorker` references the worker, and `Diagram.tsx` (the only `likec4/react` importer) is reached solely through `lazy(() => import('./Diagram'))`.

- [ ] **Step 7: Commit**

```bash
git add src/main/frontend/src/boot.tsx src/main/frontend/vite-plugin-mock-rest.ts src/main/frontend/vite.config.ts src/main/frontend/index.html src/main/frontend/test/fixtures/repos
git commit -m "plan2: boot entry, mock-REST middleware, split build config, REST fixtures"
```

---

## Task 11: Macro-editor view-picker (full UI + mount API)

**Files:**
- Create: `src/main/frontend/src/editor/ViewPicker.tsx`
- Create: `src/main/frontend/src/editor/mountEditor.tsx`
- Create: `src/main/frontend/src/editor-dev.tsx`
- Create: `src/main/frontend/editor.html`

`listViews` (Task 8) is already tested. The components are verified live in Task 12.

- [ ] **Step 1: Create `src/editor/ViewPicker.tsx`** (dropdown + live preview; the picker controls the preview)

```tsx
import { Suspense, lazy } from 'react'
import type { DriftInfo } from '../compute'
import type { ViewInfo } from './listViews'

const Diagram = lazy(() => import('../Diagram'))

export function ViewPicker({
  views,
  selected,
  onChange,
  data,
  drifts,
}: {
  views: ViewInfo[]
  selected: string
  onChange: (id: string) => void
  data: unknown
  drifts: DriftInfo[]
}) {
  return (
    <div className="likec4-editor" data-testid="likec4-editor">
      <select
        data-testid="likec4-view-picker"
        value={selected}
        onChange={e => onChange(e.target.value)}
      >
        {views.map(v => (
          <option key={v.id} value={v.id}>{v.title} ({v.id})</option>
        ))}
      </select>
      <div className="likec4-editor-preview" style={{ width: '100%', height: '400px' }}>
        <Suspense fallback={<div data-testid="likec4-loading">Rendering…</div>}>
          {/* key={selected} remounts the preview so the picker (not in-diagram clicks) drives it */}
          <Diagram key={selected} data={data} startView={selected} drifts={drifts} />
        </Suspense>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: Create `src/editor/mountEditor.tsx`** (editor-agnostic mount API)

```tsx
import { StrictMode, useEffect, useState } from 'react'
import { type Root, createRoot } from 'react-dom/client'
import '../styles.css'
import { ErrorPanel } from '../ErrorPanel'
import type { DiagramParams } from '../dataAttrs'
import { describeFailure } from '../errors'
import { type LoadResult, type PipelineDeps, loadModel } from '../pipeline'
import { ViewPicker } from './ViewPicker'
import { listViews } from './listViews'

export interface EditorHandle { destroy(): void }

export interface MountOptions {
  params: DiagramParams
  deps: PipelineDeps
  /** Fired with the chosen view id on load and on each change (Plan 3 writes it back to the macro param). */
  onSelect?: (id: string) => void
}

function Editor({ params, deps, onSelect }: MountOptions) {
  const [state, setState] = useState<LoadResult | { kind: 'loading' }>({ kind: 'loading' })
  const [selected, setSelected] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    loadModel(params, deps).then(r => {
      if (!alive) return
      setState(r)
      if (r.kind === 'ok') {
        setSelected(r.startView)
        onSelect?.(r.startView)
      }
    })
    return () => { alive = false }
  }, [params, deps])

  if (state.kind === 'loading') return <div data-testid="likec4-loading">Loading…</div>
  if (state.kind !== 'ok') return <ErrorPanel failure={describeFailure(state, params)} />

  const choose = (id: string) => {
    setSelected(id)
    onSelect?.(id)
  }
  return (
    <ViewPicker
      views={listViews(state.data)}
      selected={selected ?? state.startView}
      data={state.data}
      drifts={state.drifts}
      onChange={choose}
    />
  )
}

export function mountViewPicker(container: HTMLElement, opts: MountOptions): EditorHandle {
  const root: Root = createRoot(container)
  root.render(<StrictMode><Editor {...opts} /></StrictMode>)
  return { destroy: () => root.unmount() }
}
```

- [ ] **Step 3: Create `src/editor-dev.tsx`** (e2e host wiring)

```tsx
import { IndexedDbDumpCache } from './cache'
import { createDiagramWorker } from './createDiagramWorker'
import { mountViewPicker } from './editor/mountEditor'
import type { PipelineDeps } from './pipeline'
import { createRestClient } from './restClient'
import { WorkerPool } from './workerPool'

const deps: PipelineDeps = {
  rest: createRestClient(),
  cache: new IndexedDbDumpCache(),
  pool: new WorkerPool(createDiagramWorker, 3),
  onCompute: () => {
    ;(window as any).__likec4Computes = ((window as any).__likec4Computes ?? 0) + 1
  },
}

mountViewPicker(document.getElementById('editor')!, {
  params: { project: 'acme/architecture', ref: 'main', path: 'ok' },
  deps,
  onSelect: id => { (window as any).__editorSelected = id },
})
```

- [ ] **Step 4: Create `editor.html`**

```html
<!doctype html>
<html>
  <head><meta charset="utf-8" /><title>LikeC4 macro editor</title></head>
  <body>
    <div id="editor" style="width: 100vw; height: 100vh;"></div>
    <script type="module" src="/src/editor-dev.tsx"></script>
  </body>
</html>
```

- [ ] **Step 5: Type-check everything compiles**

Run: `cd src/main/frontend && npm run typecheck`
Expected: PASS (no errors). If `likec4/react` rejects a prop, confirm the exact name in `node_modules/likec4/react/index.d.mts` (e.g. `LikeC4DiagramProps`, `onNavigateTo: (to, event?, element?) => void`) and adjust the call.

- [ ] **Step 6: Commit**

```bash
git add src/main/frontend/src/editor/ViewPicker.tsx src/main/frontend/src/editor/mountEditor.tsx src/main/frontend/src/editor-dev.tsx src/main/frontend/editor.html
git commit -m "plan2: macro-editor view-picker UI with live preview + mount API"
```

---

## Task 12: Playwright e2e (render, navigate, fullscreen, drift, error, editor)

**Files:**
- Create: `src/main/frontend/playwright.config.ts`
- Test: `src/main/frontend/test/render.spec.ts`
- Test: `src/main/frontend/test/editor.spec.ts`

**Runtime-confirm task (browser-pixel layer).** Like the spike, the host here may lack Chromium system libs (`libnss3.so` etc.). Author and commit these specs; run them if the browser launches, otherwise record "CI-gated (Plan 4 container)" — the Node + build gates remain the local proof. The two best-known-but-unconfirmed selectors are called out inline with fallbacks.

- [ ] **Step 1: Create `playwright.config.ts`**

```ts
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './test',
  testMatch: '**/*.spec.ts',
  webServer: {
    command: 'npm run build && npm run preview',
    url: 'http://localhost:4317',
    reuseExistingServer: false,
    timeout: 180_000,
  },
  use: { baseURL: 'http://localhost:4317' },
})
```

- [ ] **Step 2: Create `test/render.spec.ts`**

```ts
import { expect, test } from '@playwright/test'

test('renders, zooms, navigates in-model, and goes fullscreen', async ({ page }) => {
  await page.goto('/')

  // Instance 1 (path=ok) renders interactive nodes.
  const ok = page.locator('.likec4-diagram[data-instance="1"] [data-testid="likec4-diagram"]')
  await expect(ok).toBeVisible({ timeout: 30_000 })
  const nodes = ok.locator('.react-flow__node')
  await expect(nodes.first()).toBeVisible({ timeout: 30_000 })

  // No recompute happened for in-model interactions yet — record the baseline compute count.
  const computesBefore = await page.evaluate(() => (window as any).__likec4Computes)
  expect(computesBefore).toBeGreaterThan(0)

  // In-place navigation: navigate from the `sys` node to `sys_detail` WITHOUT a recompute.
  await expect(ok).toHaveAttribute('data-current-view', 'index')
  const sysNode = ok.locator('.react-flow__node[data-id="sys"]')
  // Best-known navigation affordance: double-click the navigable node. If the build wires
  // navigation to an explicit button instead, click `[data-testid="likec4-navigate"]` /
  // `.react-flow__node[data-id="sys"] button` (confirm the selector in --headed and update).
  await sysNode.dblclick()
  await expect(ok).toHaveAttribute('data-current-view', 'sys_detail', { timeout: 10_000 })
  expect(await page.evaluate(() => (window as any).__likec4Computes)).toBe(computesBefore)

  // Wheel zoom changes the react-flow viewport transform.
  const before = await ok.locator('.react-flow__viewport').getAttribute('style')
  await ok.hover()
  await page.mouse.wheel(0, -400)
  await page.waitForTimeout(300)
  expect(await ok.locator('.react-flow__viewport').getAttribute('style')).not.toBe(before)

  // Fullscreen toggle adds the fullscreen class.
  await ok.getByTestId('likec4-fullscreen-toggle').click()
  await expect(ok).toHaveClass(/likec4-fullscreen/)
})

test('shows a drift banner for a stale curated layout', async ({ page }) => {
  await page.goto('/')
  const drifted = page.locator('.likec4-diagram[data-instance="2"]')
  await expect(drifted.getByTestId('likec4-drift-banner')).toBeVisible({ timeout: 30_000 })
  await expect(drifted.getByTestId('likec4-drift-banner')).toContainText('nodes-added')
})

test('shows parse diagnostics for a broken project', async ({ page }) => {
  await page.goto('/')
  const broken = page.locator('.likec4-diagram[data-instance="3"]')
  const err = broken.getByTestId('likec4-error')
  await expect(err).toBeVisible({ timeout: 30_000 })
  await expect(err).toContainText('model.likec4')
})
```

- [ ] **Step 3: Create `test/editor.spec.ts`**

```ts
import { expect, test } from '@playwright/test'

test('view-picker populates and drives the live preview without recompute', async ({ page }) => {
  await page.goto('/editor.html')

  const picker = page.getByTestId('likec4-view-picker')
  await expect(picker).toBeVisible({ timeout: 30_000 })
  await expect(picker.locator('option')).toHaveCount(2) // index + sys_detail

  const preview = page.locator('.likec4-editor-preview [data-testid="likec4-diagram"]')
  await expect(preview).toHaveAttribute('data-current-view', 'index', { timeout: 30_000 })

  const computes = await page.evaluate(() => (window as any).__likec4Computes)
  await picker.selectOption('sys_detail')
  await expect(preview).toHaveAttribute('data-current-view', 'sys_detail', { timeout: 10_000 })
  expect(await page.evaluate(() => (window as any).__editorSelected)).toBe('sys_detail')
  // Switching views reuses the loaded model — no extra compute.
  expect(await page.evaluate(() => (window as any).__likec4Computes)).toBe(computes)
})
```

- [ ] **Step 4: Attempt the browser run**

Run:
```bash
cd src/main/frontend && npx playwright install chromium && npm run test:e2e
```
Expected: all specs PASS. If Chromium fails to launch on missing system libraries (`libnss3.so` etc., no sudo), record in the README "e2e CI-gated (Plan 4 container)" and proceed — exactly the spike's GO-pending-CI handling. Likely first-run adjustments, each with a concrete fix:
- `.react-flow__node[data-id="sys"]` not found → run `--headed`, inspect the node's real attribute, update the selector.
- double-click does not navigate → switch to the explicit navigate button (see Step 2 note); confirm via the `onNavigateTo` wiring in `Diagram.tsx`.
- drift banner absent → confirm the drifted fixture's snapshot predates `mon` (Task 2 Step 8) and `compute` returns a non-empty `drifts`.

- [ ] **Step 5: Commit**

```bash
git add src/main/frontend/playwright.config.ts src/main/frontend/test/render.spec.ts src/main/frontend/test/editor.spec.ts
git commit -m "plan2: Playwright e2e — render, in-model nav, fullscreen, drift, errors, editor"
```

---

## Task 13: README, full verification, and integration gate

**Files:**
- Create: `src/main/frontend/README.md`

- [ ] **Step 1: Create `src/main/frontend/README.md`**

````markdown
# LikeC4 Confluence — Browser Bundle (Plan 2)

Production front-end for the LikeC4 Confluence plugin. Computes a laid-out LikeC4 model in a
Web Worker from `.c4`/`.likec4` + `.likec4/*.likec4.snap` fetched via the plugin REST API, caches
the dump in IndexedDB, and renders it interactively (`likec4/react`) with in-place navigation,
drift warnings, fullscreen, and a macro-editor view-picker.

## Layout
- `src/` — bundle source (see file-by-file responsibilities in the plan).
- `test/` — Node unit + compute tests (`*.test.ts`) and Playwright e2e (`*.spec.ts`).
- builds into `../resources/likec4-web/` (the eventual Confluence web-resource; Plan 3 wires it).

## Commands
- `npm test` — Node unit + compute tests (no browser).
- `npm run typecheck` — `tsc --noEmit`.
- `npm run build` — production bundle into `../resources/likec4-web/` (Vite manifest for Plan 3).
- `npm run test:e2e` — Playwright (needs Chromium system libs; CI-gated in the Plan 4 container).
- `npm run make-snapshot` — regenerate the target manual-layout fixture.

## Contracts (for Plan 3 — Java side)
- `GET /rest/likec4/1.0/resolve?project&ref` → `{ sha }`
- `GET /rest/likec4/1.0/source?project&ref&path` → `{ sha, files: { <relPath>: <content> } }`
  (only `*.c4` / `*.likec4` / `.likec4/*.likec4.snap`, relative paths preserved)
- Macro div: `<div class="likec4-diagram" data-project data-ref data-path data-view data-instance>`
- Client dump cache key is `${sha}:${path}` (refines spec §7 for multi-project repos).

## e2e status
<!-- Record here after Task 12 Step 4: PASS locally, or CI-gated (Plan 4 container). -->
````

- [ ] **Step 2: Run the full Node test suite**

Run: `cd src/main/frontend && npm test`
Expected: all suites green — `compute` (4), `restClient` (3), `cache` (3), `dataAttrs` (3), `workerPool` (3), `pipeline` (7), `errors` (4), `listViews` (2).

- [ ] **Step 3: Type-check and build**

Run: `cd src/main/frontend && npm run typecheck && npm run build`
Expected: typecheck clean; build succeeds with the split chunks from Task 10 Step 6.

- [ ] **Step 4: Fill in the README e2e status and commit**

Update the README `## e2e status` line with the Task 12 outcome (PASS locally, or CI-gated).

```bash
git add src/main/frontend/README.md
git commit -m "plan2: frontend README and verified integration gate"
```

---

## Self-Review

**Spec coverage (Plan 2 = spec §4 component 6, the browser bundle):**
- §4 step 1 read data-attrs → Task 5 (`dataAttrs.ts`).
- §4 step 2 `/resolve` → `{sha}` → Tasks 3 (client), 7 (pipeline).
- §4 step 3 IndexedDB `[sha]` hit/miss → Tasks 4 (cache), 7 (pipeline); key refined to `${sha}:${path}` (flagged).
- §4 step 4 Web Worker compute all views laid out → Tasks 2 (compute/worker), 6 (pool).
- §4 step 5 `<LikeC4Diagram>` interactive + in-place navigation → Task 9 (`onNavigateTo` → `setViewId`), e2e Task 12.
- §4a manual layouts applied + drift surfaced → Task 2 (`applyManualLayout` + `calcDriftsFromSnapshot`), Task 9 (banner), e2e Task 12.
- §5 interaction swaps view in loaded model, no recompute → Task 9; e2e asserts `__likec4Computes` unchanged (Task 12).
- §6 macro-editor view dropdown + live preview + (editor-agnostic) mount API → Tasks 8 (`listViews`), 11.
- §7 client dump cache (bounded LRU) → Task 4.
- §9 failure modes (not-found, unreachable, parse `file:line`, unknown-view list, too-large/timeout, JS-off fallback, shared pool for many diagrams) → Tasks 7, 8, 9 (+ boot fallback text), 6 (pool), e2e Task 12.
- §10 lazy worker + code-split renderer; single pinned upstream version → Tasks 6/9 (lazy), 10 Step 6 (split verified), 1 (pins).
- §11 fast-layer front-end units (data-attr, cache-key, worker protocol, error rendering, view-dropdown) + compute integration (small/multi-file/broken/manual-layout) → Tasks 2–8; e2e → Task 12.
- *Deferred (correctly out of scope):* Java macro/REST/GitLab/cache/admin (Plan 3); Docker/UPM/perf/licence (Plan 4).

**Placeholder scan:** No "TBD/handle errors later". The two runtime-confirm steps (Task 12 selectors; Task 2 Step 11 drift reason) carry concrete code + named fallbacks and a known confirmed union — the spike-precedent style the skill accepts for genuinely browser-only facts, not hand-waving.

**Type/name consistency:** `ComputeResult { data, errors, drifts }` (Task 2) flows unchanged through `WorkerResponse` (adds `computeMs`), `WorkerPool.run` → `WorkerResponse`, `pipeline` (`data/errors/drifts`), `cache` (`DumpValue = {data,errors,drifts}`), into `Diagram`/`Viewer`. `DiagramParams` (Task 5) is consumed identically by `pipeline`, `errors`, `Viewer`, `mountEditor`. `LoadResult` (Task 7) is the sole input to `describeFailure` (Task 8, via `LoadFailure = Exclude<…,'ok'>`) and `Viewer`/`mountEditor`. `PipelineDeps` (`rest/cache/pool/timeoutMs/onCompute`) identical in `pipeline`, `boot`, `editor-dev`. `Diagram` is a default export everywhere it is `lazy(() => import('./Diagram'))` (Viewer, ViewPicker). `parseDataAttrs` needs only `getAttribute` (real `HTMLElement` satisfies it). `createRestClient(base, doFetch)` injection matches its test and `boot` default. `WorkerPool(createWorker, maxWorkers)` matches test fakes and `createDiagramWorker`. Cache key `${sha}:${path}` is identical in `pipeline` (`put`/`get`) and the test (`'sha1:sub'`).

**Ordering:** `compute.ts` (Task 2) underpins `worker.ts`, `cache` types, `pipeline`. `Diagram.tsx` (Task 9) is imported lazily by `Viewer` (Task 9) and `ViewPicker` (Task 11). `vite.config.ts` is created minimal in Task 2 (tests) and extended in Task 10 (build/mock) — both edits shown in full. Execute tasks in order.
