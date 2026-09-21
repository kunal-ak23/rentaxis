import { test, expect } from '../fixtures/auth.fixture';
import type { Page } from '@playwright/test';
import { createUnit, createRenter, createLease, generateCheques, postLease, getLeaseCheques } from '../helpers/api-client';

/**
 * The cheque register end to end: generate -> post -> deposit -> clear ->
 * bounce -> replace -> bounce again (a late return, CLEARED -> BOUNCED) ->
 * approve a penalty for it -> receive cash to close it out.
 *
 * Setup (draft, generate, post) goes through the API — the wizard itself is
 * lease-lifecycle.spec.ts's job. This spec drives the REGISTER
 * (`/dashboard/finance/cheques`) and the penalty worklist
 * (`/dashboard/finance/penalties`) the way an accountant actually works
 * them, one row at a time.
 *
 * "Nets to zero" (spec Task 17) is read as: replacing a bounced cheque with
 * a fresh instrument of the same amount is a SWAP, not a write-off — the
 * tenant's ledger balance right after the replace equals the balance right
 * before the bounce that preceded it. The later late-return bounce (a
 * CLEARED row failing after the fact, with no replacement offered) is the
 * one that actually reopens a receivable — by exactly that cheque's own
 * amount, 12,750 on this lease's cheques.
 *
 * The renter's own ledger balance is read via the SAME endpoint the tenant
 * ledger page reads (`GET /finance/ledger/renter/{id}`), fetched directly
 * rather than scraped off the rendered table — one renter, one lease, so
 * summing every account's closingBalance in that response is the tenant's
 * net position without guessing at LedgerTable's markup.
 */

async function tenantLedgerBalance(page: Page, renterId: string): Promise<number> {
  const res = await page.request.get(`/api/proxy/v1/finance/ledger/renter/${renterId}`);
  expect(res.ok(), `tenant ledger read failed: ${res.status()}`).toBeTruthy();
  const ledgers: Array<{ closingBalance: number }> = await res.json();
  return ledgers.reduce((sum, l) => sum + (l.closingBalance || 0), 0);
}

/** Anything on the v1 API, through the proxy this browser session is signed in to. */
async function proxy<T>(page: Page, method: 'get' | 'post' | 'put', path: string, body?: unknown): Promise<T> {
  const res = await page.request[method](`/api/proxy/v1${path}`, body === undefined ? {} : { data: body });
  expect(res.ok(), `${method.toUpperCase()} ${path} failed (${res.status()}): ${await res.text().catch(() => '')}`).toBeTruthy();
  return (await res.json()) as T;
}

/**
 * The invariant every accounting-v2 scenario ends on: whatever was posted, the
 * books still balance. Read through the API rather than off the trial-balance
 * page — the claim is about the ledger, not about a table.
 */
async function expectTrialBalanceBalances(page: Page, label: string) {
  const rows = await proxy<Array<{ debit: number; credit: number }>>(
    page,
    'get',
    `/finance/trial-balance?asOf=${new Date().toISOString().slice(0, 10)}`,
  );
  const debit = rows.reduce((s, r) => s + (r.debit || 0), 0);
  const credit = rows.reduce((s, r) => s + (r.credit || 0), 0);
  expect(debit, `${label}: the trial balance must not be empty`).toBeGreaterThan(0);
  expect(debit, `${label}: the trial balance must balance`).toBeCloseTo(credit, 2);
}

const pad = (n: number) => String(n).padStart(2, '0');
const isoOf = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

