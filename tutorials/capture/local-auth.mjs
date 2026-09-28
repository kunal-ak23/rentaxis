#!/usr/bin/env node
// Sign the LOCAL super admin into a local web stack and save the Playwright
// storage state the recorder reads through TUTORIAL_AUTH_STATE.
//
//   node tutorials/capture/local-auth.mjs <base-url> <output.json>
//
// Local only: refuses any host other than localhost/127.0.0.1. Credentials
// come from TUTORIAL_SUPERADMIN_EMAIL / TUTORIAL_SUPERADMIN_PASSWORD, falling
// back to DataInitializer's local default. The output holds a live session
// token: it must sit in a gitignored directory (tutorials/.auth/), which this
// script checks with `git check-ignore` before writing anything.

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

const [, , baseUrlArg, outputArg] = process.argv;
if (!baseUrlArg || !outputArg) {
  console.error('Usage: node tutorials/capture/local-auth.mjs <base-url> <output.json>');
  process.exit(2);
}
const base = new URL(baseUrlArg);
if (!['localhost', '127.0.0.1'].includes(base.hostname)) {
  throw new Error(`Refusing ${base.origin}: local super-admin sign-in is for a localhost stack only.`);
}
const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const output = path.resolve(outputArg);
try {
  execFileSync('git', ['-C', repoRoot, 'check-ignore', '-q', output]);
} catch {
  throw new Error(`Refusing to write a session token to ${output}: the path is not gitignored.`);
}

const { chromium } = createRequire(path.join(repoRoot, 'web', 'package.json'))('@playwright/test');
const email = process.env.TUTORIAL_SUPERADMIN_EMAIL || 'admin@rentaxis.com';
const password = process.env.TUTORIAL_SUPERADMIN_PASSWORD || 'admin123';

const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ baseURL: base.origin });
  const page = await context.newPage();
  await page.goto('/en/auth/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/(en|ar)\/(dashboard|superadmin)/, { timeout: 30_000, waitUntil: 'domcontentloaded' });
  fs.mkdirSync(path.dirname(output), { recursive: true });
  await context.storageState({ path: output });
  fs.chmodSync(output, 0o600);
  console.log(`auth_state=${output}`);
} finally {
  await browser.close();
}
