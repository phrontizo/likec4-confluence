import { expect, test } from '@playwright/test'

// Flow 1 (spec §C1): drive the admin page's OWN form in the real browser — not direct REST.
//
// Proves end-to-end:
//   1. /plugins/servlet/likec4/admin renders the config form and pre-loads current values via REST;
//   2. clearing+typing a new baseUrl and allowlist and clicking the page's Save button runs the
//      inline JS which POSTs to /rest/likec4/1.0/admin and surfaces a success message in #likec4-status;
//   3. the typed values genuinely persist — verified independently by re-reading GET /rest admin AND
//      by reloading the page and reading the freshly pre-filled fields.
test('admin page form save persists baseUrl + allowlist', async ({ page }) => {
  test.setTimeout(90_000)
  const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:1990/confluence'
  const USER = process.env.AUTH_USER || 'admin'
  const PASS = process.env.AUTH_PASS || 'admin'

  page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 200)))
  page.on('console', m => {
    const t = m.text()
    if (/likec4|admin|error|save/i.test(t) && !/WRM|JQMIGRATE|DEPRECATED/.test(t)) console.log('CONSOLE:', m.type(), t.slice(0, 200))
  })
  page.on('response', r => {
    const u = r.url()
    if (/rest\/likec4\/1\.0\/admin/.test(u) || r.status() >= 400) console.log('RESP', r.status(), r.request().method(), u.slice(0, 140))
  })

  // Distinctive marker so we prove the TYPED value lands (keeps "acme" so Flow 2 still passes).
  const marker = `c1-marker-${Date.now()}`
  const newBaseUrl = 'http://localhost:8099'
  const newAllowlist = `acme, ${marker}`

  await page.goto(`${BASE}/login.action?os_username=${USER}&os_password=${PASS}`)
  await page.waitForLoadState('domcontentloaded')

  // Capture the current config up front (via the page's cookie session) so we can restore it in the
  // finally below. This test repoints baseUrl at http://localhost:8099 / a marker allowlist, which is
  // UNREACHABLE on the compose stack; without a restore an explicit `run.sh admin-form.spec.ts` would
  // leave every later render spec pointed at a dead repo, failing them with a misleading "won't load".
  const original = await page.evaluate(async (base) => {
    const r = await fetch(`${base}/rest/likec4/1.0/admin`, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    return r.json() as Promise<{ baseUrl: string; allowlist: string[] }>
  }, BASE)
  console.log('captured original config for restore:', JSON.stringify(original))

  try {
    await page.goto(`${BASE}/plugins/servlet/likec4/admin`)
    await page.waitForLoadState('domcontentloaded')

    // The form + its inline load() must populate from REST before we edit.
    const baseUrl = page.locator('#likec4-baseUrl')
    const allowlist = page.locator('#likec4-allowlist')
    await expect(baseUrl, 'baseUrl field must render').toBeVisible({ timeout: 15_000 })
    // load() fills baseUrl from the current config (proves the page's GET wiring works).
    await expect(baseUrl).toHaveValue(/.+/, { timeout: 15_000 })
    console.log('pre-fill baseUrl:', await baseUrl.inputValue(), '| allowlist:', await allowlist.inputValue())

    // --- Edit via the page's OWN fields, then click the page's OWN Save button ------------------
    await baseUrl.fill('')
    await baseUrl.fill(newBaseUrl)
    await allowlist.fill('')
    await allowlist.fill(newAllowlist)

    await page.locator('#likec4-save').click()

    // The inline JS must surface a success message in the status element.
    const status = page.locator('#likec4-status')
    await expect(status, 'status element must show success').toHaveClass(/success/, { timeout: 15_000 })
    await expect(status).toHaveText(/saved/i)
    console.log('status text after save:', await status.textContent())

    // --- Independent persistence proof #1: REST GET (separate from the page) --------------------
    // Use the page's own cookie session (same-origin) — NOT a Basic-auth header, which makes
    // Seraph reset the session cookie and would log the page out for proof #2.
    const restJson = await page.evaluate(async (base) => {
      const r = await fetch(`${base}/rest/likec4/1.0/admin`, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      return r.json()
    }, BASE)
    console.log('REST admin after save:', JSON.stringify(restJson))
    expect(restJson.baseUrl).toBe(newBaseUrl)
    expect(restJson.allowlist).toEqual(['acme', marker])
    expect(restJson.tokenSet).toBe(true) // token left blank -> existing token preserved

    // --- Independent persistence proof #2: re-open the admin page fresh; fields pre-fill from REST -
    await page.goto(`${BASE}/plugins/servlet/likec4/admin`)
    await page.waitForLoadState('domcontentloaded')
    // The atl.admin decorator drops our <body data-context-path>; the inline JS must still resolve the
    // context path (via AJS.contextPath) to GET the right REST URL and pre-fill the persisted values.
    await expect(baseUrl, 'reloaded baseUrl pre-fills persisted value').toHaveValue(newBaseUrl, { timeout: 15_000 })
    await expect(allowlist, 'reloaded allowlist pre-fills persisted value').toHaveValue(`acme, ${marker}`, { timeout: 15_000 })
    console.log('after fresh re-open — baseUrl:', await baseUrl.inputValue(), '| allowlist:', await allowlist.inputValue())

    await page.screenshot({ path: '/e2e/admin-form.png', fullPage: true }).catch(() => {})
  } finally {
    // Restore the pre-test config so this spec is side-effect-free even when run in isolation. Mirrors
    // the admin page's own save (JSON POST + no-check XSRF header, cookie session); best-effort — a
    // restore failure is logged but must not mask the test's own result.
    const restored = await page.evaluate(async ({ base, cfg }) => {
      const r = await fetch(`${base}/rest/likec4/1.0/admin`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
        body: JSON.stringify({ baseUrl: cfg.baseUrl, allowlist: cfg.allowlist }),
      })
      return r.status
    }, { base: BASE, cfg: original }).catch((e) => `restore threw: ${e}`)
    console.log('config restore status:', restored)
  }
})
