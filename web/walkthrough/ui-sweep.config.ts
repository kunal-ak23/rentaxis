import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/**
 * One run id for the whole run, set here (the config is evaluated once, in the
 * runner) so every worker the spec lands in reads the same organisation's
 * fixture — a failed sweep restarts its worker, and the next sweep must still
 * find the provisioned organisation rather than stop the run.
 */
process.env.WT_SWEEP_SUFFIX ??= Math.random().toString(36).slice(2, 7);

/** Admin UI simplification sweep (spec 2026-09-25 safeguard 4). No video: this is a gate, not a recording. */
export default defineConfig({
    testDir: __dirname,
    fullyParallel: false,
    workers: 1,
    retries: 0,
    // Each sweep takes about a minute; a wait that never resolves fails in 5 min
    // instead of looking like a hung runner (PR #363 R1).
    timeout: 5 * 60_000,
    globalTimeout: 45 * 60_000,
    expect: { timeout: 20_000 },
    reporter: [['list']],
    // Its own sub-folder: Playwright empties outputDir at the start of a run,
    // and walkthrough/raw is shared with the recording configs.
    outputDir: path.join(__dirname, 'raw', 'ui-sweep'),
    use: { ...devices['Desktop Chrome'], baseURL: process.env.WT_BASE_URL || 'http://localhost:3001', trace: 'retain-on-failure' },
    projects: [{ name: 'sweep', testMatch: /ui-sweep\.spec\.ts/ }],
});
