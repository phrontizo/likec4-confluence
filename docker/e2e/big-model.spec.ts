import { expect, test, type Page } from '@playwright/test'

// SCALE / STRESS sweep for the LikeC4 plugin against the LIVE Confluence 10.2.13 + PostgreSQL stack,
// driving the REAL likec4 v1.58.0 `cloud-system` example (path=big): 27 model elements + 49 deployment
// instances, 21 views (17 element, 2 deployment, 2 dynamic), 3 curated manual-layout snapshots.
//
// Goal: FIND problems at real scale. Each view test records node/edge counts, react-flow viewport
// scale, container height, drift banner, the worker's own computeMs (tapped via a Worker shim), and
// any page error — then screenshots. The picker test confirms the dropdown lists ALL 21 views.
const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
const AUTH = 'Basic ' + Buffer.from(`${process.env.AUTH_USER || 'admin'}:${process.env.AUTH_PASS || 'admin'}`).toString('base64')

// The big-model views exercised, all driven from path=big ref=main.
const VIEWS = ['cloud', 'production', 'dynamic-view-1', 'cloud-to-amazon', 'cloud_next', 'acceptance']

// Each view's macro page is SELF-SEEDED via REST, so this stress sweep reproduces on a fresh `up.sh`
// stack instead of depending on stale magic page ids from one historical seeding (which 404 with an
// opaque "page not found" on a clean stack). Mirrors sweep.spec.ts's self-seed pattern.
async function seedBigView(view: string): Promise<string> {
  const macro =
    '<ac:structured-macro ac:name="likec4-diagram">' +
    '<ac:parameter ac:name="project">acme/architecture</ac:parameter>' +
    '<ac:parameter ac:name="ref">main</ac:parameter>' +
    '<ac:parameter ac:name="path">big</ac:parameter>' +
    `<ac:parameter ac:name="view">${view}</ac:parameter></ac:structured-macro>`
  const res = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({
      type: 'page', title: `Big ${view} ${Date.now()}`, space: { key: 'LIKEC4' },
      body: { storage: { value: macro, representation: 'storage' } },
    }),
  })
  expect(res.status, `big self-seed (${view}) page create must succeed`).toBe(200)
  return (await res.json()).id as string
}

// The picker test only needs an editable host page (it drives the macro-browser override directly with
// path=big), so seed a blank one when no PICKER_PAGE override is given.
async function seedBlankPage(title: string): Promise<string> {
  const res = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({
      type: 'page', title, space: { key: 'LIKEC4' },
      body: { storage: { value: '<p>big-model picker host</p>', representation: 'storage' } },
    }),
  })
  expect(res.status, 'blank picker host page create must succeed').toBe(200)
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

// Tap the diagram worker's reply (carries computeMs) without touching the plugin build: the worker
// posts {data,errors,drifts,computeMs}; an extra addEventListener('message') fires alongside the
// pool's onmessage assignment, so we can stash compute timings on window for the test to read.
async function tapWorker(page: Page) {
  await page.addInitScript(() => {
    const w = window as any
    w.__likec4ComputeMs = []
    w.__likec4PageErrors = []
    const Orig = w.Worker
    w.Worker = class extends Orig {
      constructor(...args: any[]) {
        super(...args)
        this.addEventListener('message', (e: MessageEvent) => {
          const d: any = (e as any).data
          if (d && typeof d.computeMs === 'number') w.__likec4ComputeMs.push(d.computeMs)
        })
      }
    }
  })
}

async function dismissOnboarding(page: Page) {
  try {
    const close = page.locator('.aui-dialog2 [aria-label="Close"], [role="dialog"] button:has-text("Close"), .aui-message [aria-label="Close"]').first()
    if (await close.isVisible().catch(() => false)) { await close.click({ timeout: 3_000 }); await page.waitForTimeout(500) }
  } catch { /* never fail on overlay handling */ }
}

