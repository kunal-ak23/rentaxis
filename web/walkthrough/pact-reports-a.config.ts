import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/**
 * Standalone config for the PACT report parity PR A recordings (the shared
 * report engine and R1 Tenant Ledger — docs/superpowers/specs/
 * 2026-10-26-pact-reports-design.md §1–§2), modelled on
 * `accounting-v2-plan4.config.ts`.
 *
 * Drives a local dev stack — Next on WT_BASE_URL (default 3003), the backend on
 * WT_BACKEND_URL (default 8083) — and the spec provisions its own disposable
 * tenant through the API, so there is no auth-setup project and no `.env.local`
 * to read. Nothing here depends on the dev e2e suite's `playwright.config.ts`,
 * whose role projects would run this spec five times.
 *
 * video: 'on' — the recording IS the proof run. A take only exists because the
 * assertions in the same run passed. Takes land in walkthrough/takes/
 * pact-reports-a/, Playwright's own output in walkthrough/raw/ (both gitignored).
 */
const baseURL = process.env.WT_BASE_URL || 'http://localhost:3003';

export default defineConfig({
    testDir: __dirname,
    fullyParallel: false,
    // One worker, one tenant, one ordered story — every scenario reads the
    // leases, cheques and sessions scenario 00 created.
    workers: 1,
    retries: 0,
    timeout: 240_000,
    expect: { timeout: 20_000 },
    reporter: [['list']],
    outputDir: path.join(__dirname, 'raw'),
    use: {
        ...devices['Desktop Chrome'],
        baseURL,
        // A correct run takes the machine a few seconds per scenario, which is
        // proof but not something a person can watch. slowMo paces the actions
        // to roughly demo speed without inserting fake waits into assertions.
        launchOptions: { slowMo: Number(process.env.WT_SLOWMO ?? 180) },
        video: { mode: 'on', size: { width: 1280, height: 720 } },
        viewport: { width: 1280, height: 720 },
        acceptDownloads: true,
        trace: 'retain-on-failure',
    },
    projects: [
        {
            name: 'record',
            testMatch: /pact-reports-a\.spec\.ts/,
        },
    ],
});
