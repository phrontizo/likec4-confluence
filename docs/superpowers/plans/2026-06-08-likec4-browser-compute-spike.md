# LikeC4 Browser-Compute Spike — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove — or disprove — that a browser bundle can compute a fully laid-out LikeC4 model from raw `.c4`/`.likec4` source **plus `.likec4/*.likec4.snap` manual-layout snapshots**, off the main thread, using **upstream LikeC4 1.58.0** (no Monaco), and render it interactively via `likec4/react` — producing a go/no-go decision plus measured bundle size and compute time.

**Architecture:** A throwaway Vite + TypeScript spike under `spike/likec4-browser-compute/`. **Phase A** (fast, Node + vitest) validates the `@likec4/language-services` `fromSources → layoutedModel` semantics and — critically — whether manual-layout `.snap` files supplied in the sources record are honoured. **Phase B** (browser + Playwright) runs the same compute inside a Web Worker, posts the serialized `$data` to the main thread, renders with `likec4/react`, and asserts interactivity + manual-layout fidelity while recording bundle size and compute time.

**Tech Stack:** TypeScript, Vite 5, vitest, `@playwright/test`, React 18, JSON5, and pinned upstream LikeC4 **1.58.0** (`@likec4/language-services`, `@likec4/core`, `likec4` [for `likec4/react`], `elkjs`).

**This is a SPIKE.** The code is throwaway; the deliverable is `FINDINGS.md` (a go/no-go plus measurements) and de-risked knowledge of the exact APIs. Where an API prop shape can only be confirmed at runtime, the task says so explicitly and gives the best-known code from the playground — that is intentional, not a placeholder.

---

## File Structure

- Create: `spike/likec4-browser-compute/package.json` — pins LikeC4 1.58.0 + tooling; scripts.
- Create: `spike/likec4-browser-compute/tsconfig.json` — TS config (bundler resolution, DOM + WebWorker libs).
- Create: `spike/likec4-browser-compute/vite.config.ts` — Vite build for the page + worker; vitest config.
- Create: `spike/likec4-browser-compute/fixtures/target/spec.likec4` — element kinds.
- Create: `spike/likec4-browser-compute/fixtures/target/model.likec4` — elements + relationship.
- Create: `spike/likec4-browser-compute/fixtures/target/views.likec4` — the `index` view.
- Create (generated): `spike/likec4-browser-compute/fixtures/target/.likec4/index.likec4.snap` — manual-layout snapshot (produced by the tool below).
- Create: `spike/likec4-browser-compute/fixtures/broken/model.likec4` — deliberately invalid, for the errors test.
- Create: `spike/likec4-browser-compute/src/sources.ts` — load a fixture dir into a `Record<path, content>`.
- Create: `spike/likec4-browser-compute/src/compute.ts` — wraps `fromSources` + `layoutedModel` + `getErrors`.
- Create: `spike/likec4-browser-compute/scripts/make-snapshot.ts` — generate the `.snap` from a computed view.
- Create: `spike/likec4-browser-compute/src/worker.ts` — Web Worker entry: compute → post `$data` + timing.
- Create: `spike/likec4-browser-compute/src/main.tsx` — main thread: spawn worker, `LikeC4Model.create`, render.
- Create: `spike/likec4-browser-compute/index.html` — host page for Phase B.
- Create: `spike/likec4-browser-compute/test/compute.node.test.ts` — Phase A vitest.
- Create: `spike/likec4-browser-compute/test/render.spec.ts` — Phase B Playwright.
- Create: `spike/likec4-browser-compute/playwright.config.ts` — Playwright config (serves `vite preview`).
- Create: `spike/likec4-browser-compute/FINDINGS.md` — the go/no-go deliverable.

---

## Task 1: Scaffold the spike project pinned to upstream 1.58.0

**Files:**
- Create: `spike/likec4-browser-compute/package.json`
- Create: `spike/likec4-browser-compute/tsconfig.json`
- Create: `spike/likec4-browser-compute/vite.config.ts`

