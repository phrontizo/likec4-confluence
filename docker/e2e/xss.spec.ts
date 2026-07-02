import { expect, test } from '@playwright/test'

// XSS-in-labels live assertion (spec §8 / item D1).
//
// The `xss` fixture (docker/mock-gitlab/repos/acme/architecture/xss/) puts active HTML payloads in a
// LikeC4 element's title + description:
//   title       'Sys <img src=x onerror="window.__xss=1">'
//   description  '<script>window.__xss=1</script> plus <img src=y onerror="window.__xss=1"> ...'
// If the renderer injected those as live DOM, the onerror/script would set window.__xss = 1. The
// pass condition is: the diagram still renders, window.__xss stays falsy, no live <img>/<script>
// element is created from the payload, and the payload survives only as inert/escaped text.
//
// Runs against the COMPOSE stack (Confluence 10.2.13 at :8090, HTTP Basic auth) — the same model as
// sweep.spec.ts / c10-gates.spec.ts, so it is part of the default run.sh sweep. It SELF-SEEDS its macro
// page via REST (path=xss view=index) so it reproduces on a fresh `up.sh` stack with no manual seeding.
const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
const AUTH = 'Basic ' + Buffer.from(`${process.env.AUTH_USER || 'admin'}:${process.env.AUTH_PASS || 'admin'}`).toString('base64')

/** Resolve the XSS macro page id: an env override if set, else create a fresh macro page via REST. */
async function xssPageId(): Promise<string> {
  if (process.env.XSS_PAGE) return process.env.XSS_PAGE
  const macro =
    '<ac:structured-macro ac:name="likec4-diagram">' +
    '<ac:parameter ac:name="project">acme/architecture</ac:parameter>' +
    '<ac:parameter ac:name="ref">main</ac:parameter>' +
    '<ac:parameter ac:name="path">xss</ac:parameter>' +
    '<ac:parameter ac:name="view">index</ac:parameter></ac:structured-macro>'
  const res = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({
      type: 'page', title: `LikeC4 XSS Test ${Date.now()}`, space: { key: 'LIKEC4' },
      body: { storage: { value: macro, representation: 'storage' } },
    }),
  })
  expect(res.status, 'XSS test page self-seed must succeed').toBe(200)
  return (await res.json()).id as string
}

test('XSS payload in LikeC4 labels does not execute and is rendered escaped', async ({ page }) => {
  test.setTimeout(120_000)

  const pageErrors: string[] = []
  page.on('pageerror', err => {
    pageErrors.push(err.message)
    console.log('PAGE ERROR:', err.message)
  })

  // Preauth every request with HTTP Basic (seraph BasicAuthenticator) and open the self-seeded page via
  // the proven viewpage.action route. A login.action GET does NOT establish a session on the 10.2 stack.
  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  const pageId = await xssPageId()
  console.log('XSS page id:', pageId)
  await page.goto(`${BASE}/pages/viewpage.action?pageId=${pageId}&os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  console.log('Page title:', await page.title())

  // The diagram must actually render — otherwise we'd be asserting on a payload that never reached
  // the render path.
  const diagram = page.locator('.likec4-diagram [data-testid="likec4-diagram"]')
  await expect(diagram, 'likec4-diagram must render').toBeVisible({ timeout: 90_000 })
  const nodeCount = await page.locator('.react-flow__node').count()
  console.log('.react-flow__node count:', nodeCount)
  // The xss/index view is the same 2-node landscape as ok/index (sys + ext, `include *`), so assert the
  // EXACT expected count: a >= 1 would pass even if a model-merge/layout regression dropped a node — exactly
  // the degenerate render this fixture stresses — and a >= 2 would wave through a spurious extra node. The
  // fixture count is deterministic. GATE3 asserts the same toBe(2) on the twin.
  expect(nodeCount, 'the xss/index landscape renders exactly two nodes').toBe(2)

  // (1) THE security assertion: the payload must not have executed.
  const xssFlag = await page.evaluate(() => (window as any).__xss)
  console.log('window.__xss:', JSON.stringify(xssFlag))

  // (2) No LIVE element was created from the payload. A real injection would have produced an
  //     <img src="x"|"y"> (with onerror) or an executable <script> built from the description.
  const injected = await page.evaluate(() => {
    const imgs = document.querySelectorAll('img[src="x"], img[src="y"], img[onerror]')
    // a <script> element carrying the payload body (would be inert via innerHTML, but its mere
    // presence as a live element would indicate the HTML was parsed rather than escaped)
    const scripts = Array.from(document.querySelectorAll('script')).filter(s =>
      (s.textContent || '').includes('window.__xss'),
    )
    return { imgCount: imgs.length, scriptCount: scripts.length }
  })
  console.log('injected live elements:', JSON.stringify(injected))

  // (3) The payload survived as INERT/ESCAPED text. When React renders a string title as a text
  //     child, serializing the subtree shows the angle brackets escaped (`&lt;img …`) and the
  //     literal "onerror" appears as text — never as an attribute on a real element.
  const evidence = await page.evaluate(() => {
    const el = document.querySelector('.likec4-diagram') as HTMLElement | null
    if (!el) return { html: 'NOT FOUND', text: '', hasEscaped: false, textHasPayload: false }
    const html = el.innerHTML
    const text = el.textContent || ''
    return {
      html: html.slice(0, 1500),
      hasEscaped: html.includes('&lt;img') || html.includes('&lt;script'),
      textHasPayload: text.includes('onerror') || text.includes('<img'),
    }
  })
  console.log('escaped-in-html:', evidence.hasEscaped, ' payload-as-text:', evidence.textHasPayload)
  console.log('diagram innerHTML (head):', evidence.html)

  // Assertions ---------------------------------------------------------------------------------
  expect(xssFlag, 'window.__xss must be falsy — payload must NOT execute').toBeFalsy()
  expect(injected.imgCount, 'no live <img> element may be created from the payload').toBe(0)
  expect(injected.scriptCount, 'no live <script> element may carry the payload').toBe(0)
  expect(
    evidence.hasEscaped || evidence.textHasPayload,
    'payload must survive only as inert/escaped text',
  ).toBeTruthy()

  // (4) The render must not have thrown: a crash mid-render could mask a payload that never reached
  //     the escaping path. Filter the benign ResizeObserver loop error (same convention as big-model).
  expect(
    pageErrors.filter(e => !/ResizeObserver/.test(e)),
    'no uncaught page errors during the XSS render',
  ).toHaveLength(0)
})