test.describe('Cheque register lifecycle', () => {
  test.beforeEach(async ({}, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
    }
  });

  test('generate, post, deposit, clear, bounce, replace, a late return, approve a penalty, receive cash', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    const suffix = `${testInfo.project.name}-chq-${Date.now().toString(36)}`;
    const { adminId, adminRole, testTenantId, propertyId } = testContext;

    const unit = await createUnit(adminId, adminRole, testTenantId, {
      propertyId,
      unitNumber: `CHQ-${suffix}`,
    });
    const renter = await createRenter(adminId, adminRole, testTenantId, {
      nameEn: `Cheque Renter ${suffix}`,
      email: `chq-${suffix}@test.com`,
    });

    const today = new Date();
    const startDate = today.toISOString().slice(0, 10);
    const endDate = new Date(today.getFullYear() + 1, today.getMonth(), today.getDate()).toISOString().slice(0, 10);

    // One RENT line only (no deposit line) so four UNIFORM cheques divide
    // 51,000 into exactly 12,750 each — no tens-rounding remainder to fold
    // into the first row.
    const draft = await createLease(adminId, adminRole, testTenantId, {
      unitId: unit.id,
      renterId: renter.id,
      startDate,
      endDate,
      rentAmount: 51_000,
      paymentTerms: 4,
      lines: [{ chargeTypeCode: 'RENT', grossAmount: 51_000 }],
    });
    await generateCheques(adminId, adminRole, testTenantId, draft.id, { installments: 4, distribution: 'UNIFORM' });
    const posted = await postLease(adminId, adminRole, testTenantId, draft.id);
    expect(posted.lease.status).toBe('ACTIVE');

    const cheques = await getLeaseCheques(adminId, adminRole, testTenantId, draft.id);
    expect(cheques).toHaveLength(4);
    for (const c of cheques) expect(c.amount).toBe(12_750);
    const [chequeA, chequeB] = cheques;

    // ── register: locate this lease's rows ────────────────────────────
    await page.goto('/en/dashboard/finance/cheques');
    await page.waitForLoadState('networkidle');
    await page.getByTestId('cheque-search').fill(`Cheque Renter ${suffix}`);
    await page.getByTestId('cheque-filter-apply').click();
    await expect(page.getByTestId(`cheque-row-${chequeA.id}`)).toBeVisible({ timeout: 10_000 });

    // ── cheque A: deposit -> clear ──────────────────────────────────────
    await page.getByTestId(`cheque-row-action-deposit-${chequeA.id}`).click();
    await page.getByTestId('cheque-deposit-confirm').click();
    await expect(page.getByTestId(`cheque-row-action-clear-${chequeA.id}`)).toBeVisible({ timeout: 10_000 });
    await page.getByTestId(`cheque-row-action-clear-${chequeA.id}`).click();
    await page.getByTestId('cheque-clear-confirm').click();
    await expect(page.getByTestId(`cheque-row-action-bounce-${chequeA.id}`)).toBeVisible({ timeout: 10_000 });

    const balanceBeforeBounceReplace = await tenantLedgerBalance(page, renter.id);

    // ── cheque B: deposit -> bounce -> replace ───────────────────────────
    await page.getByTestId(`cheque-row-action-deposit-${chequeB.id}`).click();
    await page.getByTestId('cheque-deposit-confirm').click();
    await expect(page.getByTestId(`cheque-row-action-bounce-${chequeB.id}`)).toBeVisible({ timeout: 10_000 });
    await page.getByTestId(`cheque-row-action-bounce-${chequeB.id}`).click();
    await page.getByTestId('cheque-bounce-confirm').click();
    await expect(page.getByTestId(`cheque-row-action-replace-${chequeB.id}`)).toBeVisible({ timeout: 10_000 });
    await page.getByTestId(`cheque-row-action-replace-${chequeB.id}`).click();
    // VERIFY: ReplaceChequeDialog seeds row 0's amount to the bounced
    // cheque's own amount already (blankRow(0, cheque.amount)) — no fill
    // needed for a like-for-like replacement, only the confirm.
    await page.getByTestId('replace-confirm').click();
    await expect(page.getByTestId(`cheque-row-action-replace-${chequeB.id}`)).toHaveCount(0, { timeout: 10_000 });

    // "Nets to zero": swapping a bounced instrument for a fresh one of the
    // same amount changes nothing about what is owed.
    const balanceAfterReplace = await tenantLedgerBalance(page, renter.id);
    expect(balanceAfterReplace, 'a like-for-like replace must not move the tenant balance').toBeCloseTo(balanceBeforeBounceReplace, 2);

    // ── cheque A (now CLEARED): a late return — CLEARED PDC may still bounce ──
    await page.getByTestId(`cheque-row-action-bounce-${chequeA.id}`).click();
    await page.getByTestId('cheque-bounce-confirm').click();
    await expect(page.getByTestId(`cheque-row-action-replace-${chequeA.id}`)).toBeVisible({ timeout: 10_000 });

    const balanceAfterLateReturn = await tenantLedgerBalance(page, renter.id);
    expect(balanceAfterLateReturn - balanceAfterReplace, 'a late return reopens exactly its own cheque amount').toBeCloseTo(12_750, 2);

    // ── propose + approve a CHEQUE_RETURN penalty for the late return ──────
    await page.goto(`/en/dashboard/leases/${draft.id}`);
    await page.getByTestId('lease-tab-penalties').click();
    await page.getByTestId('penalty-propose-open').click();
    await page.getByTestId('penalty-amount').fill('500');
    await page.getByTestId('penalty-description').fill(`TEST-E2E late return ${suffix}`);
    await page.getByTestId('penalty-propose-confirm').click();
    await expect(page.getByTestId('penalty-row-0')).toBeVisible({ timeout: 10_000 });

    await page.getByTestId('penalty-approve-0').click();
    await page.getByTestId('penalty-approve-confirm').click();
    await expect(page.getByTestId('penalty-tab-APPROVED')).toBeVisible();
    await page.getByTestId('penalty-tab-APPROVED').click();
    await expect(page.getByTestId('penalty-row-0')).toBeVisible({ timeout: 10_000 });

    // ── receive cash against the reopened late-return cheque ───────────────
    // Approving the penalty ALSO opened its own CASH collection row on the
    // register — separate from cheque A's own reopened 12,750. This closes
    // out cheque A itself: a fresh cash receipt for the same lease/amount.
    await page.goto('/en/dashboard/finance/cheques');
    await page.getByTestId('open-cash-receipt').click();
    await page.getByTestId('cash-receipt-lease-search').fill(`Cheque Renter ${suffix}`);
    await page.getByTestId(`cash-receipt-lease-option-${draft.id}`).click();
    await expect(page.getByTestId('cash-receipt-selected-lease')).toBeVisible();
    await page.getByTestId('cash-receipt-amount').fill('12750');
    await page.getByTestId('cash-receipt-confirm').click();
    await expect(page.getByTestId('cash-receipt-selected-lease')).toHaveCount(0, { timeout: 10_000 });

    const balanceAfterCashReceipt = await tenantLedgerBalance(page, renter.id);
    // The cash receipt closes cheque A's reopened 12,750; the penalty's own
    // 500 collection row is still REGISTERED (approving opens it but does
    // not receipt it), so the net change from the late return is -12,750
    // (paid) + 500 (the fine still owed) relative to the late-return figure.
    expect(balanceAfterCashReceipt - balanceAfterLateReturn, 'the cash receipt pays the reopened rent, leaving only the fine outstanding').toBeCloseTo(-12_750, 2);
  });

  /**
   * accounting-v2 plan 3, the move-out end to end: a contract whose term began
   * in the past is closed month by month, the CILs reach the tenant ledger,
   * the contract is terminated at a date and the deposit is settled — and the
   * books balance at the end of it.
   *
   * The term STARTS IN THE PAST on purpose. Recognition only posts periods
   * whose `period_end` has already passed (`RecognitionController
   * #notInTheFuture`), so a lease that starts today has nothing to close and
   * the whole plan is invisible.
   *
   * One installment, cleared before the termination, and a deposit cheque
   * dated the contract date — so §9.1's default split hands the deposit row
   * back and KEEPS nothing. That leaves the register empty behind the
   * settlement, which is what lets `LeaseClosureService` close the contract
   * the moment the STL is posted, and what keeps this test off the
   * acknowledgement path (its own case lives in the plan 3 walkthrough).
   */
  test('month-end close, the CILs on the tenant ledger, termination and a finalised settlement', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    const suffix = `${testInfo.project.name}-mv-${Date.now().toString(36)}`;
    const { adminId, adminRole, testTenantId, propertyId } = testContext;

    const unit = await createUnit(adminId, adminRole, testTenantId, {
      propertyId,
      unitNumber: `MV-${suffix}`,
    });
    const renter = await createRenter(adminId, adminRole, testTenantId, {
      nameEn: `Move-out Renter ${suffix}`,
      email: `mv-${suffix}@test.com`,
    });

    const now = new Date();
    const start = new Date(now.getFullYear(), now.getMonth() - 2, 1);
    const startDate = isoOf(start);
    const endDate = isoOf(new Date(start.getFullYear() + 1, start.getMonth(), 0));
    const lastMonthEnd = isoOf(new Date(now.getFullYear(), now.getMonth(), 0));
    const today = isoOf(now);
    const terminationDate = isoOf(new Date(now.getFullYear(), now.getMonth(), 15));

    const draft = await createLease(adminId, adminRole, testTenantId, {
      unitId: unit.id,
      renterId: renter.id,
      startDate,
      endDate,
      rentAmount: 24_000,
      depositAmount: 3_000,
      paymentTerms: 1,
    });
    // `foldDepositsAndFeesIntoFirst` defaults to TRUE; without turning it off
    // the deposit rides inside cheque 1 and there is no deposit row to return.
    await generateCheques(adminId, adminRole, testTenantId, draft.id, {
      installments: 1,
      foldDepositsAndFeesIntoFirst: false,
    });
    const posted = await postLease(adminId, adminRole, testTenantId, draft.id);
    expect(posted.lease.status).toBe('ACTIVE');

    const cheques = await getLeaseCheques(adminId, adminRole, testTenantId, draft.id);
    const rentCheque = cheques.find(c => c.amount === 24_000)!;
    expect(rentCheque, 'the rent instalment is a row of its own').toBeTruthy();
    await proxy(page, 'put', `/cheques/${rentCheque.id}/deposit`, {});
    await proxy(page, 'put', `/cheques/${rentCheque.id}/clear`, {});

    // ── the month-end close ────────────────────────────────────────────────
    await page.goto('/en/dashboard/finance/recognition');
    await page.getByTestId('recognition-to-date').fill(lastMonthEnd);
    await expect(page.getByTestId('recognition-run')).toBeEnabled({ timeout: 15_000 });
    await page.getByTestId('recognition-run').click();
    await page.getByTestId('recognition-run-confirm').click();
    await expect(page.getByTestId('recognition-result-title')).toContainText('Recognition run', { timeout: 30_000 });

    type Entry = { periodEnd: string; status: string; journalId: string | null; journalNumber: string | null };
    const schedule = await proxy<Entry[]>(page, 'get', `/leases/${draft.id}/recognition`);
    const closed = schedule.filter(e => e.status === 'POSTED');
    expect(closed.length, 'every month of this term that has ended is now posted').toBeGreaterThan(0);
    expect(closed.every(e => e.periodEnd <= lastMonthEnd && e.journalNumber?.startsWith('CIL'))).toBeTruthy();

    // ── the CILs on the tenant ledger ──────────────────────────────────────
    // `defaultLedgerRange` opens the report on the current month, and these
    // entries are dated the month-ends that have already passed — so the From
    // box has to be moved back before they are in range.
    await page.goto(`/en/dashboard/finance/tenant-ledger?renterId=${renter.id}&leaseId=${draft.id}`);
    await page.locator('#ledger-from').fill(startDate);
    await page.getByRole('button', { name: 'Apply' }).click();
    const ledger = page.locator('body');
    await expect(ledger).toContainText('Advance Rent', { timeout: 15_000 });
    await expect(ledger).toContainText('Rental Income');
    for (const e of closed) {
      await expect(ledger, `the ledger must carry ${e.journalNumber}`).toContainText(e.journalNumber!);
    }

    // ── termination at a date ──────────────────────────────────────────────
    await page.goto(`/en/dashboard/leases/${draft.id}/terminate`);
    await page.getByTestId('terminate-date').fill(terminationDate);
    await expect(page.getByTestId('terminate-receivable-after')).toBeVisible({ timeout: 15_000 });
    await page.getByTestId('terminate-submit').click();
    await page.getByTestId('terminate-confirm').click();
    await page.waitForURL(/\/settlement$/, { timeout: 30_000 });

    const terminated = await proxy<{ status: string; terminatedOn: string }>(page, 'get', `/leases/${draft.id}`);
    expect(terminated.status).toBe('TERMINATED');
    expect(terminated.terminatedOn).toBe(terminationDate);

    // The truncated slice ends on the termination date and still has to post.
    await proxy(page, 'post', `/finance/recognition/run?to=${today}&preview=false`, {});
    await expectTrialBalanceBalances(page, 'after termination');

    // ── the settlement ─────────────────────────────────────────────────────
    await page.reload();
    const statement = await proxy<{ netRefund: number; instrumentsOutstanding: number; unrecognisedEntries: number }>(
      page,
      'get',
      `/leases/${draft.id}/settlement/preview`,
    );
    expect(statement.unrecognisedEntries, 'the close has caught up with the termination').toBe(0);
    expect(statement.instrumentsOutstanding, 'nothing was kept, so nothing is outstanding').toBe(0);
    expect(statement.netRefund, 'the deposit comes back, less nothing').toBeGreaterThan(0);

    await expect(page.getByTestId('settlement-net-refund')).toBeVisible({ timeout: 15_000 });
    // A refund needs an asset leaf to pay from. Which leaves exist depends on
    // the seeded chart, so the account is looked up rather than typed from
    // memory, and matched by its code, which is unique.
    type Account = { id: string; code: string; name: string; accountType: string; accountSubType: string | null; group: boolean; active: boolean };
    const accounts = await proxy<Account[]>(page, 'get', '/finance/accounts');
    const bank = accounts.find(
      a => a.accountType === 'ASSET' && !a.group && a.active && (a.accountSubType === 'BANK' || a.accountSubType === 'CASH'),
    );
    expect(bank, 'the seeded chart must offer a bank or cash leaf to refund from').toBeTruthy();
    await page.getByLabel('Refund paid from').fill(bank!.code);
    await page.getByRole('button', { name: new RegExp(bank!.code) }).first().click();

    await expect(page.getByTestId('settlement-finalize')).toBeEnabled({ timeout: 15_000 });
    await page.getByTestId('settlement-finalize').click();
    await page.getByTestId('settlement-finalize-confirm').click();
    await expect(page.getByTestId('settlement-status')).toHaveText('Finalized', { timeout: 30_000 });
    await expect(page.getByTestId('settlement-journal')).toContainText('STL');

    // Nothing is left on the register, so `LeaseClosureService` closes it.
    const closedLease = await proxy<{ status: string }>(page, 'get', `/leases/${draft.id}`);
    expect(closedLease.status, 'an empty register and a finalised settlement close the contract').toBe('CLOSED');

    await expectTrialBalanceBalances(page, 'after settlement');
  });
});
