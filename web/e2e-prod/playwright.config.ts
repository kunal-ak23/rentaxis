import { defineConfig, devices } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';

// Load .env.local if present. Keep the implementation tiny — no extra deps.
const envFile = path.join(__dirname, '.env.local');
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m && !process.env[m[1]]) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
}

const baseURL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';

export default defineConfig({
  // Prevent two local production suites from sharing the cumulative tenant
  // context. The hook returns its own teardown so the lock is released even
  // when a test fails.
  globalSetup: path.join(__dirname, 'suite-lock.ts'),
  // Root scan dir — covers both global-setup.ts (project: auth-setup) and tests/*.spec.ts.
  testDir: '.',
  // Sequential — the suite builds cumulative state inside a single test tenant.
  // Running in parallel would race on tenant provisioning + cheque lifecycle.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  // Prod can be slower than local — be generous.
  timeout: 60_000,
  expect: { timeout: 15_000 },

  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // Default for UI tests; API tests use their own request context.
    ignoreHTTPSErrors: false,
  },

  projects: [
    // Logs in (NextAuth credentials flow), saves cookie storage state.
    {
      name: 'auth-setup',
      testMatch: /global-setup\.ts/,
    },
    // The full smoke. All tests run with the saved storage state.
    {
      name: 'smoke',
      testMatch: /tests\/.*\.spec\.ts/,
      dependencies: ['auth-setup'],
      use: {
        ...devices['Desktop Chrome'],
        storageState: path.join(__dirname, '.auth', 'superadmin.json'),
      },
    },
  ],
});
