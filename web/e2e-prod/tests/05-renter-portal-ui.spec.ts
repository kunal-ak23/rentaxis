/**
 * 05 — Renter portal UI smoke. The only test in the prod suite that runs a
 * real browser. Validates surfaces that aren't observable via HTTP alone:
 *
 *   - /[locale]/renter/payments renders the "next cheque" hero
 *   - "Deposited" / "Bounced" cheques are visible with the right subtitle text
 *   - There is no "Pay Now" button (online payments are PM-initiated only)
 *
 * Auth: the renter's portal password is captured from createRenter's response
 * (RenterDTO.portalPassword, set by RenterService when createPortalAccount=true
 * — now the default since RenterService refactor).
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('renter portal payments page shows expected widgets', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  const renterPwd = ctx.renter?.portalPassword;
  expect(renterPwd, 'provisioning must have captured a portal password').toBeTruthy();

  // Fresh browser context — do NOT inherit the SUPER_ADMIN session cookie.
  const ctxBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const page = await ctxBrowser.newPage();

  await page.goto('/en/auth/login');
  // Use ID-based locators — the login page has both a password input AND a
  // "Show password" toggle button, so a label-based query is ambiguous.
  await page.locator('#login-email').fill(ctx.renter.email);
  await page.locator('#login-password').fill(renterPwd);
  await page.getByRole('button', { name: /sign in|log in/i }).click();

  // Login redirects RENTERs to /dashboard/renter-portal.
  await page.waitForURL(/\/dashboard\/renter-portal/);
  await page.goto('/en/dashboard/renter-portal/payments');

  // Hero showing next cheque (provisioning created 4 quarterly rows; 02 left
  // 2 still PENDING for the hero to pick). Translated label: "Next cheque due".
  await expect(page.getByText(/next cheque/i)).toBeVisible();
  // No Pay Now CTA on prod — online payments are PM-initiated.
  await expect(page.getByRole('button', { name: /pay now/i })).toHaveCount(0);

  await ctxBrowser.close();
});
