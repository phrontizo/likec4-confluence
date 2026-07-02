import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './test',
  testMatch: '**/*.spec.ts',
  webServer: {
    command: 'npm run build && npm run preview',
    url: 'http://localhost:4317',
    reuseExistingServer: false,
    timeout: 120_000,
  },
  use: { baseURL: 'http://localhost:4317' },
})
