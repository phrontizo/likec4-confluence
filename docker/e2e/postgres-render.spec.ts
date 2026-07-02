import { expect, test } from '@playwright/test'

// Render proof for the REAL atlassian/confluence:10.2.13 + PostgreSQL stack (compose `up.sh`).
// Drives the live macro page and asserts the LikeC4 diagram actually renders (react-flow nodes).
// Parameterised by env:
//   CONFLUENCE_BASE (default http://localhost:8090)  PAGE_ID (the seeded macro page id)
const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
const AUTH = 'Basic ' + Buffer.from(`${process.env.AUTH_USER || 'admin'}:${process.env.AUTH_PASS || 'admin'}`).toString('base64')

// Self-seed the demo macro page via REST (same as postgres-render-visible.spec.ts) so a default
// `./run.sh` reproduces from a clean `up.sh` with no manual PAGE_ID — matching run.sh's documented
// "all compose specs self-seed" contract. Override with PAGE_ID to reuse an existing page.
async function seedDemoPage(): Promise<string> {
  const macro =
    '<ac:structured-macro ac:name="likec4-diagram">' +
    '<ac:parameter ac:name="project">acme/architecture</ac:parameter>' +
    '<ac:parameter ac:name="ref">main2</ac:parameter>' +
    '<ac:parameter ac:name="path">ok</ac:parameter>' +
    '<ac:parameter ac:name="view">index</ac:parameter></ac:structured-macro>'
  const res = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({
      type: 'page', title: `Render demo ${Date.now()}`, space: { key: 'LIKEC4' },
      body: { storage: { value: macro, representation: 'storage' } },
    }),
  })
  expect(res.status, 'render self-seed page create must succeed').toBe(200)
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

test('LikeC4 diagram renders on Confluence 10.2.13 + PostgreSQL', async ({ page }) => {
  test.setTimeout(180_000)

  page.on('pageerror', err => console.log('PAGE ERROR:', err.message))
  page.on('console', msg => {
    const t = msg.text()
    if (/likec4|worker|resolve|source|module/i.test(t)) console.log(`CONSOLE[${msg.type()}]`, t.slice(0, 200))
  })
  page.on('response', resp => {
    const u = resp.url(); const s = resp.status()
    if (u.includes('/rest/likec4/') || u.includes('/assets/') || s >= 400) console.log(`RESPONSE ${s}: ${u.slice(0, 140)}`)
  })

  // Auth: preemptive HTTP Basic + os_authType=basic (seraph BasicAuthenticator). Avoids the
  // React login form / the 403 GET os_username shortcut on a production Confluence DC.
  await page.setExtraHTTPHeaders({ Authorization: AUTH })

  const pageId = process.env.PAGE_ID || await seedDemoPage()
  await page.goto(`${BASE}/pages/viewpage.action?pageId=${pageId}&os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  console.log('Diagram page title:', await page.title())

  // boot() mounts React into every .likec4-diagram immediately on ESM-module eval (no
  // IntersectionObserver), so no need to dismiss the first-login onboarding overlay.
  const macroDivCount = await page.locator('.likec4-diagram').count()
  console.log('.likec4-diagram count:', macroDivCount)

  // Wait for the diagram to mount + compute (REST resolve/source -> worker layout -> react-flow).
  const appeared = await page.locator('[data-testid="likec4-diagram"]')
    .waitFor({ timeout: 150_000, state: 'attached' }).then(() => true).catch(() => false)
  console.log('[data-testid="likec4-diagram"] appeared:', appeared)

  // ---- assertions FIRST (the actual proof) ----
  expect(macroDivCount, '.likec4-diagram macro div must exist').toBeGreaterThan(0)
  await expect(page.locator('[data-testid="likec4-diagram"]'), 'diagram testid visible').toBeVisible({ timeout: 5_000 })
  await expect(page.locator('.react-flow__node').first(), 'react-flow node visible').toBeVisible({ timeout: 5_000 })
  // Count AFTER the node-visible wait, never before it: the `attached` wait above resolves the moment the
  // container mounts, which on a cold stack can precede the worker compute that mounts the nodes. A count
  // taken before the visibility wait could read a stale 0 and fail this assertion even though the nodes
  // then appear (which the .first() wait above would have already confirmed).
  const nodeCount = await page.locator('.react-flow__node').count()
  console.log('REACT_FLOW_NODE_COUNT=' + nodeCount)
  expect(nodeCount, 'must have >=1 react-flow node').toBeGreaterThan(0)
  // Not just present: assert the render is not collapsed (real container height + a non-zero zoom),
  // so this proof catches the "mounts but invisible" failure rather than only DOM presence.
  const scale = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
  const clientHeight = await page.locator('[data-testid="likec4-diagram"]').evaluate(el => (el as HTMLElement).clientHeight)
  console.log('POSTGRES-RENDER scale=', scale, 'clientHeight=', clientHeight)
  expect(clientHeight, 'the diagram container must have a real, non-collapsed height').toBeGreaterThanOrEqual(300)
  expect(scale, 'the diagram must be zoomed to a visible scale, not collapsed').toBeGreaterThan(0)

  // ---- best-effort: dismiss the first-login onboarding overlay so the screenshot shows the diagram
  //      (purely cosmetic for the artifact; wrapped so it can never fail the proof) ----
  try {
    const close = page.locator('.aui-dialog2 [aria-label="Close"], [role="dialog"] button:has-text("Close"), .aui-message [aria-label="Close"]').first()
    if (await close.isVisible().catch(() => false)) { await close.click({ timeout: 3_000 }); await page.waitForTimeout(1_000) }
    await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
    await page.waitForTimeout(1_000)
  } catch (e) { console.log('overlay-dismiss/scroll skipped:', (e as Error).message?.slice(0, 120)) }
  await page.screenshot({ path: '/e2e/postgres-render.png', fullPage: true }).catch(e => console.log('screenshot failed:', e.message))
  console.log('Screenshot saved to /e2e/postgres-render.png')
})
