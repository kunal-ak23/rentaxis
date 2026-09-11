import { defineConfig, devices } from '@playwright/test';
import * as path from 'node:path';
export default defineConfig({
    testDir: __dirname,
    testMatch: /proof-shots\.spec\.ts/,
    timeout: 240_000,
    reporter: [['list']],
    use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1280, height: 900 },
        deviceScaleFactor: 2,
        baseURL: process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com',
        storageState: path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json'),
    },
});
