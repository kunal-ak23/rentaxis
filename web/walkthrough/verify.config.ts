import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs';

// Same inline .env reader as the other walkthrough configs — web/ has no dotenv
// dependency and the prod settings live in e2e-prod/.env.local.
(() => {
  const envPath = path.join(__dirname, '..', 'e2e-prod', '.env.local');
  if (!fs.existsSync(envPath)) return;
  for (const line of fs.readFileSync(envPath, 'utf8').split('\n')) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)$/.exec(line);
    if (!m) continue;
    const key = m[1];
    const value = m[2].trim().replace(/^["']|["']$/g, '');
    if (process.env[key] === undefined) process.env[key] = value;
  }
})();

/**
 * Post-deploy verification against production. Signs in as a tenant admin in
 * its own right rather than reusing the SUPER_ADMIN storageState, because the
 * thing under test is what an ordinary Arabic-locale admin sees.
 *
 * Credentials come from the environment and are never written to a file.
 */
export default defineConfig({
  testDir: __dirname,
  testMatch: /verify-arabic\.spec\.ts/,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 30_000 },
  reporter: [['list']],
  outputDir: path.join(__dirname, 'raw'),
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com',
    viewport: { width: 1440, height: 900 },
    ignoreHTTPSErrors: false,
    trace: 'retain-on-failure',
  },
});
