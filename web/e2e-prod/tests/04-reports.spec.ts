/**
 * 04 — Dashboard KPIs and the aging report.
 *
 * The v1 finance reports (/finance/transactions, /finance/reports/*) and the
 * page that drove them were retired with the one-legged ledger in accounting
 * v2 plan 1; the general ledger, tenant ledger and trial balance have their own
 * pages and are covered by 13-finance-and-settings. What is left here is the
 * dashboard KPI surface and the aging report, which plan 2 re-sources from
 * the cheque register (`GET /cheques/aging`) rather than payment schedules —
 * `/v1/payments/aging-report` is gone along with the rest of `/v1/payments/*`.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('tenant admin loads dashboard KPIs and the aging report', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  // Accessed by TENANT_ADMIN in the product (canAccessFinance permission).
  // Test as that role rather than SUPER_ADMIN so any TA-vs-SA divergence on
  // these endpoints surfaces.
  const pctx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(pctx, ctx.tenant.id);

  const res = await pctx.request.get('/api/proxy/v1/cheques/aging');
  expect(res.status(), `aging returned ${res.status()}`).toBeLessThan(500);

  const browserCtx = await browser.newContext({ baseURL: ctx.baseURL });
  const page = await browserCtx.newPage();
  await page.goto('/en/auth/login');
  await page.locator('#login-email').fill(ctx.adminEmail);
  await page.locator('#login-password').fill(ctx.adminPassword);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });
  await expect(page.getByText('Occupancy', { exact: true })).toBeVisible();
  await expect(page.getByText('Collection vs expected', { exact: true }).first()).toBeVisible();

  await browserCtx.close();

  await pctx.request.dispose();
});
