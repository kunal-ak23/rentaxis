import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/**
 * Standalone config for the accounting-v2 plan 4 scenario recordings
 * (vouchers, opening balances, reconciliation and the cut-over import),
 * modelled exactly on `accounting-v2-plan3.config.ts`.
 *
 * Drives the local dev stack (Next on 3001, backend on 8081); the spec
 * provisions its own disposable tenant through the API, so there is no
 * auth-setup project and no `.env.local` to read. Nothing here depends on the
 * dev e2e suite's `playwright.config.ts`, whose role projects would run this
 * spec five times.
 *
 * The tenant has to be its own for two reasons this plan adds to plan 3's: the
 * books-start date can only be set once while no opening-balance journal is live
 * (`TenantFiscalSettingsService.setBooksStartDate`), and the cut-over import
 * refuses a property name the organisation already holds — so both are one-shot
 * facts about a fresh organisation.
 *
 * `timeout` is longer than plan 3's because two scenarios wait on a real
 * asynchronous job: the contract import and the bulk post each run on the
 * backend's `importExecutor` and are polled by the screen under test.
 *
 * video: 'on' — the recording IS the proof run. A take only exists because the
 * assertions in the same run passed.
 */
const baseURL = process.env.WT_BASE_URL || 'http://localhost:3001';

export default defineConfig({
    testDir: __dirname,
    fullyParallel: false,
    // One worker, one tenant, one ordered story — later scenarios read state
    // (the chart, the vouchers, the opening balances, the import batch) that
    // earlier ones created.
    workers: 1,
    retries: 0,
    timeout: 300_000,
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
            testMatch: /accounting-v2-plan4\.spec\.ts/,
        },
    ],
});
