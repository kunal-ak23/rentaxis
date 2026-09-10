import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs';

// Tiny inline .env reader rather than a dotenv dependency — web/ does not have
// dotenv installed, and adding a package to record a video is not a good trade.
// Only fills variables that are not already set, so an explicit export wins.
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
 * Standalone config for the lease-creation cheque walkthrough.
 *
 * Separate from e2e-prod/playwright.config.ts on purpose: that suite is serial
 * and numbered, and 99-cleanup deletes the tenant it provisioned. Dropping a
 * recording spec into it would either run at the wrong point or have its own
 * fixtures deleted mid-run.
 *
 * video: 'on' — the recording IS the proof run. A take only exists because the
 * assertions in the same run passed.
 */
const baseURL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';

export default defineConfig({
  testDir: __dirname,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 20_000 },
  reporter: [['list']],
  outputDir: path.join(__dirname, 'raw'),
  use: {
    // Spread first so the explicit settings below win. With the spread last,
    // devices['Desktop Chrome'] silently overwrote `viewport` — same values, so
    // no behaviour change, but tsc rejects the duplicate key and it fails the
    // production web build, which type-checks this directory too.
    ...devices['Desktop Chrome'],
    baseURL,
    // A correct run of this flow takes the machine about three seconds, which
    // is a proof but not something a person can watch. slowMo paces the actions
    // to roughly demo speed without inserting fake waits into the assertions.
    launchOptions: { slowMo: Number(process.env.WT_SLOWMO ?? 220) },
    video: { mode: 'on', size: { width: 1280, height: 720 } },
    viewport: { width: 1280, height: 720 },
    trace: 'retain-on-failure',
    ignoreHTTPSErrors: false,
  },
  projects: [
    // Points at the ORIGINAL e2e-prod global-setup rather than a copy, so the
    // session tokens land in web/e2e-prod/.auth/ — a path already gitignored.
    // A copy would have written storageState into a brand-new directory that
    // nothing ignores yet, which is how session tokens get committed.
    {
      name: 'auth-setup',
      testDir: path.join(__dirname, '..', 'e2e-prod'),
      testMatch: /global-setup\.ts/,
    },
    {
      name: 'record',
      testMatch: /lease-cheques\.spec\.ts/,
      dependencies: ['auth-setup'],
      use: { storageState: path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json') },
    },
  ],
});
