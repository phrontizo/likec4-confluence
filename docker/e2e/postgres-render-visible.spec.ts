import { expect, test } from '@playwright/test'

// VISUAL render proof for the REAL atlassian/confluence:10.2.13 + PostgreSQL stack.
// Unlike postgres-render.spec.ts (which only proves nodes are *present* in the DOM), this proves the
// diagram is genuinely VISIBLE: the macro container has a real height and react-flow's fitView zoomed
// to a sensible scale (not the ~0.05 collapse caused when .likec4-diagram had no height).
//
// REGRESSION GUARD: a zero-height container OR a near-zero viewport scale fails this test, so the
// "diagram mounts but is invisible" bug can never silently come back.
//
// Parameterised by env:  CONFLUENCE_BASE (default http://localhost:8090)  PAGE_ID (override the seed)
const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:8090'
const AUTH = 'Basic ' + Buffer.from(`${process.env.AUTH_USER || 'admin'}:${process.env.AUTH_PASS || 'admin'}`).toString('base64')

// A collapsed/zoomed-out render produced scale ~0.05; a healthy 480px-tall container fits to ~>=0.3.
const MIN_SCALE = 0.3
const MIN_CONTAINER_HEIGHT = 300

// Self-seed the 2-node demo macro page (path=ok ref=main2 view=index — the curated manual-layout demo
// with the "calls" edge) via REST, so this proof reproduces on a fresh `up.sh` stack rather than
// depending on a stale magic page id. Override with PAGE_ID to reuse an existing page.
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
      type: 'page', title: `Visible demo ${Date.now()}`, space: { key: 'LIKEC4' },
      body: { storage: { value: macro, representation: 'storage' } },
    }),
  })
  expect(res.status, 'visible-render self-seed page create must succeed').toBe(200)
  return (await res.json()).id as string
}

