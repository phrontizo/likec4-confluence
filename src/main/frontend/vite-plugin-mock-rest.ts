import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import type { Connect, Plugin } from 'vite'

const KEEP = (name: string) => name.endsWith('.c4') || name.endsWith('.likec4') || name.endsWith('.likec4.snap')

/** Deterministic 40-char hex from a string. NOT cryptographic — fixture use only. */
function fakeSha(input: string): string {
  let h = 0x811c9dc5
  let out = ''
  for (let i = 0; i < 40; i++) {
    for (let j = 0; j < input.length; j++) {
      h ^= input.charCodeAt(j) + i
      h = Math.imul(h, 0x01000193)
    }
    out += ((h >>> 28) & 0xf).toString(16)
  }
  return out
}

function loadSubtree(reposDir: string, project: string, path: string): Record<string, string> {
  const base = join(reposDir, project, path)
  const out: Record<string, string> = {}
  // Dev/preview-only containment guard: `project`/`path` are unvalidated query params, so a `..` segment
  // could make `base` escape `reposDir` (e.g. path=../../etc) and read files outside the fixture tree.
  // join() collapses `..`, so a legitimate request stays under `reposDir + sep`; reject anything else
  // (mirrors the mock-gitlab server's containment check). This stub never ships to production, but keep
  // the guard so the harness can't be tricked into serving arbitrary local files.
  if (base !== reposDir && !base.startsWith(reposDir + sep)) return out
  if (!existsSync(base)) return out
  const walk = (cur: string) => {
    for (const name of readdirSync(cur)) {
      const full = join(cur, name)
      if (statSync(full).isDirectory()) walk(full)
      else if (KEEP(name)) out[relative(base, full).split(sep).join('/')] = readFileSync(full, 'utf8')
    }
  }
  walk(base)
  return out
}

export function mockRest(reposDir: string): Plugin {
  const handler: Connect.NextHandleFunction = (req, res, next) => {
    const url = new URL(req.url ?? '', 'http://localhost')
    if (!url.pathname.startsWith('/rest/likec4/1.0/')) return next()
    const project = url.searchParams.get('project') ?? ''
    const ref = url.searchParams.get('ref') ?? 'main'
    const path = url.searchParams.get('path') ?? ''
    res.setHeader('content-type', 'application/json')
    if (url.pathname.endsWith('/resolve')) {
      res.end(JSON.stringify({ sha: fakeSha(`${project}@${ref}`) }))
      return
    }
    if (url.pathname.endsWith('/source')) {
      const files = loadSubtree(reposDir, project, path)
      if (Object.keys(files).length === 0) {
        res.statusCode = 404
        res.end(JSON.stringify({ error: 'not found' }))
        return
      }
      res.end(JSON.stringify({ sha: fakeSha(`${project}@${ref}`), files }))
      return
    }
    next()
  }
  return {
    name: 'mock-likec4-rest',
    // Dev/preview only — this REST stub must NEVER influence a production build. It has no build
    // hooks today, but `apply: 'serve'` makes that guarantee explicit so a future transform/resolveId
    // added here can't silently ship into the Confluence bundle.
    apply: 'serve',
    configureServer(server) { server.middlewares.use(handler) },
    configurePreviewServer(server) { server.middlewares.use(handler) },
  }
}
