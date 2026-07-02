import { expect, test, type Page } from '@playwright/test'

// VISUAL VERIFICATION SWEEP for the LikeC4 plugin against the LIVE Confluence 10.2.13 + PostgreSQL
// stack. Each test sets up one scenario, captures a clean screenshot under /e2e (==docker/e2e), and
// logs the key DOM signals so a human can independently eyeball the render. These are *observation*
// tests: they wait for the relevant state, log it, and screenshot — they do not try to mask defects.
//
const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
const AUTH = 'Basic ' + Buffer.from(`${process.env.AUTH_USER || 'admin'}:${process.env.AUTH_PASS || 'admin'}`).toString('base64')

// Each scenario's macro page is SELF-SEEDED via REST when its env override is unset, so the sweep
// reproduces on a fresh `up.sh` stack instead of depending on stale magic page ids from one historical
// seeding (which 404 with an opaque error on a fresh stack). Override with the env var to reuse a page.
async function pageId(env: string | undefined, p: { ref: string; path: string; view: string }): Promise<string> {
  if (env) return env
  const macro =
    '<ac:structured-macro ac:name="likec4-diagram">' +
    '<ac:parameter ac:name="project">acme/architecture</ac:parameter>' +
    `<ac:parameter ac:name="ref">${p.ref}</ac:parameter>` +
    `<ac:parameter ac:name="path">${p.path}</ac:parameter>` +
    `<ac:parameter ac:name="view">${p.view}</ac:parameter></ac:structured-macro>`
  const res = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({
      type: 'page', title: `Sweep ${p.view}/${p.path} ${Date.now()}`, space: { key: 'LIKEC4' },
      body: { storage: { value: macro, representation: 'storage' } },
    }),
  })
  expect(res.status, `sweep self-seed (${p.view}/${p.path}) page create must succeed`).toBe(200)
  return (await res.json()).id as string
}

const INDEX = { ref: 'main2', path: 'ok', view: 'index' } // existing demo
const SYS_DETAIL = { ref: 'main2', path: 'ok', view: 'sys_detail' }
const BROKEN = { ref: 'brk1', path: 'broken', view: 'index' }
const DRIFT = { ref: 'drift1', path: 'drifted', view: 'index' }

