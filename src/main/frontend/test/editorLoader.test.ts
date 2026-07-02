// @vitest-environment jsdom
//
// Unit coverage for the hand-written classic editor loader (public/editor-loader.js) — the plain
// <script> Confluence injects into the macro-EDITOR web-resource batch. It is NOT an ES module (a
// browser-global IIFE), so these tests read its source text and eval it (`new Function(src)()`) inside
// jsdom with the ambient globals stubbed (window.AJS.{bind,toInit,MacroBrowser,flag,contextPath},
// window.tinymce, document.currentScript, fetch, timers), then assert observable behaviour. Each
// `run()` re-evaluates the IIFE with a FRESH closure (so module-level bundlePromise/pollTimer reset
// between cases), and the tests drive the real flow through the captured MacroBrowser opener.
//
// Covered (mirroring exactly what ships):
//   - the MacroBrowser JS-override registration + the shared poll-timer de-duplication guard;
//   - contentId()'s .value (input) vs getAttribute('content') (#content-id meta) fallback chain;
//   - entryUrl() reconstruction from the batched-vs-own-src forms + the module-key/contextPath path;
//   - injectEntryCss walking the editor-confluence entry's static-import css (skipping the lazy Diagram
//     css), the idempotency <meta> marker, and its drop on a null/failed manifest for retry;
//   - ensureBundle re-injecting a fresh <script type="module"> after an onerror (retry path);
//   - the onInsert error routing: insertMacro returning false AND insertMacro THROWING both reach the
//     visible notifyError(AJS.flag) path (Finding 1 — a synchronous throw used to escape uncaught).
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const SRC = readFileSync(join(process.cwd(), 'public', 'editor-loader.js'), 'utf8')

const MODULE_KEY = 'com.phrontizo.confluence.likec4-confluence:likec4-editor'

// Trim of the real built manifest. The editor entry is `src/editor-confluence.tsx`; it statically
// imports `_runtime-*.js` (css sidecar = panel styles) and `_mountEditor-*.js` (which DYNAMICALLY
// imports src/Diagram.tsx — that lazy css must NOT be collected here).
const MANIFEST = {
  'src/editor-confluence.tsx': {
    file: 'assets/editor-confluence.js',
    name: 'editor-confluence',
    src: 'src/editor-confluence.tsx',
    isEntry: true,
    imports: ['_reactCompat-XYhScVO2.js', '_runtime-BzTOlbBB.js', '_mountEditor-BTA4jCsq.js'],
  },
  '_reactCompat-XYhScVO2.js': { file: 'assets/reactCompat-XYhScVO2.js', name: 'reactCompat', imports: ['_runtime-BzTOlbBB.js'] },
  '_runtime-BzTOlbBB.js': { file: 'assets/runtime-BzTOlbBB.js', name: 'runtime', css: ['assets/runtime-DxMEJQXw.css'] },
  '_mountEditor-BTA4jCsq.js': {
    file: 'assets/mountEditor-BTA4jCsq.js',
    name: 'mountEditor',
    imports: ['_runtime-BzTOlbBB.js'],
    dynamicImports: ['src/Diagram.tsx'],
  },
  'src/Diagram.tsx': {
    file: 'assets/Diagram-Cj5xVEkV.js',
    name: 'Diagram',
    src: 'src/Diagram.tsx',
    isDynamicEntry: true,
    imports: ['_runtime-BzTOlbBB.js'],
    css: ['assets/Diagram-qaMq19n_.css'],
  },
} as const

function setCurrentScript(src: string | null) {
  Object.defineProperty(document, 'currentScript', {
    value: src == null ? null : { src },
    configurable: true,
  })
}

