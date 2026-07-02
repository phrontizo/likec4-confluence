import { createServer } from 'node:http'
import { readdirSync, readFileSync, lstatSync, existsSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { gzipSync } from 'node:zlib'

// join() with a single arg normalises away any trailing slash so the containment check below
// (startsWith REPOS + sep) is reliable.
const REPOS = join(process.env.REPOS_DIR || '/repos')
// Default to an UNPRIVILEGED port matching the Dockerfile (ENV PORT=8080, non-root `node` user). The
// container always sets PORT explicitly, so this fallback only bites a maintainer running server.mjs
// directly on the host — where binding :80 as non-root throws EACCES from server.listen (outside the
// per-request try/catch), exiting with an opaque stack. 8080 avoids that.
const PORT = process.env.PORT || 8080

const KEEP = (n) => n.endsWith('.c4') || n.endsWith('.likec4') || n.endsWith('.likec4.snap')

// Per-file cap on a kept fixture. Real deliverable LikeC4 sources are a few KB (the largest here is
// ~14 KB); a multi-MB kept file (a mistaken fixture, or a hostile in-tree file) would be slurped as
// UTF-8 on EVERY archive hit and, under the perf load test's concurrency, could pressure the mock into
// an OOM that cascades via compose depends_on. Fail loud (like the ustar name guard) rather than serve
// it, so an oversized fixture is caught here instead of destabilising the stack.
const MAX_FILE_BYTES = 4 * 1024 * 1024

// Request counters (perf test, spec §11). `commits` = ref→sha resolutions; `archive` = subtree
// downloads. The server-side cache + single-flight should keep `archive` ≪ the number of /source
// requests the plugin receives. Exposed via GET /__count; reset via GET /__reset.
let commitsCount = 0
let archiveCount = 0

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
      // lstatSync (NOT statSync): never follow symlinks. A cyclic/ancestor symlink under repos/ would
      // make statSync report a directory and recurse forever (stack overflow that crashes the mock,
      // contradicting its "degrade to 5xx, never crash" contract). Skip links outright — real GitLab
      // archives don't deliver them and the extractor drops them anyway.
      const st = lstatSync(full)
      if (st.isSymbolicLink()) continue
      if (st.isDirectory()) rec(full)
      else if (st.isFile()) {
        // Filter by name BEFORE reading: only LikeC4 files are ever delivered, so a non-LikeC4 file
        // (a binary image, an archive) must never be slurped into memory as UTF-8 on every request.
        const rel = relative(base, full).split(sep).join('/')
        if (KEEP(rel)) {
          if (st.size > MAX_FILE_BYTES) {
            throw new Error(`fixture file exceeds the ${MAX_FILE_BYTES}-byte cap: ${rel} (${st.size} bytes)`)
          }
          out.push({ rel, content: readFileSync(full, 'utf8') })
        }
      }
    }
  }
  rec(base)
  return out
}

// A servable project must be an existing DIRECTORY under REPOS. Real GitLab 404s a project path that
// resolves to a file; without the isDirectory check /resolve would 200 (a fake sha) while /source 500s
// (walk -> readdirSync on a file -> ENOTDIR), so the two endpoints would DISAGREE about whether the
// project exists. lstatSync (not statSync) so a symlinked path is judged on the link, not its target.
const isRepoDir = (p) => existsSync(p) && lstatSync(p).isDirectory()