function parseScale(transform: string | null): number {
  // NaN (not 0) when no transform/scale is present, so a caller can tell "unparseable" from a real 0/collapse.
  if (!transform) return NaN
  const m = /scale\(([-\d.]+)/.exec(transform)
  if (m) return parseFloat(m[1])
  const mm = /matrix\(([-\d.]+)/.exec(transform)
  return mm ? parseFloat(mm[1]) : NaN
}

async function dismissOnboarding(page: Page) {
  try {
    // Scope to dialog/notification chrome only — a bare button:has-text("Close") can match an unrelated
    // panel and dismiss the diagram chrome the screenshot is meant to prove.
    const close = page.locator(
      '.aui-dialog2 [aria-label="Close"], [role="dialog"] button:has-text("Close"), .aui-message [aria-label="Close"]',
    ).first()
    if (await close.isVisible().catch(() => false)) { await close.click({ timeout: 3_000 }); await page.waitForTimeout(500) }
  } catch { /* overlay handling must never fail the sweep */ }
}

async function gotoViewPage(page: Page, pageId: string) {
  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  await page.goto(`${BASE}/pages/viewpage.action?pageId=${pageId}&os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  await dismissOnboarding(page)
  await expect(page.locator('.likec4-diagram'), '.likec4-diagram macro div must exist').toHaveCount(1, { timeout: 15_000 })
}

// 1) sys_detail view renders coherently.
test('1 sys_detail view renders', async ({ page }) => {
  test.setTimeout(180_000)
  page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 160)))
  await gotoViewPage(page, await pageId(process.env.SYS_DETAIL_PAGE, SYS_DETAIL))

  const viewer = page.locator('[data-testid="likec4-diagram"]')
  await viewer.waitFor({ timeout: 150_000, state: 'attached' })
  await page.locator('.react-flow__node').first().waitFor({ timeout: 30_000 })
  await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(2_000)

  const currentView = await viewer.getAttribute('data-current-view')
  const nodeCount = await page.locator('.react-flow__node').count()
  const labels = await page.locator('.react-flow__node').allInnerTexts()
  const clientHeight = await page.locator('.likec4-diagram').first().evaluate(el => (el as HTMLElement).clientHeight)
  const scale = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
  console.log('SYS_DETAIL data-current-view=', currentView)
  console.log('SYS_DETAIL node count=', nodeCount)
  console.log('SYS_DETAIL node labels=', JSON.stringify(labels))
  console.log('SYS_DETAIL container height=', clientHeight, 'scale=', scale)
  await page.screenshot({ path: '/e2e/sweep-sys-detail.png', fullPage: true })
  expect(currentView).toBe('sys_detail')
  // sys_detail renders a multi-node graph (measured live: 3 nodes); assert >= 2, not the toothless >= 1,
  // so a regression that collapses it to a single node fails here too.
  expect(nodeCount).toBeGreaterThanOrEqual(2)
  // The height/scale were already measured above but only logged — assert them (the same thresholds
  // test 2/big-model use) so a collapsed/invisible render is caught here too, not just eyeballed. The
  // scale bound is strictly ABOVE the collapse value (a >= 0.05 guard passes at the exact 0.05 collapse);
  // the healthy scale is far higher (measured live: 0.57), so > 0.05 has teeth.
  expect(clientHeight, 'the diagram container must have a real, non-collapsed height').toBeGreaterThanOrEqual(300)
  expect(scale, 'the diagram must be zoomed to a visible scale, not collapsed').toBeGreaterThan(0.05)
})

// 2) path=broken -> clean error PANEL (not blank, not a raw stack trace, not a crash).
test('2 broken project shows a clean error panel', async ({ page }) => {
  test.setTimeout(150_000)
  page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 160)))
  await gotoViewPage(page, await pageId(process.env.BROKEN_PAGE, BROKEN))

  const err = page.locator('[data-testid="likec4-error"]')
  await err.waitFor({ timeout: 150_000, state: 'visible' })
  await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(500)

  const title = await err.locator('strong').innerText().catch(() => '(no title)')
  const lines = await err.locator('li').allInnerTexts()
  const role = await err.getAttribute('role')
  const cls = await err.getAttribute('class')
  const nodeCount = await page.locator('.react-flow__node').count()
  const bg = await err.evaluate(el => getComputedStyle(el as HTMLElement).backgroundColor)
  console.log('BROKEN error title=', JSON.stringify(title))
  console.log('BROKEN error lines=', JSON.stringify(lines))
  console.log('BROKEN role=', role, 'class=', cls, 'bg=', bg)
  console.log('BROKEN react-flow node count (expect 0)=', nodeCount)
  await page.screenshot({ path: '/e2e/sweep-broken.png', fullPage: true })
  expect(title.length).toBeGreaterThan(0)
  expect(lines.length).toBeGreaterThan(0)
  // REGRESSION (CSS-injection gap): the error panel must be STYLED even though it never loads the
  // lazy Diagram chunk. boot-loader.js injects the entry stylesheet (.likec4-error etc.) from the
  // Vite manifest; the old bug rendered this panel unstyled (transparent bg). A transparent/default
  // background here means our entry CSS did not load -> fail the build.
  expect(nodeCount, 'error path must NOT load the diagram chunk').toBe(0)
  expect(bg, 'error panel must have the red styled background (not transparent)').toBe('rgb(248, 215, 218)')
})

// 3) node-click navigation: index -> sys_detail in place (no recompute).
test('3 node-click navigates the view in place', async ({ page }) => {
  test.setTimeout(180_000)
  page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 160)))
  await gotoViewPage(page, await pageId(process.env.INDEX_PAGE, INDEX))

  const viewer = page.locator('[data-testid="likec4-diagram"]')
  await viewer.waitFor({ timeout: 150_000, state: 'attached' })
  await page.locator('.react-flow__node').first().waitFor({ timeout: 30_000 })
  await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(2_000)

  const before = await viewer.getAttribute('data-current-view')
  const nodesBefore = await page.locator('.react-flow__node').allInnerTexts()
  console.log('NAV before data-current-view=', before)
  console.log('NAV before nodes=', JSON.stringify(nodesBefore))
  await page.screenshot({ path: '/e2e/sweep-nav-before.png', fullPage: true })

  // Navigate: this likec4 build wires the `navigateTo: sys_detail` affordance to an explicit
  // button on the navigable node (NOT dblclick — dblclick/single-click do nothing here, confirmed
  // by introspection). Hover the `sys` node to reveal the button, then click it. data-current-view
  // must flip index -> sys_detail in place.
  const sysNode = page.locator('.react-flow__node[data-id="sys"]')
  // Assert the node + its hover-revealed navigate button actually exist BEFORE interacting, so a fixture
  // /render change (node id renamed, affordance removed) fails fast and legibly here rather than as an
  // opaque 15s data-current-view timeout below. (The node is asserted visible; the button's presence is
  // asserted with toBeAttached below — see the comment there for why.)
  await expect(sysNode, 'the navigable `sys` node must be present to navigate from').toBeVisible()
  await sysNode.scrollIntoViewIfNeeded()
  await sysNode.hover()
  // Poll for the hover-revealed navigate button instead of a fixed sleep: toBeAttached retries until the
  // overlay button appears (or times out legibly), which is more robust than a brittle 400ms wait that
  // can race the affordance reveal under load.
  const navButton = sysNode.locator('button').first()
  await expect(navButton, 'the `sys` node must expose a navigate button (affordance missing?)').toBeAttached()
  await navButton.click({ force: true })
  await expect(viewer).toHaveAttribute('data-current-view', 'sys_detail', { timeout: 15_000 })
  await page.waitForTimeout(2_000)

  const after = await viewer.getAttribute('data-current-view')
  const nodesAfter = await page.locator('.react-flow__node').allInnerTexts()
  console.log('NAV after data-current-view=', after)
  console.log('NAV after nodes=', JSON.stringify(nodesAfter))
  await page.screenshot({ path: '/e2e/sweep-nav-after.png', fullPage: true })
  expect(before).toBe('index')
  expect(after).toBe('sys_detail')
})

// 4) drift banner: a stale curated layout warns but still renders.
test('4 drift banner renders with the diagram', async ({ page }) => {
  test.setTimeout(180_000)
  page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 160)))
  await gotoViewPage(page, await pageId(process.env.DRIFT_PAGE, DRIFT))

  const viewer = page.locator('[data-testid="likec4-diagram"]')
  await viewer.waitFor({ timeout: 150_000, state: 'attached' })
  const banner = page.locator('[data-testid="likec4-drift-banner"]')
  await banner.waitFor({ timeout: 60_000, state: 'visible' })
  await page.locator('.react-flow__node').first().waitFor({ timeout: 30_000 })
  await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(2_000)

  const bannerText = await banner.innerText()
  const nodeCount = await page.locator('.react-flow__node').count()
  const scale = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
  console.log('DRIFT banner text=', JSON.stringify(bannerText))
  console.log('DRIFT node count=', nodeCount, 'scale=', scale)
  await page.screenshot({ path: '/e2e/sweep-drift.png', fullPage: true })
  expect(bannerText).toContain('Curated layout may be stale')
  // The drifted landscape renders the full 2-node graph (measured live: 2 nodes) alongside the banner;
  // assert >= 2, not the toothless >= 1, so a node-dropping regression under the drift path also fails.
  expect(nodeCount).toBeGreaterThanOrEqual(2)
})

// 5) full screen + zoom interaction.
test('5 full screen expands and zoom works', async ({ page }) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1280, height: 900 })
  page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 160)))
  await gotoViewPage(page, await pageId(process.env.INDEX_PAGE, INDEX))

  const viewer = page.locator('[data-testid="likec4-diagram"]')
  await viewer.waitFor({ timeout: 150_000, state: 'attached' })
  await page.locator('.react-flow__node').first().waitFor({ timeout: 30_000 })
  await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(2_000)

  await page.getByTestId('likec4-fullscreen-toggle').click()
  await expect(viewer).toHaveClass(/likec4-fullscreen/)
  await page.waitForTimeout(1_000)
  const pos = await viewer.evaluate(el => {
    const s = getComputedStyle(el as HTMLElement)
    const r = (el as HTMLElement).getBoundingClientRect()
    return { position: s.position, top: s.top, left: s.left, w: r.width, h: r.height }
  })
  const vp = page.viewportSize()!
  console.log('FULLSCREEN computed=', JSON.stringify(pos), 'viewport=', JSON.stringify(vp))
  // Clean fullscreen screenshot (before zooming, so it shows the expanded diagram framed normally).
  await page.screenshot({ path: '/e2e/sweep-fullscreen.png', fullPage: false })

  // Zoom interaction: in likec4 a plain wheel PANS (panOnScroll); Ctrl+wheel ZOOMS. Verify the
  // react-flow viewport SCALE changes under Ctrl+wheel.
  const scaleBefore = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
  const pane = page.locator('.react-flow__pane').first()
  const box = await pane.boundingBox()
  // Fail explicitly if the pane has no layout box, rather than skipping the zoom and then comparing two
  // identical pre-zoom scales (which would fail later with a misleading "scales are equal" message).
  expect(box, 'the react-flow pane must have a layout box to drive the zoom').toBeTruthy()
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2)
  await page.keyboard.down('Control')
  await page.mouse.wheel(0, -400)
  await page.waitForTimeout(300)
  await page.mouse.wheel(0, -400)
  await page.keyboard.up('Control')
  await page.waitForTimeout(600)
  const scaleAfter = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
  console.log('FULLSCREEN scale before=', scaleBefore, 'after=', scaleAfter)
  expect(pos.position).toBe('fixed')
  expect(pos.w).toBeGreaterThanOrEqual(vp.width - 4)
  // Ctrl+wheel UP zooms IN, so the viewport scale must INCREASE — not merely "differ" (a pan or a
  // rounding jitter would satisfy a !== check but is not a zoom).
  expect(scaleAfter).toBeGreaterThan(scaleBefore)
})

// 6) editor-inserted render: author via the native Macro Browser, publish, view the saved page.
test('6 native-editor-authored macro renders on the saved page', async ({ page }) => {
  test.setTimeout(300_000)
  page.on('pageerror', e => { if (!/emoji|emoticons/.test(e.message)) console.log('PAGEERROR:', e.message.slice(0, 160)) })
  page.on('console', m => { const t = m.text(); if (/error/i.test(t) && !/WRM|JQMIGRATE|DEPRECATED|emoji/.test(t)) console.log('C:', t.slice(0, 160)) })

  // Authenticate every request via HTTP Basic (seraph BasicAuthenticator). On the real 8090 stack
  // this is what carries auth through editpage + publish; a login.action GET does NOT establish a
  // usable editor session here.
  await page.setExtraHTTPHeaders({ Authorization: AUTH })

  const title = `Sweep editor authored ${Date.now()}`
  const createRes = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({ type: 'page', title, space: { key: 'LIKEC4' }, body: { storage: { value: '<p>authored via native macro browser</p>', representation: 'storage' } } }),
  })
  expect(createRes.status, 'page create must succeed').toBe(200)
  const pageId = (await createRes.json()).id as string
  console.log('EDITOR created page', pageId, 'url=', `${BASE}/pages/viewpage.action?pageId=${pageId}`)

  await page.goto(`${BASE}/pages/editpage.action?pageId=${pageId}`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  await page.locator('#wysiwygTextarea_ifr').waitFor({ timeout: 60_000 })
  const registered = await page.evaluate(async () => {
    const w = window as any
    for (let i = 0; i < 80 && !w.__likec4EditorOverrideRegistered; i++) await new Promise(r => setTimeout(r, 100))
    return !!w.__likec4EditorOverrideRegistered
  })
  expect(registered, 'editor override must be registered').toBe(true)

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
  await page.getByTestId('likec4-field-ref').fill('main')
  await page.getByTestId('likec4-field-path').fill('ok')
  await page.getByTestId('likec4-load-views').click()
  const picker = page.getByTestId('likec4-view-picker')
  await expect(picker).toBeVisible({ timeout: 90_000 })
  await expect(picker.locator('option')).toHaveCount(2, { timeout: 90_000 })
  await expect(page.getByTestId('likec4-field-view')).toHaveValue('index', { timeout: 30_000 })
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
    // rather than surfacing later as a misattributed "diagram did not render".
    .catch(() => console.log('post-publish navigation did not match viewpage/display within 60s; proceeding to the viewer check on', page.url()))
  console.log('EDITOR after publish url=', page.url())

  const viewer = page.locator('.likec4-diagram [data-testid="likec4-diagram"]')
  await expect(viewer).toBeVisible({ timeout: 120_000 })
  await page.locator('.react-flow__node').first().waitFor({ timeout: 30_000 })
  await page.waitForTimeout(2_000)
  const nodeCount = await page.locator('.react-flow__node').count()
  const scale = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
  const currentView = await viewer.getAttribute('data-current-view')
  console.log('EDITOR rendered page url=', page.url())
  console.log('EDITOR data-current-view=', currentView, 'node count=', nodeCount, 'scale=', scale)
  await page.screenshot({ path: '/e2e/sweep-editor-render.png', fullPage: true })
  // The editor authored the index view of the 2-node demo model (measured live: 2 nodes); assert >= 2,
  // not the toothless >= 1, so an editor-authored render that drops a node fails here too.
  expect(nodeCount).toBeGreaterThanOrEqual(2)
})
