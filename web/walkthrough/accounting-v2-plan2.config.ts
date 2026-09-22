import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/**
 * Standalone config for the accounting-v2 plan 2 scenario recordings
 * (lease posting + the PDC register), modelled exactly on
 * `accounting-v2-plan1.config.ts`.
 *
 * Drives the local dev stack (Next on 3001, backend on 8081); the spec
 * provisions its own disposable tenant through the API, so there is no
 * auth-setup project and no `.env.local` to read. Nothing here depends on
 * the dev e2e suite's `playwright.config.ts`, whose role projects would run
 * this spec five times.
 *
 * The backend must run with `rentaxis.gateway.stub.enabled=true` (or
 * `RENTAXIS_GATEWAY_STUB_ENABLED=true`). Scenario 14 drives a real online
 * payment, and the production RazorpayProvider calls the live gateway to
 * create the order, which throws on the synthetic keys this spec configures.
 * The stub removes only that call - webhook signatures are still verified for
 * real, so scenario 14 still proves the trust boundary.
 *
 * video: 'on' — the recording IS the proof run. A take only exists because
 * the assertions in the same run passed.
 */
const baseURL = process.env.WT_BASE_URL || 'http://localhost:3001';

export default defineConfig({
    testDir: __dirname,
    fullyParallel: false,
    // One worker, one tenant, one ordered story — later scenarios read state
    // (leases, cheques, journals) earlier ones created.
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
            testMatch: /accounting-v2-plan2\.spec\.ts/,
        },
    ],
});
