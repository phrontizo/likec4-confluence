# LikeC4 Confluence Diagram Plugin

A self-contained Confluence Data Center plugin that renders **interactive
[LikeC4](https://likec4.dev) diagrams** directly in a Confluence page. Diagram
sources — `.c4` / `.likec4` DSL plus optional manual-layout snapshots — live in
your own self-managed GitLab, not in Confluence; the plugin fetches them on
demand, computes the layout **in the reader's browser**, and renders a live,
navigable diagram. There is no server-side Node/headless-browser rendering and
no diagram image storage: the macro just records *which* model and view to show,
and the work happens client-side against a small REST source API.

> Status: built and **verified end-to-end on Confluence Data Center 10.2.13
> (Jakarta EE), JDK 21** — the diagram renders live on a real Postgres-backed
> install (all runtime gates pass). Originally built for Confluence 9.2; ported to
> Confluence 10.2 / Jakarta EE.

## How it works (architecture)

- **Macro → web-resource → ESM bundle.** The `likec4-diagram` macro emits a tiny
  `<div class="likec4-diagram" data-project data-ref data-path data-view data-instance data-height>` and
  requires the plugin's web-resource, which loads the Vite/React ESM bundle
  (`src/main/frontend` → `src/main/resources/likec4-web/`). The viewer auto-boots
  and binds to each macro div on the page.
- **`/resolve` + `/source` REST → Web Worker compute → `likec4/react` render.**
  The browser calls `GET /rest/likec4/1.0/resolve?project&ref` → `{ sha }` and
  `GET /rest/likec4/1.0/source?project&ref&path` → `{ sha, files }`, computes the
  laid-out model in a **Web Worker** using the bundled `likec4` engine, then
  renders it with `likec4/react` (in-place navigation, fullscreen, drift
  warnings).
- **Caching + single-flight.** Server-side: a `ref → sha` TTL cache plus an
  LRU/disk-backed source-bundle cache with single-flight and
  stale-while-revalidate, guarded by a circuit breaker. Client-side: the computed
  model dump is cached in IndexedDB keyed by `${sha}:${path}`, so revisiting a
  page does no recompute and no network.
- **GitLab archive fetch, filtered.** The server fetches the repository archive
  (`repository/archive.tar.gz`) from a self-managed GitLab and keeps **only**
  `*.c4` / `*.likec4` / `.likec4/*.likec4.snap` files, with path-traversal and
  project-allowlist enforcement — so no unrelated repo content is ever served.
- **Encrypted service token + manual-layout snapshots.** The GitLab token is
  encrypted at rest with **AES-256-GCM**; the encryption key lives on the
  **Confluence-home filesystem** (`<confluence-home>/likec4-token.key`, owner-only
  `0600`), separate from the ciphertext in the database. Manual-layout
  `.likec4.snap` snapshots are honoured when present, with **drift detection** that
  warns when a snapshot no longer matches the model.

For deeper, as-built detail see the per-module READMEs (linked under
[Project layout](#project-layout)); this top-level README does not duplicate them.

## Security model & trust boundaries

This plugin is a **confused-deputy by design**, and the trust model is a conscious
decision — understand it before you configure the allowlist:

- **The GitLab service token is privileged and shared.** Source fetches run with the
  single admin-configured token, *not* the calling user's GitLab identity. The
  plugin therefore bypasses GitLab's own per-project permissions.
- **The project allowlist — not Confluence space/page permissions — is the access
  boundary.** The `/rest/likec4/1.0/resolve` and `/source` endpoints require an
  authenticated Confluence user, but **any** logged-in user can read the LikeC4
  sources (and manual-layout snapshots) of **every** allowlisted project, regardless
  of which spaces/pages they can see. `/resolve` also acts as an allowlist-existence
  oracle. **Only allowlist projects whose architecture DSL you are comfortable
  exposing to all logged-in Confluence users.** Use narrow project paths rather than
  broad group prefixes when in doubt.
- **Admin configuration is system-admin only.** The base URL, encrypted token, and
  allowlist are set on `/plugins/servlet/likec4/admin`, gated on
  `isSystemAdmin`; the token is never returned to the browser. The GitLab base URL
  must be `https` for a **public** host (plain `http` is permitted for hosts that
  aren't publicly routable — loopback, RFC1918/IPv6-ULA addresses, single-label
  intranet names, and internal TLDs — since a self-managed GitLab is frequently
  internal), so the service token is never shipped in cleartext over the internet.
- **Defence in depth on the fetch path.** Inputs (`project`/`ref`/`path`) are
  regex-validated and URL-encoded; the project allowlist is enforced before any
  fetch; the archive is filtered to LikeC4 files only with path-traversal rejection;
  extraction is size/entry-count bounded and the HTTP fetch is timeout-bounded
  (so a hostile or slow GitLab cannot exhaust memory or threads).
- **Token at rest:** AES-256-GCM; the key lives on the Confluence-home filesystem
  (`0600`), separate from the ciphertext in the database (see the per-module READMEs).

## Requirements

- **JDK 21** (the plugin and core compile/run at release 21).
- **Node 20 + npm** (builds the browser bundle; the Maven build shells out to `npm`, and the `docker/`
  harness scripts also require host `node` to parse UPM/seed JSON). Node 20 is the pinned platform
  (`package.json` `engines`: `>=20 <21`), aligned with Confluence 10's Node runtime.
- **Docker + Docker Compose** (only for the local run/verify harness under `docker/`).
- **Confluence Data Center 10.2.13 (Jakarta EE)** — the verified, supported target.

## Build

This repo ships a committed **Maven wrapper**, so no global Maven install is
needed — just a JDK 21 on your `PATH` (or `JAVA_HOME`):

```bash
# The root plugin build depends on the standalone `likec4-source-core` artifact
# being in your local Maven repo, so install it ONCE first (it is not a reactor
# child — see likec4-core/README.md for the rationale):
cd likec4-core && ../mvnw -B install -DskipTests && cd ..
# Then build the plugin: this compiles the Jakarta wrapper, runs the npm frontend
# build, and produces the installable plugin JAR under target/.
./mvnw -B package
```

On subsequent builds (with core already installed) the `./mvnw -B package` step
alone is enough until you change anything under `likec4-core/`.

## Test

```bash
# Java core (Atlassian-independent): 255 JUnit tests
cd likec4-core && ../mvnw -B test

# Frontend (Vite/React/TS): 244 vitest tests + typecheck (use npm ci for a reproducible install)
cd src/main/frontend && npm ci && npm run typecheck && npm test
```

The Playwright browser e2e lives under `docker/e2e/` and runs against the live
compose stack (see below); it needs Chromium system libraries and is intended to
run from the harness container.

## Run / verify locally

A full, **unattended** local stack (Confluence 10.2.13 + PostgreSQL + a
mock-GitLab serving example models) is provided under `docker/`. It brings the
stack up, drives the setup wizard headlessly, UPM-installs and configures the
plugin, seeds a macro page, and runs the live Playwright render proof.

See **[`docker/README.md`](docker/README.md)** for the full compose +
timebomb-licence flow. In short:

```bash
./mvnw -B package          # build the plugin JAR (runs the wrapper unit tests too)
docker/up.sh               # bring up + configure the stack (needs a test licence — see below)
docker/e2e/c10-gate.sh     # LIVE GATE: reinstall JAR + run GATE3/4/5 (render, admin form auth-gated, editor)
docker/down.sh             # teardown
```

The **live gate** (`docker/e2e/c10-gate.sh`) is the real proof the plugin works — green
unit tests alone do not show that it renders, that the admin/WebSudo flow works, or that the
editor authors a macro on a real Confluence. `docker/e2e/run.sh` runs the fuller spec sweep.

## Install

Build the plugin JAR (`./mvnw -B package`), then in Confluence go to
**⚙ → Manage apps → Upload app** and upload the JAR from `target/`. After
installing, open the plugin's admin config page
(`/plugins/servlet/likec4/admin`) and set your GitLab **base URL**, **project
allowlist**, and **service token**. Then add the **LikeC4 Diagram** macro to a
page and pick a project/ref/view.

> UPM file-upload is disabled by default on a fresh Confluence DC; the JVM needs
> `-Dupm.plugin.upload.enabled=true` (the `docker/` harness sets this for you).

## Testing licence note

The local dev Confluence needs an Atlassian **timebomb Data Center licence**,
placed at `docker/.timebomb-license` (**gitignored — never commit it**). These
are short-lived keys for testing server/DC apps and cannot be minted offline; get
one from Atlassian's
[Timebomb licenses for testing server apps](https://developer.atlassian.com/platform/marketplace/timebomb-licenses-for-testing-server-apps/)
page. `docker/up.sh` fails loudly with this instruction if the file is missing or
rejected. (Note: a *future*-dated `CreationDate` is rejected by Confluence — use a
current-dated key.)

## Project layout

```
.
├── likec4-core/          Atlassian-independent Java: GitLab fetch, caching,
│                         token crypto, validation (255 JUnit). → likec4-core/README.md
├── src/main/frontend/    Vite/React/TS browser bundle: worker compute + render
│                         (244 vitest). → src/main/frontend/README.md
├── src/main/java/        Confluence P2 wrapper: macro, REST resources, admin servlet
│                         (127 JUnit authz/WebSudo tests in src/test/java).
├── src/main/resources/   atlassian-plugin.xml + built web-resource (likec4-web/)
├── docker/               Compose harness, mock-GitLab fixtures, e2e. → docker/README.md
├── pom.xml               Root plugin build (atlassian-plugin packaging)
├── LICENSE  NOTICE       MIT licence + third-party attribution
└── mvnw  mvnw.cmd  .mvn/ Committed Maven wrapper
```

## License

This project is licensed under the **MIT License** — see [`LICENSE`](LICENSE).

Bundled and runtime third-party dependencies (e.g. `likec4`, React, Apache
Commons Compress, `@hpcc-js/wasm-graphviz`) keep their own licenses; see
[`NOTICE`](NOTICE) for the attribution list. The example architecture model under
`docker/mock-gitlab/repos/acme/architecture/big/` is the LikeC4 project's
`cloud-system` example (MIT) — see that directory's `NOTICE`.
