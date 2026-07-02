import { expect, test } from '@playwright/test'

test('renders, zooms, navigates in-model, and goes fullscreen', async ({ page }) => {
  await page.goto('/')

  // Instance 1 (path=ok) renders interactive nodes.
  const ok = page.locator('.likec4-diagram[data-instance="1"] [data-testid="likec4-diagram"]')
  await expect(ok).toBeVisible({ timeout: 30_000 })
  const nodes = ok.locator('.react-flow__node')
  await expect(nodes.first()).toBeVisible({ timeout: 30_000 })

  // No recompute happened for in-model interactions yet — record the baseline compute count.
  const computesBefore = await page.evaluate(() => (window as any).__likec4Computes)
  expect(computesBefore).toBeGreaterThan(0)

  // In-place navigation: navigate from the `sys` node to `sys_detail` WITHOUT a recompute.
  await expect(ok).toHaveAttribute('data-current-view', 'index')
  const sysNode = ok.locator('.react-flow__node[data-id="sys"]')
  // Best-known navigation affordance: double-click the navigable node. If the build wires
  // navigation to an explicit button instead, click `[data-testid="likec4-navigate"]` /
  // `.react-flow__node[data-id="sys"] button` (confirm the selector in --headed and update).
  await sysNode.dblclick()
  await expect(ok).toHaveAttribute('data-current-view', 'sys_detail', { timeout: 10_000 })
  expect(await page.evaluate(() => (window as any).__likec4Computes)).toBe(computesBefore)

  // Wheel zoom changes the react-flow viewport transform.
  const before = await ok.locator('.react-flow__viewport').getAttribute('style')
  await ok.hover()
  await page.mouse.wheel(0, -400)
  await page.waitForTimeout(300)
  expect(await ok.locator('.react-flow__viewport').getAttribute('style')).not.toBe(before)

  // Fullscreen toggle adds the fullscreen class.
  await ok.getByTestId('likec4-fullscreen-toggle').click()
  await expect(ok).toHaveClass(/likec4-fullscreen/)
})

test('shows a drift banner for a stale curated layout', async ({ page }) => {
  await page.goto('/')
  const drifted = page.locator('.likec4-diagram[data-instance="2"]')
  await expect(drifted.getByTestId('likec4-drift-banner')).toBeVisible({ timeout: 30_000 })
  await expect(drifted.getByTestId('likec4-drift-banner')).toContainText('nodes-added')
})

test('shows parse diagnostics for a broken project', async ({ page }) => {
  await page.goto('/')
  const broken = page.locator('.likec4-diagram[data-instance="3"]')
  const err = broken.getByTestId('likec4-error')
  await expect(err).toBeVisible({ timeout: 30_000 })
  await expect(err).toContainText('model.likec4')
})
