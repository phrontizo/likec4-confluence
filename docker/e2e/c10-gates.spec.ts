import { expect, test, type Page } from '@playwright/test'

// RUNTIME VERIFICATION GATES for the LikeC4 plugin on Confluence DC 10.2.13 (the confluence-10 branch).
// Each test drives the LIVE stack via containerised Playwright, asserts the runtime contract, and
// captures a screenshot a human can independently eyeball. Auth is HTTP Basic (the stack re-enables
// it via -Dcom.atlassian.plugins.authentication.basic.auth.filter.force.allow=true).
const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
// An explicit page id can be supplied; otherwise GATE3 SELF-SEEDS its render page via REST so the gate
// reproduces on a fresh `up.sh` stack (no dependency on a manually-seeded magic id).
const RENDER_PAGE = process.env.RENDER_PAGE
// Honour the same creds the shell scripts parameterise (AUTH_USER/AUTH_PASS), defaulting to the fixed
// test creds — so changing the admin password in up.sh doesn't silently 401 the gate while the scripts pass.
const AUTH_USER = process.env.AUTH_USER || 'admin'
const AUTH_PASS = process.env.AUTH_PASS || 'admin'
const AUTH = 'Basic ' + Buffer.from(`${AUTH_USER}:${AUTH_PASS}`).toString('base64')

// Storage-format body of a LikeC4 macro page (project=acme/architecture ref=main2 path=ok view=index).
const RENDER_STORAGE =
  '<ac:structured-macro ac:name="likec4-diagram">' +
  '<ac:parameter ac:name="project">acme/architecture</ac:parameter>' +
  '<ac:parameter ac:name="ref">main2</ac:parameter>' +
  '<ac:parameter ac:name="path">ok</ac:parameter>' +
  '<ac:parameter ac:name="view">index</ac:parameter></ac:structured-macro>'

/** Resolve the render page id: an env override if set, else create a fresh macro page via REST. */
async function renderPageId(): Promise<string> {
  if (RENDER_PAGE) return RENDER_PAGE
  const res = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({
      type: 'page', title: `C10 GATE3 render ${Date.now()}`, space: { key: 'LIKEC4' },
      body: { storage: { value: RENDER_STORAGE, representation: 'storage' } },
    }),
  })
  expect(res.status, 'GATE3 render-page create must succeed').toBe(200)
  return (await res.json()).id as string
}

