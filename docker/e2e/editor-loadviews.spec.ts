import { expect, test } from '@playwright/test'

// Live verification of the macro-editor "Load views" UX (spec §6) against a running Confluence.
//
// Proves, end-to-end against the real plugin + mock GitLab:
//   1. the editor web-resource is delivered to the editor page (editor-loader.js script present);
//   2. the Macro Browser JS override for `likec4-diagram` is registered (getMacroJsOverride + flag);
//   3. invoking the override opener lazily loads the ESM editor bundle and opens the custom editor;
//   4. "Load views" runs the SAME browser pipeline (REST resolve+source -> worker compute) and
//      populates the View dropdown with the model's views (index + sys_detail) + a live preview;
//   5. selecting a view writes it back, and Insert hands the chosen params (incl. view) to
//      tinymce.confluence.MacroUtils.insertMacro.
test('macro editor: Load views populates the dropdown and writes the chosen view back', async ({ page }) => {
  test.setTimeout(180_000)
  const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:1990/confluence'
  page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 200)))
  page.on('console', m => {
    const t = m.text()
    if (/likec4|worker|error/i.test(t) && !/WRM|JQMIGRATE|DEPRECATED|emoji/.test(t)) console.log('CONSOLE:', m.type(), t.slice(0, 240))
  })
  page.on('response', r => {
    const u = r.url()
    if ((/likec4|editor-confluence|worker|Diagram|\/rest\//.test(u) || r.status() >= 400) && !/emoticons|analytics/.test(u))
      console.log('RESP', r.status(), u.slice(0, 160))
  })
  page.on('requestfailed', r => console.log('REQFAIL', r.url().slice(0, 160), r.failure()?.errorText))

  // --- Open the editor for the seeded page -------------------------------------------------------
  const USER = process.env.AUTH_USER || 'admin'
  const PASS = process.env.AUTH_PASS || 'admin'
  await page.goto(`${BASE}/login.action?os_username=${USER}&os_password=${PASS}`)
  await page.waitForLoadState('domcontentloaded')
  const lookup = await fetch(
    `${BASE}/rest/api/content?spaceKey=LIKEC4&title=${encodeURIComponent('LikeC4 Test Diagram')}`,
    { headers: { Authorization: 'Basic ' + Buffer.from(`${USER}:${PASS}`).toString('base64'), Accept: 'application/json' } },
  )
  const pageId = ((await lookup.json()).results as Array<{ id: string }>)[0]?.id
  expect(pageId, 'seeded page must exist').toBeTruthy()
  await page.goto(`${BASE}/pages/editpage.action?pageId=${pageId}`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  await page.locator('#wysiwygTextarea_ifr').waitFor({ timeout: 60_000 })

  // --- (1)+(2) web-resource delivered + override registered --------------------------------------
  const registered = await page.evaluate(async () => {
    const w = window as any
    // editor-loader.js registers on init.rte/toInit; poll briefly for it.
    for (let i = 0; i < 60 && !w.__likec4EditorOverrideRegistered; i++) await new Promise(r => setTimeout(r, 100))
    const ov = w.AJS?.MacroBrowser?.getMacroJsOverride?.('likec4-diagram')
    return {
      loaderScript: Array.from(document.querySelectorAll('script[src]'))
        .some(s => /editor-loader\.js/.test((s as HTMLScriptElement).src)),
      flag: !!w.__likec4EditorOverrideRegistered,
      overrideType: typeof ov,
      hasOpener: typeof (ov?.opener ?? ov) === 'function',
    }
  })
  console.log('registration:', JSON.stringify(registered))
  // editor-loader.js is concatenated into the editor-context JS batch (no standalone <script>), so
  // its having *run* (the window flag) is the proof it was delivered + executed in the editor context.
  expect(registered.flag, 'editor-loader.js must run in the editor context (override registered)').toBe(true)
  expect(registered.hasOpener, 'getMacroJsOverride must return an opener function').toBe(true)

  // Spy on the macro-insert API so we can assert what gets written back.
  await page.evaluate(() => {
    const mu = (window as any).tinymce.confluence.MacroUtils
    const orig = mu.insertMacro.bind(mu)
    mu.insertMacro = (m: any) => {
      ;(window as any).__likec4Inserted = m
      try {
        return orig(m)
      } catch (e) {
        ;(window as any).__likec4InsertErr = String(e)
      }
    }
  })

  // --- (3) invoke the opener -> custom editor opens ----------------------------------------------
  await page.evaluate(() => {
    const ov = (window as any).AJS.MacroBrowser.getMacroJsOverride('likec4-diagram')
    const opener = ov.opener ?? ov
    opener({ name: 'likec4-diagram', params: { project: 'acme/architecture', ref: 'main', path: 'ok' } })
  })
  await expect(page.getByTestId('likec4-macro-dialog'), 'custom macro editor dialog must open').toBeVisible({ timeout: 30_000 })

  // --- (4) Load views -> dropdown populates + live preview ---------------------------------------
  await page.getByTestId('likec4-load-views').click()
  const picker = page.getByTestId('likec4-view-picker')
  // diagnostics: poll the mount state so a failure tells us loading vs error vs nothing.
  for (let i = 0; i < 12; i++) {
    const snap = await page.evaluate(() => {
      const mount = document.querySelector('.likec4-macro-mount') as HTMLElement | null
      return {
        status: (document.querySelector('[data-testid="likec4-macro-status"]') as HTMLElement)?.textContent,
        loading: !!document.querySelector('[data-testid="likec4-loading"]'),
        picker: !!document.querySelector('[data-testid="likec4-view-picker"]'),
        error: !!document.querySelector('[data-testid="likec4-error"], .likec4-error'),
        mountHtml: mount ? mount.innerHTML.replace(/\s+/g, ' ').slice(0, 400) : 'NO MOUNT',
      }
    })
    console.log(`mount[${i}]:`, JSON.stringify(snap))
    if (snap.picker || snap.error) break
    await page.waitForTimeout(2500)
  }
  await expect(picker, 'view dropdown must appear').toBeVisible({ timeout: 90_000 })
  await expect(picker.locator('option'), 'dropdown must list the model views (index + sys_detail)')
    .toHaveCount(2, { timeout: 90_000 })
  // onSelect fired with the start view -> the View field is populated.
  await expect(page.getByTestId('likec4-field-view')).toHaveValue('index', { timeout: 30_000 })
  // live preview rendered for the start view.
  const preview = page.locator('.likec4-macro-mount [data-testid="likec4-diagram"]')
  await expect(preview, 'live preview must render').toBeVisible({ timeout: 90_000 })

  // --- (5) selecting a view writes it back, Insert hands params (incl. view) to insertMacro -------
  await picker.selectOption('sys_detail')
  await expect(page.getByTestId('likec4-field-view'), 'selecting writes the view back into the field')
    .toHaveValue('sys_detail', { timeout: 10_000 })

  await page.getByTestId('likec4-macro-insert').click()
  const inserted = await page.evaluate(() => (window as any).__likec4Inserted)
  console.log('inserted macro:', JSON.stringify(inserted), 'insertErr:', await page.evaluate(() => (window as any).__likec4InsertErr))
  // Confluence's MacroUtils.insertMacro takes {macro:{name,params}, contentId}; the editor-loader
  // wraps our chosen params in that envelope.
  expect(inserted?.macro?.name).toBe('likec4-diagram')
  expect(inserted?.macro?.params?.project).toBe('acme/architecture')
  expect(inserted?.macro?.params?.view).toBe('sys_detail')
  expect(inserted?.macro?.params?.path).toBe('ok')
  expect(inserted?.contentId, 'insertMacro must carry the editor contentId').toBeTruthy()
})
