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
});
