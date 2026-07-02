import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: '**/*.spec.ts',
  // This directory is exclusively the live e2e GATE harness, so:
  // - forbidOnly: a stray `test.only` committed in a spec would otherwise make the gate/sweep run just
  //   that ONE test and report green having silently skipped everything else. Fail instead. (Focus a
  //   single spec by passing its path to run.sh / c10-gate.sh, not with .only.)
  // - timeout: a project-wide floor so a future spec that forgets its own `test.setTimeout(...)` gets a
  //   generous budget rather than Playwright's 30s default and flakes on a cold worker-compute render.
  //   Every current spec sets its own (longer) budget, which overrides this floor.
  // - expect.timeout: a floor above Playwright's 5s default for the same reason; per-assertion timeouts
  //   in the specs still win.
  // Deliberately NO `retries`: this is a gate, so a real failure must surface, not be masked by a retry.
  forbidOnly: true,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  use: { baseURL: process.env.CONFLUENCE_BASE || 'http://localhost:8090' },
})
