import { expect, test } from '@playwright/test'

// Flow 2 (spec §C2): author the LikeC4 macro through the REAL Confluence editor + Macro Browser,
// end-to-end, then prove the saved page renders the diagram.
//
// Unlike editor-loadviews.spec.ts (which invokes the override opener synthetically), this drives the
// genuine native gestures:
//   1. open a page in the TinyMCE editor;
//   2. open the Macro Browser via the real "Other macros" toolbar item;
//   3. type "likec4" in the browser search and CLICK the real macro tile (#macro-likec4-diagram) —
//      which must trigger the C2 JS override (our custom "Load views" editor opens);
//   4. in that editor: set project/ref/path, click Load views, pick a view, click Insert;
//   5. the macro placeholder must land in the editor body; save (publish) the page;
//   6. view the saved page and assert the diagram renders a multi-node graph (.react-flow__node >= 2).
test('native macro browser inserts the LikeC4 macro and the saved page renders', async ({ page }) => {
  test.setTimeout(240_000)
  const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:1990/confluence'
  const USER = process.env.AUTH_USER || 'admin'
  const PASS = process.env.AUTH_PASS || 'admin'
  const AUTH = 'Basic ' + Buffer.from(`${USER}:${PASS}`).toString('base64')

  page.on('pageerror', e => { if (!/emoji|emoticons/.test(e.message)) console.log('PAGEERROR:', e.message.slice(0, 200)) })
  page.on('console', m => {
    const t = m.text()
    if (/likec4|worker|override|insertMacro|error/i.test(t) && !/WRM|JQMIGRATE|DEPRECATED|emoji/.test(t)) console.log('C:', m.type(), t.slice(0, 200))
  })
  page.on('response', r => {
    const u = r.url()
    if ((/\/rest\/likec4|editor-confluence|likec4-web/.test(u) || r.status() >= 400) && !/emoticons|analytics|quickreload/.test(u))
      console.log('RESP', r.status(), u.slice(0, 150))
  })

  await page.goto(`${BASE}/login.action?os_username=${USER}&os_password=${PASS}`)
  await page.waitForLoadState('domcontentloaded')

  // Fresh empty page (REST, Basic auth in Node context — separate from the browser session).
  const title = `LikeC4 native authored ${Date.now()}`
  const createRes = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({
      type: 'page', title, space: { key: 'LIKEC4' },
      body: { storage: { value: '<p>authored via native macro browser</p>', representation: 'storage' } },
    }),
  })
  expect(createRes.status, 'page create must succeed').toBe(200)
  const pageId = (await createRes.json()).id as string
  console.log('created page', pageId, title)

  // --- Open the editor --------------------------------------------------------------------------
  await page.goto(`${BASE}/pages/editpage.action?pageId=${pageId}`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  await page.locator('#wysiwygTextarea_ifr').waitFor({ timeout: 60_000 })

  // The editor web-resource must have run + registered the macro JS override.
  const registered = await page.evaluate(async () => {
    const w = window as any
    for (let i = 0; i < 80 && !w.__likec4EditorOverrideRegistered; i++) await new Promise(r => setTimeout(r, 100))
    return !!w.__likec4EditorOverrideRegistered
  })
  expect(registered, 'editor override must be registered before authoring').toBe(true)

  // --- (2) Open the real Macro Browser via the "Other macros" toolbar item ----------------------
  // It lives under the Insert (+) dropdown; open the dropdown first, then the item.
  await page.locator('#rte-button-insert').click().catch(() => {})
  await page.waitForTimeout(400)
  await page.locator('#rte-insert-macro').click()
  await page.locator('#macro-browser-dialog').waitFor({ timeout: 30_000 })

  // --- (3) Search + CLICK the genuine macro tile -> triggers the C2 override ---------------------
  const search = page.locator('#macro-browser-search')
  await search.click()
  await search.pressSequentially('likec4', { delay: 70 })
  const tile = page.locator('#macro-likec4-diagram')
  await expect(tile, 'the LikeC4 Diagram macro must be found in the Macro Browser').toBeVisible({ timeout: 15_000 })
  await tile.scrollIntoViewIfNeeded().catch(() => {})
  await tile.click()

  // The C2 override opens our custom editor (NOT the default parameter panel).
  const dialog = page.getByTestId('likec4-macro-dialog')
  await expect(dialog, 'C2 override must open the custom macro editor').toBeVisible({ timeout: 30_000 })

  // --- (4) Fill params, Load views, pick a view, Insert -----------------------------------------
  await page.getByTestId('likec4-field-project').fill('acme/architecture')
  await page.getByTestId('likec4-field-ref').fill('main')
  await page.getByTestId('likec4-field-path').fill('ok')

  await page.getByTestId('likec4-load-views').click()
  const picker = page.getByTestId('likec4-view-picker')
  await expect(picker, 'Load views must populate the view dropdown').toBeVisible({ timeout: 90_000 })
  await expect(picker.locator('option'), 'dropdown lists the model views (index + sys_detail)').toHaveCount(2, { timeout: 90_000 })
  // start view written back automatically
  await expect(page.getByTestId('likec4-field-view')).toHaveValue('index', { timeout: 30_000 })
  // pick a specific view (exercises onSelect -> writes back into the field)
  await picker.selectOption('sys_detail')
  await expect(page.getByTestId('likec4-field-view')).toHaveValue('sys_detail', { timeout: 10_000 })

  await page.getByTestId('likec4-macro-insert').click()
  // our dialog closes after insert
  await expect(dialog).toBeHidden({ timeout: 15_000 })

  // --- (5) The macro placeholder must land in the editor body -----------------------------------
  const body = page.frameLocator('#wysiwygTextarea_ifr')
  const placeholder = body.locator('[data-macro-name="likec4-diagram"]')
  await expect(placeholder, 'macro placeholder must appear in the editor body').toBeVisible({ timeout: 30_000 })
  console.log('macro placeholder present in editor body')

  // Save / publish the page.
  await page.locator('#rte-button-publish').click()
  await page.waitForLoadState('domcontentloaded', { timeout: 60_000 })
  // land on the view page (best-effort: the post-publish URL scheme can vary across Confluence builds, so
  // a non-match is not a failure here — the load-bearing assertion is the viewer visibility below. But LOG
  // a non-navigation instead of silently swallowing it, so a genuinely stuck/failed publish is legible).
  await page.waitForURL(/viewpage\.action|\/display\//, { timeout: 60_000 })
    .catch(() => console.log('post-publish navigation did not match viewpage/display within 60s; proceeding to the viewer check on', page.url()))
  console.log('after publish, url:', page.url())

  // --- (6) The saved page renders the diagram ---------------------------------------------------
  const diagram = page.locator('.likec4-diagram [data-testid="likec4-diagram"]')
  await expect(diagram, 'saved page must render the LikeC4 diagram').toBeVisible({ timeout: 90_000 })
  const nodeCount = await page.locator('.react-flow__node').count()
  console.log('.react-flow__node count on saved page:', nodeCount)
  // The editor authored a FIXED path=ok view=sys_detail macro (picked above), which renders a multi-node
  // graph (measured live: 3 nodes); assert >= 2, not the toothless >= 1, so a node-dropping regression on
  // the editor-authored render path fails here too.
  expect(nodeCount, 'rendered diagram must render a multi-node graph, not a single collapsed node').toBeGreaterThanOrEqual(2)

  await page.screenshot({ path: '/e2e/macro-native-rendered.png', fullPage: true }).catch(() => {})
})
