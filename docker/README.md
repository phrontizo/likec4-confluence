# LikeC4 Confluence — Docker Test Harness (Plan 4)

A `docker compose` stack (Confluence 10.2.13 + Postgres + mock-GitLab) plus `up.sh`, a fully
**unattended** harness: it brings up the stack, health-gates Confluence, drives the setup wizard
headlessly from a timebomb DC licence (`docker/.timebomb-license`), installs + configures the plugin
via UPM, and seeds a page with the `likec4-diagram` macro. `e2e/run.sh` then runs a live,
containerised Playwright render proof against it.

## Prereqs
- Docker + Compose, the committed `./mvnw` wrapper (JDK 21), Node/npm.
- **RAM: Confluence DC needs ≥6 GB recommended.** On a small host (≈3.8 GB) the boot is best-effort.

## Run
0. Place a valid Atlassian **timebomb DC licence** in `docker/.timebomb-license` (gitignored). It
   cannot be minted in this sandbox; get a 3-hour key from Atlassian's "Timebomb licenses for testing
   server apps" page. `up.sh` fails loudly with this instruction if the file is missing/rejected.
1. `./mvnw -B -ntp package`  — builds the plugin JAR (compiles the wrapper, bundles the frontend).
2. `docker/up.sh`   — build + start stack, health-gate, **drive the setup wizard headlessly** from the
   licence, UPM-install + configure the plugin, seed a macro page. Idempotent (re-run is a no-op).
3. `docker/e2e/c10-gate.sh`  — **the canonical live gate.** (Re)installs the freshly-built JAR via a
   clean uninstall+install (OSGi caches same-version SNAPSHOTs, so an "upgrade" silently keeps the
   old bytecode), re-configures admin (uninstall wipes plugin settings), and runs the three runtime
   gates (GATE3 macro render, GATE4 admin form loads for an authenticated admin / bounces an
   unauthenticated caller, GATE5 macro editor) via containerised
   Playwright. Exits non-zero on any failure — re-run it after each change. (For the broader
   render/sweep specs, `CONFLUENCE_BASE=http://localhost:8090 docker/e2e/run.sh` — note its sweep
   includes the memory-sensitive `big-model`/`panel-clearance` specs, which can time out on a
   RAM-constrained box: that is environmental, not a regression, and neither is part of this gate.)
4. `docker/down.sh` — teardown (`docker compose down --remove-orphans`).

