/**
 * 14 — Extended lease lifecycle after all payment-dependent smoke checks.
 * Previews the generated contract without persisting a blob, extends the lease,
 * drafts a settlement with deduction and addition lines, and finalizes it.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('tenant admin previews contract, extends, and settles the active lease', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.id, '01-provision must run first').toBeTruthy();

  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(taCtx, ctx.tenant.id);

  const previewPdf = await taCtx.request.post(
    `/api/proxy/v1/leases/${ctx.lease.id}/generate-contract/preview`,
    { failOnStatusCode: false },
  );
  expect(previewPdf.ok()).toBeTruthy();
  expect(previewPdf.headers()['content-type']).toContain('application/pdf');
  expect((await previewPdf.body()).subarray(0, 4).toString()).toBe('%PDF');

  const currentEndDate = new Date();
  currentEndDate.setFullYear(currentEndDate.getFullYear() + 1);
  const extendedEndDate = new Date(currentEndDate);
  extendedEndDate.setMonth(extendedEndDate.getMonth() + 2);
  const newEndDate = extendedEndDate.toISOString().slice(0, 10);

  // accounting-v2 plan 2's extend posts a fresh TCO for the extension's own
  // `lines`, and the cheques registered for it must total the SAME
  // VAT-inclusive figure (ExtendLeaseDialog's own `matches` gate) — unlike
  // v1's single-field `{ newEndDate }` body. One RENT line of 50,000, one
  // PDC cheque of 50,000: RENT's `vatApplicableDefault` is false, so gross
  // and VAT-inclusive coincide and the two figures need no separate VAT calc.
  const extended = await api.extendLease(taCtx, ctx.lease.id, newEndDate, 50_000);
  expect(extended.lease.status).toBe('ACTIVE');
  expect(extended.lease.endDate).toBe(newEndDate);

  // ── accounting-v2 plan 3: terminate first, settle afterwards ─────────────
  //
  // Terminating and settling are two acts now. `POST /settlement/finalize` no
  // longer ends the contract (it refused an ACTIVE lease outright:
  // "Terminate the lease before settling it"), the old `SettlementPreviewDTO`
  // (`depositAmount` / `unpaidRentTotal` / `penaltyTotal` / `suggestedRefund`)
  // is gone, and UNPAID_RENT / PENALTIES / PREPAID_RENT / UTILITY_OVERPAYMENT
  // are refused as settlement lines because they are already inside
  // `receivableBalance`.
  const today = new Date().toISOString().slice(0, 10);

  const terminationPreview = await api.previewTermination(taCtx, ctx.lease.id, today);
  expect(terminationPreview.terminationDate).toBe(today);
  expect(terminationPreview.earnedRentThroughDate).toBeGreaterThanOrEqual(0);
  const terminated = await api.terminateLease(taCtx, ctx.lease.id, {
    terminationDate: today,
    returnChequeIds: terminationPreview.chequesToReturn.map((c) => c.id),
    keepChequeIds: terminationPreview.chequesToKeep.map((c) => c.id),
    notes: `TEST-E2E move-out ${ctx.runSuffix}`,
  });
  expect(terminated.status).toBe('TERMINATED');
  expect(terminated.terminatedOn).toBe(today);

  // The truncation plans a slice ending on the termination date; the statement
  // is only honest once the close has posted it.
  await api.runRecognition(taCtx, today);

  const statement = await api.getSettlementPreview(taCtx, ctx.lease.id);
  expect(statement.unrecognisedEntries).toBe(0);
  expect(statement.depositsHeld).toBeGreaterThanOrEqual(0);
  expect(statement.asOf).toBeTruthy();

  const draft = await api.saveSettlementDraft(taCtx, ctx.lease.id, {
    notes: `TEST-E2E settlement ${ctx.runSuffix}`,
    deductions: [
      {
        category: 'CLEANING',
        description: 'TEST-Exit cleaning',
        amount: 50,
        autoCalculated: false,
        type: 'DEDUCTION',
      },
      {
        // DEPOSIT_INTEREST, not UTILITY_OVERPAYMENT: the latter is a credit the
        // receivable already carries and the server refuses it as a line.
        description: 'TEST-Deposit interest',
        amount: 25,
        autoCalculated: false,
        type: 'ADDITION',
        additionCategory: 'DEPOSIT_INTEREST',
      },
    ],
  });
  expect(draft.status).toBe('DRAFT');
  expect(draft.totalDeductions).toBe(50);
  expect(draft.totalAdditions).toBe(25);
  expect(draft.deductions).toHaveLength(2);

  const saved = await api.getSettlement(taCtx, ctx.lease.id);
  expect(saved.id).toBe(draft.id);
  expect(saved.refundAmount).toBe(draft.refundAmount);

  const adminBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const adminPage = await adminBrowser.newPage();
  await adminPage.goto('/en/auth/login');
  await adminPage.locator('#login-email').fill(ctx.adminEmail);
  await adminPage.locator('#login-password').fill(ctx.adminPassword);
  await adminPage.getByRole('button', { name: /sign in|log in/i }).click();
  await adminPage.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });
  await adminPage.goto(`/en/dashboard/leases/${ctx.lease.id}/settlement`);
  await expect(adminPage.getByRole('heading', { level: 1, name: 'Settlement' })).toBeVisible();
  await expect(adminPage.getByTestId('settlement-status')).toHaveText('Draft');
  await expect(adminPage.getByTestId('settlement-notes')).toHaveValue(
    `TEST-E2E settlement ${ctx.runSuffix}`,
  );
  await expect(adminPage.getByTestId('settlement-description-0')).toHaveValue('TEST-Exit cleaning');
  await expect(adminPage.getByTestId('settlement-description-1')).toHaveValue('TEST-Deposit interest');
  await expect(adminPage.getByTestId('settlement-total-deductions')).toContainText('50.00');
  await expect(adminPage.getByTestId('settlement-total-additions')).toContainText('25.00');

  // Finalise through the API: the bank a refund is paid from and the
  // acknowledgement a refund over outstanding paper needs are both conditional
  // on figures only the statement knows, and this suite runs against whatever
  // 01-provision left on the register.
  const priced = await api.getSettlementPreview(taCtx, ctx.lease.id);
  const refunds = priced.netRefund > 0;
  let refundBankAccountId: string | null = null;
  if (refunds) {
    const accounts = await api.getAccounts(taCtx);
    const bank = accounts.find(
      (a) =>
        a.accountType === 'ASSET'
        && a.group === false
        && a.active !== false
        && (a.accountSubType === 'BANK' || a.accountSubType === 'CASH'),
    );
    expect(bank, 'a refund needs an active bank or cash leaf to pay from').toBeTruthy();
    refundBankAccountId = bank!.id;
  }
  const finalized = await api.finalizeSettlement(taCtx, ctx.lease.id, {
    settlementDate: today,
    refundBankAccountId,
    acknowledgeOutstanding: refunds && priced.instrumentsOutstanding > 0,
  });
  expect(finalized.status).toBe('FINALIZED');
  expect(finalized.journalNumber).toMatch(/^STL/);

  await adminPage.reload();
  await expect(adminPage.getByTestId('settlement-status')).toHaveText('Finalized');
  await expect(adminPage.getByTestId('settlement-journal')).toContainText('STL');

  // Finalise does not close the contract; the register does. `LeaseClosureService`
  // closes it only once nothing is left to collect, so the expected status is
  // derived from what the statement says is still out rather than assumed.
  const after = await api.getLease(taCtx, ctx.lease.id);
  expect(after.status).toBe(priced.instrumentsOutstanding > 0 ? 'TERMINATED' : 'CLOSED');

  const events = await api.getLeaseEvents(taCtx, ctx.lease.id);
  expect(events.some((event) => event.notes.includes('Lease extended'))).toBeTruthy();
  expect(events.some((event) => event.newState === 'TERMINATED')).toBeTruthy();

  await adminBrowser.close();
  await taCtx.request.dispose();
});
