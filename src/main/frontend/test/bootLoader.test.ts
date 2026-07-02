// @vitest-environment jsdom
//
// Unit coverage for the hand-written classic boot loader (public/boot-loader.js) — the plain <script>
// Confluence injects into the diagram-view web-resource. It is NOT an ES module (a browser-global IIFE),
// so these tests read its source text and eval it (`new Function(src)()`) inside jsdom with the ambient
// globals (window.AJS / document.currentScript / fetch / timers) stubbed, then assert observable DOM +
// fetch behaviour. It has no CI coverage otherwise (only the live e2e gate exercises it) and has
// regressed silently before — notably the entry-CSS idempotency-marker drop-on-miss (commit 5773ea1).
//
// The branches covered mirror exactly what ships:
//   - resource-root + module-key reconstruction of the ESM entry URL for the batched (/s/…), unbatched
//     (/download/…) and AJS.contextPath() fallback serving forms;
//   - the /download/(batch|resources)/<plugin:web-resource>/ module-key extraction regex;
//   - Vite-manifest walking (entry -> static imports' css sidecars, skipping dynamicImports);
//   - the entry-CSS idempotency <meta> marker (no double-inject) AND its drop on a null/failed manifest
//     fetch so a later boot() retries;
//   - the ESM entry <script type="module"> injection (correct src, id-guarded single injection).
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// The classic loaders live under public/ and are read verbatim (they are not modules, so they can't be
// imported). Resolve from the vitest cwd (the frontend dir) — under the jsdom env the global URL is
// jsdom's and fileURLToPath(new URL(..)) rejects it, so avoid the URL round-trip.
const SRC = readFileSync(join(process.cwd(), 'public', 'boot-loader.js'), 'utf8')

// The manifest the loader fetches at <resBase>.vite/manifest.json — an exact trim of the real built
// manifest (src/main/resources/likec4-web/.vite/manifest.json). `index.html` is the boot entry; it
// statically imports `_runtime-*.js` (whose `css` sidecar is our panel stylesheet) and DYNAMICALLY
// imports `src/Diagram.tsx` (whose css must stay lazy and NOT be injected here).
const MANIFEST = {
  'index.html': {
    file: 'assets/main.js',
    name: 'main',
    src: 'index.html',
    isEntry: true,
    imports: ['_modulepreload-polyfill-B5Qt9EMX.js', '_runtime-BzTOlbBB.js', '_reactCompat-XYhScVO2.js'],
    dynamicImports: ['src/Diagram.tsx'],
  },
  '_modulepreload-polyfill-B5Qt9EMX.js': { file: 'assets/modulepreload-polyfill-B5Qt9EMX.js', name: 'modulepreload-polyfill' },
  '_reactCompat-XYhScVO2.js': { file: 'assets/reactCompat-XYhScVO2.js', name: 'reactCompat', imports: ['_runtime-BzTOlbBB.js'] },
  '_runtime-BzTOlbBB.js': { file: 'assets/runtime-BzTOlbBB.js', name: 'runtime', css: ['assets/runtime-DxMEJQXw.css'] },
  'src/Diagram.tsx': {
    file: 'assets/Diagram-Cj5xVEkV.js',
    name: 'Diagram',
    src: 'src/Diagram.tsx',
    isDynamicEntry: true,
    imports: ['_runtime-BzTOlbBB.js'],
    css: ['assets/Diagram-qaMq19n_.css'],
  },
} as const

// Point document.currentScript at a given src (the loader reads it at eval time). configurable so each
// test can reset it in afterEach.
function setCurrentScript(src: string | null) {
  Object.defineProperty(document, 'currentScript', {
    value: src == null ? null : { src },
    configurable: true,
  })
}

// Resolve the deferred fetch promise so the manifest .then() chain runs.
let fetchResolvers: Array<(v: unknown) => void>
function stubFetch(response: Response | null | 'reject') {
  fetchResolvers = []
  const fetchMock = vi.fn(() =>
    new Promise((resolve, reject) => {
      fetchResolvers.push(() => {
        if (response === 'reject') reject(new Error('network'))
        else resolve(response)
      })
    }),
  )
  ;(globalThis as unknown as { fetch: typeof fetch }).fetch = fetchMock as unknown as typeof fetch
  return fetchMock
}
async function flushFetch() {
  fetchResolvers.forEach((r) => r(undefined))
  // let the .then()/.json() microtask chain settle
  await new Promise((r) => setTimeout(r, 0))
  await Promise.resolve()
}

