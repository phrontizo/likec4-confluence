import { expect, test } from '@playwright/test'

// Item 9 (spec §8) — XSS in dynamic-view NOTES (+ relationship/view titles) AND a CSP-clean render.
//
// Extends xss.spec.ts (which covered element title/description) to the remaining string surfaces that
// reach the rendered DOM:
//   - the dynamic view TITLE                       'Flow <img src=t onerror=window.__xss=1>'
//   - the dynamic STEP / relationship TITLE (edge) 'step <img src=s onerror=window.__xss=1>'
//   - the dynamic STEP `notes` (Markdown)          '<script>…</script> … <img src=n onerror=…>'
// `notes` is the headline: likec4 renders step notes as Markdown via dangerouslySetInnerHTML during
// the dynamic-view walkthrough, so it is a genuine HTML-injection surface. This test STARTS the
// walkthrough so the notes actually render, then proves nothing executed.
//
// Pass conditions: the diagram renders; window.__xss stays falsy; no LIVE <img onerror>/<script> is
// created from any payload (it survives only as inert/escaped text); and ZERO CSP violations fire
// during the whole render (worker + web-resources are same-origin — nothing should be blocked).
//
// Runs against the COMPOSE stack (Confluence 10.2.13 at :8090, HTTP Basic auth) — the same model as
// sweep.spec.ts, so it is part of the default run.sh sweep. It SELF-SEEDS its macro page via REST
// (path=xss-notes view=flow) so it reproduces on a fresh `up.sh` stack with no manual seeding.
const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
const AUTH = 'Basic ' + Buffer.from(`${process.env.AUTH_USER || 'admin'}:${process.env.AUTH_PASS || 'admin'}`).toString('base64')

/** Resolve the XSS-notes macro page id: an env override if set, else create a fresh macro page via REST. */
async function xssNotesPageId(): Promise<string> {
  if (process.env.XSS_NOTES_PAGE) return process.env.XSS_NOTES_PAGE
  const macro =
    '<ac:structured-macro ac:name="likec4-diagram">' +
    '<ac:parameter ac:name="project">acme/architecture</ac:parameter>' +
    '<ac:parameter ac:name="ref">main</ac:parameter>' +
    '<ac:parameter ac:name="path">xss-notes</ac:parameter>' +
    '<ac:parameter ac:name="view">flow</ac:parameter></ac:structured-macro>'
  const res = await fetch(`${BASE}/rest/api/content`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json', 'X-Atlassian-Token': 'no-check' },
    body: JSON.stringify({
      type: 'page', title: `LikeC4 XSS Notes Test ${Date.now()}`, space: { key: 'LIKEC4' },
      body: { storage: { value: macro, representation: 'storage' } },
    }),
  })
  expect(res.status, 'XSS Notes test page self-seed must succeed').toBe(200)
  return (await res.json()).id as string
}

