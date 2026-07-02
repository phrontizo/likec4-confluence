import { expect, test } from '@playwright/test'

test('view-picker populates and drives the live preview without recompute', async ({ page }) => {
  await page.goto('/editor.html')

  const picker = page.getByTestId('likec4-view-picker')
  await expect(picker).toBeVisible({ timeout: 30_000 })
  await expect(picker.locator('option')).toHaveCount(2) // index + sys_detail

  const preview = page.locator('.likec4-editor-preview [data-testid="likec4-diagram"]')
  await expect(preview).toHaveAttribute('data-current-view', 'index', { timeout: 30_000 })

  const computes = await page.evaluate(() => (window as any).__likec4Computes)
  await picker.selectOption('sys_detail')
  await expect(preview).toHaveAttribute('data-current-view', 'sys_detail', { timeout: 10_000 })
  expect(await page.evaluate(() => (window as any).__editorSelected)).toBe('sys_detail')
  // Switching views reuses the loaded model — no extra compute.
  expect(await page.evaluate(() => (window as any).__likec4Computes)).toBe(computes)
})
