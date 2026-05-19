/**
 * Prod smoke — auth setup project.
 *
 * Logs in as SUPER_ADMIN via NextAuth credentials flow, saves the cookie
 * storage state, and stashes a tiny context file the spec files can read.
 *
 * Provisioning of the test tenant happens in 01-provision.spec.ts, NOT here,
 * so a failure during provisioning produces a normal test report instead of
 * an opaque "global setup failed".
 */
import { test as setup, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { loginAsNextAuth } from './helpers/prod-client';

const AUTH_FILE = path.join(__dirname, '.auth', 'superadmin.json');
const CONTEXT_FILE = path.join(__dirname, '.test-context.json');

setup('login as SUPER_ADMIN', async () => {
  const baseURL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';
  const email = process.env.PROD_SUPERADMIN_EMAIL;
  const password = process.env.PROD_SUPERADMIN_PASSWORD;

  expect(
    email && password,
    'PROD_SUPERADMIN_EMAIL and PROD_SUPERADMIN_PASSWORD must be set in web/e2e-prod/.env.local',
  ).toBeTruthy();

  const pctx = await loginAsNextAuth(baseURL, email!, password!);

  // Persist the cookie state for downstream tests.
  fs.mkdirSync(path.dirname(AUTH_FILE), { recursive: true });
  await pctx.request.storageState({ path: AUTH_FILE });

  // Stash identity for later specs.
  fs.writeFileSync(
    CONTEXT_FILE,
    JSON.stringify(
      {
        baseURL,
        user: pctx.user,
        runSuffix: Date.now().toString(36),
      },
      null,
      2,
    ),
  );

  console.log(`Logged in as ${pctx.user?.email} (${pctx.user?.role}). State saved.`);
  await pctx.request.dispose();
});