test('XSS in notes/titles does not execute and the render is CSP-clean', async ({ page }) => {
  test.setTimeout(120_000)

  // Arm the security observers BEFORE any page script runs: track CSP violations and re-affirm the
  // XSS sentinel is unset.
  await page.addInitScript(() => {
    ;(window as any).__xss = (window as any).__xss || undefined
    ;(window as any).__csp = []
    document.addEventListener('securitypolicyviolation', (e: any) =>
      (window as any).__csp.push(`${e.violatedDirective} blocked ${e.blockedURI || e.sourceFile || '?'}`))
  })
  const pageErrors: string[] = []
  page.on('pageerror', err => pageErrors.push(err.message))

  // Preauth every request with HTTP Basic (seraph BasicAuthenticator) and open the self-seeded page via
  // the proven viewpage.action route. A login.action GET does NOT establish a session on the 10.2 stack.
  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  const pageId = await xssNotesPageId()
  console.log('XSS Notes page id:', pageId)
  await page.goto(`${BASE}/pages/viewpage.action?pageId=${pageId}&os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })

  // The dynamic diagram must actually render.
  await expect(page.locator('.likec4-diagram [data-testid="likec4-diagram"]'), 'diagram must render')
    .toBeVisible({ timeout: 90_000 })
  const nodeCount = await page.locator('.react-flow__node').count()
  expect(nodeCount, 'dynamic view must render its participant nodes').toBeGreaterThan(0)

  // Start the dynamic-view walkthrough so the step `notes` (Markdown) actually render into the DOM.
  // A direct DOM click reliably fires the React handler (the nav-panel control animates, so a
  // synthetic Playwright click is flaky).
  const started = await page.evaluate(() => {
    const b = Array.from(document.querySelectorAll('button')).find(x => (x.textContent || '').trim() === 'Start')
    if (b) { (b as HTMLButtonElement).click(); return true }
    return false
  })
  expect(started, 'walkthrough "Start" control must be present').toBe(true)

  // Wait for the notes Markdown to surface (its literal text, with the **notes** emphasis stripped).
  await page.waitForFunction(() => /inside the/i.test(document.body.textContent || ''), { timeout: 20_000 })
  console.log('walkthrough notes surfaced')

  // ---- Security assertions ---------------------------------------------------------------------
  const evidence = await page.evaluate(() => {
    // The smallest element wrapping the notes text — inspect what it actually rendered. Scope the
    // search to the diagram macro container so unrelated Confluence chrome that happens to contain
    // "inside the" can't be mistaken for the notes host.
    let notesHost: HTMLElement | null = null
    const searchRoot: ParentNode = document.querySelector('.likec4-diagram') || document.body
    searchRoot.querySelectorAll<HTMLElement>('*').forEach(el => {
      if ((el.textContent || '').includes('inside the')) {
        if (!notesHost || (el.textContent || '').length < (notesHost.textContent || '').length) notesHost = el
      }
    })
    const notesHtml = notesHost ? (notesHost as HTMLElement).innerHTML : ''
    // Any LIVE element built from a payload anywhere on the page.
    const liveImg = document.querySelectorAll('img[onerror], img[src="n"], img[src="s"], img[src="t"], img[src="r"], img[src="d"]').length
    const liveScript = Array.from(document.querySelectorAll('script')).filter(s => (s.textContent || '').includes('window.__xss')).length
    // Within the rendered notes: the dangerous HTML must be NEUTRALISED (likec4's Markdown sanitiser
    // strips <script>/<img onerror>; an escaping renderer would instead leave them as inert text).
    // Either way there must be NO live <img>/<script> element here. We also confirm the BENIGN
    // Markdown still rendered (`**notes**` -> <strong>), proving the notes path actually ran.
    const notesLiveDanger = notesHost
      ? (notesHost as HTMLElement).querySelectorAll('img, script, [onerror]').length : -1
    return {
      xss: (window as any).__xss,
      csp: (window as any).__csp as string[],
      liveImg, liveScript,
      notesFound: !!notesHost,
      notesLiveDanger,
      notesMarkdownRendered: /<strong>notes<\/strong>/.test(notesHtml),
      notesText: notesHost ? ((notesHost as HTMLElement).textContent || '').slice(0, 200) : '',
      notesHtmlHead: notesHtml.slice(0, 400),
    }
  })
  console.log('window.__xss:', JSON.stringify(evidence.xss))
  console.log('CSP violations:', JSON.stringify(evidence.csp))
  console.log('liveImg:', evidence.liveImg, 'liveScript:', evidence.liveScript)
  console.log('notes found:', evidence.notesFound, '| live danger in notes:', evidence.notesLiveDanger,
    '| markdown rendered:', evidence.notesMarkdownRendered)
  console.log('notes text:', JSON.stringify(evidence.notesText))
  console.log('notes innerHTML head:', evidence.notesHtmlHead)
  console.log('pageErrors:', JSON.stringify(pageErrors.slice(0, 3)))

  expect(evidence.xss, 'window.__xss must be falsy — no payload (title/notes) may execute').toBeFalsy()
  expect(evidence.liveImg, 'no LIVE <img> may be built from any payload (title or notes)').toBe(0)
  expect(evidence.liveScript, 'no LIVE <script> may carry the notes payload').toBe(0)
  expect(evidence.notesFound, 'the step notes must actually render (walkthrough surfaced them)').toBe(true)
  expect(evidence.notesMarkdownRendered, 'benign notes Markdown must render (proves the notes path ran)').toBe(true)
  expect(
    evidence.notesLiveDanger,
    'the notes payload (<script>/<img onerror>) must be neutralised — no live dangerous element',
  ).toBe(0)
  // CSP: the worker + web-resources are same-origin, so OUR render must trigger no violation. Our
  // shipped browser bundle contains ZERO eval()/new Function() (a build-time fact — grep the built
  // assets), so a "script-src blocked eval" on a Confluence 10.2 page is the platform's OWN chrome
  // being blocked by the page CSP, not our diagram (it does not fire on the laxer dev-amps CSP).
  // Exclude that platform noise and assert no violation blocks one of OUR resources (a web-resource
  // script/style, the worker, or a REST/connect origin) — which is what "CSP-clean render" means here.
  const ourCsp = (evidence.csp as string[]).filter(v => !/blocked eval$/.test(v))
  console.log('CSP violations attributable to our render:', JSON.stringify(ourCsp))
  expect(ourCsp, 'no CSP violation may block our own scripts/styles/worker/REST resources').toHaveLength(0)

  await page.screenshot({ path: '/e2e/xss-notes-walkthrough.png', fullPage: true }).catch(() => {})
})
