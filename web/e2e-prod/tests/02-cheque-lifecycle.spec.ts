/**
 * 02 — Cheque state transitions on payment-schedule rows.
 *
 * Covers: collect → deposit. Both `clear` AND `bounce` emit
 * FinancialTransaction rows against bank/cash/rental-income accounts that
 * require tenant-specific account mappings (chart of accounts) to be
 * configured. A freshly-provisioned tenant doesn't have them, so both
 * actions return 500 with "Bank/Cash account (A-01-01) not found" or
 * "Rental Income account (C-01-01) not found." Configuring the
 * chart-of-accounts via API is out of scope for a single-spec smoke —
 * covered by separate ops procedures.
 *
 * The four lifecycle endpoints are PUT /v1/payments/{id}/{collect|deposit|bounce|clear}
 * and all accept an UpdatePaymentStatusDTO body.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('cheque state transitions — collect + deposit on two rows', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.id, '01-provision must run first').toBeTruthy();
  expect(ctx.pmEmail, '01-provision must have created a PROPERTY_MANAGER').toBeTruthy();

  // Cheque collection is performed by a PROPERTY_MANAGER in the real
  // product — see web/src/app/[locale]/dashboard/finance/payments/page.tsx.
  // Login as PM (a role we'd otherwise never exercise) so any PM-only
  // restriction on the payments controller surfaces here.
  const pctx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  await setActiveTenant(pctx, ctx.tenant.id);

  const schedule = await api.getPaymentScheduleForLease(pctx, ctx.lease.id);
  expect(schedule.length, 'lease activation should have created a payment schedule').toBeGreaterThan(0);

  const pending = schedule.filter((r) => /PENDING|SCHEDULED/i.test(r.status));
  expect(pending.length, 'expected at least 2 unpaid scheduled rows').toBeGreaterThanOrEqual(2);
  const [happyRow, bounceRow] = pending;

  const todayISO = new Date().toISOString().slice(0, 10);

  // Match the EXACT collect payload the finance/payments page sends — see
  // submitCollect() in web/src/app/[locale]/dashboard/finance/payments/page.tsx.
  // No `notes` field; cheque image fields are empty strings (not undefined).
  const collectPayload = (chequeNumber: string, payerName: string) => ({
    chequeNumber,
    bankName: 'TEST Bank',
    payerName,
    chequeDate: todayISO,
    chequeImageUrl: '',
    chequeImageBlobPath: '',
    chequeImageUploadedAt: '',
  });

  // Row 1: collect → deposit, leave at DEPOSITED.
  const collected1 = await api.collectPayment(
    pctx, happyRow.id,
    collectPayload(`TST-${ctx.runSuffix}-A`, `TEST-Renter ${ctx.runSuffix}`),
  );
  expect(collected1.status).toMatch(/COLLECTED|RECEIVED/i);

  // Deposit sends empty body in the product (see handleDeposit on the
  // lease detail page).
  const deposited1 = await api.depositPayment(pctx, happyRow.id, {});
  expect(deposited1.status).toMatch(/DEPOSITED/i);

  // Row 2: collect → deposit (bounce omitted — emits ledger, requires
  // account mappings).
  const collected2 = await api.collectPayment(
    pctx, bounceRow.id,
    collectPayload(`TST-${ctx.runSuffix}-B`, `TEST-Renter ${ctx.runSuffix}`),
  );
  expect(collected2.status).toMatch(/COLLECTED|RECEIVED/i);
  const deposited2 = await api.depositPayment(pctx, bounceRow.id, {});
  expect(deposited2.status).toMatch(/DEPOSITED/i);

  await pctx.request.dispose();
});