for (const view of VIEWS) {
  test(`big view ${view} renders`, async ({ page }) => {
    test.setTimeout(200_000)
    const errors: string[] = []
    page.on('pageerror', e => { errors.push(e.message); console.log('PAGEERROR:', e.message.slice(0, 200)) })
    await tapWorker(page)
    await page.setExtraHTTPHeaders({ Authorization: AUTH })
    const pageId = await seedBigView(view)
    const t0 = Date.now()
    await page.goto(`${BASE}/pages/viewpage.action?pageId=${pageId}&os_authType=basic`)
    await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
    await dismissOnboarding(page)
    await expect(page.locator('.likec4-diagram'), 'macro div').toHaveCount(1, { timeout: 20_000 })

    const viewer = page.locator('[data-testid="likec4-diagram"]')
    await expect(viewer, 'diagram viewer visible').toBeVisible({ timeout: 180_000 })
    await page.locator('.react-flow__node').first().waitFor({ timeout: 40_000 })
    const wallMs = Date.now() - t0
    await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
    await page.waitForTimeout(2_500) // let fitView settle

    const currentView = await viewer.getAttribute('data-current-view')
    const nodeCount = await page.locator('.react-flow__node').count()
    const edgeCount = await page.locator('.react-flow__edge').count()
    const clientHeight = await page.locator('.likec4-diagram').first().evaluate(el => (el as HTMLElement).clientHeight)
    const scale = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
    const hasDrift = await page.locator('[data-testid="likec4-drift-banner"]').count()
    const computeMs = await page.evaluate(() => (window as any).__likec4ComputeMs ?? [])
    // The number of worker computes observed IS the length of the computeMs array tapWorker collects;
    // there is no separate __likec4Computes global (reading it always logged 0), so derive it here.
    const computes = computeMs.length

    console.log(`BIGVIEW ${view} current=${currentView} nodes=${nodeCount} edges=${edgeCount} ` +
      `height=${clientHeight} scale=${scale.toFixed(3)} drift=${hasDrift} computeMs=${JSON.stringify(computeMs)} ` +
      `computes=${computes} wallMs=${wallMs} pageErrors=${errors.length}`)

    await page.screenshot({ path: `/e2e/big-${view}.png`, fullPage: true })

    expect(currentView, 'diagram must be on the requested view').toBe(view)
    // Every big-model view renders a multi-node graph (measured live: 8-25 nodes); assert >= 2, not the
    // toothless >= 1, so a model-merge/layout regression that collapses the view to a single node fails.
    expect(nodeCount, 'view must render a multi-node graph, not a single collapsed node').toBeGreaterThanOrEqual(2)
    expect(clientHeight, 'container must have real height').toBeGreaterThanOrEqual(300)
    // Strictly ABOVE the ~0.05 collapse the message names — a >= 0.05 guard passes at the exact collapse
    // value it is meant to catch. Big views legitimately zoom out, but the healthy floor is far higher
    // (measured live: 0.18-0.35), so > 0.05 has real teeth without rejecting a legitimately zoomed-out view.
    expect(scale, 'viewport must zoom sensibly (regression: ~0.05 collapse)').toBeGreaterThan(0.05)
    expect(errors.filter(e => !/ResizeObserver/.test(e)), 'no uncaught page errors').toHaveLength(0)
  })
}

test('picker lists ALL big-model views', async ({ page }) => {
  test.setTimeout(200_000)
  page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 200)))
  // Same preemptive HTTP Basic + os_authType=basic the view-page tests use (the form-login GET does
  // not reliably establish a session on this stack).
  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  const pickerPage = process.env.PICKER_PAGE || await seedBlankPage(`Big picker ${Date.now()}`)
  await page.goto(`${BASE}/pages/editpage.action?pageId=${pickerPage}&os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  // NOTE: do NOT run the onboarding dismiss here — on the editor page `button:has-text("Close")`
  // matches the editor's own Close button and would exit the editor. editpage redirects to
  // resumedraft.action; the editor JS registers the override on init.rte/toInit.
  await page.waitForTimeout(8_000)
  const registered = await page.evaluate(async () => {
    const w = window as any
    for (let i = 0; i < 150 && !w.__likec4EditorOverrideRegistered; i++) await new Promise(r => setTimeout(r, 100))
    return !!w.__likec4EditorOverrideRegistered
  })
  expect(registered, 'editor override must register').toBe(true)

  await page.evaluate(() => {
    const ov = (window as any).AJS.MacroBrowser.getMacroJsOverride('likec4-diagram')
    const opener = ov.opener ?? ov
    opener({ name: 'likec4-diagram', params: { project: 'acme/architecture', ref: 'main', path: 'big' } })
  })
  await expect(page.getByTestId('likec4-macro-dialog')).toBeVisible({ timeout: 30_000 })
  await page.getByTestId('likec4-load-views').click()

  const picker = page.getByTestId('likec4-view-picker')
  await expect(picker, 'view dropdown appears').toBeVisible({ timeout: 120_000 })
  // The real cloud-system has 21 views; the dropdown must list them ALL, not just a couple.
  await expect(picker.locator('option')).toHaveCount(21, { timeout: 120_000 })
  const optionTexts = await picker.locator('option').allInnerTexts()
  console.log('PICKER_OPTION_COUNT=', optionTexts.length)
  console.log('PICKER_OPTIONS=', JSON.stringify(optionTexts))
  // preview rendered for the start view
  await expect(page.locator('.likec4-macro-mount [data-testid="likec4-diagram"]')).toBeVisible({ timeout: 90_000 })
  await page.screenshot({ path: '/e2e/big-picker.png', fullPage: true })
})
