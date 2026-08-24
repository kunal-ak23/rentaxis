/**
 * 13a — Cheque clear/failure plus penalty receipt and waiver lifecycles.
 * Runs after 13-finance-and-settings has seeded the chart of accounts.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('manager clears and fails cheques while renter sees paid and waived fines', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  const pmCtx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );

  const adminBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const adminPage = await adminBrowser.newPage();
  await adminPage.goto('/en/auth/login');
  await adminPage.locator('#login-email').fill(ctx.adminEmail);
  await adminPage.locator('#login-password').fill(ctx.adminPassword);
  await adminPage.getByRole('button', { name: /sign in|log in/i }).click();
  await adminPage.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });
  await adminPage.goto('/en/dashboard/settings/fines');
  await expect(adminPage.getByRole('heading', { level: 1, name: 'Cheque-failure fines' })).toBeVisible();
  const fineInputs = adminPage.locator('input[type="number"]');
  await expect(fineInputs.nth(0)).toHaveValue('500');
  await expect(fineInputs.nth(1)).toHaveValue('350');
  await expect(fineInputs.nth(2)).toHaveValue('750');
  await expect(fineInputs.nth(3)).toHaveValue('2');
  await expect(fineInputs.nth(4)).toHaveValue('25');

  const schedule = await api.getPaymentScheduleForLease(pmCtx, ctx.lease.id);
  const deposited = schedule.filter((item) => item.status === 'DEPOSITED');
  expect(deposited.length, '02-cheque-lifecycle must leave two deposited cheques').toBeGreaterThanOrEqual(2);

  const cleared = await api.clearPayment(pmCtx, deposited[0].id, {
    notes: 'TEST-E2E cheque cleared',
  });
  expect(cleared.status).toBe('CLEARED');

  const receipt = await pmCtx.request.get(`/api/proxy/v1/payments/${deposited[0].id}/receipt`, {
    failOnStatusCode: false,
  });
  expect(receipt.ok()).toBeTruthy();
  expect(receipt.headers()['content-type']).toContain('application/pdf');
  expect((await receipt.body()).subarray(0, 4).toString()).toBe('%PDF');

  const failedPaid = await api.markPaymentFailed(
    pmCtx,
    deposited[1].id,
    'ACCOUNT_CLOSED',
    'TEST-E2E account closed',
  );
  expect(failedPaid.schedule.status).toMatch(/FAILED|BOUNCED/);
  expect(failedPaid.penalty.penaltyAmount).toBe(750);

  const renterOpen = await api.listPenalties(renterCtx, ctx.lease.id, 'open');
  expect(renterOpen.content.some((item) => item.id === failedPaid.penalty.id)).toBeTruthy();
  const openPaidPenalty = renterOpen.content.find((item) => item.id === failedPaid.penalty.id)!;
  expect(openPaidPenalty.failureReason).toBe('ACCOUNT_CLOSED');
  expect(openPaidPenalty.outstanding).toBeGreaterThan(0);

  const renterBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const renterPage = await renterBrowser.newPage();
  await renterPage.goto('/en/auth/login');
  await renterPage.locator('#login-email').fill(ctx.renter.email);
  await renterPage.locator('#login-password').fill(ctx.renter.portalPassword);
  await renterPage.getByRole('button', { name: /sign in|log in/i }).click();
  await renterPage.waitForURL(/\/dashboard\/renter-portal/, { timeout: 15_000 });
  await renterPage.goto('/en/dashboard/renter-portal/penalties');
  await expect(renterPage.getByRole('heading', { level: 1, name: 'Penalties' })).toBeVisible();
  await expect(renterPage.getByText('Account closed', { exact: true })).toBeVisible();
  await expect(renterPage.getByText('Open', { exact: true }).last()).toBeVisible();

  const penaltyReceipt = await api.recordPenaltyPayment(
    pmCtx,
    failedPaid.penalty.id,
    openPaidPenalty.outstanding,
    `TEST-PEN-${ctx.runSuffix}`,
  );
  expect(penaltyReceipt.amount).toBe(openPaidPenalty.outstanding);

  const clearedPenalties = await api.listPenalties(renterCtx, ctx.lease.id, 'cleared');
  const paidPenalty = clearedPenalties.content.find((item) => item.id === failedPaid.penalty.id);
  expect(paidPenalty?.status).toBe('CLEARED');
  expect(paidPenalty?.outstanding).toBe(0);
  expect(paidPenalty?.payments.some((item) => item.id === penaltyReceipt.id)).toBeTruthy();

  await renterPage.getByRole('button', { name: 'Cleared / Waived' }).click();
  await expect(renterPage.getByText('Account closed', { exact: true })).toBeVisible();
  await expect(renterPage.getByText('Cleared', { exact: true })).toBeVisible();
  await renterPage.getByRole('button', { name: /payment history/i }).click();
  await expect(renterPage.getByText(`TEST-PEN-${ctx.runSuffix}`, { exact: true })).toBeVisible();

  const pending = (await api.getPaymentScheduleForLease(pmCtx, ctx.lease.id)).find(
    (item) => /PENDING|SCHEDULED/.test(item.status),
  );
  expect(pending, 'a four-installment lease must retain a cheque for waiver coverage').toBeTruthy();
  const today = new Date().toISOString().slice(0, 10);
  await api.collectPayment(pmCtx, pending!.id, {
    chequeNumber: `TST-${ctx.runSuffix}-WAIVE`,
    bankName: 'TEST Bank',
    payerName: `TEST-Renter ${ctx.runSuffix}`,
    chequeDate: today,
  });
  await api.depositPayment(pmCtx, pending!.id);
  const failedWaived = await api.markPaymentFailed(
    pmCtx,
    pending!.id,
    'SIGNATURE_MISMATCH',
    'TEST-E2E signature mismatch',
  );
  expect(failedWaived.penalty.penaltyAmount).toBe(350);

  const waived = await api.waivePenalty(
    taCtx,
    failedWaived.penalty.id,
    'TEST-E2E bank confirmed an operational error',
  );
  expect(waived.status).toBe('WAIVED');
  expect(waived.waived).toBe(true);
  expect(waived.outstanding).toBe(0);

  const finalList = await api.listPenalties(renterCtx, ctx.lease.id);
  expect(finalList.content.find((item) => item.id === failedWaived.penalty.id)).toMatchObject({
    status: 'WAIVED',
    waived: true,
  });

  await renterPage.reload();
  await renterPage.getByRole('button', { name: 'Cleared / Waived' }).click();
  await expect(renterPage.getByText('Signature mismatch', { exact: true })).toBeVisible();
  await expect(renterPage.getByText('Waived', { exact: true })).toBeVisible();

  await adminBrowser.close();
  await renterBrowser.close();
  await pmCtx.request.dispose();
  await taCtx.request.dispose();
  await renterCtx.request.dispose();
});
