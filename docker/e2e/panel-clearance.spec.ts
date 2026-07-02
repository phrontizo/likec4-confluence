import { expect, test, type Page } from '@playwright/test'

// REGRESSION GUARD for the "top node hidden under the nav panel" polish bug.
//
// likec4's top-left navigation panel (logo + back/forward + the view-id button) is absolutely
// positioned OVER the top of the diagram. Its bottom sits ~78px below the macro container top.
// likec4's own default fit-view padding is only `top: 58px`, so on TALL views the topmost node fit
// to 58px and ended up half-hidden UNDER the panel (e.g. the green "interacts with the system"
// actor on big-cloud). Diagram.tsx now passes `fitViewPadding={{ top: 100, ... }}` so the topmost
// node clears the panel with breathing room.
//
// This spec drives the LIVE Confluence 10.2.13 + PostgreSQL stack and asserts, on each of the tall
// views named in the bug report, that the topmost react-flow node sits BELOW the nav panel (and a
// comfortable distance below the container top). It also screenshots a small 2-node view to confirm
// the extra padding did not awkwardly over-shrink it.
const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
const AUTH = 'Basic ' + Buffer.from(`${process.env.AUTH_USER || 'admin'}:${process.env.AUTH_PASS || 'admin'}`).toString('base64')

// The fit padding we apply: top is biased to clear the ~78px nav panel. The topmost node must sit at
// least this far below the container top (minus a little sub-pixel/zoom-rounding slack).
const TOP_PAD = 100
const TOP_CLEARANCE_MIN = 90

// Self-seed each scenario's macro page via REST so this guard reproduces on a fresh `up.sh` stack
// instead of depending on stale magic page ids (which 404 with an opaque error on a clean stack).
async function seedMacroPage(p: { ref: string; path: string; view: string }): Promise<string> {
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
      type: 'page', title: `Clearance ${p.view}/${p.path} ${Date.now()}`, space: { key: 'LIKEC4' },
      body: { storage: { value: macro, representation: 'storage' } },
    }),
  })
  expect(res.status, `panel-clearance self-seed (${p.view}/${p.path}) must succeed`).toBe(200)
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