// Minimal ustar tar writer (dep-free).
function tarHeader(name, size) {
  if (Buffer.byteLength(name, 'utf8') > 100) {
    // ustar caps the entry name at 100 bytes; b.write would SILENTLY truncate, corrupting the path the
    // extractor sees. Fail loud so a too-long fixture name is caught here, not silently mis-served.
    // NOTE: `name` here is the FULL archive path = `<project-basename>-<40-hex-sha>/` prefix + the
    // fixture's repo-relative path (see the archive handler's `${top}/${f.rel}`). The prefix alone eats
    // ~50 bytes (e.g. `architecture-<40 hex>/` = 54), so fixture filenames/subdirs must be kept SHORT —
    // the longest current entry name is 93 bytes (98 via a top-level group root), leaving little
    // headroom. `oversizedFixtures` re-checks this at boot so a too-long path fails fast with all
    // offenders named, rather than 500ing per request. This mock deliberately does NOT emit PAX/GNU
    // long-name records; keep fixtures within the ustar limit rather than lifting it.
    throw new Error(`tar entry name exceeds the 100-byte ustar limit: ${name}`)
  }
  const b = Buffer.alloc(512)
  b.write(name, 0, 100)
  b.write('0000644', 100, 7); b.write('0000000', 108, 7); b.write('0000000', 116, 7)
  b.write(size.toString(8).padStart(11, '0'), 124, 11)
  b.write('00000000000', 136, 11)
  b.write('        ', 148, 8)
  b.write('0', 156, 1)
  b.write('ustar 00', 257, 8)
  let sum = 0; for (let i = 0; i < 512; i++) sum += b[i]
  // Conventional ustar checksum field: 6 octal digits, a NUL terminator, then a space (offsets 148..155).
  b.write(sum.toString(8).padStart(6, '0') + '\0 ', 148, 8)
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

const server = createServer((req, res) => {
 try {
  const url = new URL(req.url, 'http://localhost')
  // Liveness probe for the compose healthcheck — deliberately does NOT touch the request counters, so
  // the every-5s healthcheck cannot inflate commitsCount and spuriously fail the perf/zero-git proofs
  // (which assert commits==0 / commits<=resolves after a /__reset).
  if (url.pathname === '/__health') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: true }))
    return
  }
  // Counter introspection endpoints (perf test, spec §11) — not part of the GitLab API surface.
  if (url.pathname === '/__count') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ commits: commitsCount, archive: archiveCount }))
    return
  }
  if (url.pathname === '/__reset') {
    commitsCount = 0
    archiveCount = 0
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ commits: commitsCount, archive: archiveCount }))
    return
  }
  const parts = url.pathname.split('/').filter(Boolean) // api v4 projects :enc repository ...
  const enc = parts[3]
  const project = enc ? decodeURIComponent(enc) : ''
  // Route on the FIXED positional path segments (parts[4]='repository', parts[5]=endpoint) rather than a
  // substring match on the whole pathname: real GitLab's URL is
  // /api/v4/projects/:enc/repository/{commits/:ref,archive.tar.gz}, and the project (parts[3]) is a single
  // url-encoded segment (its slashes are %2F), so a positional check can never be fooled by a project name
  // that happens to contain "/repository/...". Equivalent for every real request; just not fragile.
  const isRepository = parts[4] === 'repository'
  // The path-derived commit ref lives at the FIXED position right after `repository/commits` (parts[6]).
  // Deriving it via parts.indexOf('commits') would MISFIRE for a project literally named `commits`
  // (parts[3]): indexOf would match the project segment and read the ref from `repository` instead. Decode
  // it the SAME way `project` (parts[3]) and query refs (URLSearchParams.get already decodes) are — so an
  // encoded ref such as `feature%2Ffoo` hashes the same string the plugin sent, matching real GitLab's
  // decode-the-ref contract — or default to `main`. (A query ?ref= wins if present.)
  const ref = url.searchParams.get('ref')
    || (isRepository && parts[5] === 'commits' && parts[6] !== undefined
          ? decodeURIComponent(parts[6])
          : 'main')
  if (isRepository && parts[5] === 'commits') {
    // Containment + existence, MIRRORING the archive branch below: real GitLab 404s an unknown or empty
    // project on the commits endpoint too. Without this, /resolve succeeds for a bogus/traversal/empty
    // project (fakeSha('@main') collapsing to a constant) — masking a resolve-path project-validation
    // regression that would only surface later at /source. Reject before counting a served resolution.
    const base = join(REPOS, project)
    if (!base.startsWith(REPOS + sep) || !isRepoDir(base)) { res.writeHead(404); res.end('no repo'); return }
    commitsCount++
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ id: fakeSha(`${project}@${ref}`) }))
    return
  }
  if (isRepository && parts[5] === 'archive.tar.gz') {
    // NOTE: unlike real GitLab, this mock IGNORES the &path= subtree filter and always returns the
    // whole project tree. That is safe for the render path because the plugin re-filters client-side
    // (PathSafety/GitLabArchiveExtractor), and the real subtree-scoping contract is covered by the
    // recorded gitlab.com contract test. It does mean this mock cannot catch a wrong/missing path
    // sent to GitLab.
    //
    // NOTE (sha<->ref consistency): the mock CANNOT cross-check that the caller-supplied ?sha= matches
    // the sha /resolve issued for a ref — the archive endpoint receives only a sha (no ref), exactly as
    // real GitLab does (the plugin builds `archive.tar.gz?sha=<resolved-40-hex>`, and a full-40-hex macro
    // ref bypasses /resolve entirely — RefShaCache short-circuits full shas), so there is no ref here to
    // hash and compare against, and no reverse sha->ref map. It therefore serves the tree under whatever
    // valid-shaped sha is asked for. The resolve->source sha-pinning invariant (that /source uses exactly
    // the sha /resolve returned) is a PLUGIN-side property, pinned by the core unit tests and the recorded
    // gitlab.com contract test — not something this test double can enforce.
    const sha = url.searchParams.get('sha') || fakeSha(`${project}@${ref}`)
    const base = join(REPOS, project)
    // Containment: real GitLab cannot escape the project root, and 404s an unknown/absent project. join()
    // collapses any "../" in the url-decoded project, so a crafted project like "..%2F..%2Fetc" resolves
    // OUTSIDE REPOS, and an ABSENT project segment (parts[3] === undefined -> project "") makes join()
    // collapse to REPOS itself — both would let walk() read files it shouldn't (elsewhere on disk, or the
    // WHOLE repos tree). Require base to be a real child of REPOS: this rejects the outside-tree traversal
    // AND base === REPOS (the empty-project hole) at once. (A DOUBLE-SLASH "//" in the path is NOT a
    // distinct route: parts = split('/').filter(Boolean) DROPS the empty segment, so "//" collapses to a
    // single "/" and the SAME real project is parsed and served normally — equivalent to one slash, not a
    // 404. Containment is unaffected: the startsWith(REPOS + sep) + isRepoDir() checks above still hold.)
    if (!base.startsWith(REPOS + sep)) { res.writeHead(404); res.end('no repo'); return }
    if (!isRepoDir(base)) { res.writeHead(404); res.end('no repo'); return }
    // Guard the caller-supplied ?sha= before it becomes the ustar name prefix below. The plugin only ever
    // passes a resolved 40-hex commit sha (fakeSha's shape), so a value outside [0-9a-f]{6,40} is a fuzzed/
    // hostile request: reject it with a clean 400 rather than letting an over-long sha push `top` past the
    // 100-byte ustar name cap, which would throw in tarGz and degrade to an opaque 500 (real GitLab 400s a
    // malformed sha too). Containment/existence 404s stay ahead of this, mirroring real GitLab ordering.
    if (!/^[0-9a-f]{6,40}$/.test(sha)) { res.writeHead(400); res.end('bad sha'); return }
    const top = `${project.split('/').pop()}-${sha}`
    // `${top}/${f.rel}` is the ustar entry name; `top` is ~50 bytes (basename + `-` + 40-hex sha), and
    // ustar caps the whole name at 100 bytes (tarHeader throws past that), so keep fixture paths short.
    // walk() already keeps only LikeC4 files (it filters by name before reading), so no second filter
    // here. The tree is re-read per request (no caching) ON PURPOSE: a perf run wants the real
    // fetch-shape so the plugin's cache/single-flight is what keeps `archive` ≈ 1, not the mock.
    const entries = walk(base).map((f) => ({ name: `${top}/${f.rel}`, content: f.content }))
    // Serialize BEFORE counting or writing headers: tarGz can still throw (e.g. a ustar entry-name over
    // 100 bytes), which must degrade to the outer catch's 500 with NO archive counted and NO 200 header
    // already sent. Count only a genuinely SERVED archive (past the containment/existence 404s AND a
    // successful serialization) so a bad/escaping/failing request can't inflate `archive` and spuriously
    // trip the perf assertion (archive <= ARCHIVE_MAX).
    const body = tarGz(entries)
    archiveCount++
    res.writeHead(200, { 'content-type': 'application/gzip' })
    res.end(body)
    return
  }
  res.writeHead(404); res.end('not found')
 } catch (err) {
  // A test-double must DEGRADE to a 5xx, never crash the process: an uncaught throw in an http
  // callback exits Node, killing the mock and cascading via compose depends_on. Covers a malformed
  // %-escape (decodeURIComponent) or an unreadable file (walk skips symlinks via lstatSync, so a
  // cyclic/dangling link can no longer recurse or throw here).
  console.error('mock-gitlab request error:', err && err.message)
  if (!res.headersSent) res.writeHead(500, { 'content-type': 'text/plain' })
  res.end('mock error')
 }
})