- [ ] **Step 1: Create `package.json`**

```json
{
  "name": "likec4-browser-compute-spike",
  "private": true,
  "type": "module",
  "scripts": {
    "test:node": "vitest run",
    "make-snapshot": "tsx scripts/make-snapshot.ts",
    "build": "vite build",
    "preview": "vite preview --port 4317 --strictPort",
    "test:browser": "playwright test"
  },
  "dependencies": {
    "@likec4/core": "1.58.0",
    "@likec4/language-services": "1.58.0",
    "likec4": "1.58.0",
    "elkjs": "0.9.3",
    "json5": "2.2.3",
    "react": "18.3.1",
    "react-dom": "18.3.1"
  },
  "devDependencies": {
    "@playwright/test": "1.48.0",
    "@types/react": "18.3.12",
    "@types/react-dom": "18.3.1",
    "@vitejs/plugin-react": "4.3.3",
    "tsx": "4.19.2",
    "typescript": "5.6.3",
    "vite": "5.4.10",
    "vitest": "2.1.4"
  }
}
```

- [ ] **Step 2: Create `tsconfig.json`**

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
  "include": ["src", "test", "scripts", "vite.config.ts"]
}
```

- [ ] **Step 3: Create `vite.config.ts`**

```ts
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  worker: { format: 'es' },
  test: {
    // Phase A runs in Node; the language-services browser entry uses an in-memory FS.
    environment: 'node',
    include: ['test/**/*.node.test.ts'],
  },
})
```

- [ ] **Step 4: Install and verify the pinned versions**

Run:
```bash
cd spike/likec4-browser-compute && npm install && npm ls likec4 @likec4/language-services @likec4/core
```
Expected: all three resolve to `1.58.0` with no `UNMET`/`invalid` markers. If any resolves to a different version, fix the pin before continuing — version drift between the engine and `likec4/react` is a known failure mode (spec §10).

- [ ] **Step 5: Commit**

```bash
git add spike/likec4-browser-compute/package.json spike/likec4-browser-compute/tsconfig.json spike/likec4-browser-compute/vite.config.ts spike/likec4-browser-compute/package-lock.json
git commit -m "spike: scaffold likec4 browser-compute spike pinned to 1.58.0"
```

---

## Task 2: Fixture project + `fromSources` smoke test (Node)

**Files:**
- Create: `spike/likec4-browser-compute/fixtures/target/spec.likec4`
- Create: `spike/likec4-browser-compute/fixtures/target/model.likec4`
- Create: `spike/likec4-browser-compute/fixtures/target/views.likec4`
- Create: `spike/likec4-browser-compute/src/sources.ts`
- Create: `spike/likec4-browser-compute/src/compute.ts`
- Test: `spike/likec4-browser-compute/test/compute.node.test.ts`

- [ ] **Step 1: Create the fixture — `fixtures/target/spec.likec4`**

```
specification {
  element system
  element container
}
```

- [ ] **Step 2: Create `fixtures/target/model.likec4`**

```
model {
  system sys {
    title 'My System'
    container web 'Web App'
    container db 'Database'
    web -> db 'reads/writes'
  }
}
```

- [ ] **Step 3: Create `fixtures/target/views.likec4`**

```
views {
  view index {
    title 'Landscape'
    include *
  }
}
```

- [ ] **Step 4: Create `src/sources.ts` (load a fixture dir into a path→content record)**

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

- [ ] **Step 5: Create `src/compute.ts` (the API under test)**

```ts
import { fromSources } from '@likec4/language-services/browser'

export interface ComputeResult {
  /** Serializable LayoutedLikeC4ModelData — safe to postMessage and feed to LikeC4Model.create. */
  data: unknown
  errors: Array<{ message: string; line: number; sourceFsPath: string }>
}