`up.sh` is parameterised by env (`CONFLUENCE_PORT`, `MOCK_BASEURL`, `ALLOWLIST_JSON`, …). The plugin's
admin `baseUrl` defaults to `http://mockgitlab:8080` — the compose service name on its port 8080 (the
non-root mock's listen port), i.e. how the plugin (inside the Confluence container) reaches mock-GitLab
(NOT `localhost`).

## Dev/test-only JVM flags (this harness only)

The compose Confluence container sets three system properties via `JVM_SUPPORT_RECOMMENDED_ARGS`
(see `compose.yaml`). They exist **only to make this unattended local harness work** and must **not**
be used in production — a real install authenticates via **PAT/OAuth** and installs a
**signed/Marketplace build**:

- `-Dupm.plugin.upload.enabled=true` — a freshly set-up Confluence DC rejects UPM **file-upload**
  with HTTP 403 ("Plugins cannot be installed via upload") unless this is on. Needed because the
  harness UPM-installs the locally built JAR rather than installing from Marketplace.
- `-Dcom.atlassian.plugins.authentication.basic.auth.filter.force.allow=true` — Confluence 10.2
  ships the `atlassian-authentication-plugin`, which **blocks HTTP Basic auth on API calls by default**
  for new installs. The harness (UPM install, `/rest/likec4` calls, Playwright specs) authenticates
  with HTTP Basic, so this re-enables it (the 10.2 equivalent of "Allow basic authentication on API
  calls" in Security Configuration). **Test convenience only.**
- `-Datlassian.upm.signature.check.disabled=true` — Confluence 10.2's UPM (`universal-plugin-manager
  8.0.17`) verifies app **signatures** on uploaded JARs by default and rejects the unsigned dev build
  ("Signature check failed!"). Disabling the check lets the unsigned local JAR install. **Test
  convenience only** — production installs a signed/Marketplace artifact and leaves this check on.

## What is proven live

All of v1 is **verified end-to-end live on a real Confluence Data Center 10.2.13** (the `docker compose`
stack + mock-GitLab — see "Runtime gates on Confluence DC 10.2.13" below) and is the full product
working end-to-end, not a compile/boot check:
- the plugin installs and **wires** into Confluence 10.2.13; the macro **renders the diagram**
  (Playwright `.react-flow__node`);
- the **C2 macro inserts via the native macro browser** and the **C1 admin config page saves
  in-browser** (`3444d62` — both Playwright-verified, each fixing a real bug);
- **install/upgrade RUN LIVE via UPM** (`83c1022`): fresh-install v0.1.0 → upgrade v0.1.1, config
  persists, renders on both — `install-upgrade.sh` exits PASS;
- the **macro-editor view-picker**, the **k6 perf test** (zero server-side git on render), and the
  **notes-XSS + CSP** check all pass (`dab22c5`).

### Runtime gates on Confluence DC 10.2.13

The plugin is **verified end-to-end live on a real Confluence Data Center 10.2.13** (Jakarta EE,
Postgres-backed compose stack). Runtime gates **3–5 are driven by containerised Playwright** in
`docker/e2e/c10-gates.spec.ts` (each captures a screenshot, `docker/e2e/c10-*.png`, a human can
eyeball). Gate **1** (plugin enables) is checked by the install/setup scripts (`up.sh`/`c10-gate.sh`);
gate **2** (Jakarta REST 200) is not asserted directly by those scripts but is exercised transitively —
GATE3's macro render cannot succeed unless `/resolve`+`/source` serve — and is covered directly by the
core/wrapper unit tests. Auth is HTTP Basic, re-enabled by the dev/test JVM flag above. All five are
covered — gates **3–5** by the live Playwright run, gates **1–2** by the install scripts + unit tests:

1. **Plugin enables** — UPM installs the (unsigned dev) JAR and reports it **enabled** with all its
   declared modules active (UPM's module tally is computed differently from the count of
   `atlassian-plugin.xml` elements, so the exact number isn't asserted).
2. **Jakarta REST** — `GET /rest/likec4/1.0/resolve` → `{sha}` and `GET /rest/likec4/1.0/source?path=ok`
   → 200 (the `jakarta.ws.rs` 3.1 resources serve; allowlist enforced).
3. **Macro renders** — a storage-format macro page renders the LikeC4 diagram: `.react-flow__node`
   present, `data-current-view=index`, viewport scaled (`c10-render.png`).
4. **Admin servlet** — the Jakarta `AdminServlet` returns **200** and renders the decorated config form
   at `/plugins/servlet/likec4/admin` (`c10-admin.png`).
5. **Macro editor** — the classic editor still exists on 10.2 (`#wysiwygTextarea_ifr`,
   `AJS.MacroBrowser`/`tinymce.confluence.MacroUtils`): the macro authors via the **native Macro
   Browser**, the custom **"Load views" picker** populates, and the published page renders
   (`c10-editor.png`, `c10-editor-rendered.png`).

### PostgreSQL — VERIFIED on the 10.2.13 stack

Confluence **10.2.13 on PostgreSQL** is verified end-to-end on this exact `docker/` stack, no longer
"by construction":
- **Setup wizard driven headlessly to RUNNING** on Postgres (licence → deployment → content → user
  management → administrator; the DB step auto-satisfied by the compose `ATL_JDBC_*` env). The exact
  POSTs are in the `up.sh` header (all LIVE-PROVEN). The SDK Developer timebomb licence validates on
  10.2.13 and the setup wizard is the same flow as on 9.2.
- **Postgres confirmed the live DB:** `confluence.cfg.xml` shows
  `hibernate.connection.url = jdbc:postgresql://postgres:5432/confluence` (driver `org.postgresql.Driver`,
  `PostgreSQLDialect`), and `psql -U confluence -d confluence \dt` lists the full set of Confluence
  tables (`AO_*`, `CONTENT`, `USERS`, …).
- **Plugin installed via UPM** (enabled), **admin configured** (`baseUrl=http://mockgitlab:8080`,
  `allowlist=["acme"]`), `/resolve` → `{sha}` and `/source?path=ok` → 200 (plugin reaches the mock
  over the compose network), and the **diagram RENDERS** — Playwright (`docker/e2e/c10-gates.spec.ts`)
  asserts `[data-testid="likec4-diagram"]` visible + `.react-flow__node` present. The admin servlet
  `/plugins/servlet/likec4/admin` returns **200**.

**Real production-only bug this surfaced + fixed (`boot-loader.js`).** The earlier render proofs ran on
amps `confluence:run` (**H2**), where web-resources are served **individually** — so the viewer
boot-loader could derive the ESM-entry URL from its own `/download/resources/...` src. On a *production*
install Confluence **batches** that resource into a `/download/batch/.../_/...` super-batch URL, and the
batch endpoint serves the loader's own JS for any sub-path — so the derived `main.js` URL returned the
boot-loader, not the React bundle, and **nothing rendered**. `boot-loader.js` now reconstructs the
absolute `/download/resources/<module-key>/likec4-web/assets/main.js` URL (robust to batched *and*
unbatched serving), mirroring what `editor-loader.js` already did. This is the one functional gap the
H2 proofs masked; it is now fixed and the diagram renders on the production Postgres stack.

### Licence + UPM-upload nuances (proven 2026-06-29)
- **Licence:** the server-ID is **not** enforced, but a **future `CreationDate` IS rejected** ("not a
  valid license key"). A P3H/future-dated key fails; an Atlassian *SDK Developer* timebomb (e.g. an
  amps home's `atlassian.license.message`) or a freshly-generated non-future-dated DC timebomb works.
- **UPM file-upload:** a clean Confluence DC 10.2 returns **403 "Plugins cannot be installed via
  upload"** unless `-Dupm.plugin.upload.enabled=true` is on the JVM; 10.2 additionally enforces app
  **signature verification** on uploads and **blocks HTTP Basic auth on API calls** by default.
  `compose.yaml` relaxes all three for the harness via `JVM_SUPPORT_RECOMMENDED_ARGS` — see
  "Dev/test-only JVM flags" above (test conveniences only; a production install uses a
  signed/Marketplace build + PAT/OAuth).

The Playwright e2e runs from the Plan 4 Playwright container only because this dev host lacks Chromium
system libraries (`libnss3` etc.) — it is *not* unproven.

## Boot outcome (compose stack)

**Confluence 10.2.13 boots and reaches `FIRST_RUN` on Postgres via direct JDBC** (compose `ATL_JDBC_*`
env), then `up.sh` drives the wizard headlessly to `RUNNING` once a valid licence is supplied.

Confluence 10.2 is heavier than 9.2, so the compose container now runs `mem_limit: 3g` with
`JVM_MAXIMUM_MEMORY` defaulting to 2048m (`.env.example` bumps the heap; Confluence DC recommends
≥6 GB — give it more on a larger host). For reference, an early boot snapshot on the original 9.2.x
image (`docker stats --no-stream`, 3.8 GB host, container `mem_limit: 2g`) was:
```
NAME                                  CPU %     MEM USAGE / LIMIT     MEM %
likec4-confluence-test-confluence-1   0.20%     814MiB / 2GiB         39.74%
likec4-confluence-test-postgres-1     0.02%     82.85MiB / 3.828GiB   2.11%
likec4-confluence-test-mockgitlab-1   0.00%     14.12MiB / 3.828GiB   0.36%
```

A fresh instance stops at `FIRST_RUN`; `up.sh` then completes the setup wizard headlessly. **Every
step is now LIVE-PROVEN on this Postgres stack** (2026-06-29): licence (`/setup/dosetuplicense.action`
+ `confLicenseString` + scraped `atl_token`) → deployment type (`setupcluster.action`,
`newCluster=skipCluster`) → [DB auto-satisfied by `ATL_JDBC_*`] → content (`setupdata.action`,
`contentChoice=blank`) → user management (`setupusermanagementchoice.action`,
`userManagementChoice=internal`) → administrator (`setupadministrator.action`, which completes setup
and flips `/status` to `RUNNING`). See the `up.sh` header comment for the exact fields. No golden
snapshot is needed.

Teardown: `docker/down.sh` (`docker compose down --remove-orphans`) — all three containers and the
network removed. It deliberately does NOT pass `-v`: the compose file declares no named volumes today,
so `-v` would be a no-op now but would silently wipe a future persisted volume (see the `down.sh`
header comment).

## Plugin runtime wiring (final-review fixes)

The first plugin build compiled the wrapper but would not have *wired* at runtime. The Plan 4 final
review caught this (no compiler/boot check surfaces it — only a live install would). Fixed in the
JAR and verified by inspecting its contents:
- **Spring-Scanner index generated** (`atlassian-spring-scanner-maven-plugin` + `-runtime` provided)
  → `META-INF/plugin-components/component-confluence` is present, so the `@ConfluenceComponent`
  macro/REST/admin beans register.
- **Host OSGi services imported into the plugin context** → the SAL beans (`PluginSettingsFactory`,
  `UserManager`, `ApplicationProperties`, `WebSudoManager`) via `<component-import>` declarations in
  `atlassian-plugin.xml`, and `PageBuilderService` via a spring-scanner `@ComponentImport` on the macro
  constructor.
- **Plugin key resolved** (`<atlassian.plugin.key>` = `com.phrontizo.confluence.likec4-confluence`)
  → matches the macro's `requireWebResource(...)`, so the JS bundle actually loads.
- **`compressResources=false`** → Vite's already-minified JS ships verbatim (no 0-byte `-min.js`).

**Was CI-gated; now proven live (see next section).** Originally the only end-to-end runtime check
was a live install onto a set-up Confluence + Playwright. With the host RAM raised to 8 GB this was
actually run — and it surfaced (and fixed) a real runtime DI bug the JAR-level checks could not.

## Original amps `confluence:run` dev workflow (historical)

> Historical record of the original Confluence 9.2 / amps dev workflow, kept as the as-built proof
> log. The **current** verified harness is the `docker compose` 10.2.13 stack and the runtime gates
> above. (The build's `productVersion` is now 10.2.13 on amps 9.12.5, but `confluence:run` itself
> still boots the pre-baked H2 test-resources home — it is NOT the verified harness; see the WALL
> section. The `confluence.version=9.2.20` figures below are from the original run.)

Originally proven live with **amps `confluence:run`** (downloads Confluence 9.2.20, applies a built-in
**development licence**, auto-sets-up the instance, installs the plugin — no setup wizard / timebomb
licence needed) plus the mock-GitLab and QuickReload. From the repo root:

```
# mock-GitLab on :8099 (serves the two GitLab endpoints from docker/mock-gitlab/repos)
REPOS_DIR="$PWD/docker/mock-gitlab/repos" PORT=8099 node docker/mock-gitlab/server.mjs &
# Confluence + plugin + dev licence. NOTE: pipe `tail -f /dev/null` into it, else amps treats
# stdin EOF as Ctrl-C and shuts down the instant it becomes ready.
tail -f /dev/null | ./mvnw -B confluence:run -DskipTests
# QuickReload hot-reloads on `rm -rf target/classes/likec4-web && ./mvnw -o package`.
# NEVER run `mvn clean` — it deletes target/confluence/home and kills the running instance.
```

**Proven live in Confluence 9.2.20** (admin/admin, http://localhost:1990/confluence):
- `POST /rest/likec4/1.0/admin {baseUrl,token,allowlist}` → `{"ok":true}`
- `GET /rest/likec4/1.0/resolve?project=acme/architecture&ref=main` → `{"sha":"cd0c7dac…"}` (plugin → mock)
- `GET /rest/likec4/1.0/source?…&path=ok` → `{"sha":…,"files":{model/spec/views.likec4 + .likec4/index.likec4.snap}}`
  — only LikeC4 files delivered (§8 file-type guarantee); snapshot included.
- `GET …/resolve?project=secret/repo` → **403 `{"error":"not allowed"}`** (allowlist enforced live).
- The macro emits `<div class="likec4-diagram" data-project="acme/architecture" …>` on a real page.
- **Admin config page** (`/plugins/servlet/likec4/admin`, `AdminServlet`): renders the config form in
  native atl.admin chrome, admin-gated (anon → 302), and the **in-browser save persists**
  (`admin-form.spec.ts`, `admin-chrome.spec.ts`, `3444d62` — fixed a real context-path bug).
- **Macro insert + view-picker** (§6, `macro-native.spec.ts` + `editor-loadviews.spec.ts`): the macro
  **inserts via the native macro browser** and renders (`3444d62` — fixed real `insertMacro`
  `{macro,contentId}` envelope + broken-icon bugs); the macro-browser override mounts the picker, the
  view dropdown populates (index + sys_detail), live preview renders, and the chosen view is written
  back into the `data-view` param. The native-UI macro-insertion gesture is itself browser-driven —
  no longer a by-construction claim.

**The runtime DI fix** (commit `plan4: fix plugin runtime DI …`): JAX-RS resources are instantiated by
Jersey/HK2, which cannot resolve Spring/SAL beans (`UnsatisfiedDependencyException`). Fixed by
declaring `AdminConfig` + `SourceServiceProvider` as `atlassian-plugin.xml` `<component>`s with
`<component-import>` for `PluginSettingsFactory` + `UserManager`, each exposing a static `INSTANCE`
set in its `@Inject` constructor; the plain-Jersey REST resources read `getInstance()`.

## Full browser pixel-render — PROVEN

The last gap is now closed: **the LikeC4 diagram renders in a live Confluence 10.2.13 page**
(`docker/e2e/c10-gates.spec.ts` GATE3 on the compose stack, containerised Playwright →
`.react-flow__node` visible, `[data-testid="likec4-diagram"]` visible). Closing it required wiring
the web-resource AND fixing four real browser-integration bugs that no build/server check could
surface:

1. **Web-resource `<script>` injection** — the `<web-resource>` now ships `boot-loader.js` (a classic
   script Confluence injects) which loads the ESM entry as `<script type="module">`; the rest of the
   bundle (stable `assets/main.js` + hashed lazy `worker`/`Diagram` chunks) is served as a downloadable
   directory so the entry's relative imports + the module worker URL resolve.
2. **REST context path** — `restClient` used `/rest/…` (root); a real Confluence is at a context path
   (`/confluence/rest/…`). Now derived from `window.AJS.contextPath()` (guarded so Node/vitest +
   the Vite dev-server, where there's no AJS, fall back to `''`).
3. **Absolute asset paths** — Vite default `base: '/'` made the worker/lazy-chunk URLs resolve from
   the server root (404). Fixed with `base: './'` so they resolve from the deep resource URL.
4. **`React.useEffectEvent`** — `@xyflow/react` (bundled in `likec4` 1.58.0) calls an experimental
   React API that never shipped in React 19 stable; polyfilled in `boot.tsx` before the renderer
   chunk lazy-loads.

Run it: against the **compose** stack, `docker/up.sh` then `docker/e2e/c10-gate.sh` — it (re)installs
the JAR and runs **GATE3**, the canonical render proof, alongside GATE4/5. The whole pipeline — macro →
web-resource → ESM bundle → `/resolve` + `/source` (plugin → GitLab) → Web Worker compute →
`likec4/react` render — is proven end-to-end live.

## §11 performance test — authored

`docker/perf/` is the §11 performance test: a **k6** driver (`load-test.js`) hammers `/resolve` +
`/source` with N concurrent VUs while `docker/perf/run.sh` reads the mock-GitLab request counters
(added to `mock-gitlab/server.mjs`: `GET /__count` / `GET /__reset`) and asserts that server-side
caching + single-flight coalesced the load — the GitLab `archive` endpoint is hit **at most once per
`(project, sha, path)`**, far fewer than the number of `/source` requests. The page-render path does
**zero** git work by construction (the macro emits only a `<div>`; all git work is in the
browser-driven REST calls the counters measure). Details + how-to in `docker/perf/README.md`.
(Restart the live mock after pulling, so the `/__count` + `/__reset` endpoints exist.)

## §11 install/upgrade test — RUN-verified live via UPM

`docker/install-upgrade.sh` is the §11 install/upgrade test: build + UPM-install the prior plugin
version → configure admin + seed a macro page → assert it renders → bump the pom `<version>`,
rebuild, **UPM-upgrade** → re-assert the macro page still renders **and** the admin config
(`baseUrl`/`allowlist`) persisted across the upgrade. Clear `echo` PASS/FAIL gates, `set -euo
pipefail`, and the pom is restored on exit.

**RUN-verified live (2026-06-28)** end-to-end against the dev amps `confluence:run` instance at
`http://localhost:1990/confluence` via the **UPM REST API** — `install-upgrade.sh` exits
`INSTALL/UPGRADE: PASS`. Proven live:
- **Fresh install:** UPM-`DELETE` the pre-installed copy → `/plugins/servlet/likec4/admin` + `/rest/likec4/1.0/resolve` both **404** → UPM-`POST` the `0.1.0-SNAPSHOT` JAR → enabled, admin servlet **200**, REST works.
- **Render:** the seeded `likec4-diagram` macro page renders live — Playwright `.react-flow__node` **= 2**, `[data-testid="likec4-diagram"]` visible (`docker/e2e` container).
- **Upgrade:** bump pom → rebuild → UPM-`POST` the `0.1.1-SNAPSHOT` JAR over the existing (same key); plugin reports `0.1.1-SNAPSHOT` enabled.
- **Persistence:** without reconfiguring, `GET /rest/likec4/1.0/admin` still returns `{"allowlist":["acme"],"baseUrl":"http://localhost:8099","tokenSet":true}` AND the same page still renders (`.react-flow__node` = 2) on `0.1.1`.

`BASE` is parameterised so the same script targets **either** backend — `BASE=http://localhost:1990/confluence`
(dev amps, default) **or** `BASE=http://localhost:8090` (the `docker compose` stack that `up.sh` brings
up unattended; root context, no `/confluence` path). **For the compose backend also set
`MOCK_BASEURL=http://mockgitlab:8080`** (the compose service DNS name): Confluence there runs in a
container, so the default `http://localhost:8099` would resolve to the container itself and the diagram
would never load — and because step 4's macro-div check is zero-git it would still PASS, so a wrong value
fails *silently*. Because the dev amps instance **pre-installs** the plugin, the script
`UNINSTALL_FIRST=true` (default) removes it before installing, so a genuine *fresh* install is exercised
on either target.

QuickReload caveat (amps only): bumping the version changes the JAR filename, so QuickReload may
auto-deploy the new JAR a moment before the script's UPM `POST` — same end state. Re-creating an
*existing* filename does not re-trigger it, and UPM will not *downgrade* in place, so the script's
clean restore uses uninstall→install. Two UPM media-type gotchas the script encodes: the token
comes from the `upm-token` response header of `GET …/?Accept: application/vnd.atl.plugins.installed+json`,
and the plugin-detail/poll endpoint needs `Accept: application/vnd.atl.plugins.plugin+json`
(`application/json` **406**s).

## External Postgres for `confluence:run` (residual R0) — WALL

> Historical: this wall was investigated against the original **amps `confluence-maven-plugin:9.2.6`**
> / Confluence 9.2 dev workflow. It is the reason the verified Postgres harness is the `docker compose`
> 10.2.13 stack (`up.sh`), not `confluence:run`. Kept as the reproducible evidence.

**Status: WALL.** The amps `confluence-maven-plugin:9.2.6` `confluence:run` (dev) goal **cannot** run
Confluence 9.2.20 against an external database. Its `<dataSource>` element only ever becomes a
*secondary* Tomcat JNDI datasource, and Confluence 8.0+ no longer reads its **main** DB from JNDI.
The main DB **and** the auto-generated timebomb licence both come from a pre-baked **H2** home that
the run goal extracts — there is no amps knob to make `run` provision or point at Postgres. (Running
on real Postgres is the separate `docker compose` path below.)

### What was attempted (a faithful, real run — not faked)

`pom.xml` profile `postgres` (retained) configures the plugin with the exact amps `DataSource` bean
fields — confirmed from `amps-maven-plugin-9.2.6` `META-INF/maven/plugin.xml` + the decompiled
`com.atlassian.maven.plugins.amps.DataSource` (these `<dataSource>` child-element names are unchanged
in the amps **9.12.5** the build now pins, which the `pom.xml` comment re-confirms — this WALL section
records the original 9.2.6 investigation):

```xml
<dataSource>
  <url>jdbc:postgresql://localhost:5432/confluence</url>
  <driver>org.postgresql.Driver</driver>
  <username>confluence</username>
  <password>confluence</password>
  <libArtifacts>
    <libArtifact><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><version>42.7.3</version></libArtifact>
  </libArtifacts>
</dataSource>
```

Run (2026-06-29), against the compose Postgres published on `localhost:5432`
(`docker compose -f compose.yaml -f compose.pg-publish.yaml up -d postgres`), fresh home forced first:

```
rm -rf target/confluence/home
tail -f /dev/null | ./mvnw -B -Ppostgres confluence:run -DskipTests
```

`productLicense` left **UNSET** → amps used its built-in timebomb licence. Confluence reached
`{"state":"RUNNING"}` ("confluence started successfully in 107s") with **no** manual licence.

### Proof it ran on H2, not Postgres

- Freshly-extracted `target/confluence/home/confluence.cfg.xml` (zero `postgres` references; the
  baked `atlassian.license.message` is the auto timebomb licence):
  ```
  <setupType>install</setupType>
  hibernate.connection.driver_class = org.h2.Driver
  hibernate.connection.username     = sa
  hibernate.dialect                 = com.atlassian.confluence.impl.hibernate.dialect.H2V4200Dialect
  ```
- The external Postgres stayed **empty** — Confluence created zero tables:
  ```
  $ docker exec …-postgres-1 psql -U confluence -d confluence -c '\dt'
  Did not find any relations.
  $ … -tAc "select count(*) … table_schema='public'"  ->  0
  ```
- Cargo Tomcat `…/cargo-confluence-home/conf/context.xml` holds only the `UserTransaction` resource —
  **no** Postgres JNDI `<Resource>` was emitted.
- The `<libArtifacts>` driver *was* honored — `postgresql-42.7.3.jar` is copied into Tomcat
  `common/lib/` — but nothing consumes it; it is inert.

### Exactly why (amps 9.2.6 internals)

1. The mojo `<dataSource>` → `product.setDataSources([ds])` (`AbstractProductHandlerMojo`).
2. `AbstractWebappProductHandler.getJndiDataSources()` **filters to datasources whose `<jndi>` is
   non-blank**, then `generateJndiDataSourceSystemProperties()` turns each into a
   `cargo.datasource.datasource` (a Tomcat JNDI resource). Our direct-JDBC datasource has **no**
   `<jndi>`, so it is dropped entirely — hence the empty `context.xml`.
3. Even *with* a `<jndi>` name it would only add a Tomcat JNDI resource. The **main** Confluence DB
   connection lives in `confluence.cfg.xml`, which the run goal takes verbatim from the pre-baked
   `com.atlassian.confluence.plugins:confluence-plugin-test-resources` home zip
   (`AbstractProductHandler.getProductHomeData` → `getTestResourcesArtifact`) — that home is H2 and
   carries the timebomb licence. `ConfluenceProductHandler` never rewrites the DB connection; it only
   (a) adds the embedded H2/HSQLDB driver as a container dependency
   (`ConfluenceEmbeddedDatabaseResolver`) and (b) overwrites the licence property *only if a user
   licence is set* (`ConfluenceLicenseConfigurer`, gated on `product.hasUserConfiguredLicense()`).
4. Confluence 8.0+ dropped the JNDI-datasource option for its main DB, so even the JNDI route (3)
   could not repoint the main DB on 9.2.20.

`rm -rf target/confluence/home` does **not** change this — it just forces re-extraction of the same
pre-baked H2 home.

### What WOULD run Confluence on Postgres (and why it's a different harness)

Real Postgres requires the **`docker compose` path** (the official `atlassian/confluence:10.2.13`
image — `docker/compose.yaml` + `docker/.env`, `CONFLUENCE_PORT=8090`) with the Postgres datasource
configured in the *container* (the compose `ATL_JDBC_*` env, direct JDBC) and the setup wizard
completed. `docker/up.sh` automates exactly this end-to-end — health-gate → headless wizard (licence
+ post-licence steps) → UPM install + configure → seed page — gated only on a valid external timebomb
DC licence in `docker/.timebomb-license`. The amps dev-run is **H2-only by design**; `up.sh` is the
Postgres harness.

The `postgres` profile is kept as the reproducible evidence of this wall, and because the same
`<dataSource>` also feeds the amps **`prepare-database`** goal (`PrepareDatabaseMojo`), which *can*
DROP/CREATE the Postgres DB + user and import a SQL dump — but still does not point `run` at it.