function jsonResponse(body: unknown, ok = true): Response {
  return { ok, json: () => Promise.resolve(body) } as unknown as Response
}

function run() {
  // eslint-disable-next-line no-new-func
  new Function(SRC)()
}

const cssLinks = () =>
  Array.from(document.querySelectorAll('link[rel="stylesheet"][data-likec4-entry-css]')).map((l) =>
    (l as HTMLLinkElement).getAttribute('href'),
  )
const esmEntry = () => document.getElementById('likec4-esm-entry') as HTMLScriptElement | null

beforeEach(() => {
  document.head.replaceChildren()
  document.body.replaceChildren()
  ;(window as unknown as { AJS?: unknown }).AJS = undefined
})

afterEach(() => {
  setCurrentScript(null)
  vi.restoreAllMocks()
})

describe('boot-loader URL reconstruction', () => {
  it('reconstructs the ESM entry from a BATCHED (/s/…) super-batch src', async () => {
    // Production: the loader is batched into /s/<hash>/_/download/... — the root is everything before /s/.
    setCurrentScript(
      'https://wiki.example.com/confluence/s/-abc123/_/download/batch/com.phrontizo.confluence.likec4-confluence:likec4-web/com.phrontizo.confluence.likec4-confluence:likec4-web.js',
    )
    stubFetch(jsonResponse(MANIFEST))
    run()
    await flushFetch()
    // context-root is preserved (origin + /confluence context path), module key from the batch segment.
    expect(esmEntry()?.src).toBe(
      'https://wiki.example.com/confluence/download/resources/com.phrontizo.confluence.likec4-confluence:likec4-web/likec4-web/assets/main.js',
    )
  })

  it('reconstructs the ESM entry from an UNBATCHED (/download/…) dev src', async () => {
    setCurrentScript(
      'http://localhost:8090/download/resources/com.phrontizo.confluence.likec4-confluence:likec4-web/boot-loader.js',
    )
    stubFetch(jsonResponse(MANIFEST))
    run()
    await flushFetch()
    expect(esmEntry()?.src).toBe(
      'http://localhost:8090/download/resources/com.phrontizo.confluence.likec4-confluence:likec4-web/likec4-web/assets/main.js',
    )
  })

  it('falls back to AJS.contextPath() + the default module key when the src has neither /s/ nor /download/', async () => {
    // A reverse proxy that rewrote /download/ — no derivable root, so warn + use AJS.contextPath().
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    ;(window as unknown as { AJS: unknown }).AJS = { contextPath: () => '/wiki' }
    setCurrentScript('https://proxy.example.com/assets/boot-loader.js')
    stubFetch(jsonResponse(MANIFEST))
    run()
    await flushFetch()
    // No derivable root from the src, so the code uses AJS.contextPath() ('/wiki') verbatim as the root
    // (it has no origin to prepend in this path). Assert the raw attribute the loader set, not the
    // browser-resolved .src (jsdom would resolve the leading-'/' path against the document origin).
    expect(esmEntry()?.getAttribute('src')).toBe(
      '/wiki/download/resources/com.phrontizo.confluence.likec4-confluence:likec4-web/likec4-web/assets/main.js',
    )
    expect(warn).toHaveBeenCalled()
  })

  it('bails out entirely when there is no currentScript src', () => {
    setCurrentScript(null)
    const fetchMock = stubFetch(jsonResponse(MANIFEST))
    run()
    // `if (!me || !me.src) return;` — nothing injected, no manifest fetched.
    expect(fetchMock).not.toHaveBeenCalled()
    expect(esmEntry()).toBeNull()
    expect(document.getElementById('likec4-entry-css')).toBeNull()
  })
})

describe('boot-loader module-key extraction regex', () => {
  // Exercise the exact `/download/(?:batch|resources)/<plugin:web-resource>/` capture used to build the
  // /download/resources/ base, via the observable entry URL.
  const cases: Array<[string, string]> = [
    [
      'http://h/download/resources/my.plugin.key:web-res/boot-loader.js',
      'http://h/download/resources/my.plugin.key:web-res/likec4-web/assets/main.js',
    ],
    [
      'http://h/context/s/x/_/download/batch/some.plugin:the-key/some.plugin:the-key.js',
      'http://h/context/download/resources/some.plugin:the-key/likec4-web/assets/main.js',
    ],
  ]
  it.each(cases)('extracts the module key from %s', async (src, expected) => {
    setCurrentScript(src)
    stubFetch(jsonResponse(MANIFEST))
    run()
    await flushFetch()
    expect(esmEntry()?.src).toBe(expected)
  })

  it('uses the default module key when the src has no <plugin:web-resource> segment', async () => {
    // /download/ present (so root is derivable) but no `key:key` segment -> regex misses -> default key.
    setCurrentScript('http://h/download/somethingelse/boot-loader.js')
    stubFetch(jsonResponse(MANIFEST))
    run()
    await flushFetch()
    expect(esmEntry()?.src).toBe(
      'http://h/download/resources/com.phrontizo.confluence.likec4-confluence:likec4-web/likec4-web/assets/main.js',
    )
  })
})