/** Compute a fully laid-out model from a path→content record. */
export async function compute(sources: Record<string, string>): Promise<ComputeResult> {
  const likec4 = await fromSources(sources)
  const errors = likec4.getErrors().map(e => ({
    message: e.message,
    line: e.line,
    sourceFsPath: e.sourceFsPath,
  }))
  const model = await likec4.layoutedModel()
  return { data: model.$data, errors }
}
```

- [ ] **Step 6: Write the failing test — `test/compute.node.test.ts`**

```ts
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { compute } from '../src/compute'
import { loadSources } from '../src/sources'

const targetDir = fileURLToPath(new URL('../fixtures/target', import.meta.url))

describe('fromSources → layoutedModel', () => {
  it('computes a layouted model with the index view', async () => {
    const { data, errors } = await compute(loadSources(targetDir))
    expect(errors).toEqual([])
    const d = data as any
    expect(d._stage).toBe('layouted')
    expect(Object.keys(d.views)).toContain('index')
    const indexView = d.views.index
    // A laid-out view has positioned nodes.
    expect(indexView.nodes.length).toBeGreaterThan(0)
    expect(typeof indexView.nodes[0].x).toBe('number')
    expect(typeof indexView.nodes[0].y).toBe('number')
  })
})
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd spike/likec4-browser-compute && npm run test:node -- test/compute.node.test.ts`
Expected: PASS. If `fromSources` throws or `views.index` is absent, the upstream browser API differs from the documented shape — record this as a **blocking finding** in `FINDINGS.md` and stop; the architecture assumption has failed. If `d.views.index` exists but the node field names differ (e.g. `position` instead of `x`/`y`), inspect `JSON.stringify(d.views.index.nodes[0])`, update the assertion to the real field, and note the real shape in `FINDINGS.md`.

- [ ] **Step 8: Commit**

```bash
git add spike/likec4-browser-compute/fixtures/target spike/likec4-browser-compute/src/sources.ts spike/likec4-browser-compute/src/compute.ts spike/likec4-browser-compute/test/compute.node.test.ts
git commit -m "spike: validate fromSources computes a layouted model (node)"
```

---

## Task 3: Validate the errors API (Node)

**Files:**
- Create: `spike/likec4-browser-compute/fixtures/broken/model.likec4`
- Test: `spike/likec4-browser-compute/test/compute.node.test.ts` (add a case)

- [ ] **Step 1: Create an invalid fixture — `fixtures/broken/model.likec4`**

```
model {
  system sys {
    container web 'Web App'
    web -> nonexistent 'broken relation'
  }
}
```

(There is no `specification` and the relation targets an undeclared element — both should produce diagnostics.)

- [ ] **Step 2: Write the failing test (append to `test/compute.node.test.ts`)**

```ts
import { readFileSync } from 'node:fs'

it('surfaces parse/validation errors with line numbers', async () => {
  const brokenDir = fileURLToPath(new URL('../fixtures/broken', import.meta.url))
  const { errors } = await compute(loadSources(brokenDir))
  expect(errors.length).toBeGreaterThan(0)
  expect(errors[0]).toMatchObject({
    message: expect.any(String),
    line: expect.any(Number),
  })
  expect(errors[0].sourceFsPath).toContain('model.likec4')
})
```

- [ ] **Step 3: Run the test**

Run: `cd spike/likec4-browser-compute && npm run test:node`
Expected: PASS — both the happy-path and the errors case. If `getErrors()` returns `[]` for clearly-broken input, record in `FINDINGS.md` that error reporting needs a different accessor (check the `LikeC4` class d.ts for `hasErrors()`/diagnostics) and adjust.

- [ ] **Step 4: Commit**

```bash
git add spike/likec4-browser-compute/fixtures/broken spike/likec4-browser-compute/test/compute.node.test.ts
git commit -m "spike: validate error reporting from fromSources"
```

---

## Task 4: Manual-layout snapshot — generation + application (THE gating risk)

**Files:**
- Create: `spike/likec4-browser-compute/scripts/make-snapshot.ts`
- Create (generated): `spike/likec4-browser-compute/fixtures/target/.likec4/index.likec4.snap`
- Test: `spike/likec4-browser-compute/test/compute.node.test.ts` (add a case)

This is the highest-risk validation (spec §4a, §10): does a `.likec4/<viewId>.likec4.snap` supplied in the sources record actually override layout?

- [ ] **Step 1: Create `scripts/make-snapshot.ts` (generate a perturbed snapshot from the computed view)**

```ts
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import JSON5 from 'json5'
import { compute } from '../src/compute'
import { loadSources } from '../src/sources'

