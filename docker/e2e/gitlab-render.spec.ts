import { expect, test, type Page } from '@playwright/test'

// REAL self-managed GitLab end-to-end proof (bucket 2). The plugin admin config has been pointed at
// a live `gitlab/gitlab-ce` instance (http://gitlab:8929 on the compose network) that holds the
// big/ cloud-system LikeC4 model pushed over git. This spec creates a macro page bound to that real
// project and verifies the diagram renders — i.e. macro -> web-resource -> /resolve + /source ->
// REAL GitLab commits + archive API -> worker -> likec4/react -> react-flow nodes.
const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
const AUTH = 'Basic ' + Buffer.from(`${process.env.AUTH_USER || 'admin'}:${process.env.AUTH_PASS || 'admin'}`).toString('base64')
const PROJECT = process.env.GL_PROJECT || 'acme/architecture'
const REF = process.env.GL_REF || 'main'
const PATH = process.env.GL_PATH || '' // model is at repo root after the git push
const VIEW = process.env.GL_VIEW || 'index'

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
    // Scope to the onboarding/notification dialog chrome only — a bare button:has-text("Close") can
    // match an unrelated panel and dismiss the diagram chrome (or, in the editor, exit the editor).
    const close = page.locator(
      '.aui-dialog2 [aria-label="Close"], [role="dialog"] button:has-text("Close"), .aui-message [aria-label="Close"]',
    ).first()
    if (await close.isVisible().catch(() => false)) { await close.click({ timeout: 3_000 }); await page.waitForTimeout(500) }
  } catch { /* overlay handling must never fail the test */ }
}

test('real gitlab-ce backed macro renders the cloud-system landscape', async ({ page }) => {
  test.setTimeout(240_000)
  page.on('pageerror', e => { if (!/emoji|emoticons/.test(e.message)) console.log('PAGEERROR:', e.message.slice(0, 200)) })
  page.on('console', m => { const t = m.text(); if (/error/i.test(t) && !/WRM|JQMIGRATE|DEPRECATED|emoji/.test(t)) console.log('C:', t.slice(0, 200)) })
  await page.setExtraHTTPHeaders({ Authorization: AUTH })

  const macro = `<ac:structured-macro ac:name="likec4-diagram">`
    + `<ac:parameter ac:name="project">${PROJECT}</ac:parameter>`
    + `<ac:parameter ac:name="ref">${REF}</ac:parameter>`
    + (PATH ? `<ac:parameter ac:name="path">${PATH}</ac:parameter>` : '')
    + `<ac:parameter ac:name="view">${VIEW}</ac:parameter>`
    + `</ac:structured-macro>`

  const title = `Real GitLab render ${Date.now()}`
  const createRes = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({ type: 'page', title, space: { key: 'LIKEC4' }, body: { storage: { value: `<p>Backed by REAL gitlab-ce.</p>${macro}`, representation: 'storage' } } }),
  })
  expect(createRes.status, 'page create must succeed').toBe(200)
  const pageId = (await createRes.json()).id as string
  console.log('GITLAB-RENDER created page', pageId, 'url=', `${BASE}/pages/viewpage.action?pageId=${pageId}`)

  await page.goto(`${BASE}/pages/viewpage.action?pageId=${pageId}&os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  await dismissOnboarding(page)
  await expect(page.locator('.likec4-diagram'), 'macro div must exist').toHaveCount(1, { timeout: 20_000 })

  const viewer = page.locator('[data-testid="likec4-diagram"]')
  // Assert real VISIBILITY, not mere attachment: a 0-height / collapsed-to-~0-scale render would pass
  // an `attached` check and a node-count check but is not actually rendered — the exact failure mode
  // postgres-render-visible.spec.ts guards. This is the sole automated proof of the real-GitLab backend,
  // so it must hold the same bar as GATE3.
  await expect(viewer).toBeVisible({ timeout: 180_000 })
  await page.locator('.react-flow__node').first().waitFor({ timeout: 60_000 })
  await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(2_000)

  const currentView = await viewer.getAttribute('data-current-view')
  const nodeCount = await page.locator('.react-flow__node').count()
  const labels = await page.locator('.react-flow__node').allInnerTexts()
  const scale = parseScale(await page.locator('.react-flow__viewport').first().getAttribute('style'))
  const clientHeight = await viewer.evaluate(el => (el as HTMLElement).clientHeight)
  const errorPanel = await page.locator('.likec4-diagram').first().evaluate(el => (el.textContent || '').slice(0, 200))
  console.log('GITLAB-RENDER data-current-view=', currentView)
  console.log('GITLAB-RENDER node count=', nodeCount, 'scale=', scale, 'clientHeight=', clientHeight)
  console.log('GITLAB-RENDER node labels=', JSON.stringify(labels))
  console.log('GITLAB-RENDER diagram text=', JSON.stringify(errorPanel))
  await page.screenshot({ path: '/e2e/gitlab-render.png', fullPage: true })
  expect(currentView).toBe(VIEW)
  // Hold the same bar as GATE3 and the sibling render specs: assert MULTIPLE nodes, not merely >= 1. The
  // cloud-system landscape is a multi-node model, so a degenerate render that dropped all but one node (a
  // model-merge/layout regression) must fail here — this spec is the sole automated proof of the REAL
  // gitlab-ce backend, so a >= 1 that a single-node collapse would satisfy defeats its purpose.
  expect(nodeCount, 'the cloud-system landscape must render as multiple nodes, not a single-node collapse')
    .toBeGreaterThanOrEqual(2)
  expect(scale, 'the diagram must be zoomed to a visible scale, not collapsed').toBeGreaterThan(0)
  expect(clientHeight, 'the diagram container must have a real, non-collapsed height').toBeGreaterThanOrEqual(300)
})