// A MacroBrowser stub that captures the override opener the loader registers so tests can drive the
// real insert/ensureBundle flow.
type Override = { opener: (macro: unknown) => void }
function makeAJS(overrides: Partial<Record<string, unknown>> = {}) {
  const registered: { override?: Override } = {}
  const flags: Array<{ type: string; title: string; body: string }> = []
  const ajs = {
    contextPath: () => '',
    flag: (f: { type: string; title: string; body: string }) => flags.push(f),
    MacroBrowser: {
      setMacroJsOverride: (_name: string, o: Override) => {
        registered.override = o
      },
    },
    ...overrides,
  }
  ;(window as unknown as { AJS: unknown }).AJS = ajs
  return { ajs, registered, flags }
}

let fetchResolvers: Array<() => void>
function stubFetch(response: unknown | null | 'reject') {
  fetchResolvers = []
  const fetchMock = vi.fn(
    () =>
      new Promise((resolve, reject) => {
        fetchResolvers.push(() => {
          if (response === 'reject') reject(new Error('network'))
          else resolve({ ok: response != null, json: () => Promise.resolve(response) } as unknown as Response)
        })
      }),
  )
  ;(globalThis as unknown as { fetch: typeof fetch }).fetch = fetchMock as unknown as typeof fetch
  return fetchMock
}
async function flushFetch() {
  fetchResolvers.forEach((r) => r())
  await new Promise((r) => setTimeout(r, 0))
  await Promise.resolve()
}

function run() {
  // eslint-disable-next-line no-new-func
  new Function(SRC)()
}

const cssLinks = () =>
  Array.from(document.querySelectorAll('link[rel="stylesheet"][data-likec4-entry-css]')).map((l) =>
    (l as HTMLLinkElement).getAttribute('href'),
  )
const esmEntry = () => document.getElementById('likec4-editor-esm-entry') as HTMLScriptElement | null

beforeEach(() => {
  document.head.replaceChildren()
  document.body.replaceChildren()
  ;(window as unknown as { AJS?: unknown; tinymce?: unknown; LikeC4Editor?: unknown }).AJS = undefined
  ;(window as unknown as { tinymce?: unknown }).tinymce = undefined
  ;(window as unknown as { LikeC4Editor?: unknown }).LikeC4Editor = undefined
})