async function gotoDiagram(page: Page, pageId: string) {
  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  await page.goto(`${BASE}/pages/viewpage.action?pageId=${pageId}&os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  try {
    const close = page.locator('.aui-dialog2 [aria-label="Close"], [role="dialog"] button:has-text("Close"), .aui-message [aria-label="Close"]').first()
    if (await close.isVisible().catch(() => false)) { await close.click({ timeout: 3_000 }); await page.waitForTimeout(500) }
  } catch { /* overlay handling must never fail the proof */ }
  await page.locator('[data-testid="likec4-diagram"]').waitFor({ timeout: 180_000, state: 'attached' })
  await page.locator('.react-flow__node').first().waitFor({ timeout: 40_000 })
  await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(2_500) // let fitView settle
}

// Geometry: container box, nav-panel bottom (relative to container top), and the topmost node's top
// (relative to container top). The nav panel is likec4's top-left content-navigation bar.
async function measure(page: Page) {
  return page.evaluate(() => {
    const cont = document.querySelector('.likec4-diagram') as HTMLElement | null
    const cb = cont!.getBoundingClientRect()
    const panel = document.querySelector('.content-navigation') as HTMLElement | null
    const pr = panel?.getBoundingClientRect()
    let topNodeOffset = Infinity
    let topNodeId: string | null = null
    document.querySelectorAll('.react-flow__node').forEach(el => {
      const r = (el as HTMLElement).getBoundingClientRect()
      const off = r.top - cb.top
      if (off < topNodeOffset) { topNodeOffset = off; topNodeId = (el as HTMLElement).getAttribute('data-id') }
    })
    return {
      panelBottomRelContainer: pr ? pr.bottom - cb.top : null,
      panelFound: !!panel,
      topNodeOffset,
      topNodeId,
      nodeCount: document.querySelectorAll('.react-flow__node').length,
    }
  })
}

// Tall views named in the bug report (path=big ref=main). big-cloud is the load-bearing one whose
// green actor was clipped; its screenshot is the deliverable proof.
const TALL: Array<{ view: string; shot?: string }> = [
  { view: 'cloud', shot: '/e2e/big-cloud-fixed.png' },
  { view: 'production' },
  { view: 'dynamic-view-1' },
]

for (const { view, shot } of TALL) {
  test(`tall view ${view}: topmost node clears the nav panel`, async ({ page }) => {
    test.setTimeout(200_000)
    page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 160)))
    await gotoDiagram(page, await seedMacroPage({ ref: 'main', path: 'big', view }))

    const m = await measure(page)
    const scale = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
    console.log(`CLEARANCE ${view}: nodes=${m.nodeCount} topNode=${m.topNodeId} ` +
      `topNodeOffset=${m.topNodeOffset.toFixed(1)} panelFound=${m.panelFound} ` +
      `panelBottom=${m.panelBottomRelContainer?.toFixed?.(1)} scale=${scale.toFixed(3)}`)

    if (shot) await page.screenshot({ path: shot, fullPage: true }).catch(e => console.log('shot failed:', e.message))

    expect(m.nodeCount, 'view must render nodes').toBeGreaterThan(0)
    // Primary guard: the topmost node sits a comfortable distance below the container top, clearing
    // the ~78px nav panel. (Regression: the old build fit it to 58px -> half-hidden under the panel.)
    expect(m.topNodeOffset, `top node must clear the nav panel (>= ${TOP_CLEARANCE_MIN}px below container top)`)
      .toBeGreaterThanOrEqual(TOP_CLEARANCE_MIN)
    expect(m.topNodeOffset, 'top node should sit near the configured top padding, not far below it')
      .toBeLessThanOrEqual(TOP_PAD + 80)
    // Secondary guard: if the nav panel is in the DOM, the top node must be at/below its bottom edge.
    if (m.panelFound && m.panelBottomRelContainer != null) {
      expect(m.topNodeOffset, 'top node top must be at/below the nav panel bottom (no overlap)')
        .toBeGreaterThanOrEqual(m.panelBottomRelContainer - 4)
    }
  })
}

// No-regression: the small 2-node view must still render at a sensible zoom (not over-shrunk by the
// extra padding) and its nodes stay within the container. Screenshot is a deliverable.
test('small 2-node view still looks good with the new padding', async ({ page }) => {
  test.setTimeout(180_000)
  page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 160)))
  await gotoDiagram(page, await seedMacroPage({ ref: 'main2', path: 'ok', view: 'index' }))

  const container = page.locator('.likec4-diagram').first()
  const cb = (await container.boundingBox())!
  const m = await measure(page)
  const scale = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
  console.log(`SMALL: nodes=${m.nodeCount} topNodeOffset=${m.topNodeOffset.toFixed(1)} ` +
    `panelBottom=${m.panelBottomRelContainer?.toFixed?.(1)} scale=${scale.toFixed(3)} ` +
    `container=${cb.width.toFixed(0)}x${cb.height.toFixed(0)}`)
  await page.screenshot({ path: '/e2e/small-padding-check.png', fullPage: true }).catch(e => console.log('shot failed:', e.message))

  expect(m.nodeCount, 'small view renders its nodes').toBeGreaterThanOrEqual(2)
  // Not over-shrunk: the same MIN_SCALE the visible-render proof uses.
  expect(scale, 'small view must not be over-shrunk by the extra padding (scale >= 0.3)').toBeGreaterThanOrEqual(0.3)
  // Nodes stay inside the container.
  const nodes = page.locator('.react-flow__node')
  const n = await nodes.count()
  for (let i = 0; i < n; i++) {
    const nb = (await nodes.nth(i).boundingBox())!
    expect(nb.y, `node ${i} top within container`).toBeGreaterThanOrEqual(cb.y - 2)
    expect(nb.y + nb.height, `node ${i} bottom within container`).toBeLessThanOrEqual(cb.y + cb.height + 2)
  }
})