const targetDir = fileURLToPath(new URL('../fixtures/target', import.meta.url))
const SHIFT = 500

const { data } = await compute(loadSources(targetDir))
const view = structuredClone((data as any).views.index)
// Move the first node by a large, recognisable amount and mark the view manual.
view.nodes[0].x += SHIFT
view._layout = 'manual'

mkdirSync(`${targetDir}/.likec4`, { recursive: true })
writeFileSync(`${targetDir}/.likec4/index.likec4.snap`, JSON5.stringify(view, null, 2), 'utf8')
console.log(`Wrote snapshot; node[0] '${view.nodes[0].id}' x shifted by ${SHIFT}`)
```

- [ ] **Step 2: Generate the snapshot**

Run: `cd spike/likec4-browser-compute && npm run make-snapshot`
Expected: prints the shifted node id and writes `fixtures/target/.likec4/index.likec4.snap`.

- [ ] **Step 3: Write the failing test (append to `test/compute.node.test.ts`)**

```ts
it('applies a manual-layout snapshot supplied via sources', async () => {
  const targetDir = fileURLToPath(new URL('../fixtures/target', import.meta.url))
  const sources = loadSources(targetDir)
  // Sanity: the snapshot file must be in the sources record under the .likec4/ dir.
  expect(Object.keys(sources)).toContain('.likec4/index.likec4.snap')

  const { data } = await compute(sources)
  const d = data as any
  // Either the model exposes manualLayouts, or the view is tagged manual — assert both signals.
  const indexView = d.views.index
  expect(indexView._layout).toBe('manual')
  // The perturbed node must be at the shifted coordinate, proving the snapshot won over auto-layout.
  const shifted = indexView.nodes.find((n: any) => n.x >= 500)
  expect(shifted, 'expected a node at the manually-shifted x position').toBeTruthy()
})
```

- [ ] **Step 4: Run the test**

Run: `cd spike/likec4-browser-compute && npm run test:node`
Expected: PASS.

**If it FAILS** (snapshot ignored), this is the spike's central question — investigate before concluding, in this order, recording the outcome in `FINDINGS.md`:
1. Inspect whether non-`.c4`/`.likec4` keys reach the workspace FS: the manual-layouts reader scans the project's `.likec4/` dir via the FileSystemProvider, which may be populated differently from Langium documents.
2. Check `@likec4/language-services` for an init option that enables/points manual layouts — the browser `fromSources` calls `createFromSources(langium, logger, sources, {})`; read the `InitOptions` type for a `manualLayouts` flag/dir and pass it.
3. Check whether the snapshot must be produced by the library's own writer (`LikeC4ManualLayouts.write`) rather than hand-serialized `$data.views.index`, in case the on-disk schema differs from the in-memory view; if so, regenerate via that API.

Record the working mechanism (or "manual layouts are NOT applyable in standalone browser compute") as a **must-carry requirement** for Plan 2.

- [ ] **Step 5: Commit**

```bash
git add spike/likec4-browser-compute/scripts/make-snapshot.ts "spike/likec4-browser-compute/fixtures/target/.likec4/index.likec4.snap" spike/likec4-browser-compute/test/compute.node.test.ts
git commit -m "spike: validate manual-layout snapshot application"
```

---

## Task 5: Compute inside a Web Worker (browser build)

**Files:**
- Create: `spike/likec4-browser-compute/src/worker.ts`
- Create: `spike/likec4-browser-compute/src/main.tsx`
- Create: `spike/likec4-browser-compute/index.html`

- [ ] **Step 1: Create `src/worker.ts` (off-main-thread compute)**

```ts
import { compute } from './compute'

