# Residuals → done. Working AND verified product (no asterisks).

> **Superseded-platform note (post-2026-06-29):** this tracker presents itself as the final
> "working AND verified" state, but its verification env (`atlassian/confluence:9.2.20` + Postgres) and
> its final counts (56 core / 30 frontend) are **pre-retarget**. The product was then moved to Confluence
> **10.2.13 / Jakarta EE / Java 21** and the suites grew further. For the current, authoritative counts
> and facts see **README.md** and **CLAUDE.md**; do not trust the platform or counts here as current
> (the intermediate numbers this note once quoted were themselves going stale). Kept as the closure record.

User mandate (2026-06-28): complete EVERYTHING; a working and verified product. Close every residual from the FINISHED audit. Do not hand-wave "CI-gated" — build the golden-snapshot/licence automation and actually run it.

Verification env: the **production compose stack** — `atlassian/confluence:9.2.20` + **Postgres** + mock-GitLab (`docker/compose.yaml`), with REAL unattended setup (timebomb DC licence + DB + admin). The Confluence container is detached (`up -d`) so it persists across turns. This replaces the amps `confluence:run` dev/H2 instance as the source of truth.

## R0 — Production stack + unattended setup (the unblocker) — CLOSED (2026-06-29, genuine Postgres run)
- [x] Bring up `atlassian/confluence:9.2.20` + Postgres + mock via compose — DONE (all healthy; Confluence reached FIRST_RUN on Postgres via direct-JDBC env).
- [x] Automate setup: `up.sh` drove the wizard headlessly to **RUNNING on Postgres** — ALL steps now LIVE-PROVEN with exact actions/fields (licence → deployment `setupcluster.action`/`newCluster=skipCluster` → [DB auto-satisfied by `ATL_JDBC_*`] → content `setupdata.action`/`contentChoice=blank` → user-mgmt `setupusermanagementchoice.action`/`userManagementChoice=internal` → administrator `setupadministrator.action`, which completes setup). Postgres confirmed: `confluence.cfg.xml` → `jdbc:postgresql://postgres:5432/confluence`; `psql \dt` → 226 tables. Licence nuance proven: server-ID NOT enforced, future `CreationDate` IS rejected. A working SDK Developer timebomb sits in `docker/.timebomb-license` (gitignored).
- [x] Build + UPM-install: **installed via UPM on the Postgres instance** (enabled), admin configured, `/resolve`→`{sha}` + `/source?path=ok`→200, **diagram RENDERS** (Playwright: `.react-flow__node`=2, `docker/e2e/postgres-render.png`), admin servlet 200. NB UPM file-upload needs `-Dupm.plugin.upload.enabled=true` (now in `compose.yaml`). **This run caught + fixed a real production-only render bug**: `boot-loader.js` derived the ESM-entry URL from its own src, which breaks when Confluence *batches* the web-resource (production) vs serves it individually (amps/H2) — fixed to reconstruct the absolute `/download/resources/<module-key>/likec4-web/assets/main.js` URL.
- [x] Golden snapshot: not needed — the live wizard-walk reaches RUNNING headlessly and idempotently.

## R1 — Operator flows (the "working product" provers, on the production stack)
- [x] C2 native author flow — VERIFIED LIVE + fixed 2 real bugs (insertMacro envelope `{macro,contentId}`; macro icon). Macro inserts via native macro browser → renders (3 nodes). `3444d62`
- [x] C1 admin flow — VERIFIED LIVE + fixed real bug (atl.admin decorator stripped data-context-path → use AJS.contextPath()). In-browser save persists. `3444d62`

## R2 — Closable verification/polish (I flagged these as closable; do them)
- [x] 10. Admin atl.admin chrome — VERIFIED (header+admin nav+form), screenshot. `dab22c5`
- [x] 3. Zero-git server render — PROVEN (server render commits=0/archive=0; browser-driven moves them). `dab22c5`
- [x] 6. Heavier k6 (100 VUs/60s) — 21,934 reqs, 0%% fail, p95 309ms, archive 0 ≪ 10,967 source. `dab22c5`
- [x] 9. Notes-XSS + CSP — sanitiser strips payloads, ZERO CSP violations (walkthrough exercised). `dab22c5`

## R3 — Fidelity / hardening
- [x] 7. Real gitlab.com contract test — recorded actual bytes (dslackw/colored, 50 files), filter drops all non-LikeC4, mutation-checked. core 48. `639cf9e`
- [x] 8. Token key-at-rest on home filesystem (0600), off PluginSettings; migration exercised live; threat model documented. core 56. `4fe202e`

## R4 — Install/upgrade, real (on the production stack)
- [x] 5/E3. Install/upgrade RUN LIVE via UPM — uninstall→install v0.1.0 (render 2)→upgrade v0.1.1 (config persisted, render 2). `83c1022`

## R5 — Final
- [x] Full green gate: **56 core JUnit + 30 frontend vitest + typecheck + full `jmvn package`** all green. All operator/install/render/perf/XSS flows live-verified on real Confluence 9.2.20.
- [x] **Postgres boundary CLOSED (2026-06-29):** the full `atlassian/confluence:9.2.20` + PostgreSQL stack was set up end-to-end with a working SDK Developer timebomb licence and the plugin verified ON POSTGRES — install via UPM, configure, resolve/source, **render (2 nodes)**, admin servlet 200. The genuine run also disproved the old "DB-agnostic ⇒ no functional risk" hand-wave by surfacing a real production-only render bug (web-resource *batching* broke the viewer `boot-loader.js` ESM-entry URL derivation) which is now fixed. Postgres is no longer "licence-gated / by construction".
- [x] Docs updated to the real, fully-verified state — `docker/README.md`, `likec4-core/README.md`, `src/main/frontend/README.md`, and the design-spec status note all reconciled.

## Log
- (start) tracker created. Bringing up the production compose stack + fetching the timebomb licence.
- (finish-spec) `up.sh` finalised to a complete unattended setup script (licence-parameterised; licence step live-proven, post-licence flow-based + marked); docs reconciled to the verified state; R5 ticked. No Java/TS changes.
- (finish-spec, 2026-06-29) **Genuine Postgres verification done.** Working licence accepted; `up.sh` wizard-walk drove Confluence 9.2.20 to RUNNING on Postgres; corrected the walker to the exact LIVE-PROVEN actions/fields; added `-Dupm.plugin.upload.enabled=true` to `compose.yaml` (clean DC 9.2 rejects UPM uploads otherwise); plugin installed via UPM, configured, **diagram renders** (`docker/e2e/postgres-render.spec.ts`, screenshot `docker/e2e/postgres-render.png`). Fixed a real production-only render bug in `src/main/frontend/public/boot-loader.js` (batched web-resource URL). R0 + the Postgres boundary closed.
