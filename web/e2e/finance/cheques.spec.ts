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
 * rather than scraped off the rendered table — one renter, one lease, so it
 * needs no guessing at LedgerTable's markup.
 *
 * What it must NOT do is sum every account in that response. The renter
 * dimension is stamped on BOTH legs of every journal, so the landlord's own
 * bank leaf is in there too: a CRT is Dr bank / Cr PDC receivable and both
 * lines carry this renter. Summing the lot is therefore a mini trial balance
 * over one renter — identically zero, and unmovable by anything the register
 * does. (Verified against the books: after post, after a clearing, after a
 * bounce, after a replace and after a late return, that sum is 0.00 every
 * time.)
 *
 * So the sum is taken over an ALLOW-LIST, not over "everything except the
 * bank": the three roles a renter's debt actually lives in, read from the
 * posting engine's own role->account map. An exclude-list has the wrong
 * failure mode — a bank leaf re-tagged `OTHER_ASSET` slips back into the sum,
 * which collapses to the identically-zero trial balance again and lets
 * "a like-for-like replace moves nothing" pass for the wrong reason. Under an
 * allow-list a re-mapped leaf drops OUT and every assertion goes red.
 */

/**
 * Where a renter's debt sits: the receivable it is raised in, the PDC
 * receivable the instruments are held in, and the advance rent the contract
 * was credited to. Deliberately NOT the income accounts — an approved fine
 * credits penalty income and stays owed until it is collected, which is the
 * whole point of the last assertion in this test.
 */
const OWED_BY_RENTER = ['RENT_RECEIVABLE', 'PDC_RECEIVABLE', 'ADVANCE_RENT'];

/** `GET /properties/{id}/accounts` — the same mapping `PostingService` resolves a role through. */
async function renterObligationAccounts(page: Page, propertyId: string): Promise<Set<string>> {
  const mappings = await proxy<Array<{ role: string; accountId: string | null }>>(
    page,
    'get',
    `/properties/${propertyId}/accounts`,
  );
  const ids = new Set(
    mappings.filter(m => OWED_BY_RENTER.includes(m.role) && m.accountId).map(m => m.accountId as string),
  );
  // A role the property does not map would silently shrink the sum, and a
  // shrinking sum is how this test used to pass while measuring nothing.
  expect(ids.size, `the property must map every one of ${OWED_BY_RENTER.join(', ')}`).toBe(OWED_BY_RENTER.length);
  return ids;
}