export interface WorkerRequest { sources: Record<string, string> }
export interface WorkerResponse {
  data: unknown
  errors: Array<{ message: string; line: number; sourceFsPath: string }>
  computeMs: number
}

self.onmessage = async (e: MessageEvent<WorkerRequest>) => {
  const start = performance.now()
  try {
    const { data, errors } = await compute(e.data.sources)
    const res: WorkerResponse = { data, errors, computeMs: performance.now() - start }
    ;(self as unknown as Worker).postMessage(res)
  } catch (err) {
    ;(self as unknown as Worker).postMessage({
      data: null,
      errors: [{ message: String(err), line: 0, sourceFsPath: '' }],
      computeMs: performance.now() - start,
    } satisfies WorkerResponse)
  }
}
```

- [ ] **Step 2: Create `index.html`**

```html
<!doctype html>
<html>
  <head><meta charset="utf-8" /><title>LikeC4 spike</title></head>
  <body>
    <div id="root" style="width: 100vw; height: 100vh;"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 3: Create `src/main.tsx` (spawn worker, embed fixture sources, expose timing)**

The fixture sources are embedded via Vite's `?raw` imports so the page is self-contained (no server fetch needed for the spike).

```tsx
import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import type { WorkerResponse } from './worker'
import spec from '../fixtures/target/spec.likec4?raw'
import model from '../fixtures/target/model.likec4?raw'
import views from '../fixtures/target/views.likec4?raw'
import snap from '../fixtures/target/.likec4/index.likec4.snap?raw'
import { Diagram } from './diagram'

const sources: Record<string, string> = {
  'spec.likec4': spec,
  'model.likec4': model,
  'views.likec4': views,
  '.likec4/index.likec4.snap': snap,
}

function App() {
  const [resp, setResp] = useState<WorkerResponse | null>(null)
  useEffect(() => {
    const worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (e: MessageEvent<WorkerResponse>) => {
      ;(window as any).__spike = e.data // exposed for Playwright assertions
      setResp(e.data)
    }
    worker.postMessage({ sources })
    return () => worker.terminate()
  }, [])

  if (!resp) return <div data-testid="status">computing…</div>
  if (!resp.data) return <div data-testid="error">{resp.errors.map(e => e.message).join('; ')}</div>
  return <Diagram data={resp.data} viewId="index" />
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
```

- [ ] **Step 4: Type-check the build compiles**

Run: `cd spike/likec4-browser-compute && npx tsc --noEmit`
Expected: PASS (note: `src/diagram.tsx` is created in Task 6; this step will report it missing — that is expected and is resolved by Task 6. Run this check at the end of Task 6 instead if executing strictly in order).

- [ ] **Step 5: Commit**

```bash
git add spike/likec4-browser-compute/src/worker.ts spike/likec4-browser-compute/src/main.tsx spike/likec4-browser-compute/index.html
git commit -m "spike: web worker compute + page scaffold"
```

---

## Task 6: Render with `likec4/react` + Playwright e2e (browser)

**Files:**
- Create: `spike/likec4-browser-compute/src/diagram.tsx`
- Create: `spike/likec4-browser-compute/playwright.config.ts`
- Test: `spike/likec4-browser-compute/test/render.spec.ts`

- [ ] **Step 1: Create `src/diagram.tsx` (render the computed model)**

Based on the playground pattern: reconstruct the model with `LikeC4Model.create`, find the view, and pass it to `<LikeC4Diagram>`.