afterEach(() => {
  setCurrentScript(null)
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('editor-loader MacroBrowser registration', () => {
  it('registers the likec4-diagram JS override once MacroBrowser is present (via AJS.toInit)', () => {
    const captured: { override?: Override } = {}
    ;(window as unknown as { AJS: unknown }).AJS = {
      toInit: (fn: () => void) => fn(), // runs init immediately
      MacroBrowser: { setMacroJsOverride: (_n: string, o: Override) => (captured.override = o) },
    }
    run()
    expect(captured.override).toBeTruthy()
    expect(typeof captured.override?.opener).toBe('function')
    expect((window as unknown as { __likec4EditorOverrideRegistered?: boolean }).__likec4EditorOverrideRegistered).toBe(true)
  })

  it('polls for MacroBrowser when it is not ready at init, and does NOT stack duplicate poll timers', () => {
    vi.useFakeTimers()
    const setInterval = vi.spyOn(globalThis, 'setInterval')
    let mb: { setMacroJsOverride: (n: string, o: Override) => void } | undefined
    const inits: Array<() => void> = []
    ;(window as unknown as { AJS: unknown }).AJS = {
      // Capture BOTH init hooks (init.rte bind + toInit) so we can fire init twice while MacroBrowser is
      // still absent — the second fire must not create a second interval (the pollTimer guard).
      bind: (_evt: string, fn: () => void) => inits.push(fn),
      toInit: (fn: () => void) => inits.push(fn),
      get MacroBrowser() {
        return mb
      },
    }
    run()
    // Fire init twice with MacroBrowser still undefined -> exactly ONE interval created.
    inits.forEach((fn) => fn())
    expect(setInterval).toHaveBeenCalledTimes(1)

    // Now make MacroBrowser available; the running poll registers and clears itself.
    let captured: Override | undefined
    mb = { setMacroJsOverride: (_n, o) => (captured = o) }
    vi.advanceTimersByTime(200)
    expect(captured).toBeTruthy()
  })
})

describe('editor-loader contentId fallback chain', () => {
  // contentId() is reached via insertMacro -> we drive it through the opener + a tinymce stub that
  // records the {macro, contentId} it receives.
  function driveInsert(): { received?: { contentId?: string } } {
    const rec: { received?: { contentId?: string } } = {}
    ;(window as unknown as { tinymce: unknown }).tinymce = {
      confluence: { MacroUtils: { insertMacro: (arg: { contentId?: string }) => (rec.received = arg) } },
    }
    return rec
  }

  it('reads the id from an input[name="pageId"].value', async () => {
    setCurrentScript('http://h/download/resources/plug:editor/editor-loader.js')
    const { registered } = makeAJS()
    stubFetch(MANIFEST) // ensureBundle also injects entry CSS; keep the manifest fetch stubbed + quiet
    ;(window as unknown as { LikeC4Editor: unknown }).LikeC4Editor = {
      createDeps: () => ({}),
      openMacroEditor: (opts: { onInsert: (u: unknown) => void }) => opts.onInsert({ name: 'likec4-diagram', params: {} }),
    }
    const input = document.createElement('input')
    input.setAttribute('name', 'pageId')
    input.value = '65678'
    document.body.appendChild(input)
    const rec = driveInsert()
    run()
    // init ran (no AJS.toInit stub -> falls through to init()); trigger the opener.
    registered.override?.opener({ params: {} })
    await Promise.resolve()
    await Promise.resolve()
    expect(rec.received?.contentId).toBe('65678')
  })

  it('falls back to #content-id getAttribute("content") when the element has no .value', async () => {
    setCurrentScript('http://h/download/resources/plug:editor/editor-loader.js')
    const { registered } = makeAJS()
    stubFetch(MANIFEST)
    ;(window as unknown as { LikeC4Editor: unknown }).LikeC4Editor = {
      createDeps: () => ({}),
      openMacroEditor: (opts: { onInsert: (u: unknown) => void }) => opts.onInsert({ name: 'likec4-diagram', params: {} }),
    }
    // A <meta id="content-id" content="99001"> — no .value, so the code must use getAttribute('content').
    const meta = document.createElement('meta')
    meta.id = 'content-id'
    meta.setAttribute('content', '99001')
    document.head.appendChild(meta)
    const rec = driveInsert()
    run()
    registered.override?.opener({ params: {} })
    await Promise.resolve()
    await Promise.resolve()
    expect(rec.received?.contentId).toBe('99001')
  })
})

describe('editor-loader entryUrl + entry-CSS', () => {
  it('derives the entry URL from its own src when served individually as editor-loader.js', async () => {
    setCurrentScript('http://h/download/resources/plug:editor/editor-loader.js')
    const { registered } = makeAJS()
    stubFetch(MANIFEST)
    run()
    // injectEntryCss runs inside ensureBundle(), which the opener drives.
    registered.override?.opener({ params: {} })
    await flushFetch()
    // resBase = own dir; entry css sidecar (runtime) injected, lazy Diagram css skipped. Also confirms
    // the ESM entry itself was built from the own-src path.
    expect(cssLinks()).toEqual(['http://h/download/resources/plug:editor/likec4-web/assets/runtime-DxMEJQXw.css'])
    expect(cssLinks().some((h) => h?.includes('Diagram'))).toBe(false)
    expect(esmEntry()?.src).toBe('http://h/download/resources/plug:editor/likec4-web/assets/editor-confluence.js')
  })

  it('derives the entry URL from its own src even when the src carries a #fragment', async () => {
    // A script src ending "editor-loader.js#..." must still take the own-src branch, not fall through to
    // the MODULE_KEY/contextPath path. Confluence's WRM does not emit fragments today, so this is
    // defensive — the own-src regex just handles "?" or end-of-string besides "#".
    setCurrentScript('http://h/download/resources/plug:editor/editor-loader.js#anything')
    const { registered } = makeAJS()
    stubFetch(MANIFEST)
    run()
    registered.override?.opener({ params: {} })
    await flushFetch()
    expect(esmEntry()?.src).toBe('http://h/download/resources/plug:editor/likec4-web/assets/editor-confluence.js')
  })

  it('builds the entry URL from MODULE_KEY + contextPath when served in a batch (src is not editor-loader.js)', async () => {
    // Batched: currentScript.src is the batch URL, not editor-loader.js -> use contextPath + MODULE_KEY.
    setCurrentScript('http://h/s/x/_/download/batch/whatever/whatever.js')
    const { registered } = makeAJS({ contextPath: () => '/wiki' })
    stubFetch(MANIFEST)
    run()
    registered.override?.opener({ params: {} })
    await flushFetch()
    expect(cssLinks()).toEqual([
      `/wiki/download/resources/${MODULE_KEY}/likec4-web/assets/runtime-DxMEJQXw.css`,
    ])
    expect(esmEntry()?.getAttribute('src')).toBe(
      `/wiki/download/resources/${MODULE_KEY}/likec4-web/assets/editor-confluence.js`,
    )
  })

  it('the idempotency <meta> marker prevents a double fetch/inject; a null manifest drops it for retry', async () => {
    setCurrentScript('http://h/download/resources/plug:editor/editor-loader.js')

    // First open (fresh IIFE eval): manifest !ok -> null -> marker removed, nothing injected. (The ESM
    // entry <script> is also injected but that's asserted elsewhere; here we assert only the CSS marker.)
    const first = makeAJS()
    const firstFetch = stubFetch(null)
    run()
    first.registered.override?.opener({ params: {} })
    await flushFetch()
    expect(firstFetch).toHaveBeenCalledTimes(1)
    expect(document.getElementById('likec4-editor-entry-css')).toBeNull()
    expect(cssLinks()).toEqual([])

    // Second open with a good manifest now succeeds because the marker was dropped (regression parity
    // with boot-loader commit 5773ea1).
    const second = makeAJS()
    const secondFetch = stubFetch(MANIFEST)
    // The first open injected the ESM entry <script>; a fresh IIFE (a new editor page-load) would start
    // from a clean DOM. Drop that leftover element so the second ensureBundle takes the fresh-inject
    // branch rather than the `existing`-element branch — the latter starts a real waitForGlobal
    // setTimeout poll that outlives this test (window is torn down before it fires → an unhandled
    // ReferenceError that makes vitest exit non-zero even though every test passed).
    document.getElementById('likec4-editor-esm-entry')?.remove()
    run()
    second.registered.override?.opener({ params: {} })
    await flushFetch()
    expect(secondFetch).toHaveBeenCalledTimes(1)
    expect(cssLinks()).toEqual(['http://h/download/resources/plug:editor/likec4-web/assets/runtime-DxMEJQXw.css'])
  })

  it('the marker makes a re-open on the same page a no-op (no second fetch, no duplicate link)', async () => {
    setCurrentScript('http://h/download/resources/plug:editor/editor-loader.js')
    const { registered } = makeAJS()
    const fetchMock = stubFetch(MANIFEST)
    run()
    registered.override?.opener({ params: {} })
    await flushFetch()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const before = cssLinks().length
    // Re-open within the SAME IIFE closure (same page session): injectEntryCss early-returns on marker.
    registered.override?.opener({ params: {} })
    await flushFetch()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(cssLinks().length).toBe(before)
  })
})

describe('editor-loader ensureBundle retry after onerror', () => {
  it('re-injects a fresh <script type="module"> after the previous entry errored', async () => {
    setCurrentScript('http://h/download/resources/plug:editor/editor-loader.js')
    stubFetch(MANIFEST)
    const { registered } = makeAJS()
    run()

    // First open: ensureBundle injects the ESM entry <script>. Simulate a load failure (onerror) —
    // this must remove the dead element AND reject the cached promise so the next open retries.
    registered.override?.opener({ params: {} })
    await Promise.resolve()
    const first = esmEntry()
    expect(first).not.toBeNull()
    expect(first?.type).toBe('module')
    expect(first?.src).toBe('http://h/download/resources/plug:editor/likec4-web/assets/editor-confluence.js')
    first?.onerror?.(new Event('error'))
    await Promise.resolve()
    await Promise.resolve()
    // Dead element removed so the retry path doesn't re-poll a bundle that will never publish.
    expect(esmEntry()).toBeNull()

    // Second open re-injects a FRESH entry script (the cached rejected promise was nulled).
    registered.override?.opener({ params: {} })
    await Promise.resolve()
    const second = esmEntry()
    expect(second).not.toBeNull()
    expect(second).not.toBe(first)
  })
})

describe('editor-loader onInsert error routing (Finding 1)', () => {
  // Publish a LikeC4Editor global whose openMacroEditor CAPTURES the onInsert callback instead of calling
  // it inline. In production openMacroEditor mounts a dialog and onInsert fires later from the Insert
  // button's CLICK handler — i.e. OUTSIDE opener()'s `ensureBundle().then(...).catch(notifyError)` chain.
  // Invoking the captured callback here reproduces that: without the Finding-1 try/catch, a synchronous
  // throw from insertMacro escapes to the caller (the click handler) uncaught rather than being caught by
  // the opener's promise .catch — so the test can distinguish the fixed vs unfixed behaviour.
  function publishEditor(): { onInsert?: (u: unknown) => void } {
    const captured: { onInsert?: (u: unknown) => void } = {}
    ;(window as unknown as { LikeC4Editor: unknown }).LikeC4Editor = {
      createDeps: () => ({}),
      openMacroEditor: (opts: { onInsert: (u: unknown) => void }) => {
        captured.onInsert = opts.onInsert
      },
    }
    return captured
  }
  const macro = { name: 'likec4-diagram', params: {} }

  it('shows a visible flag when tinymce is absent (insertMacro returns false)', async () => {
    setCurrentScript('http://h/download/resources/plug:editor/editor-loader.js')
    const { registered, flags } = makeAJS()
    stubFetch(MANIFEST)
    ;(window as unknown as { tinymce?: unknown }).tinymce = undefined // insertMacro -> false
    const captured = publishEditor()
    run()
    registered.override?.opener({ params: {} })
    await Promise.resolve()
    await Promise.resolve()
    // Simulate the later Insert click.
    captured.onInsert?.(macro)
    expect(flags.length).toBe(1)
    expect(flags[0].type).toBe('error')
    expect(flags[0].body).toContain('tinymce unavailable')
  })

  it('routes a SYNCHRONOUS throw from MacroUtils.insertMacro to the visible flag (was uncaught before)', async () => {
    setCurrentScript('http://h/download/resources/plug:editor/editor-loader.js')
    const { registered, flags } = makeAJS()
    stubFetch(MANIFEST)
    // tinymce present, but insertMacro throws synchronously (e.g. the placeholder POST throws).
    ;(window as unknown as { tinymce: unknown }).tinymce = {
      confluence: {
        MacroUtils: {
          insertMacro: () => {
            throw new Error('illegal argument')
          },
        },
      },
    }
    const captured = publishEditor()
    run()
    registered.override?.opener({ params: {} })
    await Promise.resolve()
    await Promise.resolve()
    // The Insert click: without the fix this throw escapes the click handler uncaught (silent failure,
    // no flag); with the try/catch it becomes a visible error flag.
    expect(() => captured.onInsert?.(macro)).not.toThrow()
    expect(flags.length).toBe(1)
    expect(flags[0].type).toBe('error')
    expect(flags[0].body).toBe('illegal argument')
  })
})
