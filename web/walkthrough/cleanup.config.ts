import { defineConfig } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs';

// Same inline .env reader as lease-cheques.config.ts — web/ has no dotenv
// dependency, and the credentials live in e2e-prod/.env.local.
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
 * Runs only the walkthrough cleanup. Kept separate from the recording config
 * so a cleanup can never be triggered as a side effect of recording, and no
 * video is captured of a destructive operation.
 */
const baseURL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';

export default defineConfig({
  testDir: __dirname,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 20 * 60_000,
  expect: { timeout: 30_000 },
  reporter: [['list']],
  outputDir: path.join(__dirname, 'raw'),
  use: { baseURL, ignoreHTTPSErrors: false },
  projects: [
    {
      name: 'auth-setup',
      testDir: path.join(__dirname, '..', 'e2e-prod'),
      testMatch: /global-setup\.ts/,
    },
    {
      name: 'cleanup',
      testMatch: /cleanup\.spec\.ts/,
      dependencies: ['auth-setup'],
      use: { storageState: path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json') },
    },
  ],
});
