# Confluence LikeC4 Diagram Plugin — Design

- **Date:** 2026-06-05
- **Status:** Implemented — v1 (§1–§12) built and proven live in Confluence 9.2.20, **including end-to-end on PostgreSQL** (see below). _(9.2.20 is the historical build target; the product was retargeted to Confluence 10.2.13 / Jakarta EE / Java 21 — see the retarget note below.)_
- **Target:** Confluence **Data Center 10.2.13** (Jakarta EE, Java 21; P2 / Atlassian SDK Java app, installed via UPM).

> **Retarget note (post-2026-06-29):** v1 was originally built and proven live on
> Confluence **9.2.20** (`javax.*`, Java 17) — the 9.2.20 narrative throughout this
> spec is that *historical* proof. The product was then retargeted to Confluence
> **10.2.13 / Jakarta EE / Java 21** (the sole supported target). For the current
> build/test/target facts see **README.md** and **CLAUDE.md**; this spec is kept as
> the original design record.

## Implementation status (as of 2026-06-29)

**All of v1 (§1–§12) is implemented and proven live in Confluence 9.2.20.** The whole pipeline runs
end-to-end — macro → web-resource → ESM bundle → `/resolve`+`/source` (plugin → GitLab) → Web Worker
compute → `likec4/react` render — verified live on a real Confluence 9.2.20 instance (amps
`confluence:run`, H2) + a mock-GitLab, with Playwright driving real browser gestures, **and now also
on the production `atlassian/confluence:9.2.20` + PostgreSQL stack** (see the PostgreSQL note below).

- **Tests green:** **82** likec4-core JUnit (`~/.local/bin/jmvn -B -o test`), **49** frontend vitest
  (`npm test`), `npm run typecheck` clean, and the full `~/.local/bin/jmvn -B package` (wrapper +
  bundled frontend). _(These are the 9.2.20-era counts captured in this historical snapshot; the current
  10.2.13 / Jakarta target runs **252** core / **241** frontend / **123** wrapper — see the retarget
  note above and README.md / CLAUDE.md for the authoritative current figures.)_ The core suite includes
  a **real gitlab.com contract test** that replays
  actually-recorded gitlab.com API bytes and asserts the source filter drops every non-LikeC4 file
  (`639cf9e`).
- **Operator flows verified live** (`3444d62`, Playwright): the **C1 admin config page** form
  **saves in-browser** (fixed a real `atl.admin`-decorator context-path bug); the **C2 macro**
  **inserts via the native macro browser + "Load views" picker and renders** (fixed real `insertMacro`
  `{macro,contentId}` envelope + broken-icon bugs). The native-UI macro-insertion gesture is itself
  browser-driven — no longer a by-construction claim.
- **Install / upgrade RUN LIVE via UPM** (`83c1022`): uninstall → fresh-install v0.1.0 (renders) →
  upgrade v0.1.1 (admin config persists, still renders). `docker/install-upgrade.sh` exits PASS.
- **Render + REST proven live (Playwright against Confluence 9.2.20):** the diagram **renders**
  (`.react-flow__node`); the **admin config page** sits in native **atl.admin** chrome and is
  admin-gated; the **macro-editor view-picker** (§6) populates, previews, and writes back the view;
  the **k6 performance test** (§11, 100 VUs/60s: 0% fail, p95 309 ms, archive fetches 0 ≪ 10,967
  `/source` calls) shows server-side caching + single-flight hold and **zero server-side git on
  macro render**; the **notes-XSS + CSP** check (§8) passes (payloads stripped, zero CSP violations).
- **Token key-at-rest hardened** (`4fe202e`): the AES key now lives on the Confluence-home filesystem
  (0600), off PluginSettings; the legacy-key migration was exercised live; threat model documented.
- **Deferred / optional (not v1):** §13 "Later (optional)" **server-side computed-dump cache**
  remains explicitly out of scope per the spec.

