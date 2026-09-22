import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/**
 * Standalone config for the accounting-v2 plan 3 scenario recordings
 * (per-day rent recognition, termination and settlement), modelled exactly on
 * `accounting-v2-plan2.config.ts`.
 *
 * Drives the local dev stack (Next on 3001, backend on 8081); the spec
 * provisions its own disposable tenant through the API, so there is no
 * auth-setup project and no `.env.local` to read. Nothing here depends on
 * the dev e2e suite's `playwright.config.ts`, whose role projects would run
 * this spec five times.
 *
 * Unlike plan 2 this spec needs no gateway stub: nothing here touches an
 * online payment. What it does need is a tenant whose books are NOT locked
 * when it starts, which is why it provisions its own — scenario 04 closes the
 * period and a period lock only ever moves forwards.
 *
 * video: 'on' — the recording IS the proof run. A take only exists because
 * the assertions in the same run passed.
 */
const baseURL = process.env.WT_BASE_URL || 'http://localhost:3001';

export default defineConfig({
    testDir: __dirname,
    fullyParallel: false,
    // One worker, one tenant, one ordered story — later scenarios read state
    // (leases, schedules, journals, the period lock) earlier ones created.
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
        trace: 'retain-on-failure',
    },
    projects: [
        {
            name: 'record',
            testMatch: /accounting-v2-plan3\.spec\.ts/,
        },
    ],
});
