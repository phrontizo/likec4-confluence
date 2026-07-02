import { expect, test } from '@playwright/test'

test('renders the index view interactively with manual layout applied', async ({ page }) => {
  await page.goto('/')

  const diagram = page.getByTestId('diagram')
  await expect(diagram).toBeVisible({ timeout: 30_000 })

  const nodes = page.locator('.react-flow__node')
  await expect(nodes.first()).toBeVisible({ timeout: 30_000 })
  expect(await nodes.count()).toBeGreaterThan(0)

  const result = await page.evaluate(() => (window as any).__spike)
  expect(result.errors).toEqual([])
  expect(result.computeMs).toBeGreaterThan(0)
  expect(result.data.views.index._layout).toBe('manual')

  const before = await page.locator('.react-flow__viewport').getAttribute('style')
  await diagram.hover()
  await page.mouse.wheel(0, -400)
  await page.waitForTimeout(300)
  const after = await page.locator('.react-flow__viewport').getAttribute('style')
  expect(after).not.toBe(before)
})