**PostgreSQL — now VERIFIED (2026-06-29), and it caught a real production-only bug.** The full stack
(official `atlassian/confluence:9.2.20` + **Postgres**, direct-JDBC + mock-GitLab, `docker/compose.yaml`)
was set up end-to-end with a working SDK Developer timebomb licence: `up.sh`'s wizard-walk drove
licence → deployment → content → user-management → administrator to `RUNNING`; `confluence.cfg.xml`
shows `jdbc:postgresql://postgres:5432/confluence` and `psql \dt` lists 226 Confluence tables; the
plugin **installed via UPM** (enabled), was **configured** (`baseUrl=http://mockgitlab`,
`allowlist=["acme"]`), `/resolve`→`{sha}` + `/source?path=ok`→200, and the **diagram RENDERS**
(Playwright `docker/e2e/postgres-render.spec.ts`: `[data-testid="likec4-diagram"]` visible,
`.react-flow__node`=2, screenshot `docker/e2e/postgres-render.png`); the admin servlet returns 200.

This genuine Postgres run **invalidated the earlier "DB-agnostic, no functional risk" hand-wave** in
one concrete way: the prior render proofs ran on amps `confluence:run` (**H2**), where web-resources
are served *individually*, so the viewer **`boot-loader.js`** derived the ESM-entry URL from its own
`/download/resources/...` src and worked. A **production** install **batches** that resource into a
`/download/batch/.../_/...` super-batch URL whose endpoint serves the loader's own JS for any sub-path
— so the derived `main.js` URL returned the boot-loader, not the React bundle, and **nothing rendered**.
Fixed: `boot-loader.js` now reconstructs the absolute `/download/resources/<module-key>/likec4-web/assets/main.js`
URL (robust to batched and unbatched serving), mirroring what `editor-loader.js` already did. The
DB-agnostic persistence reasoning still holds; the render-path assumption did not, and is now closed.
Licence nuances: the server-ID is **not** enforced but a **future `CreationDate` IS rejected**; UPM
file-upload needs `-Dupm.plugin.upload.enabled=true` (set in `compose.yaml`).

Per-module detail and run recipes live in `likec4-core/README.md`, `src/main/frontend/README.md`,
`docker/README.md`, and `docker/perf/README.md`.

## 1. Goal

Let a Confluence page author embed a **fully interactive** LikeC4 diagram whose source
(`.c4` / `.likec4` DSL) lives in a **self-managed GitLab** repository. Readers get the real
LikeC4 experience in-page — pan/zoom, hover highlights, and click-to-navigate between linked
views — across an enterprise install of **~30,000 users (~7,000 concurrent)** sourcing from
**~1,000 repositories**.

## 2. Confirmed decisions

| Decision | Choice | Rationale |
|---|---|---|
| Platform | Confluence 9.2.20 **Data Center**, P2 Java app | The version implies DC/Server; installed via UPM. |
| Reader experience | **Fully interactive** embed | LikeC4's core value is navigable diagrams. |
| Compute locus | **Browser-side** | Forced: "self-contained plugin only" + the JVM cannot run the modern JS toolchain (ESM + web-workers + `elkjs`); precomputed-in-repo and an always-on sidecar were both ruled out. |
| Git host | **GitLab self-managed**, ~1,000 repos | Central service-account token (read-only). |
| Permissions | **Confluence page permissions only** | If you can see the page, you can see the diagram; a central identity fetches source. |
| LikeC4 source | **`.c4` / `.likec4` source + `.likec4/*.likec4.snap` manual-layout snapshots** | All other files excluded/rejected. Snapshots are required for faithful rendering of curated layouts (see §4a). |
| Versioning | Pinned **upstream** LikeC4 release | Not the local fork (which has diverged for another purpose). Engine + renderer from the same upstream version. |

### Why browser-side compute scales here (the key insight)

Interactivity needs only **(renderer + a precomputed, laid-out model)**. The renderer
(`@likec4/diagram`, re-exported as `likec4/react`) consumes a model produced once; computation
(`parse → model → ELK layout`) can happen anywhere. The compute output depends **only on source
content `(repo@commit, path)`**, never on the viewer or selected view — so it is cached per
commit, not per viewer. Pushing compute to the **browser** adds *zero* server-side compute and
*zero* new services: the cluster only serves static, cacheable assets it is already sized to
serve at 7k concurrent. Per-client compute runs off-main-thread in a Web Worker and scales
horizontally for free (every viewer brings their own CPU).

