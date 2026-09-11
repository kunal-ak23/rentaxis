import { defineConfig } from '@playwright/test';
import * as path from 'node:path';

/** API-only check; no browser, no recording, creates nothing. */
export default defineConfig({
    testDir: __dirname,
    testMatch: /verify-deposit-cheques\.spec\.ts/,
    timeout: 120_000,
    reporter: [['list']],
    use: { baseURL: process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com' },
});