// Validate fixtures ONCE at boot. walk()/tarHeader's per-request caps stay as defense-in-depth, but on
// their own a single offending fixture would 500 EVERY archive fetch — an opaque "diagram won't load"
// whose true cause is buried in stderr, per request. Fail fast at startup instead, naming ALL offenders,
// so a bad fixture is caught at boot rather than mid-gate. Two independent limits are checked:
//   - size: st.size > MAX_FILE_BYTES (walk's per-file cap).
//   - ustar entry-name length: tarHeader throws once `<top>/<rel>` exceeds 100 bytes (no PAX/GNU
//     long-name records). The entry name is `<basename(project)>-<40-hex-sha>/<rel-to-project>`; its
//     length is monotone in how SHALLOW the served project root is (a shallower root shortens the
//     basename by less than it lengthens the rel), so the worst case for a given file is its shallowest
//     servable root — the top-level dir under REPOS (base === REPOS is rejected by the archive handler).
//     Checking that worst case here proves the file is safe under EVERY servable project root.
//     HEADROOM: the current deepest fixture (the big-model `big/.likec4/*.likec4.snap` tree) sits at
//     ~98/100 bytes — only ~2 bytes of slack. Adding a fixture with a path a few bytes longer under that
//     tree, or renaming a group dir, will trip THIS check. That is by design (fail-loud at boot beats a
//     mid-gate 500), but budget for it; emitting PAX long-name records would be the alternative if needed.
const USTAR_NAME_MAX = 100;
const SHA_PLACEHOLDER = '0'.repeat(40); // a git SHA-1 is 40 hex chars; only its LENGTH matters here
function badFixtures(root) {
  const oversized = [];
  const overlongNames = [];
  const rec = (cur) => {
    for (const name of readdirSync(cur)) {
      const full = join(cur, name)
      const st = lstatSync(full)
      if (st.isSymbolicLink()) continue
      if (st.isDirectory()) rec(full)
      else if (st.isFile()) {
        const rel = relative(root, full).split(sep).join('/')
        if (!KEEP(rel)) continue
        if (st.size > MAX_FILE_BYTES) oversized.push(`${rel} (${st.size} bytes)`)
        // Worst-case entry name = shallowest servable root (the top-level dir under REPOS). A file
        // directly under REPOS (no '/') is never inside a project archive, so it has no entry name.
        const slash = rel.indexOf('/')
        if (slash > 0) {
          const top = rel.slice(0, slash)
          const within = rel.slice(slash + 1)
          const entryName = `${top}-${SHA_PLACEHOLDER}/${within}`
          const bytes = Buffer.byteLength(entryName, 'utf8')
          if (bytes > USTAR_NAME_MAX) overlongNames.push(`${rel} -> ${entryName} (${bytes} bytes)`)
        }
      }
    }
  }
  if (existsSync(root)) rec(root)
  return { oversized, overlongNames }
}
const { oversized, overlongNames } = badFixtures(REPOS)
if (oversized.length) {
  console.error(
    `mock-gitlab: ${oversized.length} fixture(s) exceed the ${MAX_FILE_BYTES}-byte cap; every archive ` +
    `fetch would 500 until fixed:\n  ${oversized.join('\n  ')}`)
}
if (overlongNames.length) {
  console.error(
    `mock-gitlab: ${overlongNames.length} fixture(s) would exceed the ${USTAR_NAME_MAX}-byte ustar ` +
    `entry-name limit at their shallowest servable project root; every such archive fetch would 500 ` +
    `until the path is shortened:\n  ${overlongNames.join('\n  ')}`)
}
if (oversized.length || overlongNames.length) {
  process.exit(1)
}

server.listen(PORT, () => console.log(`mock-gitlab on ${PORT}, repos=${REPOS}`))