## 3. Non-goals / scope boundaries

- No always-on sidecar or serverless compute service (self-contained JAR only).
- No precomputed-model *build artifacts* committed to source repos (the rejected "publish a
  CI-built `model.json`" approach). Note: `.likec4/*.likec4.snap` manual-layout snapshots are
  **authored source**, not a CI build artifact, and do **not** replace browser compute (§4a).
- No per-user GitLab permission enforcement (page permissions govern visibility).
- No support for non-GitLab hosts in v1 (architecture leaves room, but not built).
- No fetching of any file other than `.c4` / `.likec4` and `.likec4/*.likec4.snap` manual-layout snapshots.
- v1 assumes the **default `.likec4/` manual-layouts directory**; a non-default `manualLayouts.outDir` (set in the unread config file) is not supported.

## 4. Architecture & components

A single self-contained JAR. A **thin Java server side** (git access + caching, *no rendering*)
and a **browser bundle** (all LikeC4 compute + render).

```
Confluence page (viewer's browser)
  └─ [likec4-diagram macro] → emits <div data-project data-ref data-path data-view data-instance>
        │  loads web-resource (JS/CSS bundle)
        ▼
  Browser bundle (TypeScript)
    1. read data-attrs
    2. GET /rest/likec4/1.0/resolve?project&ref      → { sha }      (cheap; short TTL)
    3. IndexedDB[sha] hit → render from cached dump (no fetch, no compute)
                    miss → GET /rest/likec4/1.0/source?project&ref&path → { sha, files }
    4. Web Worker: @likec4/language-server (browser) + elkjs → LikeC4Model (all views, laid out)
       → store dump in IndexedDB[sha]
    5. <LikeC4Diagram> renders data-view (or default/index view) — interactive,
       in-place navigation to linked views
        ▲
Confluence JVM (Java plugin)
    • likec4-diagram macro    — validates params, emits placeholder, requires web-resource
    • Source REST resource    — /resolve, /source; authenticated; allowlist-checked
    • GitLabSourceClient      — base-URL + service token; resolve branch→sha;
                                fetch subtree via archive endpoint;
                                keep ONLY *.c4 / *.likec4 + .likec4/*.likec4.snap
    • CacheLayer              — (a) ref→sha [short TTL]  (b) source-bundle by (project@sha,path)
                                [bounded, disk-backed]; single-flight; stale-while-revalidate
    • AdminConfig             — GitLab URL, encrypted token, repo allowlist, TTLs
```

**Components (one job each):**

1. **`likec4-diagram` macro (Java)** — `xhtml-macro` with parameter metadata for the macro
   browser; validates params; emits `<div class="likec4-diagram" data-…>` and requires the
   web-resource. **Does no git work at page-render time** (keeps render cheap at 7k concurrent).
2. **Source REST resource (Java)** — `GET /rest/likec4/1.0/resolve` (`project, ref` → `{sha}`)
   and `GET /rest/likec4/1.0/source` (`project, ref, path` → `{sha, files:{name→content}}`).
   Requires an authenticated Confluence user; validates project against the allowlist.
3. **GitLabSourceClient (Java)** — resolves branch/tag → commit sha; fetches the project subtree
   at the sha via GitLab's **archive** endpoint (one request); **keeps only `*.c4` / `*.likec4`
   source plus `.likec4/*.likec4.snap` manual-layout snapshots** (matcher: `endsWith('.likec4.snap')`),
   preserving their relative paths. Everything else is dropped.
4. **CacheLayer (Java)** — see §7.
5. **AdminConfig (Java)** — GitLab base URL, **encrypted** service token, repo allowlist
   (by group/prefix), default branch, cache TTLs; admin cache-flush.
6. **Web bundle (TypeScript)** — the front end above; built with Vite into one web-resource;
   LikeC4 engine + renderer pinned to a single upstream version; shared Web Worker pool.

## 4a. Manual layouts (curated positions)

LikeC4 lets authors hand-curate node positions; those are persisted as **manual-layout
snapshots** under the project's `.likec4/` directory — one file per curated view, named
`<viewId>.likec4.snap`, a `LayoutedView` serialised as **JSON5** (`_layout: 'manual'`).

- **Why they matter:** the language server applies them during view computation, reconciled
  against the live model (`calcDriftsFromSnapshot` handles drift when the model changed since the
  snapshot). If we ignore them, curated views render with auto-layout instead of the author's
  intended layout — a fidelity bug, not a cosmetic one.
- **They do not replace compute:** snapshots are per-view *overlays* reconciled against the
  freshly-computed model. The browser worker still computes the model, then applies the manual
  layouts. The `.snap` is **not** a standalone renderable model.
- **Delivery to the worker:** the worker is given a **virtual filesystem mirroring the repo
  subtree** (the `.c4`/`.likec4` files *and* `.likec4/*.likec4.snap`) so the language server's
  manual-layouts scanner discovers them at the expected relative paths. This is part of the §10
  spike.
- **v1 limitation:** only the **default `.likec4/` directory** is honoured (the config file that
  could set `manualLayouts.outDir` is not fetched).
- **Possible later optimisation:** optimistically render a snapshot's `LayoutedView` immediately
  while the worker validates/recomputes in the background.

## 5. Data flow & lifecycle

- **Page render (server):** macro validates params, emits the placeholder `<div>`, requires the
  web-resource. No git access.
- **Browser, per diagram:** `/resolve` → IndexedDB lookup by `sha` → hit renders immediately;
  miss fetches `/source`, computes in the Web Worker, stores the dump under `sha`, renders.
- **Interaction:** element click fires `onNavigateTo`, swapping the view *within the already-
  loaded model* — no network, no recompute. Fullscreen/lightbox for large diagrams.
- **Server on `/source` miss:** resolve ref→sha, pull archive subtree at sha, keep
  `*.c4`/`*.likec4` **and `.likec4/*.likec4.snap`** (relative paths preserved), cache the bundle,
  return. The worker loads all of them into its virtual filesystem so manual layouts apply (§4a).

A repeat viewer of an unchanged diagram does **one cheap `/resolve` call and renders from local
cache** — no source bytes over the wire, no recompute. This is what makes hundreds of concurrent
diagram-viewers cheap.

## 6. Authoring UX

A **"LikeC4 Diagram"** macro-browser entry with parameters:

| Param | Meaning | Default |
|---|---|---|
| **Project** | GitLab project path (`group/sub/repo`) or numeric ID | — (required) |
| **Ref** | branch / tag / commit | repo default branch |
| **Path** | directory holding the LikeC4 project within the repo | repo root |
| **View** | starting view id | project default/index view |

**View dropdown:** a **"Load views"** action in the macro editor fetches + computes the model
once (reusing the exact browser pipeline), then populates **View** as a dropdown (id + title) and
shows a live preview. If skipped, the author types the view id. Unknown project/ref/view and
`.c4` parse errors surface as inline editor warnings.

*A `path` scopes exactly one project (no config file is read). A repo with multiple projects is
addressed by pointing `path` at the right subdirectory.*

## 7. Caching & freshness

Three tiers; **no distributed cache required** (important on a DC cluster):

| Tier | Key | Lifetime | Notes |
|---|---|---|---|
| ref→sha (server, per-node) | `(project, ref)` | short TTL, default 60s | The freshness knob. Full 40-char SHAs cached permanently. |
| source bundle (server, per-node, disk-backed) | `(project, sha, path)` | long, LRU-bounded | Content is **sha-immutable** → per-node caching is correct; survives restarts. |
| computed dump (client, IndexedDB) | `sha` | bounded LRU | Eliminates recompute on revisits; per-browser. |

- **Why no distributed cache:** source bundles are keyed by an immutable SHA, so per-node caches
  can never be stale. ref→sha is short-TTL, so minor per-node propagation variance is acceptable.
- **Single-flight:** concurrent cold misses for the same `(project@sha, path)` coalesce into one
  GitLab archive fetch (lock per key) — no thundering herd on a freshly-pushed popular diagram.
- **Resilience:** if GitLab is slow/down, serve stale source bundle if present
  (stale-while-revalidate) + circuit-breaker/backoff.
- **Refresh:** per-macro refresh control (bypasses ref→sha TTL) + admin cache-flush.
- **HTTP caching:** static bundle served with far-future expiry (web-resource versioning);
  `/source` for a full SHA marked immutable.

## 8. Security model

- **Service token** stored **encrypted** in admin config; never reaches the browser.
- **Repo allowlist** (by GitLab group/prefix) — without it the authenticated `/source` endpoint
  would be an open read-proxy for anything the token can see. The endpoint requires an
  authenticated Confluence user **and** validates the project against the allowlist.
- **File-type guarantee:** only `*.c4` / `*.likec4` source and `.likec4/*.likec4.snap` layout
  snapshots (JSON5 layout data) are ever fetched or delivered — the plugin cannot exfiltrate
  secrets, `.env`, or arbitrary files into the browser. Verified by a fixture test (repo with
  secrets/other files → assert never fetched or delivered).
- **Input validation / traversal:** project checked against allowlist; `ref`/`path` sanitised;
  archive extraction guarded against tar/zip-slip (`..`, absolute paths rejected).
- **Least privilege:** service account is `read_repository`, minimal group scope (documented).
- **Render safety:** `.c4` is consumed as *data* by the LikeC4 parser (not eval'd / not HTML).
  **Verify item:** confirm the renderer sanitises markdown/HTML in element descriptions/notes
  (no XSS via diagram labels). CSP: same-origin worker + web-resources.

## 9. Failure modes

Every failure degrades to a clear in-macro message — never a broken page.

- Project/ref/view not found → explicit message echoing the params.
- GitLab 401/403/unreachable → "Cannot reach source repository" (+ admin-only detail); serve
  stale if cached.
- `.c4` parse/validation errors → show LikeC4 diagnostics (`file:line`) so authors can fix.
- Unknown view id → list available views.
- Compute timeout / very large model → cancellable spinner, then a "view too large" message
  (no server fallback by design).
- JS disabled / bundle fails → placeholder text fallback.
- Many diagrams on one page → a small **shared Web Worker pool** (2–3 workers, queued) so a
  20-diagram page doesn't spawn 20 workers.

## 10. LikeC4 versioning & the de-risking spike

- The bundle pins a **single upstream LikeC4 release** (the published `likec4` package re-exporting
  `likec4/react`, plus `@likec4/language-server` browser-worker and `elkjs`). Engine and renderer
  from the same version so the computed-model shape never drifts from the renderer.
- **MUST-DO SPIKE before committing to the architecture:** validate that upstream
  `@likec4/language-server`'s **browser-worker can compute a model from raw `.c4` standalone** —
  outside the playground / `@likec4/vite-plugin` scaffolding — and that the result renders via
  `likec4/react`. The spike **must also load `.likec4/*.likec4.snap` into the worker's virtual
  filesystem and confirm manual layouts are applied** (§4a). Also confirm the manual-layouts
  feature exists in the pinned upstream version. The model *shape* and the manual-layout mechanism
  were confirmed against a local fork (v1.57.0); upstream must be validated independently. This
  spike seeds the compute-integration tests.

## 11. Testing strategy

**Fast layer (no Docker, every commit):**
- Java unit: `GitLabSourceClient` (mocked API — archive parsing, sha resolution, **file filtering:
  keep `.c4`/`.likec4` + `.likec4/*.likec4.snap`, drop everything else**, relative-path
  preservation, error mapping); `CacheLayer` (TTL, LRU, single-flight); macro HTML output; REST
  auth + allowlist + traversal/SSRF rejection; token encryption round-trip.
- Front-end unit (vitest): data-attr parsing; IndexedDB/cache-key logic (`fake-indexeddb`);
  worker protocol; error rendering; view-dropdown population.
- Compute integration: fixture `.c4`/`.likec4` projects (small / large / multi-file /
  deliberately-broken / **with `.likec4/*.likec4.snap` manual layouts**) → assert model computes,
  a known view renders, **and a curated view honours its snapshot positions** (not auto-layout).
  Grows out of the §10 spike. (Use the real `target` project — `spec.likec4` + `target.likec4` +
  `views.likec4` + `.likec4/index.likec4.snap` — as a reference fixture.)

**Heavy layer (fresh ephemeral Docker stack, torn down each run):** e2e, performance, install/upgrade.
- `docker compose` stack on this host per run: **Confluence 9.2.20** (Docker Hub image) +
  **Postgres** + **mock GitLab stub**.
- **Mock GitLab stub:** a tiny container serving only the two endpoints the plugin uses
  (resolve ref→sha, archive-subtree) from fixture repos — fast boot, deterministic, low RAM.
  Real-API fidelity covered separately by a contract test against recorded responses.
- Harness: bring up → health-gate on Confluence `/status` → seed fixtures (mock repos + a
  Confluence space/page with the macro) → install plugin JAR via **UPM REST API** → run suite →
  tear down.
- **Install/upgrade:** install prior version → upgrade to new → assert macro still renders and
  admin config persists.
- **e2e (Playwright):** load a page with a known diagram → nodes appear, click-navigation works,
  fullscreen works.
- **Performance:** drive concurrent `/resolve`+`/source` and page loads (k6/Gatling container);
  assert caching + single-flight hold and the page-render path does **zero** git work.
- **Confluence licence (unattended setup):** inject a **3-hour timebomb DC licence** — a copy-paste
  key published on Atlassian's developer docs ("Timebomb licenses for testing server apps";
  *no* Marketplace Partner status needed), applied via Confluence's license REST endpoint at
  stack-up. Boot from a **golden pre-setup Confluence-home + Postgres snapshot** so each run starts
  already-set-up and only (re)applies a fresh key (avoids the multi-minute setup wizard). The
  3-hour window comfortably covers a test run. (Note: Atlassian is winding down DC Marketplace
  sales — new DC sales stop 30 Mar 2026 — so longer-lived "developer licence" routes are no longer
  reliable; re-applying the timebomb key is the pragmatic fallback for manual sessions.)

## 12. Prerequisites & open items

- **Confluence test licence:** a 3-hour **timebomb DC licence** copied from Atlassian's developer
  docs ("Timebomb licenses for testing server apps") — no Marketplace Partner status required.
  Applied at test-stack boot via the license REST endpoint.
- **Spike (§10):** upstream browser-worker compute-from-source validation **incl. manual-layout
  snapshot application** — gates the whole design.
- **Verify (§8):** LikeC4 renderer sanitises label/description markup (XSS check).
- **Golden snapshot:** build the pre-setup Confluence-home + Postgres snapshot for unattended boot.

## 13. Phasing

- **v1:** everything above — browser-side compute, GitLab source fetch
  (`.c4`/`.likec4` + `.likec4/*.likec4.snap`), manual-layout application, three-tier caching,
  interactive render with in-place navigation, admin config + allowlist, full test pyramid.
- **Later (optional):** self-contained **server-side computed-dump cache** — the first viewer's
  browser posts its computed dump back; the plugin caches it (own cache / Confluence attachment)
  keyed by source-hash so subsequent viewers skip compute. Deferred for its trust nuances. Still
  no separate service.

## 14. Risks

- **Upstream browser-worker viability** (§10) — highest risk; spike first.
- **Very large models** on weak clients — mitigated by worker-offload, IndexedDB caching, and the
  optional phase-2 server-side dump cache; no server compute fallback by design.
- **Confluence unattended setup / licensing** — mitigated by golden snapshot + timebomb licence.
- **GitLab API/version drift** — isolated behind `GitLabSourceClient` + a recorded-response
  contract test.
