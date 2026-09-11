import { defineConfig, devices } from '@playwright/test';
import * as path from 'node:path';
export default defineConfig({
    testDir: __dirname,
    testMatch: /verify-rent-warning\.spec\.ts/,
    timeout: 180_000,
    reporter: [['list']],
    use: {
        ...devices['Desktop Chrome'],
        baseURL: process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com',
        storageState: path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json'),
    },
});
