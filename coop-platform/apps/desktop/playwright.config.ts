import { defineConfig, devices } from '@playwright/test'

/**
 * Browser tests of the screens. They need the backend running on :8090 (npm run dev proxies /api to it). Tests that sign in need
 * E2E_USER and E2E_PASSWORD in the environment (never stored in a file); without them those tests are skipped.
 * They only read: nothing here posts to the ledger (the database tests in the backend cover posting).
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:5173',
    viewport: { width: 1366, height: 768 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 768 } } }],
  webServer: { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true, timeout: 60_000 },
})