```tsx
import { LikeC4Model } from '@likec4/core/model'
import { LikeC4Diagram, LikeC4ModelProvider, useLikeC4Model } from 'likec4/react'
import { useMemo } from 'react'

export function Diagram({ data, viewId }: { data: unknown; viewId: string }) {
  const model = useMemo(() => LikeC4Model.create(data as any), [data])
  return (
    <LikeC4ModelProvider likec4model={model}>
      <ViewRenderer viewId={viewId} />
    </LikeC4ModelProvider>
  )
}

function ViewRenderer({ viewId }: { viewId: string }) {
  const view = useLikeC4Model().findView(viewId)
  if (!view) return <div data-testid="error">view not found: {viewId}</div>
  // Playground passes a DiagramView; confirm the prop name/shape from the bundled
  // `likec4/react` d.ts (`node_modules/likec4/react/dist/index.d.mts`). It is `view={view.$view}`
  // for a layouted view; if the type rejects it, log Object.keys(view) and use the matching accessor.
  return (
    <div data-testid="diagram" style={{ width: '100%', height: '100%' }}>
      <LikeC4Diagram view={(view as any).$view} zoomable pannable />
    </div>
  )
}
```

- [ ] **Step 2: Create `playwright.config.ts`**

```ts
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './test',
  testMatch: '**/*.spec.ts',
  webServer: {
    command: 'npm run build && npm run preview',
    url: 'http://localhost:4317',
    reuseExistingServer: false,
    timeout: 120_000,
  },
  use: { baseURL: 'http://localhost:4317' },
})
```

- [ ] **Step 3: Write the failing test — `test/render.spec.ts`**

```ts
import { expect, test } from '@playwright/test'

test('renders the index view interactively with manual layout applied', async ({ page }) => {
  await page.goto('/')

  // Diagram mounts after the worker returns.
  const diagram = page.getByTestId('diagram')
  await expect(diagram).toBeVisible({ timeout: 30_000 })

  // The renderer draws nodes (xyflow/react-flow renders .react-flow__node elements).
  const nodes = page.locator('.react-flow__node')
  await expect(nodes.first()).toBeVisible({ timeout: 30_000 })
  expect(await nodes.count()).toBeGreaterThan(0)

  // Pull the worker result the page stashed on window.
  const result = await page.evaluate(() => (window as any).__spike)
  expect(result.errors).toEqual([])
  expect(result.computeMs).toBeGreaterThan(0)
  // Manual layout survived the compute → render round trip.
  expect(result.data.views.index._layout).toBe('manual')

  // Interactivity smoke: a wheel zoom changes the react-flow viewport transform.
  const before = await page.locator('.react-flow__viewport').getAttribute('style')
  await diagram.hover()
  await page.mouse.wheel(0, -400)
  await page.waitForTimeout(300)
  const after = await page.locator('.react-flow__viewport').getAttribute('style')
  expect(after).not.toBe(before)
})
```

- [ ] **Step 4: Install the Playwright browser, then run**

Run:
```bash
cd spike/likec4-browser-compute && npx playwright install chromium && npm run test:browser
```
Expected: PASS. Likely first-run adjustments and how to resolve each:
- `.react-flow__node` selector wrong → open `npx playwright test --headed`, inspect the real DOM class the renderer uses, update the selector, and record it in `FINDINGS.md`.
- `<LikeC4Diagram view={...}>` prop rejected/blank → confirm the prop from the d.ts (Step 1 note), fix, re-run.
- Worker fails to load under `vite preview` (ESM worker) → confirm `worker.format: 'es'` is set (Task 1) and the browser is Chromium; record any module-worker constraint as a Plan 2 packaging note (Confluence web-resources must serve the worker same-origin).

- [ ] **Step 5: Commit**

```bash
git add spike/likec4-browser-compute/src/diagram.tsx spike/likec4-browser-compute/playwright.config.ts spike/likec4-browser-compute/test/render.spec.ts
git commit -m "spike: render computed model with likec4/react + playwright e2e"
```

---

## Task 7: Measure, decide, and write FINDINGS.md

**Files:**
- Create: `spike/likec4-browser-compute/FINDINGS.md`

- [ ] **Step 1: Capture the production bundle size**

