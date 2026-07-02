import { expect, test } from '@playwright/test'

// Loads the seeded "LikeC4 Test Diagram" page (created by docker/up.sh from seed-page.json) and asserts
// the macro's bundle rendered the diagram. Auth: preemptive HTTP Basic + os_authType=basic — the
// compose 10.2 stack (this spec's :8090 default) does NOT establish a session from a login.action GET,
// so this matches the other compose specs (GATE3 etc.) rather than the dev-amps login.action style.
test('seeded Confluence page renders the LikeC4 diagram', async ({ page }) => {
  // A cold worker-compute render on a freshly-booted stack can exceed Playwright's 30s default,
  // which would abort the test before its own 60s visibility assertions complete. Match the sibling
  // compose specs' budget so the assertions, not the test wrapper, decide the timeout.
  test.setTimeout(180_000)
  const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
  const AUTH = 'Basic ' + Buffer.from(`${process.env.AUTH_USER || 'admin'}:${process.env.AUTH_PASS || 'admin'}`).toString('base64')
  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  // Precondition: docker/up.sh seeds this page from seed-page.json; this spec targets it by title and
  // CANNOT self-seed. If it is absent, skip with a clear message rather than failing with an opaque 60s
  // visibility timeout (the diagram never appears because there is no page).
  const lookup = await page.request.get(
    // URL-encode the title (matching the sibling specs' encodeURIComponent form) so a title that ever
    // gains an encoding-sensitive character can't silently look up the wrong page and skip as "absent".
    `${BASE}/rest/api/content?spaceKey=LIKEC4&title=${encodeURIComponent('LikeC4 Test Diagram')}`,
    { headers: { Authorization: AUTH } },
  )
  // Gate on results[].length, NOT the response's `size` field: `size` is the count of the RETURNED page
  // of results and can be absent/0 on some Confluence builds even when `results` is populated, which
  // would skip this spec vacuously (a green run that proved nothing). Match the sibling compose specs
  // (zero-git-browser.spec.ts) that read `results`.
  const results = lookup.ok() ? (await lookup.json()).results : null
  const seeded = Array.isArray(results) && results.length > 0
  test.skip(!seeded, 'seeded "LikeC4 Test Diagram" page not present — run docker/up.sh first')
  // Absolute URL with os_authType=basic (a leading-slash relative path would resolve against the origin
  // and drop any context path). The page title has spaces -> '+' in the /display/ path.
  await page.goto(`${BASE}/display/LIKEC4/LikeC4+Test+Diagram?os_authType=basic`)
  const diagram = page.locator('.likec4-diagram [data-testid="likec4-diagram"]')
  await expect(diagram).toBeVisible({ timeout: 60_000 })
  await expect(diagram.locator('.react-flow__node').first()).toBeVisible({ timeout: 60_000 })
  // The seeded page is the ok/index 2-node landscape (seed-page.json: path=ok view=index). Assert the
  // EXACT expected count: a >= 1 would pass a degenerate render that dropped a node, and a >= 2 would wave
  // through a spurious extra node. The fixture count is deterministic. GATE3 asserts the same toBe(2) here.
  expect(await diagram.locator('.react-flow__node').count(),
    'the seeded ok/index landscape renders exactly two nodes').toBe(2)
})
