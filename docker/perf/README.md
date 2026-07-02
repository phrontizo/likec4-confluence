# Performance test (spec §11)

Proves the property §11 calls out: under concurrent load, the **server-side cache + single-flight
coalesce git work**, so the GitLab `archive` endpoint is hit **at most once per `(project, sha,
path)`** no matter how many `/source` requests arrive — and the **page-render path does zero git
work** by construction.

## How it works

1. **`../mock-gitlab/server.mjs`** counts the two GitLab calls the plugin makes (`commits` =
   ref→sha, `archive` = subtree download) and exposes them:
   - `GET /__count` → `{"commits":N,"archive":M}`
   - `GET /__reset` → zeroes both.
   (Restart the mock after pulling this change so those endpoints exist — the live mock loaded the
   old code.)
2. **`load-test.js`** is a [k6](https://k6.io) script run in the `grafana/k6` container. N concurrent
   VUs hammer `${BASE}/rest/likec4/1.0/resolve` and `…/source` for one `(project, ref, path)` with
   admin HTTP Basic auth. Thresholds: `http_req_failed` rate `< 1%`, `http_req_duration` p95 `< 2s`.
   It also tracks custom counters (`likec4_resolve_reqs`, `likec4_source_reqs`) so the runner knows
   exactly how many REST calls were issued.
   `load-test.js`'s `handleSummary()` prints a parseable digest of these counts + the threshold
   metrics to **stdout** (k6's `--summary-export` is deprecated/ignored on current images, and the
   unprivileged k6 container usually can't write a summary file into a host-owned bind mount).
3. **`run.sh`** resets the mock counters, runs k6, parses the request counts from the k6 stdout
   digest, reads `/__count`, and asserts:
   - **(A)** k6 issued a meaningful number of `/source` requests (`≥ MIN_SOURCE`, default 100);
   - **(B)** the mock saw **`archive ≤ ARCHIVE_MAX`** (default 1) — i.e. thousands of `/source`
     requests coalesced into **≤1** git archive fetch (0 if the server cache was already warm);
   - **(C)** the mock saw **`commits ≤ COMMITS_MAX`** (default 1) — the whole run targets a single ref,
     so the ref→sha cache must coalesce every `/resolve` into **≤1** commits lookup (0 if warm), the direct
     analogue of (B). (The older `commits ≤ /resolve` bound was near-vacuous — a fully disabled RefShaCache
     yields `commits == /resolve`, which passed it — so it was tightened to `COMMITS_MAX`.) This is a hard
     gate (`run.sh` exits non-zero on failure), not informational: the mock's liveness healthcheck uses a
     separate `/__health` route that does not touch the counters, so `commits` reflects only real
     `/resolve` traffic and the bound is meaningful.
   It prints `PERF: PASS` / `PERF: FAIL` and exits non-zero on failure.

## Why ≤1 (and not exactly 1)

`SourceBundleCache` is disk-backed and `(project, sha, path)`-keyed, so once any node has fetched a
bundle the archive endpoint is never hit again for that key. The live Confluence cache is usually
**warm** from earlier runs, so a fresh perf run typically records **0** archive fetches; a fully
cold server records **exactly 1** (single-flight collapses the concurrent cold-miss stampede into
one load). Either way `archive ≤ 1 ≪ /source requests`, which is the property under test.

## Page-render does zero git work — `zero-git-render.sh`

The macro emits only `<div class="likec4-diagram" …>` at render time — no `/resolve` or `/source`.
All git work happens later, in the browser-driven REST calls measured here. So a page that is merely
rendered (not opened in a browser) adds **nothing** to the `commits`/`archive` counters — the §11
"zero git work at page-render time" guarantee is structural, and the counters above only ever move
in response to REST traffic.

`zero-git-render.sh` proves exactly this against the live mock counters:

- **(A)** resets the counters, then renders the page **storage→view via REST** (`GET
  /rest/api/content/{id}?expand=body.view`) — server-side, **no browser**. The rendered HTML contains
  the macro `<div class="likec4-diagram" …>` (the macro executed) yet the mock saw `commits==0 &&
  archive==0` (zero git).
- **(B)** resets again and loads the **same** page in a real browser (`zero-git-browser.spec.ts`, in
  `../e2e`), so the worker fetches `/resolve` + `/source`; the mock counters now **move** — proving
  all git is browser-side.

## Run

```bash
# Confluence up + plugin installed + admin configured (baseUrl→mock, allowlist has the project),
# mock-GitLab restarted (so /__count + /__reset exist).
docker/perf/run.sh                       # default 50 VUs / 20s
VUS=100 DURATION=60s docker/perf/run.sh  # heavier load (spec §11)
docker/perf/zero-git-render.sh           # server-render-is-zero-git proof + browser contrast
# Tunables (env): BASE, AUTH (or AUTH_PLAIN), MOCK, VUS, DURATION, PROJECT, REF, SRC_PATH,
#                 ARCHIVE_MAX, MIN_SOURCE, K6_IMAGE.
```

Defaults target the **`docker compose` stack** — the current verified harness. `BASE` is resolved by
`../lib/resolve-confluence-base.sh` (honours `CONFLUENCE_PORT` / `docker/.env`, else
`http://localhost:8090`); Basic auth comes from `AUTH_PLAIN` (default `admin:admin`, matching the compose
stack's default admin, which `up.sh` provisions as `admin`/`admin`; override `AUTH_PLAIN=admin:<pass>`
only if you set a non-default `ADMIN_PASS`). Override
`BASE`/`MOCK` to target the historical amps `confluence:run` workflow (Confluence on
`http://localhost:1990/confluence`, a host-run `mock-gitlab/server.mjs` on `:8099`). `compose.yaml`
deliberately does **not** publish a host port for `mockgitlab`, so the counter endpoints
(`/__count`/`/__reset`) are not reachable from the host by default. Bring the stack up with the
committed `compose.mock-publish.yaml` overlay (publishes the mock on `:8099`):

```bash
docker compose -f docker/compose.yaml -f docker/compose.mock-publish.yaml up -d
```

then override the defaults: `BASE=http://localhost:8090 MOCK=http://localhost:8099
AUTH_PLAIN=admin:<your-admin-pass> docker/perf/run.sh`.
