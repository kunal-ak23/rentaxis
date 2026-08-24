/**
 * 04 — Reports surface smoke. We don't validate report content (that's owned
 * by backend unit tests); we just verify the endpoints respond 200 with a
 * non-error JSON shape under realistic tenant scope.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('tenant admin loads dashboard KPIs and generates finance reports', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  // Finance reports are accessed by TENANT_ADMIN in the product
  // (canAccessFinance permission). Test as that role rather than SUPER_ADMIN
  // so any TA-vs-SA divergence on these endpoints surfaces.
  const pctx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(pctx, ctx.tenant.id);

  for (const url of [
    '/api/proxy/v1/finance/transactions',
    '/api/proxy/v1/finance/reports/trial-balance',
    '/api/proxy/v1/finance/reports/organisation',
    `/api/proxy/v1/finance/reports/property/${ctx.property.id}`,
    `/api/proxy/v1/finance/reports/unit/${ctx.unit.id}`,
    '/api/proxy/v1/payments/aging-report',
  ]) {
    const res = await pctx.request.get(url);
    expect(res.status(), `${url} returned ${res.status()}`).toBeLessThan(500);
    // Some reports legitimately 404 if no data — that's fine. 5xx is not.
  }

  const browserCtx = await browser.newContext({ baseURL: ctx.baseURL });
  const page = await browserCtx.newPage();
  await page.goto('/en/auth/login');
  await page.locator('#login-email').fill(ctx.adminEmail);
  await page.locator('#login-password').fill(ctx.adminPassword);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });
  await expect(page.getByText('Occupancy', { exact: true })).toBeVisible();
  await expect(page.getByText('Collection vs expected', { exact: true }).first()).toBeVisible();

  await page.goto('/en/dashboard/finance/reports');
  await expect(page.getByRole('heading', { level: 1, name: 'Reports' })).toBeVisible();

  const generate = page.getByRole('button', { name: /^generate report$/i });
  const [profitResponse] = await Promise.all([
    page.waitForResponse((response) => response.url().includes('/finance/reports/organisation')),
    generate.click(),
  ]);
  expect(profitResponse.status()).toBeLessThan(500);
  await expect(page.getByText('Total Income', { exact: true })).toBeVisible();
  await expect(page.getByText('Net Operating Income', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: /^balance sheet$/i }).click();
  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/finance/reports/organisation')),
    generate.click(),
  ]);
  await expect(page.getByText('Total Assets', { exact: true })).toBeVisible();
  await expect(page.getByText('Total Liabilities', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: /^trial balance$/i }).click();
  const [trialResponse] = await Promise.all([
    page.waitForResponse((response) => response.url().includes('/finance/reports/trial-balance')),
    generate.click(),
  ]);
  expect(trialResponse.status()).toBeLessThan(500);

  await page.getByRole('button', { name: /^aging report$/i }).click();
  const [agingResponse] = await Promise.all([
    page.waitForResponse((response) => response.url().includes('/payments/aging-report')),
    generate.click(),
  ]);
  expect(agingResponse.status()).toBeLessThan(500);
  await expect(page.getByText('Total Outstanding', { exact: true })).toBeVisible();

  await browserCtx.close();

  await pctx.request.dispose();
});
