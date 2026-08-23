/**
 * 13a — Cheque clear/failure plus penalty receipt and waiver lifecycles.
 * Runs after 13-finance-and-settings has seeded the chart of accounts.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('manager clears and fails cheques while renter sees paid and waived fines', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  const pmCtx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );

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

  await pmCtx.request.dispose();
  await taCtx.request.dispose();
  await renterCtx.request.dispose();
});
