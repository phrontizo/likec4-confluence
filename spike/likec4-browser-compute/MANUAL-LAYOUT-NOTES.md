# Manual-layout snapshot application — findings (Task 4)

LikeC4 **1.58.0**, validated on Node v20.19.2 against the *installed* `node_modules`.

## VERDICT: YES — but NOT via `fromSources` directly. The browser must apply snapshots itself with the public `applyManualLayout` from `@likec4/core`.

A `.likec4/<viewId>.likec4.snap` passed inside the `fromSources` sources record is **NOT**
applied automatically, and passing it through verbatim is actively harmful. We instead apply
it explicitly in `src/compute.ts` using `applyManualLayout(autoView, snapshot)`. The node really
moves because of the snapshot (verified by removing the `.snap` → the test fails; and by leaving
the sibling node at its auto position).

## Why `fromSources` ignores the `.snap`

The browser entrypoint `@likec4/language-services/browser` → `fromSources(sources)` calls
`createFromSources(createLanguageServices(), logger, sources, {})` with **no options**.

`createLanguageServices()` (browser, no args) composes the shared module from defaults that are
**`NoFileSystem` + `NoLikeC4ManualLayouts`**:

- `NoopLikeC4ManualLayouts.read()` / `.readSnapshot()` → `Promise.resolve(null)`.
- `NoopFileSystemProvider.scanDirectory()` → `Promise.resolve([])`, `.readFile()` throws.

The real reader, `DefaultLikeC4ManualLayouts.readManualLayouts(project)`
(`@likec4/language-server/.../LikeC4FileSystem.mjs`), works by:
1. `outDir = config.manualLayouts?.outDir ?? '.likec4'`
2. `FileSystemProvider.scanDirectory(outDir, isManualLayoutFile)` where `isManualLayoutFile` =
   ends with `.likec4.snap` (and is not exactly `.likec4.snap`),
3. `JSON5.parse(readFile(uri))` per file, tag `_layout: 'manual'`, key by `view.id`.

But the only `FileSystemProvider` that can actually find/read those files,
`SymLinkTraversingFileSystemProvider` (`WithFileSystem`), is **Node-only** — it imports
`node:fs`, `node:fs/promises`, `fdir`, `chokidar`, `langium/node` and crawls the **real disk**
(`fdir().crawl(uri.fsPath)`). `fromSources` builds a **virtual** `/workspace` Langium document
store; there is nothing on disk to crawl. So even injecting `WithLikeC4ManualLayouts` into the
browser services would not help — there is no browser-safe FS provider that serves the virtual
sources to the manual-layouts reader.

Note: even the **Node** `fromSources` (`@likec4/language-services/node`) defaults to
`useFileSystem: false, manualLayouts: false`. Manual layouts via the library's own reader only
happen through `fromWorkspace(dir)` against a real directory. `fromWorkspace`/`fromWorkdir` both
**throw "not yet implemented"** in the browser build.

Passing the `.snap` straight into `fromSources` is harmful: `.snap` is not a registered LikeC4
file extension, so `createFromSources` appends `.c4` (→ `index.likec4.snap.c4`) and parses it as
LikeC4, producing a parse error
(`Expecting token of type 'EOF' but found '{'`, `sourceFsPath: /workspace/.likec4/index.likec4.snap.c4`)
that pollutes `getErrors()`, while the layout stays auto.

## The mechanism we use (the one Plan 2 should rely on)

`@likec4/core` publicly exports two pure, browser-safe functions:

```ts
applyManualLayout<V extends LayoutedView>(autoLayouted: V, snapshot: ViewManualLayoutSnapshot): V
calcDriftsFromSnapshot<V extends LayoutedView>(autoLayouted: V, snapshot: ViewManualLayoutSnapshot): V
```

So `src/compute(sources)`:
1. Splits source keys: anything ending in `.likec4.snap` is a snapshot, JSON5-parsed and kept aside
   (`viewId` = basename minus the `.likec4.snap` suffix). Everything else is LikeC4 source.
2. Feeds **only** the LikeC4 sources to `fromSources` → `layoutedModel()` → `$data`.
3. For each snapshot, replaces `$data.views[viewId]` with
   `applyManualLayout(autoView, { ...snap, id: viewId, _stage: 'layouted' })`.

`applyManualLayout` asserts (from `@likec4/core` manual-layout chunk):
`autoLayouted.id === snapshot.id`, `autoLayouted._stage === 'layouted'`,
`snapshot._stage === 'layouted'`, and `autoLayouted._layout !== 'manual'`. The result is tagged
`_layout: 'manual'` with node positions taken from the snapshot and style/labels safe-merged from
the fresh auto view.

## File placement / detection

- Snapshot path: `.likec4/<viewId>.likec4.snap` (POSIX key inside the sources record).
  `loadSources` **preserves the nested key** (`.likec4/index.likec4.snap`) — it is *not* flattened
  to a basename. Verified.
- viewId derivation matches the language-server: strip the trailing `.likec4.snap` from the
  basename.

## The real `.snap` schema

It is a **JSON5-serialised `LayoutedView`** (`ViewManualLayoutSnapshot`), tagged `_layout: 'manual'`
— byte-for-byte the same shape as `$data.views.<viewId>` (`_stage: 'layouted'`). Our
`scripts/make-snapshot.ts` produces it by `structuredClone($data.views.index)`, shifting
`nodes[0].x += 500`, setting `_layout = 'manual'`, and `JSON5.stringify(..., null, 2)`. Top-level
keys: `_type, tags, links, _stage, sourcePath, description, title, id, autoLayout, hash, bounds,
nodes, edges, _layout`. Per-node keys include `id, parent, level, children, inEdges, outEdges,
title, modelRef, shape, color, style, tags, kind, x, y, width, height, labelBBox`. This matches
what `DefaultLikeC4ManualLayouts.readManualLayouts` reads back (it `JSON5.parse`s the file and
spreads `{ ..., _layout: 'manual' }`, then keys by `prop('id')`).

## Reconciliation / drift behaviour (observed)

`applyManualLayout` reconciles a snapshot against the *live* auto-layouted view rather than blindly
trusting it:
- Matching nodes: take `x/y/width/height/labelBBox` from the snapshot.
- A snapshot node whose model element no longer exists: kept with `drifts: ['removed']`, and the
  view gets `drifts: ['nodes-removed']`. Live nodes are still positioned from their auto layout;
  it does not throw. (Verified by injecting a phantom `ghost` node into a snapshot.)
- New/changed model elements similarly surface as view/node/edge `drifts` (`nodes-added`,
  `*-changed`, etc.). When there are no drifts, the view keeps the snapshot `hash` and `drifts` is
  removed.
- `calcDriftsFromSnapshot` is the read-only variant that returns the auto view annotated with
  drifts (and downgrades to `_layout: 'auto'` if nothing meaningfully matches) — useful if Plan 2
  wants to *detect* staleness without applying.

So curated diagrams render with curated positions, and the UI can detect/flag drift. Reconciliation
is graceful (no crash) when the model has changed since the snapshot was taken.

## What this means for Plan 2

- The browser compute worker must own snapshot application: strip `.likec4.snap` from the fetched
  source set, compute auto layout via `fromSources`, then `applyManualLayout` per view. This is
  exactly what `src/compute.ts` now does and is fully browser-safe (no `node:*` deps).
- Do **not** expect `fromSources` to honour `.snap` files, and do **not** feed them in raw.