function parseScale(transform: string | null): number {
  // NaN (not 0) when no transform/scale is present, so a caller can tell "unparseable" from a real 0/collapse.
  if (!transform) return NaN
  const m = /scale\(([-\d.]+)/.exec(transform)
  if (m) return parseFloat(m[1])
  const mm = /matrix\(([-\d.]+)/.exec(transform)
  return mm ? parseFloat(mm[1]) : NaN
}

async function dismissOnboarding(page: Page) {
  // Confluence 10.2 pops a "Check out what's changed" onboarding modal (and a license health-check
  // flag) that can appear a beat after load; try a few times so it does not obscure the screenshot.
  for (let i = 0; i < 4; i++) {
    try {
      const close = page.locator(
        '.aui-dialog2 [aria-label="Close"], [role="dialog"] button:has-text("Close"), button:has-text("Don\'t remind me again"), .aui-message [aria-label="Close"]',
      ).first()
      if (await close.isVisible().catch(() => false)) {
        await close.click({ timeout: 3_000 }).catch(() => {})
        await page.waitForTimeout(400)
        continue
      }
    } catch { /* overlay handling must never fail a gate */ }
    await page.waitForTimeout(600)
  }
}

// GATE 3 — MACRO RENDER: a storage-format macro page renders the LikeC4 diagram (react-flow nodes).
test('GATE3 macro render on Confluence 10.2.13', async ({ page }) => {
  test.setTimeout(240_000)
  // Collect uncaught page errors and assert NONE (bar known-benign chrome noise) at the end: the
  // positive render assertions below still pass if a JS error fired but left the 2-node render intact,
  // so without this a runtime regression that doesn't visibly break the diagram would slip through.
  const IGNORED_PAGEERROR = /emoji|emoticons|ResizeObserver/
  // Ignore known browser/platform chrome noise (e.g. the "ResizeObserver loop" warning) ONLY when it does
  // NOT originate in our bundle — "ResizeObserver" in particular is a common substring in xyflow/react-flow
  // error chains, so a real likec4-web error whose message merely contains it must still fail the gate.
  // Mirror GATE5's likec4-web stack-scoping; fall back to the message match when no stack is available.
  const isBenignChromeNoise = (e: Error) => {
    if (!IGNORED_PAGEERROR.test(e.message)) return false
    const stack = e.stack ?? ''
    if (/likec4-web/.test(stack)) return false // originates in our bundle -> never benign
    if (stack) return true // has a (non-bundle) stack -> genuine platform/chrome noise
    // No stack to scope by origin: "ResizeObserver" alone is a common substring in xyflow error chains, so
    // a STACKLESS error is benign only for the exact "ResizeObserver loop ..." browser warning (or the
    // emoji/emoticons platform noise) — a stackless bundle error whose message merely CONTAINS
    // "ResizeObserver" must still fail the gate.
    return /ResizeObserver loop /.test(e.message) || /emoji|emoticons/.test(e.message)
  }
  const pageErrors: string[] = []
  page.on('pageerror', e => {
    if (!isBenignChromeNoise(e)) { pageErrors.push(e.message); console.log('PAGEERROR:', e.message.slice(0, 200)) }
  })
  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  const renderPage = await renderPageId()
  await page.goto(`${BASE}/pages/viewpage.action?pageId=${renderPage}&os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  await dismissOnboarding(page)

  await expect(page.locator('.likec4-diagram'), '.likec4-diagram macro div must exist').toHaveCount(1, { timeout: 30_000 })
  const viewer = page.locator('[data-testid="likec4-diagram"]')
  // Assert real VISIBILITY, not just attachment: a 0-height / display:none container with a mounted
  // react-flow would pass an attached check but is not actually rendered for a human.
  await expect(viewer).toBeVisible({ timeout: 180_000 })
  await page.locator('.react-flow__node').first().waitFor({ timeout: 60_000 })
  await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(2_000)

  await dismissOnboarding(page) // the "what's changed" modal can appear a beat after load
  const currentView = await viewer.getAttribute('data-current-view')
  const nodeCount = await page.locator('.react-flow__node').count()
  const labels = await page.locator('.react-flow__node').allInnerTexts()
  const scale = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
  const clientHeight = await viewer.evaluate(el => (el as HTMLElement).clientHeight)
  console.log('GATE3 data-current-view=', currentView)
  console.log('GATE3 node count=', nodeCount, 'scale=', scale, 'clientHeight=', clientHeight)
  console.log('GATE3 node labels=', JSON.stringify(labels))
  await page.screenshot({ path: '/e2e/c10-render.png', fullPage: true })
  expect(currentView).toBe('index')
  // The seeded path=ok view=index is the curated 2-node demo (systems `sys` + `ext`; see the mock repo
  // and postgres-render-visible.spec.ts). Assert EXACTLY two nodes — a degenerate render that DROPS a node
  // OR a model-merge regression that ADDS a spurious node (which a >= 2 would wave through) must fail the
  // gate it is the sole guardian of. The fixture node count is deterministic (2 top-level systems).
  expect(nodeCount, 'the index view of the demo model renders exactly two nodes').toBe(2)
  expect(scale).toBeGreaterThan(0)
  // Not collapsed: the diagram container must have real height (catches the fitView-to-nothing bug).
  expect(clientHeight, 'the diagram container must have a real, non-collapsed height').toBeGreaterThanOrEqual(300)
  expect(pageErrors, 'no uncaught page errors (bar known-benign chrome noise) during the render').toHaveLength(0)
})

// GATE 4 — ADMIN SERVLET: an authenticated admin reaches the Jakarta config form with the saved config
// loaded. NOTE on scope: this gate authenticates via HTTP Basic, which the servlet deliberately EXEMPTS
// from WebSudo (a Basic request re-presents the password each call), so the WebSudo password branch
// below is effectively dead on this harness — and Secure Admin Sessions is OFF by default here anyway,
// so there is nothing to elevate against. WebSudo enforcement for an interactive COOKIE session
// (challenge presented, form NOT rendered until elevated; the Basic-auth exemption; non-admin 403 before
// any challenge) is proven by the wrapper unit tests in AdminServletTest, not by this gate. This gate
// therefore proves: (a) an admin can reach + load the form, and (b) — added below — the endpoint is NOT
// open to an unauthenticated caller (so the happy path passes on the credential, not an open servlet).
test('GATE4 admin servlet on Confluence 10.2.13', async ({ page }) => {
  test.setTimeout(120_000)

  // Negative check FIRST, with no Authorization header: the admin config form must NOT be served to an
  // unauthenticated caller (the servlet bounces it to login). `fetch` here carries no page headers, and
  // redirect:'manual' keeps the 3xx so we can ASSERT the bounce — a followed-redirect login page also
  // lacks the form id, so the body check alone would pass even on a wrongly-200 open servlet.
  const unauth = await fetch(`${BASE}/plugins/servlet/likec4/admin`, { redirect: 'manual' })
  const unauthBody = await unauth.text()
  const unauthLocation = unauth.headers.get('location') || ''
  console.log('GATE4 unauthenticated status=', unauth.status, 'location=', unauthLocation)
  expect([301, 302, 303, 307, 308, 401, 403],
    `an unauthenticated caller must be redirected to login or denied, got ${unauth.status}`)
    .toContain(unauth.status)
  expect(unauthBody, 'the admin config form must NOT be served to an unauthenticated caller')
    .not.toContain('id="likec4-baseUrl"')
  // If the bounce is a redirect, assert its target is a Confluence login/auth endpoint — so this proves a
  // real login bounce, not merely that the form markup is absent from a body (which a wrongly-open page or
  // a redirect looping back to the servlet would also satisfy). Skipped for a 401/403 (no Location).
  if (unauth.status >= 300 && unauth.status < 400) {
    expect(unauthLocation, 'a 3xx bounce must carry a Location that targets Confluence login/auth')
      .toMatch(/login|authenticate|auth/i)
  }

  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  await page.goto(`${BASE}/plugins/servlet/likec4/admin?os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })

  // WebSudo challenge: a fresh (un-elevated) session is bounced to the password re-auth form. Complete
  // it. (Guarded so the gate also passes if the session is already elevated or the feature is off.)
  const onWebSudo = await page.locator('#password').isVisible({ timeout: 5_000 }).catch(() => false)
  console.log('GATE4 websudo challenge presented=', onWebSudo)
  if (onWebSudo) {
    await page.fill('#password', AUTH_PASS)
    await Promise.all([
      page.waitForLoadState('domcontentloaded', { timeout: 30_000 }),
      page.click('#authenticateButton'),
    ])
  }
  await dismissOnboarding(page)

  await expect(page.locator('#likec4-admin-form')).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('#likec4-baseUrl')).toBeVisible()
  await expect(page.locator('#likec4-allowlist')).toBeVisible()
  // The form's load() pulls the saved config from the REST endpoint; the baseUrl should populate.
  await page.waitForTimeout(1_500)
  const baseUrlVal = await page.locator('#likec4-baseUrl').inputValue()
  const allowlistVal = await page.locator('#likec4-allowlist').inputValue()
  console.log('GATE4 loaded baseUrl=', baseUrlVal, '| allowlist=', allowlistVal)
  expect(baseUrlVal).toContain('mockgitlab')
  await page.screenshot({ path: '/e2e/c10-admin.png', fullPage: true })
})

