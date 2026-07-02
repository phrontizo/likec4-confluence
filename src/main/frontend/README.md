# LikeC4 Confluence — Browser Bundle (Plan 2)

Production front-end for the LikeC4 Confluence plugin. Computes a laid-out LikeC4 model in a
Web Worker from `.c4`/`.likec4` + `.likec4/*.likec4.snap` fetched via the plugin REST API, caches
the dump in IndexedDB, and renders it interactively (`likec4/react`) with in-place navigation,
drift warnings, fullscreen, and a macro-editor view-picker.

## Layout
- `src/` — bundle source (see file-by-file responsibilities in the plan).
- `test/` — Node unit + compute tests (`*.test.ts`) and Playwright e2e (`*.spec.ts`).
- builds into `../resources/likec4-web/` — the Confluence web-resource, wired up in
  `src/main/resources/atlassian-plugin.xml` (boot-loader shim + `main`/`editor-confluence` entries).

## Commands
- `npm test` — Node unit + compute tests (no browser).
- `npm run typecheck` — `tsc --noEmit`.
- `npm run build` — production bundle into `../resources/likec4-web/` (Vite manifest for Plan 3).
- `npm run test:e2e` — Playwright (needs Chromium system libs; run from the Plan 4 container — proven
  live, see "e2e status" below).
- `npm run make-snapshot` — regenerate the target manual-layout fixture.

## Contracts (for Plan 3 — Java side)
- `GET /rest/likec4/1.0/resolve?project&ref` → `{ sha }`
- `GET /rest/likec4/1.0/source?project&ref&path` → `{ sha, files: { <relPath>: <content> } }`
  (only `*.c4` / `*.likec4` / `.likec4/*.likec4.snap`, relative paths preserved)
- Macro div: `<div class="likec4-diagram" data-project data-ref data-path data-view data-instance data-height>`
- Client dump cache key is `${sha}:${path}` (refines spec §7 for multi-project repos).

## e2e status
The Node unit + compute tests (**244 vitest**) and the production build are the local green gates
(`npm test`, `npm run typecheck`, `npm run build`). The Playwright browser e2e is committed and
**proven live** against Confluence Data Center 10.2.13 (run from the Playwright container because this dev
host lacks Chromium system libraries — `libnss3` etc.; it is *not* unproven). Verified live:
- the diagram **renders** (`docker/e2e/c10-gates.spec.ts` GATE3 → `.react-flow__node`);
- the **macro inserts via the native macro browser** and renders (`docker/e2e/macro-native.spec.ts`,
  `3444d62` — fixed real `insertMacro` envelope + icon bugs);
- the **macro-editor "Load views" picker** populates/previews/writes back (`editor-loadviews.spec.ts`);
- the **admin config page saves in-browser** in native atl.admin chrome (`admin-form.spec.ts`,
  `admin-chrome.spec.ts`, `3444d62` — fixed a real context-path bug);
- **notes-XSS sanitised with zero CSP violations** (`xss-notes.spec.ts`, `dab22c5`).

See `docker/README.md` for the full live-proof log.

## Plan 3 handoff notes — done
All Plan 3 wiring handoffs are now implemented; kept here as the as-built record.
- **Serve the whole `assets/` dir as the web-resource — done.** The web-resource ships a classic
  `boot-loader.js` shim plus stable `assets/main.js` / `editor-confluence.js` entries **and the
  whole directory of hashed chunks**, so the entry's relative imports and the module-worker URL
  (`assets/worker-*.js`, ~2.7 MB, referenced by hash and intentionally absent from `manifest.json`)
  resolve at runtime.
- **Viewer auto-boots:** `boot.tsx` calls `boot()` on load, so the macro just emits
  `<div class="likec4-diagram" data-…>` and loads the `main` entry. No further JS wiring.
- **Editor entry parametrised — done.** `src/editor-confluence.tsx` is the real macro-editor entry
  (no hardcoded project); it is wired into Confluence via `src/editor/macroEditor.ts` +
  `public/editor-loader.js`, which override the macro browser to mount `mountViewPicker(container,
  {params, deps, onSelect})`, feed the live macro params, and write the chosen view back into the
  `data-view` param. (`editor-dev.tsx` remains the standalone e2e harness.)
- **Cache LRU stamp — fixed.** The IndexedDB `accessedAt` stamp is now a persisted monotonic
  counter seeded from the stored max on open, so a freshly-computed entry is no longer evicted
  before older persisted entries across reloads when the 50-entry bound is hit. Covered by
  `test/cache.test.ts`.