function parseScale(transform: string | null): number {
  // NaN (not 0) when no transform/scale is present, so a caller can tell "unparseable" from a real 0/collapse.
  if (!transform) return NaN
  const m = /scale\(([-\d.]+)/.exec(transform)
  if (m) return parseFloat(m[1])
  // Fallback: a CSS matrix(a,b,c,d,e,f) — `a` is the x-scale.
  const mm = /matrix\(([-\d.]+)/.exec(transform)
  return mm ? parseFloat(mm[1]) : NaN
}

test('LikeC4 diagram is VISIBLE on Confluence 10.2.13 + PostgreSQL (real height, sane zoom)', async ({ page }) => {
  test.setTimeout(180_000)

  page.on('pageerror', err => console.log('PAGE ERROR:', err.message))

  // Preemptive HTTP Basic (seraph BasicAuthenticator) — same approach as postgres-render.spec.ts.
  await page.setExtraHTTPHeaders({ Authorization: AUTH })
  const pageId = process.env.PAGE_ID || await seedDemoPage()
  await page.goto(`${BASE}/pages/viewpage.action?pageId=${pageId}&os_authType=basic`)
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 })
  console.log('Diagram page title:', await page.title())

  // Best-effort: dismiss the first-login onboarding overlay so it can't sit on top of the diagram.
  try {
    const close = page.locator('.aui-dialog2 [aria-label="Close"], [role="dialog"] button:has-text("Close"), .aui-message [aria-label="Close"]').first()
    if (await close.isVisible().catch(() => false)) { await close.click({ timeout: 3_000 }); await page.waitForTimeout(500) }
  } catch { /* never fail the proof on overlay handling */ }

  const macroDivCount = await page.locator('.likec4-diagram').count()
  console.log('.likec4-diagram count:', macroDivCount)
  expect(macroDivCount, '.likec4-diagram macro div must exist').toBeGreaterThan(0)

  // Wait for mount + compute (REST resolve/source -> worker layout -> react-flow).
  await page.locator('[data-testid="likec4-diagram"]').waitFor({ timeout: 150_000, state: 'attached' })
  await page.locator('.react-flow__node').first().waitFor({ timeout: 30_000, state: 'attached' })
  await page.locator('.likec4-diagram').first().scrollIntoViewIfNeeded()
  // Let react-flow's fitView settle before measuring the viewport transform.
  await page.waitForTimeout(2_000)

  // ---- the actual VISIBLE proof ----
  const container = page.locator('.likec4-diagram').first()
  const clientHeight = await container.evaluate(el => (el as HTMLElement).clientHeight)
  const transform = await page.locator('.react-flow__viewport').first().getAttribute('style')
  const scale = parseScale(transform)
  console.log('CONTAINER_CLIENT_HEIGHT=' + clientHeight)
  console.log('VIEWPORT_TRANSFORM=' + transform)
  console.log('VIEWPORT_SCALE=' + scale)

  // The two nodes' bounding boxes must lie within the container's visible rect.
  const containerBox = await container.boundingBox()
  expect(containerBox, 'container must have a layout box').not.toBeNull()
  const nodes = page.locator('.react-flow__node')
  const nodeCount = await nodes.count()
  console.log('REACT_FLOW_NODE_COUNT=' + nodeCount)
  expect(nodeCount, 'must render exactly the two diagram nodes').toBe(2)

  const cb = containerBox!
  const pad = 2 // allow a hair of sub-pixel/border slack
  for (let i = 0; i < Math.min(nodeCount, 2); i++) {
    const nb = await nodes.nth(i).boundingBox()
    console.log(`NODE_${i}_BOX=`, JSON.stringify(nb))
    expect(nb, `node ${i} must have a layout box`).not.toBeNull()
    const b = nb!
    expect(b.width, `node ${i} must have width`).toBeGreaterThan(0)
    expect(b.height, `node ${i} must have height`).toBeGreaterThan(0)
    expect(b.x, `node ${i} left within container`).toBeGreaterThanOrEqual(cb.x - pad)
    expect(b.y, `node ${i} top within container`).toBeGreaterThanOrEqual(cb.y - pad)
    expect(b.x + b.width, `node ${i} right within container`).toBeLessThanOrEqual(cb.x + cb.width + pad)
    expect(b.y + b.height, `node ${i} bottom within container`).toBeLessThanOrEqual(cb.y + cb.height + pad)
  }

  // ---- EDGE COHERENCE: the "calls" edge must visibly CONNECT both node boxes ----
  // REGRESSION: an incoherent manual-layout snapshot (a node shifted without re-routing its pinned
  // edge) rendered the "calls" edge dangling in empty space with "My System" floating away
  // disconnected. The rendered edge path must come right up against BOTH node boxes — a dangling
  // edge sits hundreds of px from its source node, far beyond this tolerance.
  const rectGap = (a: { x: number; y: number; width: number; height: number }, b: typeof a): number => {
    const dx = Math.max(0, Math.max(a.x - (b.x + b.width), b.x - (a.x + a.width)))
    const dy = Math.max(0, Math.max(a.y - (b.y + b.height), b.y - (a.y + a.height)))
    return Math.hypot(dx, dy)
  }
  const EDGE_NODE_MAX_GAP = 40 // screen px
  const edgePath = page.locator('.react-flow__edge-path').first()
  await edgePath.waitFor({ timeout: 15_000, state: 'attached' })
  const edgeBox = await edgePath.boundingBox()
  console.log('EDGE_PATH_BOX=', JSON.stringify(edgeBox))
  expect(edgeBox, 'edge path must have a layout box').not.toBeNull()
  const eb = edgeBox!
  for (let i = 0; i < Math.min(nodeCount, 2); i++) {
    const nb = await nodes.nth(i).boundingBox()
    expect(nb, `node ${i} box for edge-coherence`).not.toBeNull()
    const gap = rectGap(eb, nb!)
    console.log(`EDGE_TO_NODE_${i}_GAP=${gap.toFixed(1)}`)
    expect(gap, `the calls edge must connect node ${i} (gap ${gap.toFixed(1)}px) — regression: dangling edge`).toBeLessThanOrEqual(EDGE_NODE_MAX_GAP)
  }

  for (const path of ['/e2e/postgres-render-visible.png', '/e2e/postgres-render-coherent.png']) {
    await page.screenshot({ path, fullPage: true }).catch(e => console.log('screenshot failed:', e.message))
    console.log('Screenshot saved to ' + path)
  }

  // ---- REGRESSION ASSERTIONS: a 0-height / zoomed-out (~0.05) render must fail here ----
  expect(clientHeight, 'macro container must have a real height (regression: 0-height collapse)').toBeGreaterThanOrEqual(MIN_CONTAINER_HEIGHT)
  expect(scale, 'react-flow viewport must be zoomed sensibly (regression: scale ~0.05)').toBeGreaterThanOrEqual(MIN_SCALE)
})
