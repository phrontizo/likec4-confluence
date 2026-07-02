# LikeC4 Confluence — Docker Test Harness Implementation Plan

> **Superseded-platform note (post-2026-06-29):** this is a historical planning record. It was written
> for a Confluence **9.2.20** compose stack (`atlassian/confluence:9.2.20`, the `~/.local/bin/jmvn`
> build, `docker/e2e/run.sh`); the product was later retargeted to Confluence **10.2.13 / Jakarta EE /
> Java 21**, built with the committed **`./mvnw`**, and the required live gate is now
> `docker/e2e/c10-gate.sh` (GATE3/4/5). Any image, build command, or script name below reflects that
> earlier era — for the current facts see **README.md**, **CLAUDE.md**, and **docker/README.md**. Kept as
> the original plan record.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the heavy/integration layer (spec §11 heavy layer + §12): package the Confluence plugin JAR (which **compiles** the Plan 3 SDK-gated wrapper for the first time), a tiny mock-GitLab stub container, a `docker compose` stack (Confluence 9.2.20 + Postgres + mock-GitLab), and the harness that brings it up, health-gates, applies a timebomb licence, seeds fixtures, installs the plugin via UPM, runs the Plan 2 Playwright e2e against the live page, and tears down.

**Architecture:** A `docker/` harness directory plus a frontend-build hook added to the root `pom.xml`. The crown jewel is `jmvn package`: it runs `npm run build` (Plan 2 frontend → web-resource) and compiles the Plan 3 wrapper (`src/main/java/com/phrontizo/confluence/likec4/**`) against the real Confluence 9.2.20 APIs (resolvable, public) — converting "authored/gated" into "compiles green" and producing a deployable JAR. The mock-GitLab stub serves the two GitLab endpoints `GitLabSourceClient` calls, from fixture repos. The compose stack wires Confluence → Postgres + the mock. Playwright runs **inside a container** (sidestepping the host's missing Chromium libs).

**Tech Stack:** Docker 26 + Compose 2.26, Maven via `~/.local/bin/jmvn` (JDK 21), amps `maven-confluence-plugin` (Confluence 9.2.20), Node/npm (frontend build), `atlassian/confluence:9.2.20` + `postgres:15` images, `mcr.microsoft.com/playwright` for e2e, a small mock-GitLab HTTP server.

**Execution tiers (per the chosen scope — "run all + attempt Confluence boot"):**
- **Executed here (green gates):** the plugin JAR build (Task 1 — verifies the Plan 3 wrapper compiles + produces the JAR), the mock-GitLab stub (Task 2 — built + curl-verified), Postgres + mock-GitLab containers up + healthchecked (Task 3).
- **Best-effort, RAM-gated (host has 3.8 GB total / ~1.8 GB free; Confluence DC wants ~2 GB+ for its JVM):** booting `atlassian/confluence:9.2.20`, UPM install, and the live Playwright e2e (Tasks 4–6). If Confluence OOMs/times out, the harness + specs are committed and the live run is documented as RAM-gated (the same honest gating pattern as Plan 2's CI-gated e2e). The non-Confluence pieces remain the proven deliverables.

---

## Scope

**In scope:** spec §12 prerequisites (timebomb licence, golden snapshot approach), §11 heavy layer (compose stack, mock-GitLab stub, bring-up/health-gate/seed/UPM-install/teardown harness, install/upgrade, e2e). The plugin JAR build is the high-value addition that closes the Plan 3 gate.

**Out of scope / deferred:** the §11 performance layer (k6/Gatling) is authored as a script stub but not driven (needs a stable booted Confluence); the recorded-response GitLab **contract** test (§14) is noted but secondary. The Plan 3 `likec4-core` follow-ups (bound disk cache, circuit-breaker, admin servlet, `sanitizeProject`) are tracked in `likec4-core/README.md` and addressed opportunistically if the wrapper build surfaces them.

**Carried context:**
- Plan 2 frontend builds (Vite) from `src/main/frontend` → `src/main/resources/likec4-web/` (gitignored). The web-resource serves that dir.
- Plan 3 `likec4-core` is installed to `~/.m2` (`com.phrontizo.confluence:likec4-source-core:0.1.0-SNAPSHOT`); the root plugin depends on it.
- The mock-GitLab stub must match `GitLabSourceClient`: `GET /api/v4/projects/:enc/repository/commits/:ref` → `{"id":"<sha>"}` and `GET /api/v4/projects/:enc/repository/archive.tar.gz?sha=&path=` → a tar.gz whose entries are `<top>/<path>/<file>`.

---

## File Structure

- Modify: `pom.xml` — add the frontend build hook (exec-maven-plugin: `npm ci` + `npm run build` in `generate-resources`) and any plugin deps the wrapper compile needs (e.g. `atlassian-spring-scanner-annotation`, `jackson` for REST JSON). Finalise the web-resource.
- Create: `docker/mock-gitlab/` — `server.mjs` (Node http server serving the two endpoints from fixture repos), `Dockerfile`, `repos/acme/architecture/{ok,broken}/…` (reuse Plan 2 fixtures).
- Create: `docker/compose.yaml` — `postgres`, `mockgitlab`, `confluence` services + healthchecks + mem limits.
- Create: `docker/.env.example` — image tags, ports, mem.
- Create: `docker/up.sh` — compose up; health-gate Confluence `/status`; apply timebomb licence (REST); seed a space + a page with the macro; install the plugin JAR via UPM REST.
- Create: `docker/down.sh` — `docker compose down -v` teardown.
- Create: `docker/seed-page.xml` (or inline) — storage-format body embedding the `likec4-diagram` macro pointed at the mock.
- Create: `docker/e2e/` — `playwright.config.ts` + `live.spec.ts` (loads the seeded Confluence page, asserts the diagram renders), run via the Playwright container.
- Create: `docker/README.md` — how to run, the RAM caveat, and the timebomb-licence/golden-snapshot notes.

---

## Task 1: Build the plugin JAR (compiles the Plan 3 wrapper) — CROWN JEWEL, executed

This packages the Confluence plugin with amps, which **compiles `src/main/java/com/phrontizo/confluence/likec4/**` against the real Confluence 9.2.20 APIs** (public, resolvable) — the first real compile of the Plan 3 wrapper. It also runs the Plan 2 frontend build into the web-resource. This is an iterative build task: add the known config, run `jmvn package`, and fix compile/descriptor errors against real output.

**Files:**
- Modify: `pom.xml` (root)

- [ ] **Step 1: Add the frontend build hook + spring-scanner annotation dep to `pom.xml`**

In `<dependencies>` add (the wrapper uses `@Scanned`/`@Named`/`@Inject`):
```xml
    <dependency>
      <groupId>com.atlassian.plugin</groupId>
      <artifactId>atlassian-spring-scanner-annotation</artifactId>
      <version>${atlassian.spring.scanner.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>javax.inject</groupId>
      <artifactId>javax.inject</artifactId>
      <version>1</version>
      <scope>provided</scope>
    </dependency>
```

In `<build><plugins>` add the frontend build (runs before resources are packaged so the Vite output lands in the web-resource):
```xml
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.1.1</version>
        <executions>
          <execution>
            <id>frontend-install</id>
            <phase>generate-resources</phase>
            <goals><goal>exec</goal></goals>
            <configuration>
              <executable>npm</executable>
              <workingDirectory>${project.basedir}/src/main/frontend</workingDirectory>
              <arguments><argument>ci</argument></arguments>
            </configuration>
          </execution>
          <execution>
            <id>frontend-build</id>
            <phase>generate-resources</phase>
            <goals><goal>exec</goal></goals>
            <configuration>
              <executable>npm</executable>
              <workingDirectory>${project.basedir}/src/main/frontend</workingDirectory>
              <arguments><argument>run</argument><argument>build</argument></arguments>
            </configuration>
          </execution>
        </executions>
      </plugin>
```

- [ ] **Step 2: Build the plugin JAR**

Run: `~/.local/bin/jmvn -B package` (from the repo root). This is ONLINE and SLOW the first time — it downloads the Confluence 9.2.20 dependency tree (hundreds of MB) + amps. Expect several minutes.
Expected eventual outcome: `BUILD SUCCESS` and a JAR under `target/` (e.g. `target/likec4-confluence-0.1.0-SNAPSHOT.jar`).

- [ ] **Step 3: Fix compile/descriptor errors iteratively (this is the point of the task)**

The Plan 3 wrapper was authored but never compiled. Read each error and fix minimally; likely ones and their fixes:
- **Missing symbol for a Confluence/SAL/JAX-RS type** → add the matching `provided` dependency (the `confluence` BOM usually transitively provides it; if not, add e.g. `javax.inject`, `com.atlassian.sal:sal-api`, `jsr311-api`, `atlassian-plugins-webresource` — most are already in the Plan 3 pom).
- **`PageBuilderService` / `requireWebResource` signature mismatch** → confirm the method against the resolved `atlassian-plugins-webresource` API and adjust the call.
- **`UserManager.getRemoteUserKey()` / `isSystemAdmin(UserKey)` mismatch** → confirm against the resolved `sal-api` and adjust (some versions use `UserProfile`/`UserKey`).
- **spring-scanner processing error** → ensure `atlassian-spring-scanner-annotation` is present; amps 9.x runs the scanner automatically.
- **web-resource descriptor invalid** (`atlassian-plugin.xml` `<resource location="likec4-web/">`) → if amps rejects a bare directory resource, point it at the built entry or add a `<directory>` resource; the goal is a valid descriptor that ships the `likec4-web/assets` dir.
Keep fixes minimal and faithful to the Plan 3 design. If a Confluence API genuinely differs from what the wrapper assumed, fix the wrapper (and note it). Re-run `jmvn -B package` until `BUILD SUCCESS`. After the first full download, `~/.local/bin/jmvn -B -o package` is faster (offline) for re-runs.

- [ ] **Step 4: Verify the JAR contents**

Run:
```bash
JAR=$(ls target/*.jar | grep -v sources | head -1)
~/.local/jdk-21/bin/jar tf "$JAR" | grep -E "atlassian-plugin.xml|likec4-web/|com/phrontizo/confluence/likec4/.*class" | head
```
Expected: the JAR contains `atlassian-plugin.xml`, the compiled wrapper classes, and the `likec4-web/` web-resource (the Vite-built assets). Record the JAR path — Task 4 installs it via UPM.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java src/main/resources/atlassian-plugin.xml
git commit -m "plan4: build Confluence plugin JAR (compiles Plan 3 wrapper) + frontend build hook"
```
End every commit with a blank line then:
`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

## Task 2: Mock-GitLab stub — executed (build + curl-verified)

A dependency-free Node http server that serves the two endpoints `GitLabSourceClient` calls, from fixture repos. It builds the archive tar.gz in-process (a tiny ustar writer) so `GitLabArchiveExtractor` (commons-compress) can read it.

**Files:**
- Create: `docker/mock-gitlab/server.mjs`
- Create: `docker/mock-gitlab/Dockerfile`
- Create: `docker/mock-gitlab/repos/acme/architecture/ok/{spec,model,views}.likec4` + `.likec4/index.likec4.snap`
- Create: `docker/mock-gitlab/repos/acme/architecture/broken/model.likec4`

- [ ] **Step 1: Create `docker/mock-gitlab/server.mjs`**

```js
import { createServer } from 'node:http'
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { gzipSync } from 'node:zlib'

const REPOS = process.env.REPOS_DIR || '/repos'
const PORT = process.env.PORT || 80

const KEEP = (n) => n.endsWith('.c4') || n.endsWith('.likec4') || n.endsWith('.likec4.snap')

function fakeSha(input) {
  let h = 0x811c9dc5, out = ''
  for (let i = 0; i < 40; i++) {
    for (let j = 0; j < input.length; j++) { h ^= input.charCodeAt(j) + i; h = Math.imul(h, 0x01000193) }
    out += ((h >>> 28) & 0xf).toString(16)
  }
  return out
}

function walk(base) {
  const out = []
  const rec = (cur) => {
    for (const name of readdirSync(cur)) {
      const full = join(cur, name)
      if (statSync(full).isDirectory()) rec(full)
      else out.push({ rel: relative(base, full).split(sep).join('/'), content: readFileSync(full, 'utf8') })
    }
  }
  rec(base)
  return out
}

// Minimal ustar tar writer (dep-free).
function tarHeader(name, size) {
  const b = Buffer.alloc(512)
  b.write(name, 0, 100)
  b.write('0000644', 100, 7); b.write('0000000', 108, 7); b.write('0000000', 116, 7)
  b.write(size.toString(8).padStart(11, '0'), 124, 11)
  b.write('00000000000', 136, 11)
  b.write('        ', 148, 8)
  b.write('0', 156, 1)
  b.write('ustar\x0000', 257, 8)
  let sum = 0; for (let i = 0; i < 512; i++) sum += b[i]
  b.write(sum.toString(8).padStart(6, '0') + '\x00 ', 148, 8)
  return b
}
function tarGz(entries) {
  const chunks = []
  for (const { name, content } of entries) {
    const data = Buffer.from(content, 'utf8')
    chunks.push(tarHeader(name, data.length), data)
    const pad = (512 - (data.length % 512)) % 512
    if (pad) chunks.push(Buffer.alloc(pad))
  }
  chunks.push(Buffer.alloc(1024))
  return gzipSync(Buffer.concat(chunks))
}

createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost')
  const parts = url.pathname.split('/').filter(Boolean) // api v4 projects :enc repository ...
  const enc = parts[3]
  const project = enc ? decodeURIComponent(enc) : ''
  const ref = url.searchParams.get('ref') || (parts.includes('commits') ? parts[parts.indexOf('commits') + 1] : 'main')
  if (url.pathname.includes('/repository/commits/')) {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ id: fakeSha(`${project}@${ref}`) }))
    return
  }
  if (url.pathname.includes('/repository/archive.tar.gz')) {
    const sha = url.searchParams.get('sha') || fakeSha(`${project}@${ref}`)
    const base = join(REPOS, project)
    if (!existsSync(base)) { res.writeHead(404); res.end('no repo'); return }
    const top = `${project.split('/').pop()}-${sha}`
    const entries = walk(base).filter((f) => KEEP(f.rel)).map((f) => ({ name: `${top}/${f.rel}`, content: f.content }))
    res.writeHead(200, { 'content-type': 'application/gzip' })
    res.end(tarGz(entries))
    return
  }
  res.writeHead(404); res.end('not found')
}).listen(PORT, () => console.log(`mock-gitlab on ${PORT}, repos=${REPOS}`))
```

- [ ] **Step 2: Create fixtures** — copy Plan 2's compute fixtures into the mock repos

```bash
mkdir -p docker/mock-gitlab/repos/acme/architecture
cp -r src/main/frontend/test/fixtures/likec4/target  docker/mock-gitlab/repos/acme/architecture/ok
cp -r src/main/frontend/test/fixtures/likec4/broken  docker/mock-gitlab/repos/acme/architecture/broken
```
Confirm `ok/` has the three `.likec4` files + `.likec4/index.likec4.snap`; `broken/` has `model.likec4`.

- [ ] **Step 3: Create `docker/mock-gitlab/Dockerfile`**

```dockerfile
FROM node:20-alpine
WORKDIR /app
COPY server.mjs /app/server.mjs
COPY repos /repos
ENV REPOS_DIR=/repos PORT=80
EXPOSE 80
CMD ["node", "server.mjs"]
```

- [ ] **Step 4: Verify the stub locally (executed)** — run with node, curl both endpoints

```bash
cd docker/mock-gitlab
REPOS_DIR="$PWD/repos" PORT=8099 node server.mjs &
MOCK=$!
sleep 1
echo "--- commits ---"
curl -s "http://localhost:8099/api/v4/projects/acme%2Farchitecture/repository/commits/main"
echo; echo "--- archive entries (path=ok) ---"
curl -s "http://localhost:8099/api/v4/projects/acme%2Farchitecture/repository/archive.tar.gz?sha=deadbeef&path=ok" | tar tz 2>/dev/null
kill $MOCK
```
Expected: the commits call returns `{"id":"<40-hex>"}`; the archive untar lists entries like `architecture-deadbeef/ok/model.likec4`, `…/ok/.likec4/index.likec4.snap`, etc. (so `GitLabArchiveExtractor` with `path=ok` will strip `<top>/ok/` and yield `model.likec4`, `.likec4/index.likec4.snap`). If `tar tz` shows a malformed archive, the ustar header is wrong — fix the writer before continuing.

- [ ] **Step 5: Build the image (executed)**

Run: `docker build -t likec4-mock-gitlab:dev docker/mock-gitlab`
Expected: image builds.

- [ ] **Step 6: Commit**

```bash
git add docker/mock-gitlab
git commit -m "plan4: mock-GitLab stub (commits + archive.tar.gz) with fixtures"
```

---

## Task 3: docker compose stack — Postgres + mock executed; Confluence authored

**Files:**
- Create: `docker/compose.yaml`
- Create: `docker/.env.example`

- [ ] **Step 1: Create `docker/.env.example`**

```env
CONFLUENCE_IMAGE=atlassian/confluence:9.2.20
POSTGRES_IMAGE=postgres:15
CONFLUENCE_PORT=8090
# RAM is tight (host ~3.8GB). Keep Confluence heap modest; boot is best-effort.
JVM_MINIMUM_MEMORY=512m
JVM_MAXIMUM_MEMORY=1536m
POSTGRES_PASSWORD=confluence
```

- [ ] **Step 2: Create `docker/compose.yaml`**

```yaml
name: likec4-confluence-test
services:
  postgres:
    image: ${POSTGRES_IMAGE:-postgres:15}
    environment:
      POSTGRES_DB: confluence
      POSTGRES_USER: confluence
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-confluence}
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U confluence"]
      interval: 5s
      timeout: 3s
      retries: 20
    tmpfs: [/var/lib/postgresql/data]   # ephemeral; faster + lower disk for tests

  mockgitlab:
    image: likec4-mock-gitlab:dev
    build: ./mock-gitlab
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost/api/v4/projects/acme%2Farchitecture/repository/commits/main"]
      interval: 5s
      timeout: 3s
      retries: 10

  confluence:
    image: ${CONFLUENCE_IMAGE:-atlassian/confluence:9.2.20}
    depends_on:
      postgres: { condition: service_healthy }
      mockgitlab: { condition: service_healthy }
    environment:
      ATL_DB_TYPE: postgresql
      ATL_DB_DRIVER: org.postgresql.Driver
      ATL_JDBC_URL: jdbc:postgresql://postgres:5432/confluence
      ATL_JDBC_USER: confluence
      ATL_JDBC_PASSWORD: ${POSTGRES_PASSWORD:-confluence}
      JVM_MINIMUM_MEMORY: ${JVM_MINIMUM_MEMORY:-512m}
      JVM_MAXIMUM_MEMORY: ${JVM_MAXIMUM_MEMORY:-1536m}
      ATL_TOMCAT_PORT: "8090"
    ports:
      - "${CONFLUENCE_PORT:-8090}:8090"
    mem_limit: 2g
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8090/status"]
      interval: 15s
      timeout: 10s
      retries: 40
      start_period: 180s
```

- [ ] **Step 3: Bring up the light services and health-check them (executed)**

Run:
```bash
cd docker
cp -n .env.example .env
docker compose up -d postgres mockgitlab
# wait for health
for i in $(seq 1 30); do
  pg=$(docker inspect -f '{{.State.Health.Status}}' $(docker compose ps -q postgres) 2>/dev/null)
  mk=$(docker inspect -f '{{.State.Health.Status}}' $(docker compose ps -q mockgitlab) 2>/dev/null)
  echo "postgres=$pg mock=$mk"; [ "$pg" = healthy ] && [ "$mk" = healthy ] && break; sleep 3
done
# verify mock through the container network from the host-published… (mock has no published port; check via docker exec)
docker compose exec -T mockgitlab wget -qO- "http://localhost/api/v4/projects/acme%2Farchitecture/repository/commits/main"; echo
docker compose down
```
Expected: both reach `healthy`; the mock returns the commit JSON. This proves the stack's data plane (DB + mock) works independently of the heavy Confluence boot.

- [ ] **Step 4: Commit**

```bash
git add docker/compose.yaml docker/.env.example
git commit -m "plan4: docker compose stack (postgres + mock-gitlab + confluence)"
```

---

## Task 4: Harness scripts — authored (Confluence-dependent steps best-effort)

Bring-up/health-gate/seed/install + teardown. The Confluence-touching steps (licence, seed, UPM install) only run once Confluence is healthy (Task 6 attempts that boot); they are authored and committed regardless.

**Files:**
- Create: `docker/up.sh`
- Create: `docker/down.sh`
- Create: `docker/seed-page.json`

- [ ] **Step 1: Create `docker/down.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
docker compose down -v --remove-orphans
echo "torn down."
```

- [ ] **Step 2: Create `docker/seed-page.json`** (Confluence storage-format body embedding the macro, pointed at the mock)

```json
{
  "type": "page",
  "title": "LikeC4 Test Diagram",
  "space": { "key": "LIKEC4" },
  "body": {
    "storage": {
      "value": "<ac:structured-macro ac:name=\"likec4-diagram\"><ac:parameter ac:name=\"project\">acme/architecture</ac:parameter><ac:parameter ac:name=\"ref\">main</ac:parameter><ac:parameter ac:name=\"path\">ok</ac:parameter><ac:parameter ac:name=\"view\">index</ac:parameter></ac:structured-macro>",
      "representation": "storage"
    }
  }
}
```

- [ ] **Step 3: Create `docker/up.sh`** (bring up, health-gate, licence, seed, install)

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
cp -n .env.example .env || true
BASE="http://localhost:${CONFLUENCE_PORT:-8090}"
AUTH="admin:admin"

echo ">> building + starting stack"
docker compose up -d --build

echo ">> waiting for Confluence /status to report RUNNING/FIRST_RUN (best-effort; RAM-gated)"
deadline=$(( $(date +%s) + 1200 ))   # 20 min
until curl -fsS "$BASE/status" 2>/dev/null | grep -qE 'RUNNING|FIRST_RUN|SETUP'; do
  [ "$(date +%s)" -gt "$deadline" ] && { echo "!! Confluence did not become ready in time (likely RAM-gated)"; docker compose logs --tail=40 confluence; exit 2; }
  sleep 10
done
echo ">> Confluence responded: $(curl -fsS "$BASE/status")"

# NOTE: a fresh Confluence requires the setup wizard + a licence. For unattended runs, boot from a
# GOLDEN pre-setup Confluence-home + Postgres snapshot (spec §12) and only (re)apply a fresh
# 3-hour timebomb DC licence here via the licence REST endpoint. The timebomb key is the
# copy-paste developer key from Atlassian's "Timebomb licenses for testing server apps" page.
# Pseudocode for the post-setup path:
#   curl -u "$AUTH" -X POST "$BASE/rest/plugins/1.0/.../license" ...   # apply timebomb licence
#   curl -u "$AUTH" -X POST "$BASE/rest/api/space" -H 'Content-Type: application/json' \
#        -d '{"key":"LIKEC4","name":"LikeC4","type":"global"}'         # seed space
#   curl -u "$AUTH" -X POST "$BASE/rest/api/content" -H 'Content-Type: application/json' \
#        --data @seed-page.json                                         # seed page with the macro

echo ">> installing the plugin JAR via UPM REST"
JAR=$(ls ../target/*.jar 2>/dev/null | grep -v sources | head -1)
if [ -z "${JAR:-}" ]; then echo "!! no plugin JAR — run 'jmvn -B package' (Task 1) first"; exit 1; fi
UPM_TOKEN=$(curl -s -u "$AUTH" -H 'Accept: application/vnd.atl.plugins.installed+json' \
  "$BASE/rest/plugins/1.0/" -D - -o /dev/null | tr -d '\r' | awk -F': ' 'tolower($1)=="upm-token"{print $2}')
curl -s -u "$AUTH" -X POST "$BASE/rest/plugins/1.0/?token=$UPM_TOKEN" \
  -H 'Accept: application/json' -F "plugin=@$JAR;type=application/java-archive" >/dev/null
echo ">> install request submitted; verify under Manage Apps."

echo ">> stack ready at $BASE"
```

- [ ] **Step 4: `chmod +x` and lint the scripts (executed — syntax only, no Confluence needed)**

Run:
```bash
chmod +x docker/up.sh docker/down.sh
bash -n docker/up.sh && bash -n docker/down.sh && echo "scripts parse OK"
```
Expected: `scripts parse OK` (this checks shell syntax without running them — the live bring-up is Task 6).

- [ ] **Step 5: Commit**

```bash
git add docker/up.sh docker/down.sh docker/seed-page.json
git commit -m "plan4: harness scripts (up/health-gate/licence/seed/UPM-install, down)"
```

---

## Task 5: Live e2e against the stack — authored; run is best-effort (RAM-gated)

Reuses the Plan 2 selectors, but loads the **seeded Confluence page** and asserts the macro rendered. Playwright runs **inside a container** (`mcr.microsoft.com/playwright`), so the host's missing Chromium libs don't matter — only Confluence booting does.

**Files:**
- Create: `docker/e2e/playwright.config.ts`
- Create: `docker/e2e/live.spec.ts`
- Create: `docker/e2e/run.sh`

- [ ] **Step 1: Create `docker/e2e/playwright.config.ts`**

```ts
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',
  use: { baseURL: process.env.CONFLUENCE_BASE || 'http://localhost:8090' },
})
```

- [ ] **Step 2: Create `docker/e2e/live.spec.ts`**

```ts
import { expect, test } from '@playwright/test'

// Loads the seeded "LikeC4 Test Diagram" page and asserts the macro's bundle rendered the diagram.
test('seeded Confluence page renders the LikeC4 diagram', async ({ page }) => {
  await page.goto('/login.action?os_username=admin&os_password=admin')
  await page.goto('/display/LIKEC4/LikeC4+Test+Diagram')
  const diagram = page.locator('.likec4-diagram [data-testid="likec4-diagram"]')
  await expect(diagram).toBeVisible({ timeout: 60_000 })
  await expect(diagram.locator('.react-flow__node').first()).toBeVisible({ timeout: 60_000 })
})
```

- [ ] **Step 3: Create `docker/e2e/run.sh`** (runs Playwright in a container against the live stack)

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
docker run --rm --network host -v "$PWD:/e2e" -w /e2e \
  -e CONFLUENCE_BASE="${CONFLUENCE_BASE:-http://localhost:8090}" \
  mcr.microsoft.com/playwright:v1.48.0-jammy \
  sh -c "npm i -D @playwright/test@1.48.0 >/dev/null 2>&1 && npx playwright test"
```

- [ ] **Step 4: Validate syntax (executed — no Confluence needed)**

Run: `chmod +x docker/e2e/run.sh && bash -n docker/e2e/run.sh && echo "e2e runner parses OK"`
Expected: `e2e runner parses OK`. (The actual run is gated on a booted Confluence — Task 6.)

- [ ] **Step 5: Commit**

```bash
git add docker/e2e
git commit -m "plan4: live Playwright e2e (containerised) against the seeded page"
```

---

## Task 6: Attempt the full boot, document the outcome, README + review

**Files:**
- Create: `docker/README.md`

- [ ] **Step 1: Attempt the full stack boot (best-effort — RAM-gated)**

Run: `docker/up.sh` (gives Confluence up to 20 min). Watch RAM with `docker stats --no-stream` in another shell.
- **If Confluence reaches a `/status` of `FIRST_RUN`/`SETUP`/`RUNNING`:** great — proceed to run `docker/e2e/run.sh` (note: a fresh instance needs the setup wizard + licence before the macro page exists; without the golden snapshot the e2e is expected to stop at the setup screen — record how far it got).
- **If it OOMs / never becomes ready in 20 min (likely at 3.8 GB):** capture `docker compose logs --tail=60 confluence` and `docker stats`, then `docker/down.sh`. Record this as **RAM-gated** — the harness + JAR + mock are the proven deliverables; the live boot needs a CI host with ≥6 GB RAM (and a golden pre-setup snapshot for unattended setup).

- [ ] **Step 2: Create `docker/README.md`** documenting how to run + the outcome

````markdown
# LikeC4 Confluence — Docker Test Harness (Plan 4)

A `docker compose` stack (Confluence 9.2.20 + Postgres + mock-GitLab) plus a harness that builds
the plugin JAR, brings up the stack, health-gates Confluence, applies a timebomb DC licence, seeds
a page with the `likec4-diagram` macro, installs the plugin via UPM, and runs a live Playwright e2e.

## Prereqs
- Docker + Compose, the bootstrapped `~/.local/bin/jmvn` (JDK 21), Node/npm.
- **RAM: Confluence DC needs ≥6 GB recommended.** On a small host (≈3.8 GB) the boot is RAM-gated.

## Run
1. `~/.local/bin/jmvn -B package`  — builds the plugin JAR (compiles the wrapper, bundles the frontend).
2. `docker/up.sh`   — build + start stack, health-gate, (licence/seed/install).
3. `CONFLUENCE_BASE=http://localhost:8090 docker/e2e/run.sh`  — containerised Playwright e2e.
4. `docker/down.sh` — teardown.

## Proven here vs gated
- **Proven:** plugin JAR builds (wrapper compiles against Confluence 9.2.20); mock-GitLab stub
  (commits + archive.tar.gz) verified; Postgres + mock containers healthy.
- **RAM-gated (CI, ≥6 GB):** the live Confluence boot, UPM install, and the live e2e. Use a golden
  pre-setup Confluence-home + Postgres snapshot for unattended setup, and re-apply a 3-hour timebomb
  DC licence (Atlassian "Timebomb licenses for testing server apps").

## Boot outcome (fill from Task 6 Step 1)
<!-- e.g. "Confluence reached FIRST_RUN in ~Xs" OR "RAM-gated: OOM at startup in 3.8 GB; see logs." -->
````

- [ ] **Step 3: Commit + final review**

```bash
git add docker/README.md
git commit -m "plan4: harness README + documented boot outcome"
```
Then dispatch a final reviewer over the whole Plan 4 diff to confirm: the plugin JAR genuinely built (wrapper compiles), the mock stub matches `GitLabSourceClient`'s contract, the compose/harness are coherent, and the boot outcome is honestly recorded.

---

## Self-Review

**Spec coverage (§11 heavy layer + §12):**
- Plugin JAR / web-resource packaging (compiles the Plan 3 wrapper) → Task 1 (executed — the key gate).
- Mock-GitLab stub (resolve ref→sha + archive subtree) → Task 2 (executed, curl-verified).
- `docker compose` stack (Confluence 9.2.20 + Postgres + mock) → Task 3 (postgres+mock executed; Confluence authored).
- Bring-up → health-gate → seed → UPM install → teardown → Task 4 (authored; Confluence steps best-effort).
- e2e (Playwright) against a live page → Task 5 (authored; containerised to dodge host libs; run best-effort).
- Install/upgrade, performance, timebomb licence, golden snapshot → noted in Tasks 4/6 + README; the licence/golden-snapshot are documented approaches (full automation needs a booted Confluence — RAM-gated).
- Boot attempt + honest outcome → Task 6.

**Placeholder scan:** The Confluence-dependent harness steps are authored with real REST calls and clearly marked best-effort/RAM-gated (the same honest gating as Plan 2's CI-gated e2e), not hand-waving. The licence/seed block in `up.sh` is intentionally pseudocode-commented because it depends on the golden-snapshot setup path that a 3.8 GB host can't exercise — flagged, not hidden.

**Consistency:** the mock stub's archive entries (`<top>/<path>/<file>`) are exactly what `GitLabArchiveExtractor` strips (first segment + `path` prefix); `fakeSha` matches Plan 2's mock so shas are stable. The macro params in `seed-page.json` (`project/ref/path/view`) match `LikeC4DiagramMacro`'s reads and Plan 2's `parseDataAttrs`. The e2e selectors (`.likec4-diagram [data-testid="likec4-diagram"]`, `.react-flow__node`) match Plan 2's `Diagram.tsx`. The UPM install targets `target/*.jar` from Task 1.

**Tiers:** Tasks 1–3 are executed (green gates); Tasks 4–6's Confluence-touching parts are best-effort/RAM-gated and committed regardless.
