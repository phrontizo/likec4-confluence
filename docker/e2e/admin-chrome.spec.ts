import { expect, test } from '@playwright/test'

// Item 10 (admin native chrome): the admin page must render INSIDE Confluence's native admin chrome
// — the `atl.admin` Sitemesh decorator (top #header, the left admin-console navigation sidebar) —
// not as a bare standalone HTML page. AdminServlet emits a full HTML document carrying
// <meta name="decorator" content="atl.admin"/>; Sitemesh extracts our body and re-wraps it in the
// Confluence administration template. This proves the decorator actually fired by asserting the
// chrome elements exist in the LIVE DOM alongside our own config form, and saves a screenshot.
test('admin page renders inside Confluence native admin chrome', async ({ page }) => {
  test.setTimeout(90_000)
  const BASE = process.env.CONFLUENCE_BASE || 'http://localhost:1990/confluence'
  const USER = process.env.AUTH_USER || 'admin'
  const PASS = process.env.AUTH_PASS || 'admin'

  await page.goto(`${BASE}/login.action?os_username=${USER}&os_password=${PASS}`)
  await page.waitForLoadState('domcontentloaded')
  await page.goto(`${BASE}/plugins/servlet/likec4/admin`)
  await page.waitForLoadState('domcontentloaded')

  // --- Native chrome (only present when the atl.admin decorator wraps the servlet output) ---------
  // The Confluence top header — a bare servlet page would not have it.
  await expect(page.locator('#header'), 'Confluence #header (decorator chrome) must be present').toBeVisible({ timeout: 15_000 })
  // The administration console left navigation sidebar.
  const adminNav = page.locator('#admin-navigation, #admin-menu')
  await expect(adminNav.first(), 'admin-console navigation sidebar must be present').toBeVisible({ timeout: 15_000 })
  // Our own menu item is rendered as a link inside that admin nav.
  await expect(page.locator('#likec4-admin-link'), 'the LikeC4 admin nav item must appear in the sidebar').toHaveCount(1)
  // The decorator appends " - Confluence" to our <title> when it re-wraps the page.
  expect(await page.title(), 'decorated <title> carries the Confluence suffix').toMatch(/Confluence/)

  // --- Our config form must render alongside the chrome (decoration didn't drop our content) ------
  await expect(page.locator('#likec4-admin-form'), 'the LikeC4 config form must render inside the chrome').toBeVisible({ timeout: 15_000 })
  await expect(page.locator('#likec4-baseUrl'), 'baseUrl field renders').toBeVisible()
  await expect(page.locator('#likec4-allowlist'), 'allowlist field renders').toBeVisible()
  // It must pre-fill from REST (proves the page is live, not a static shell).
  await expect(page.locator('#likec4-baseUrl'), 'baseUrl pre-fills from REST').toHaveValue(/.+/, { timeout: 15_000 })

  console.log('admin chrome OK — title:', await page.title(), '| baseUrl:', await page.locator('#likec4-baseUrl').inputValue())
  await page.screenshot({ path: '/e2e/admin-chrome.png', fullPage: true })
})