async function tenantLedgerBalance(page: Page, renterId: string, owed: Set<string>): Promise<number> {
  const res = await page.request.get(`/api/proxy/v1/finance/ledger/renter/${renterId}`);
  expect(res.ok(), `tenant ledger read failed: ${res.status()}`).toBeTruthy();
  const ledgers: Array<{ accountId: string; closingBalance: number }> = await res.json();
  return ledgers
    .filter(l => owed.has(l.accountId))
    .reduce((sum, l) => sum + (l.closingBalance || 0), 0);
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
    // `receipt`, not `bounce`. DEPOSITED already offers Bounce (registerActions
    // .ts: "DEPOSITED -> clear, bounce"), so waiting for Bounce here was
    // satisfied by the row's PREVIOUS state and the ledger read below raced the
    // CRT — the register looked settled while the clearing was still in
    // flight. Receipt is offered on CLEARED + PDC and nowhere else, so it is
    // the first thing on this row that means "the clearing landed".
    await expect(page.getByTestId(`cheque-row-action-receipt-${chequeA.id}`)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId(`cheque-row-action-clear-${chequeA.id}`)).toHaveCount(0);

    const owed = await renterObligationAccounts(page, propertyId);
    const balanceBeforeBounceReplace = await tenantLedgerBalance(page, renter.id, owed);
    // Anchored, not merely compared against itself. The contract raised 51,000
    // of rent receivable and closed it with four PDRs into PDC receivable
    // against 51,000 of advance rent; cheque A then cleared 12,750 of that PDC
    // into the bank, which is not one of these three accounts. So the renter's
    // position here is -12,750 and nothing else, and a filter that let the
    // bank back in would read 0.00 — which is exactly the reading that made
    // the next assertion vacuous before this spec was fixed.
    expect(balanceBeforeBounceReplace, 'the filter must leave a real position behind').toBeCloseTo(-12_750, 2);

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
    const balanceAfterReplace = await tenantLedgerBalance(page, renter.id, owed);
    expect(balanceAfterReplace, 'a like-for-like replace must not move the tenant balance').toBeCloseTo(balanceBeforeBounceReplace, 2);

    // ── cheque A (now CLEARED): a late return — CLEARED PDC may still bounce ──
    await page.getByTestId(`cheque-row-action-bounce-${chequeA.id}`).click();
    await page.getByTestId('cheque-bounce-confirm').click();
    await expect(page.getByTestId(`cheque-row-action-replace-${chequeA.id}`)).toBeVisible({ timeout: 10_000 });

    const balanceAfterLateReturn = await tenantLedgerBalance(page, renter.id, owed);
    expect(balanceAfterLateReturn - balanceAfterReplace, 'a late return reopens exactly its own cheque amount').toBeCloseTo(12_750, 2);

    // ── propose + approve a CHEQUE_RETURN penalty for the late return ──────
    await page.goto(`/en/dashboard/leases/${draft.id}`);
    await page.getByTestId('lease-tab-penalties').click();
    await page.getByTestId('penalty-propose-open').click();
    // The tab opens RaisePenaltyDialog (#12). Its fields are addressed by `id`,
    // not by testid — they carry `htmlFor` labels and nothing else. The
    // incident date defaults to today (Dubai), which is what this case wants.
    await page.locator('#raise-penalty-reason').selectOption('CHEQUE_RETURN');
    await page.locator('#raise-penalty-amount').fill('500');
    await page.locator('#raise-penalty-narration').fill(`TEST-E2E late return ${suffix}`);
    await page.getByTestId('raise-penalty-confirm').click();

    // Row 0 is NOT this proposal. The two bounces above already had the rule
    // engine propose their own CHEQUE_RETURN fines, so the PROPOSED tab holds
    // at least two rows and the positional testid picks whichever the queue
    // sorted first — the one this test typed is found by its own narration.
    const proposed = page.locator('[data-testid^="penalty-row-"]')
      .filter({ hasText: `TEST-E2E late return ${suffix}` })
      .first();
    await expect(proposed).toBeVisible({ timeout: 10_000 });

    // `PenaltyQueue` remounts on propose (its `key` is bumped) and refetches,
    // so the Approve button this resolves can be swapped out from under the
    // click — which lands on a detached node and opens no dialog. Retried as
    // one step: setting `decision` is idempotent, and a dialog that genuinely
    // never opens still fails here.
    await expect(async () => {
      await proposed.getByRole('button', { name: 'Approve' }).click();
      await expect(page.getByTestId('penalty-approve-confirm')).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 20_000 });
    await page.getByTestId('penalty-approve-confirm').click();

    await expect(page.getByTestId('penalty-tab-APPROVED')).toBeVisible();
    await page.getByTestId('penalty-tab-APPROVED').click();
    await expect(
      page.locator('[data-testid^="penalty-row-"]').filter({ hasText: `TEST-E2E late return ${suffix}` }).first(),
    ).toBeVisible({ timeout: 10_000 });

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

    const balanceAfterCashReceipt = await tenantLedgerBalance(page, renter.id, owed);
    // Two things happened between the late return and here, and the arithmetic
    // is their sum, not one of them:
    //
    //   the approved fine   PEN Dr rent receivable 500 / Cr penalty income,
    //                       then its collection row's PDR moves that 500 into
    //                       PDC receivable — still owed, still on the register
    //                       as a REGISTERED row nobody has receipted:      +500
    //   the cash receipt    a PDR for 12,750 and the CRT that settles it into
    //                       cash, which is not one of these three accounts:
    //                                                                  -12,750
    //
    // So -12,250, and that figure is only legible because the sum is taken
    // over the renter's obligation accounts alone. Counting penalty income as
    // well — which is what an exclude-list does — cancels the fine against
    // itself and reports -12,750: the same number the old comment here claimed
    // while describing the arithmetic for -12,250.
    expect(balanceAfterCashReceipt - balanceAfterLateReturn, 'the cash receipt pays the reopened rent and leaves the fine outstanding').toBeCloseTo(-12_250, 2);
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
    // The confirm posts `preview.terminationDate` — the date the figures on
    // screen were priced for — and NOT what the picker currently reads
    // (terminate/page.tsx :174-181, and the doc comment at :25-33 says why).
    // The page opens already priced for today, so `terminate-receivable-after`
    // is on screen before the fill below and waiting for it proves nothing:
    // click too early and the contract is terminated on today's date, which is
    // exactly how this test used to end up with terminatedOn = today. Wait for
    // the pricing FOR THIS DATE, then for the button that pricing re-enables —
    // `pricing` and `preview` are committed in the same render, so an enabled
    // button means the preview on screen is this date's.
    //
    // Registered BEFORE the goto, not after the fill. On the 15th of the month
    // the page's opening price is already for this date and the fill is a
    // no-op (same value -> no state change -> no second request), so the only
    // response that can satisfy this wait is the opening one — and a listener
    // attached after `goto` resolves can miss it and burn the timeout. Once a
    // month, which is the worst kind of red.
    const repriced = page.waitForResponse(
      r => r.url().includes(`/leases/${draft.id}/terminate/preview`)
        && r.url().includes(`date=${terminationDate}`)
        && r.ok(),
      { timeout: 30_000 },
    );
    await page.goto(`/en/dashboard/leases/${draft.id}/terminate`);
    await page.getByTestId('terminate-date').fill(terminationDate);
    await repriced;
    await expect(page.getByTestId('terminate-receivable-after')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('terminate-submit')).toBeEnabled({ timeout: 15_000 });
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

    // The settlement is a stored document, and Finalize posts the row that
    // Save draft writes: `SettlementService.finalizeSettlement` answers
    // "No settlement found for this lease" (:528-530) when there is none. The
    // screen offers both buttons side by side and this test only ever pressed
    // the second, so the page came back with `settlement-finalize-error` and
    // never rendered `settlement-status` at all (it only exists once a stored
    // row does, settlement/page.tsx :528-540). Saved BEFORE the refund account
    // is named, because saving re-reads the statement and the picker's value
    // is page state, not part of the draft.
    await page.getByTestId('settlement-save-draft').click();
    await expect(page.getByTestId('settlement-status')).toHaveText('Draft', { timeout: 15_000 });

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
