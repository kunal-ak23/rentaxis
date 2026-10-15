import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/** Bug 26/27 + ux6 sweep: listing photos and promotion images render through the backend. Local stack only. */
export default defineConfig({
    testDir: __dirname,
    fullyParallel: false,
    workers: 1,
    retries: 0,
    timeout: 3 * 60_000,
    expect: { timeout: 20_000 },
    reporter: [['list']],
    outputDir: path.join(__dirname, 'raw', 'listing-media'),
    use: { ...devices['Desktop Chrome'], baseURL: process.env.WT_BASE_URL || 'http://localhost:3010', trace: 'retain-on-failure' },
    projects: [{ name: 'listing-media', testMatch: /listing-media\.spec\.ts/ }],
});
