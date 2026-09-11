import { defineConfig } from '@playwright/test';
export default defineConfig({ testDir: __dirname, testMatch: /verify-lease-term\.spec\.ts/, timeout: 180_000,
  reporter: [['list']], use: { baseURL: process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com' } });