// GATE 5 — EDITOR: author the macro via the native Macro Browser (the editor JS override + custom
// "Load views" dialog), publish, and confirm it renders on the saved page. The unknown on 10.2 is
// whether the classic editor (AJS.MacroBrowser / tinymce.confluence.MacroUtils) still exists.
test('GATE5 macro editor on Confluence 10.2.13', async ({ page }) => {
  test.setTimeout(360_000)
  // Known-benign UPSTREAM Confluence bug (filtered so it isn't logged as alarming noise): when a macro
  // tile is selected, Confluence loads a macro-browser PREVIEW whose handler `AJS.MacroBrowser.previewOnload`
  // (and the preview iframe's own about:srcdoc onload) null-deref `dialog.activeMetadata.macroName` / `.body`.
  // This happens for ANY macro that uses a `setMacroJsOverride` opener — which bypasses the default dialog
  // that would set `activeMetadata` — i.e. our editor override AND Confluence's own jira*/jirachart macros.
  // It is in uneditable platform code and cannot be fixed from the plugin (verified: priming activeMetadata
  // only swaps `macroName`→`body`; srcdoc-setter/setAttribute/MutationObserver interception don't reach the
  // platform-parsed iframe). Authoring is unaffected — this gate proves the macro still authors + renders.
  const IGNORED_PAGEERROR = /emoji|emoticons|ResizeObserver/
  // The known-benign macro-browser preview null-deref (reading 'macroName'/'body', see the note above)
  // lives in UNEDITABLE platform code, so its stack never references our bundle. Ignore THAT TypeError
  // shape ONLY when the stack does not point at likec4-web — so a real reading 'macroName'/'body'
  // regression FROM OUR bundle is not silently swallowed by a bare substring match (which would keep the
  // gate green through a genuine null-deref in our code). If no stack is available, fall back to the
  // message match so this can never regress the gate on the benign platform error.
  const isBenignMacroBrowserPreview = (e: Error) =>
    /reading '(macroName|body)'/.test(e.message) && !/likec4-web/.test(e.stack ?? '')
  // Same likec4-web stack-scoping for the chrome-noise filter: "ResizeObserver" et al. are ignored only
  // when they do not originate in our bundle, so a genuine bundle error carrying one of those substrings
  // still fails the gate rather than being swallowed by a bare message match.
  const isBenignChromeNoise = (e: Error) => {
    if (!IGNORED_PAGEERROR.test(e.message)) return false
    const stack = e.stack ?? ''
    if (/likec4-web/.test(stack)) return false // originates in our bundle -> never benign
    if (stack) return true // has a (non-bundle) stack -> genuine platform/chrome noise
    // No stack to scope by origin: "ResizeObserver" alone is a common substring in xyflow error chains, so
    // a STACKLESS error is benign only for the exact "ResizeObserver loop ..." browser warning (or the
    // emoji/emoticons platform noise) — a stackless bundle error whose message merely CONTAINS
    // "ResizeObserver" must still fail the gate.
    return /ResizeObserver loop /.test(e.message) || /emoji|emoticons/.test(e.message)
  }
  const pageErrors: string[] = []
  page.on('pageerror', e => {
    if (!isBenignChromeNoise(e) && !isBenignMacroBrowserPreview(e)) {
      pageErrors.push(e.message); console.log('PAGEERROR:', e.message.slice(0, 200))
    }
  })
  await page.setExtraHTTPHeaders({ Authorization: AUTH })

  const title = `C10 editor authored ${Date.now()}`
  const createRes = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({ type: 'page', title, space: { key: 'LIKEC4' }, body: { storage: { value: '<p>authored via native macro browser</p>', representation: 'storage' } } }),
  })
  expect(createRes.status, 'page create must succeed').toBe(200)
  const pageId = (await createRes.json()).id as string
  console.log('GATE5 created page', pageId)

  await page.goto(`${BASE}/pages/editpage.action?pageId=${pageId}`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })

  // Probe the classic editor surface. If 10.2 dropped TinyMCE this wait is where it fails.
  const editorPresent = await page.locator('#wysiwygTextarea_ifr').waitFor({ timeout: 90_000 }).then(() => true).catch(() => false)
  const apis = await page.evaluate(() => {
    const w = window as any
    return {
      macroBrowser: !!(w.AJS && w.AJS.MacroBrowser),
      macroUtils: !!(w.tinymce && w.tinymce.confluence && w.tinymce.confluence.MacroUtils),
      overrideRegistered: !!w.__likec4EditorOverrideRegistered,
    }
  })
  console.log('GATE5 editorPresent=', editorPresent, 'APIs=', JSON.stringify(apis))
  expect(editorPresent, 'classic TinyMCE editor (#wysiwygTextarea_ifr) must be present on 10.2').toBe(true)

  const registered = await page.evaluate(async () => {
    const w = window as any
    for (let i = 0; i < 100 && !w.__likec4EditorOverrideRegistered; i++) await new Promise(r => setTimeout(r, 100))
    return !!w.__likec4EditorOverrideRegistered
  })
  expect(registered, 'editor macro-browser override must register').toBe(true)

  await page.locator('#rte-button-insert').click().catch(() => {})
  await page.waitForTimeout(400)
  await page.locator('#rte-insert-macro').click()
  await page.locator('#macro-browser-dialog').waitFor({ timeout: 30_000 })
  const search = page.locator('#macro-browser-search')
  await search.click()
  await search.pressSequentially('likec4', { delay: 70 })
  const tile = page.locator('#macro-likec4-diagram')
  await expect(tile).toBeVisible({ timeout: 15_000 })
  await tile.scrollIntoViewIfNeeded().catch(() => {})
  await tile.click()

  const dialog = page.getByTestId('likec4-macro-dialog')
  await expect(dialog).toBeVisible({ timeout: 30_000 })
  await page.getByTestId('likec4-field-project').fill('acme/architecture')
  await page.getByTestId('likec4-field-ref').fill('main2')
  await page.getByTestId('likec4-field-path').fill('ok')
  await page.getByTestId('likec4-load-views').click()
  const picker = page.getByTestId('likec4-view-picker')
  await expect(picker).toBeVisible({ timeout: 90_000 })
  await expect(picker.locator('option')).toHaveCount(2, { timeout: 90_000 })
  await expect(page.getByTestId('likec4-field-view')).toHaveValue('index', { timeout: 30_000 })
  await page.screenshot({ path: '/e2e/c10-editor.png', fullPage: true })
  await page.getByTestId('likec4-macro-insert').click()
  await expect(dialog).toBeHidden({ timeout: 15_000 })

  const body = page.frameLocator('#wysiwygTextarea_ifr')
  await expect(body.locator('[data-macro-name="likec4-diagram"]')).toBeVisible({ timeout: 30_000 })
  await page.locator('#rte-button-publish').click()
  await page.waitForLoadState('domcontentloaded', { timeout: 60_000 })
  await page.waitForURL(/viewpage\.action|\/display\//, { timeout: 60_000 })
    // Best-effort: the post-publish URL scheme can vary across Confluence builds, so a non-match is not a
    // failure here — the load-bearing assertion is the viewer visibility below. But LOG a non-navigation
    // (instead of silently swallowing it) so a genuinely stuck/failed publish is legible in the output
    // rather than surfacing 120s later as a misattributed "diagram did not render".
    .catch(() => console.log('post-publish navigation did not match viewpage/display within 60s; proceeding to the viewer check on', page.url()))
  console.log('GATE5 after publish url=', page.url())

  const viewer = page.locator('.likec4-diagram [data-testid="likec4-diagram"]')
  await expect(viewer).toBeVisible({ timeout: 120_000 })
  await page.locator('.react-flow__node').first().waitFor({ timeout: 60_000 })
  await page.waitForTimeout(2_000)
  await dismissOnboarding(page) // the "what's changed" modal can pop on the freshly-loaded view page
  const nodeCount = await page.locator('.react-flow__node').count()
  const currentView = await viewer.getAttribute('data-current-view')
  console.log('GATE5 rendered view=', currentView, 'node count=', nodeCount)
  await page.screenshot({ path: '/e2e/c10-editor-rendered.png', fullPage: true })
  // Same curated 2-node demo as GATE3 (path=ok view=index) — assert EXACTLY two nodes (a dropped OR a
  // spurious extra node must fail), the fixture count being deterministic.
  expect(nodeCount, 'the authored index view renders exactly two nodes').toBe(2)
  // No uncaught page errors during authoring + render, EXCEPT the known-benign upstream macro-browser
  // preview bug (filtered above): a real regression in the editor override or render must fail the gate.
  expect(pageErrors, 'no uncaught page errors (bar the known-benign macro-browser bug) during authoring+render').toHaveLength(0)
})
