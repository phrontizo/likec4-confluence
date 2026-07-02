import { expect, test } from '@playwright/test'

// REGRESSION GUARD for the CSS-injection gap: the ERROR (and LOADING) panels must be STYLED on a
// Confluence page even though they never load the lazy `Diagram` chunk.
//
// Root cause that this guards: our styles.css is bundled (Vite CSS code-splitting) into the
// statically-imported shared chunk's CSS sidecar (assets/preload-helper-*.css). A static `import`
// of that chunk does NOT inject its CSS — only `__vitePreload` of the *dynamic* Diagram import does.
// So before the fix, the error path rendered with NO `.likec4-error` rule present (transparent bg,
// Confluence default text). boot-loader.js now injects the entry's transitive CSS via the Vite
// manifest, so the panel is styled regardless of which lazy chunks load.
//
// Asserts the error panel's computed background-color is the red rgb(248, 215, 218) (from
// `.likec4-error { background:#f8d7da }`) with real padding, WITHOUT any react-flow/Diagram chunk.
const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
const AUTH = 'Basic ' + Buffer.from(`${process.env.AUTH_USER || 'admin'}:${process.env.AUTH_PASS || 'admin'}`).toString('base64')

// Self-seed the broken-project macro page (path=broken ref=brk1 view=index) via REST so the guard
// reproduces on a fresh `up.sh` stack rather than depending on a stale magic page id. Override with
// BROKEN_PAGE to reuse an existing page.
async function seedBrokenPage(): Promise<string> {
  const macro =
    '<ac:structured-macro ac:name="likec4-diagram">' +
    '<ac:parameter ac:name="project">acme/architecture</ac:parameter>' +
    '<ac:parameter ac:name="ref">brk1</ac:parameter>' +
    '<ac:parameter ac:name="path">broken</ac:parameter>' +
    '<ac:parameter ac:name="view">index</ac:parameter></ac:structured-macro>'
  const res = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({
      type: 'page', title: `Broken styled ${Date.now()}`, space: { key: 'LIKEC4' },
      body: { storage: { value: macro, representation: 'storage' } },
    }),
  })
  expect(res.status, 'broken-styled self-seed page create must succeed').toBe(200)
  return (await res.json()).id as string
}

test('error panel is styled (red bg) without loading the Diagram chunk', async ({ page }) => {
  test.setTimeout(180_000)
  page.on('pageerror', e => console.log('PAGEERROR:', e.message.slice(0, 160)))

  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  const brokenPage = process.env.BROKEN_PAGE || await seedBrokenPage()
  await page.goto(`${BASE}/pages/viewpage.action?pageId=${brokenPage}&os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })

  // best-effort onboarding overlay dismissal (cosmetic for the screenshot)
  try {
    const close = page.locator('.aui-dialog2 [aria-label="Close"], [role="dialog"] button:has-text("Close"), .aui-message [aria-label="Close"]').first()
    if (await close.isVisible().catch(() => false)) { await close.click({ timeout: 3_000 }); await page.waitForTimeout(500) }
  } catch { /* never fail on overlay handling */ }

  const err = page.locator('[data-testid="likec4-error"]')
  await err.waitFor({ timeout: 150_000, state: 'visible' })
  await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
  await page.waitForTimeout(500)

  const style = await err.evaluate(el => {
    const s = getComputedStyle(el as HTMLElement)
    return { bg: s.backgroundColor, color: s.color, paddingTop: s.paddingTop, borderRadius: s.borderTopLeftRadius }
  })
  // Which of OUR stylesheets actually loaded (proves it did NOT come from the Diagram chunk).
  const css = await page.evaluate(() =>
    Array.from(document.styleSheets)
      .map(s => s.href || '')
      .filter(h => /assets\/.*\.css/.test(h))
      .map(h => h.replace(/^.*\/assets\//, 'assets/')))
  const reactFlowNodes = await page.locator('.react-flow__node').count()
  const diagramCssLoaded = css.some(h => /Diagram-.*\.css/.test(h))

  console.log('BROKEN error computed style=', JSON.stringify(style))
  console.log('BROKEN loaded asset CSS=', JSON.stringify(css))
  console.log('BROKEN react-flow node count (expect 0)=', reactFlowNodes)
  console.log('BROKEN Diagram chunk CSS loaded (expect false)=', diagramCssLoaded)

  await page.screenshot({ path: '/e2e/sweep-broken-fixed.png', fullPage: true })

  // The styling must be present WITHOUT the lazy Diagram chunk (the whole point of the fix).
  expect(reactFlowNodes, 'error path must NOT mount react-flow').toBe(0)
  expect(diagramCssLoaded, 'error styling must not depend on the Diagram chunk CSS').toBe(false)

  // The actual fix proof: red panel background (#f8d7da == rgb(248, 215, 218)) + real padding.
  expect(style.bg, 'error panel must have the red styled background (not transparent)').toBe('rgb(248, 215, 218)')
  expect(parseFloat(style.paddingTop), 'error panel must have real padding').toBeGreaterThan(0)
})
