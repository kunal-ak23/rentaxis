import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
  timeout: 30_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    // Setup project: seeds data via API
    {
      name: 'setup',
      testMatch: /global-setup\.ts/,
    },

    // Auth setup: logs in each role and saves storage state
    {
      name: 'auth-setup',
      testMatch: /auth-setup\.ts/,
      dependencies: ['setup'],
    },

    // Anonymous tests (login/register) - no auth needed, just depends on seed data
    {
      name: 'anonymous',
      testMatch: /auth\/(login|register)\.spec\.ts/,
      dependencies: ['setup'],
      use: { ...devices['Desktop Chrome'] },
    },

    // Role-based projects - exclude auth/login and auth/register specs
    {
      name: 'super-admin',
      testIgnore: /auth\/(login|register)\.spec\.ts/,
      testMatch: /\.spec\.ts/,
      dependencies: ['auth-setup'],
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/super-admin.json',
      },
    },
    {
      name: 'tenant-admin',
      testIgnore: /auth\/(login|register)\.spec\.ts/,
      testMatch: /\.spec\.ts/,
      dependencies: ['auth-setup'],
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/tenant-admin.json',
      },
    },
    {
      name: 'property-manager',
      testIgnore: /auth\/(login|register)\.spec\.ts/,
      testMatch: /\.spec\.ts/,
      dependencies: ['auth-setup'],
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/property-manager.json',
      },
    },
    {
      name: 'tenant-user',
      testIgnore: /auth\/(login|register)\.spec\.ts/,
      testMatch: /\.spec\.ts/,
      dependencies: ['auth-setup'],
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/tenant-user.json',
      },
    },
    {
      name: 'renter',
      testIgnore: /auth\/(login|register)\.spec\.ts/,
      testMatch: /\.spec\.ts/,
      dependencies: ['auth-setup'],
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/renter.json',
      },
    },
  ],
});
