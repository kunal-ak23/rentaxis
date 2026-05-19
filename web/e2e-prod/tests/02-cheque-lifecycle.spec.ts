/**
 * 02 — Cheque lifecycle: collect → deposit → clear on one row,
 *      collect → deposit → bounce on another. Asserts state transitions
 *      and that FinancialTransaction rows are emitted on clear/bounce.
 *
 * The four lifecycle endpoints are PUT /v1/payments/{id}/{collect|deposit|clear|bounce}
 * and all accept an UpdatePaymentStatusDTO body.
 */
import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, ProdContext, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('cheque lifecycle — happy path (clear) and bounce path', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.id, '01-provision must run first').toBeTruthy();

  const request = await playwrightRequest.newContext({
    baseURL: ctx.baseURL,
    storageState: path.join(__dirname, '..', '.auth', 'superadmin.json'),
  });
  const pctx: ProdContext = { baseURL: ctx.baseURL, request, user: ctx.user };
  await setActiveTenant(pctx, ctx.tenant.id);

  const schedule = await api.getPaymentScheduleForLease(pctx, ctx.lease.id);
  expect(schedule.length, 'lease activation should have created a payment schedule').toBeGreaterThan(0);

  // Pick the first PENDING row for the clear path, the second for the bounce path.
  const pending = schedule.filter((r) => /PENDING|SCHEDULED/i.test(r.status));
  expect(pending.length, 'expected at least 2 unpaid scheduled rows').toBeGreaterThanOrEqual(2);
  const [happyRow, bounceRow] = pending;

  // ---- Happy path ---------------------------------------------------------
  const todayISO = new Date().toISOString().slice(0, 10);
  const collected = await api.collectPayment(pctx, happyRow.id, {
    chequeNumber: `TST-${ctx.runSuffix}-A`,
    bankName: 'TEST Bank',
    chequeDate: todayISO,
    payerName: `TEST-Renter ${ctx.runSuffix}`,
    notes: 'e2e collect',
  });
  expect(collected.status).toMatch(/COLLECTED|RECEIVED/i);

  const deposited = await api.depositPayment(pctx, happyRow.id, { notes: 'e2e deposit' });
  expect(deposited.status).toMatch(/DEPOSITED/i);

  const cleared = await api.clearPayment(pctx, happyRow.id, { notes: 'e2e clear' });
  expect(cleared.status).toMatch(/CLEARED|PAID/i);

  // Clearing should emit a FinancialTransaction. Worth asserting because
  // a missing ledger entry is a silent data-loss bug.
  const txs = await api.getFinancialTransactions(pctx, ctx.lease.id);
  expect(
    txs.some((t) => /RENT|INCOME|PAYMENT/i.test(t.type)),
    'expected a rent/payment ledger entry after clearing a cheque',
  ).toBeTruthy();

  // ---- Bounce path --------------------------------------------------------
  await api.collectPayment(pctx, bounceRow.id, {
    chequeNumber: `TST-${ctx.runSuffix}-B`,
    bankName: 'TEST Bank',
    chequeDate: todayISO,
  });
  await api.depositPayment(pctx, bounceRow.id);
  const bounced = await api.bouncePayment(pctx, bounceRow.id, { notes: 'e2e bounce — insufficient funds' });
  expect(bounced.status).toMatch(/BOUNCED|FAILED/i);

  await request.dispose();
});
