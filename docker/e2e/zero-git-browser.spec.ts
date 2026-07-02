import { expect, test } from '@playwright/test'

// Item 3 (spec §11) — BROWSER CONTRAST half of the zero-git-render proof.
//
// zero-git-render.sh first proves the SERVER-SIDE macro render (curl, no JS) makes ZERO git calls to
// GitLab (the macro emits only its <div>). This spec is the contrast: it loads the SAME page in a
// real browser, so the web-resource runs, the worker fetches /resolve + /source, and the diagram
// renders — which DOES drive git traffic at the mock. The script reads the mock counters before and
// after and asserts they moved here (browser-driven) while staying at 0 for the curl render.
//
// Env: CONFLUENCE_BASE, PAGE_TITLE (defaults to the seeded "LikeC4 Test Diagram", path=ok).
// This spec preauths with HTTP Basic (below) — the compose-stack pattern, same as live.spec.ts — so its
// bare default targets the compose Confluence at :8090, NOT the retired dev-amps :1990/confluence backend.
// In practice zero-git-render.sh always passes CONFLUENCE_BASE (resolved via resolve-confluence-base.sh),
// so the default only bites a bare `npx playwright test zero-git-browser.spec.ts`.
test('browser render of the macro page drives git traffic (contrast to zero-git server render)', async ({ page }) => {
  test.setTimeout(120_000)
  const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
  const TITLE = process.env.PAGE_TITLE || 'LikeC4 Test Diagram'
  const USER = process.env.AUTH_USER || 'admin'
  const PASS = process.env.AUTH_PASS || 'admin'

  // Preemptive HTTP Basic + os_authType=basic — matches live.spec.ts/sweep.spec.ts. A login.action GET
  // does NOT establish a session on the 10.2 compose stack (form login is disabled on the harness), so
  // the old approach silently failed to authenticate and then timed out at the visibility assertion.
  const AUTH = 'Basic ' + Buffer.from(`${USER}:${PASS}`).toString('base64')
  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  const lookup = await fetch(
    `${BASE}/rest/api/content?spaceKey=LIKEC4&title=${encodeURIComponent(TITLE)}`,
    { headers: { Authorization: AUTH, Accept: 'application/json' } })
  const results = (await lookup.json()).results as Array<{ id: string }>
  expect(results.length, `page "${TITLE}" must exist`).toBeGreaterThan(0)
  const pageId = results[0].id

  await page.goto(`${BASE}/pages/viewpage.action?pageId=${pageId}&os_authType=basic`)
  const diagram = page.locator('.likec4-diagram [data-testid="likec4-diagram"]')
  await expect(diagram, 'diagram must render in the browser').toBeVisible({ timeout: 90_000 })
  await expect(page.locator('.react-flow__node').first(), 'at least one node must render').toBeVisible({ timeout: 90_000 })
  // The seeded page is the ok/index 2-node landscape (path=ok). Assert the EXACT expected count: a >= 1
  // would pass a degenerate render that dropped a node, and a >= 2 would wave through a spurious extra
  // node. The fixture count is deterministic. GATE3 asserts the same toBe(2) on this fixture.
  expect(await page.locator('.react-flow__node').count(),
    'the ok/index landscape renders exactly two nodes').toBe(2)
})