describe('boot-loader entry-CSS injection', () => {
  beforeEach(() => {
    setCurrentScript('http://h/download/resources/plug:web/boot-loader.js')
  })

  it('injects only the entry static-import css sidecar (runtime), NOT the lazy Diagram css', async () => {
    stubFetch(jsonResponse(MANIFEST))
    run()
    await flushFetch()
    expect(cssLinks()).toEqual(['http://h/download/resources/plug:web/likec4-web/assets/runtime-DxMEJQXw.css'])
    // The dynamic Diagram chunk's CSS must stay lazy.
    expect(cssLinks().some((h) => h?.includes('Diagram'))).toBe(false)
  })

  it('the idempotency <meta> marker prevents a second injectEntryCss from re-fetching/double-inserting', async () => {
    const fetchMock = stubFetch(jsonResponse(MANIFEST))
    run()
    await flushFetch()
    expect(document.getElementById('likec4-entry-css')).not.toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const before = cssLinks().length
    // A second boot on the same page (re-eval) must be a no-op: marker present -> early return.
    run()
    await flushFetch()
    expect(fetchMock).toHaveBeenCalledTimes(1) // no second fetch
    expect(cssLinks().length).toBe(before) // no duplicate link
  })

  it('drops the idempotency marker on a null (non-ok) manifest so a later boot can retry (regression 5773ea1)', async () => {
    // First boot: manifest fetch returns !ok -> r.ok ? … : null -> marker removed, no css injected.
    stubFetch(jsonResponse({}, /* ok */ false))
    run()
    await flushFetch()
    expect(document.getElementById('likec4-entry-css')).toBeNull()
    expect(cssLinks()).toEqual([])

    // Second boot with a good manifest now succeeds because the marker was dropped.
    const fetchMock = stubFetch(jsonResponse(MANIFEST))
    run()
    await flushFetch()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(cssLinks()).toEqual(['http://h/download/resources/plug:web/likec4-web/assets/runtime-DxMEJQXw.css'])
  })

  it('drops the idempotency marker when the manifest fetch REJECTS so a later boot can retry', async () => {
    const debug = vi.spyOn(console, 'debug').mockImplementation(() => {})
    stubFetch('reject')
    run()
    await flushFetch()
    expect(document.getElementById('likec4-entry-css')).toBeNull()
    expect(cssLinks()).toEqual([])
    expect(debug).toHaveBeenCalled()
  })

  it('does not re-inject a css link whose href is already present (per-file idempotency)', async () => {
    // Pre-seed the exact runtime href, then boot: the per-file guard must skip it.
    const pre = document.createElement('link')
    pre.rel = 'stylesheet'
    pre.href = 'http://h/download/resources/plug:web/likec4-web/assets/runtime-DxMEJQXw.css'
    document.head.appendChild(pre)
    stubFetch(jsonResponse(MANIFEST))
    run()
    await flushFetch()
    // No NEW data-likec4-entry-css link was added (the pre-seeded one has no marker attr).
    expect(cssLinks()).toEqual([])
    expect(document.querySelectorAll('link[rel="stylesheet"]').length).toBe(1)
  })
})

describe('boot-loader ESM entry injection', () => {
  beforeEach(() => {
    setCurrentScript('http://h/download/resources/plug:web/boot-loader.js')
  })

  it('injects the ESM entry as <script type="module"> exactly once (id-guarded)', async () => {
    stubFetch(jsonResponse(MANIFEST))
    run()
    await flushFetch()
    const s = esmEntry()
    expect(s).not.toBeNull()
    expect(s?.type).toBe('module')
    expect(s?.src).toBe('http://h/download/resources/plug:web/likec4-web/assets/main.js')
    // A second boot must not inject a duplicate entry (load() is id-guarded).
    run()
    await flushFetch()
    expect(document.querySelectorAll('#likec4-esm-entry').length).toBe(1)
  })
})