Run: `cd spike/likec4-browser-compute && npm run build`
Record the emitted chunk sizes (Vite prints them), especially the worker chunk (the language-services + elk weight) and the main chunk (React + `likec4/react`). Note gzipped sizes.

- [ ] **Step 2: Capture compute time**

From the last Playwright run, read `result.computeMs` (the test logs it via `window.__spike`). Record it for the small fixture. Optionally add a larger fixture (duplicate the system several times) and re-measure to get a size→time sense, since spec §model-size is "very mixed".

- [ ] **Step 3: Write `FINDINGS.md`**

```markdown
# Spike Findings — LikeC4 Browser Compute (upstream 1.58.0)

## Verdict: GO / NO-GO  <!-- pick one -->

## What was proven
- [ ] `fromSources` computes a layouted model in the browser (no Monaco)
- [ ] Errors are reported with line numbers
- [ ] Manual-layout `.snap` supplied via sources IS applied  (mechanism: …)
- [ ] Off-main-thread compute via Web Worker works
- [ ] `likec4/react` renders the model interactively (pan/zoom verified)

## Measurements
- Worker chunk: __ KB (gz __ KB)
- Main/render chunk: __ KB (gz __ KB)
- Compute time (small fixture): __ ms
- Compute time (large fixture, ~N elements): __ ms

## Exact APIs confirmed (carry into Plan 2)
- Compute: `fromSources(record)` → `await likec4.layoutedModel()` → `.$data`
- View node position fields: `x`/`y` (or: …)
- `<LikeC4Diagram>` view prop: `view={viewModel.$view}` (or: …)
- react-flow node selector: `.react-flow__node` (or: …)
- Manual-layout mechanism: …

## Risks / surprises for Plan 2
- …
```

Fill every checkbox and blank from the actual runs. The **Verdict** gates Plan 2: if manual layouts cannot be applied, or the worker bundle is unacceptably large, or compute time is untenable for large models, escalate to the user with the data before any production code is written.

- [ ] **Step 4: Commit**

```bash
git add spike/likec4-browser-compute/FINDINGS.md
git commit -m "spike: record findings and go/no-go verdict"
```

---

## Self-Review

**Spec coverage (this plan only covers the §10 spike, by design):**
- §10 browser-worker compute-from-source standalone → Tasks 2, 5.
- §10 + §4a manual-layout `.snap` application → Task 4 (Node) + Task 6 (render fidelity).
- §10 render via `likec4/react` → Task 6.
- §9 error reporting (parse/validation diagnostics) → Task 3.
- §model-size compute-time concern → Task 7 measurement.
- §2/§10 version pinning (engine + renderer same upstream version) → Task 1.
- Bundle-size input to the §model-size / client-weight risk → Task 7.
- *Deferred to later plans (correctly out of scope here):* GitLab fetch/filtering, caching tiers, REST/macro/admin (Plan 3), IndexedDB/worker-pool/view-dropdown production bundle (Plan 2), Docker test harness (Plan 4).

**Placeholder scan:** No "TBD/TODO/handle errors later". The runtime-confirmation notes in Tasks 4/6 are explicit investigative steps with real assertion code and named fallbacks — appropriate for a spike, not hand-waving.

**Type/name consistency:** `compute()` returns `{ data, errors }` and is used identically in Tasks 2, 3, 4, and `worker.ts`. `WorkerResponse` (`data`/`errors`/`computeMs`) is defined in `worker.ts` and consumed unchanged in `main.tsx` and `render.spec.ts`. `loadSources` signature is stable across tasks. Fixture paths (`fixtures/target/...`, `.likec4/index.likec4.snap`) match between `make-snapshot.ts`, `main.tsx`, and the tests.

**Ordering note:** `src/diagram.tsx` (Task 6) is imported by `src/main.tsx` (Task 5). Tasks must be executed in order; the Task 5 type-check is deferred to the end of Task 6 (called out in Task 5 Step 4).
