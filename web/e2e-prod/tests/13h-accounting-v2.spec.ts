/**
 * 13h — Accounting v2 end to end against a deployed stack.
 *
 * A second contract inside the suite's disposable tenant, driven the way the
 * client's office drives one: post it, bank the cheques, let one come back,
 * replace it, close the months that have ended — then check the invariants
 * that make the ledger trustworthy (spec §5–6, §12):
 *
 *   1. a posted contract owes nothing and holds nothing until money moves:
 *      the PDRs take the whole contract value back off the receivable;
 *   2. a returned cheque puts the debt back and its replacement takes it off
 *      again, while the bank keeps only what actually cleared;
 *   3. recognition posts at month END, never the 1st (decision D13);
 *   4. the trial balance balances — before and after a reversal;
 *   5. the journal refuses to reverse a document's own entry, and says which
 *      screen owns the correction. A manual voucher is the one entry this
 *      endpoint will reverse.
 *
 * Conventions follow 02-cheque-lifecycle.spec.ts: read .test-context.json, go
 * through /api/proxy with a NextAuth session, assert on API responses. The
 * role is TENANT_ADMIN — `LedgerController` and `JournalController` admit
 * SUPER_ADMIN, TENANT_ADMIN and ACCOUNTANT, and a PROPERTY_MANAGER (02's
 * role) is deliberately not among them.
 *
 * Every date is derived from the run date, never hard-coded to a calendar
 * year: the suite runs after every deploy, and a contract dated 1 January
 * would have no completed recognition period at all in the first weeks of the
 * year, so `posted > 0` would pass in March and fail in January.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant, type ProdContext } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

/**
 * Local calendar date, not UTC. `toISOString().slice(0, 10)` on a date built
 * with `new Date(y, m, d)` shifts by the runner's offset — in Gulf Standard
 * Time the 1st of a month comes back as the last day of the previous one, and
 * every assertion about month boundaries below would be off by a day.
 */
function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function plusDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return ymd(new Date(y, m - 1, d + days));
}

function plusMonths(date: string, months: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return ymd(new Date(y, m - 1 + months, d));
}

test('a contract posts, its cheques clear, bounce and are replaced, and the month closes', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.tenant?.id, '01-provision must run first').toBeTruthy();
  expect(ctx.property?.id, '01-provision must have created a property').toBeTruthy();

  const pctx: ProdContext = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(pctx, ctx.tenant.id);

  try {
    // ── The property's account set ──────────────────────────────────────────
    // Every role a contract posts through has to resolve to a leaf, or the
    // post is refused. `PropertyService` generates the set on create, so this
    // is a precondition check, not a setup step.
    const mappings = await api.getPropertyAccounts(pctx, ctx.property.id);
    const byRole = new Map(mappings.filter((m) => m.accountId).map((m) => [m.role, m]));
    for (const role of ['RENT_RECEIVABLE', 'ADVANCE_RENT', 'RENTAL_INCOME', 'PDC_RECEIVABLE', 'BANK']) {
      expect(
        byRole.has(role),
        `role ${role} must resolve to an account or the lease post is refused`,
      ).toBeTruthy();
    }
    // Ledger rows are matched on the account CODE these roles resolve to. The
    // generated names are property-specific ("… — TEST-Tower abc123"), so a
    // name regex would be asserting on a label rather than on an account.
    const receivableCode = byRole.get('RENT_RECEIVABLE')!.accountCode!;
    const bankCode = byRole.get('BANK')!.accountCode!;

    // ── A contract of this spec's own ───────────────────────────────────────
    // Twelve months ending in the future, starting eight months back, so there
    // are always completed periods for the close to post and the whole cheque
    // lifecycle happens in the past (the books of a fresh tenant are unlocked,
    // but a cheque cannot be cleared on a date that has not happened).
    const now = new Date();
    const today = ymd(now);
    const startDate = ymd(new Date(now.getFullYear(), now.getMonth() - 8, 1));
    const endDate = plusDays(plusMonths(startDate, 12), -1);

    const unit = await api.createUnit(pctx, {
      propertyId: ctx.property.id,
      unitNumber: `TEST-ACC-${ctx.runSuffix}`,
      expectedRent: 60000,
    });
    // A ledger fixture, not a portal user: the synthetic `@e2e.rentaxis.test`
    // domain (10b's pattern) rather than 01-provision's real Gmail alias, which
    // that spec uses deliberately so an operator can confirm the invite mail
    // really delivered. Nothing here reads a mailbox, and `createPortalAccount:
    // false` keeps the run from sending an invite to a domain that does not
    // accept mail.
    const renter = await api.createRenter(pctx, {
      nameEn: `TEST-Accounting ${ctx.runSuffix}`,
      email: `test-accounting-renter-${ctx.runSuffix}@e2e.rentaxis.test`,
      createPortalAccount: false,
    });

    // 60,000 rent + 12,000 deposit, matched to the rial by the grid below —
    // the post refuses any other total.
    const lease = await api.createLease(pctx, {
      unitId: unit.id,
      renterId: renter.id,
      startDate,
      endDate,
      contractDate: startDate,
      gracePeriodDays: 5,
      rentAmount: 60000,
      depositAmount: 12000,
      paymentTerms: 2,
    });
    expect(lease.status).toMatch(/DRAFT/i);
    expect(Number(lease.contractValue)).toBe(72000);

    // The grid the wizard saves: one deposit cheque and two rent instalments.
    // `PUT /leases/{id}/cheques` replaces the whole DRAFT grid and re-numbers
    // the rows 1..n in list order, so the order here is the order below.
    const grid = await api.saveLeaseCheques(pctx, lease.id, [
      {
        postingDate: startDate,
        chequeNumber: `ACC-${ctx.runSuffix}-D`,
        chequeDate: plusDays(startDate, 4),
        payeeBank: 'Emirates NBD',
        amount: 12000,
        narration: 'Security Deposit',
        mode: 'PDC',
      },
      {
        postingDate: startDate,
        chequeNumber: `ACC-${ctx.runSuffix}-1`,
        chequeDate: plusDays(startDate, 4),
        payeeBank: 'Emirates NBD',
        amount: 30000,
        narration: 'Rent - 1st Installment',
        mode: 'PDC',
      },
      {
        postingDate: startDate,
        chequeNumber: `ACC-${ctx.runSuffix}-2`,
        chequeDate: plusMonths(startDate, 6),
        payeeBank: 'Emirates NBD',
        amount: 30000,
        narration: 'Rent - 2nd Installment',
        mode: 'PDC',
      },
    ]);
    expect(grid).toHaveLength(3);

    const posted = await api.postDraftLease(pctx, lease.id);
    expect(posted.lease.status).toMatch(/ACTIVE/i);
    expect(posted.tcoEntryNumber, 'posting must stamp a TCO number').toMatch(/^TCO-/);

    // One TCO for the contract, one PDR per registered instrument (spec §6.4).
    const journals = await api.getJournals(pctx, { leaseId: lease.id });
    const byType = journals.content.reduce<Record<string, number>>((acc, j) => {
      acc[j.docType] = (acc[j.docType] ?? 0) + 1;
      return acc;
    }, {});
    expect(byType.TCO).toBe(1);
    expect(byType.PDR).toBe(3);

    // ── Invariant 1: nothing owed before any money moves ────────────────────
    const atPost = await api.getRenterLedger(pctx, renter.id, startDate, endDate);
    const receivableAtPost = atPost.find((a) => a.accountCode === receivableCode);
    expect(receivableAtPost, 'the renter ledger must include rent receivable').toBeTruthy();
    expect(Number(receivableAtPost!.closingBalance)).toBeCloseTo(0, 2);
    expect(
      atPost.some((a) => a.accountCode === bankCode),
      'no cheque has cleared, so the bank has no line on this contract yet',
    ).toBeFalsy();

    // ── The register: clear one, return one, replace it ─────────────────────
    const rows = await api.getLeaseCheques(pctx, lease.id);
    expect(rows.map((r) => r.amount).map(Number)).toEqual([12000, 30000, 30000]);
    const [depositCheque, firstRent] = rows;

    await api.depositCheque(pctx, depositCheque.id, { date: plusDays(startDate, 5) });
    const cleared = await api.clearCheque(pctx, depositCheque.id, { date: plusDays(startDate, 7) });
    expect(cleared.status).toMatch(/CLEARED/i);
    expect(cleared.crtJournalId, 'clearing must post a CRT').toBeTruthy();

    await api.depositCheque(pctx, firstRent.id, { date: plusDays(startDate, 5) });
    await api.clearCheque(pctx, firstRent.id, { date: plusDays(startDate, 7) });
    const bounced = await api.bounceCheque(pctx, firstRent.id, 'BOUNCE', { date: plusDays(startDate, 11) });
    expect(bounced.status).toMatch(/BOUNCED/i);
    expect(bounced.cbrJournalId, 'a return must post a CBR').toBeTruthy();

    const replacements = await api.replaceCheque(pctx, firstRent.id, [
      {
        amount: 30000,
        mode: 'PDC',
        chequeNumber: `ACC-${ctx.runSuffix}-1R`,
        chequeDate: plusMonths(startDate, 2),
        payeeBank: 'Emirates NBD',
      },
    ]);
    expect(replacements).toHaveLength(1);
    expect(replacements[0].status).toMatch(/REGISTERED/i);

    // ── Invariant 2: the bank holds what cleared, the paper holds the rest ──
    // The deposit cheque cleared (12,000 in). The first instalment cleared and
    // was then taken back out, so the CBR credits the same bank account it
    // debited and the balance is the deposit alone. Its replacement's PDR puts
    // the 30,000 back on paper, which takes the receivable to nil again.
    const afterBounce = await api.getRenterLedger(pctx, renter.id, startDate, endDate);
    const bank = afterBounce.find((a) => a.accountCode === bankCode);
    expect(bank, 'a cleared cheque must show on the bank account').toBeTruthy();
    expect(Number(bank!.closingBalance)).toBeCloseTo(12000, 2);
    expect(afterBounce.flatMap((a) => a.rows).filter((r) => r.docType === 'CBR').length).toBeGreaterThan(0);
    const receivableAfter = afterBounce.find((a) => a.accountCode === receivableCode);
    expect(Number(receivableAfter!.closingBalance)).toBeCloseTo(0, 2);

    // ── Invariant 3: the close posts at month end ───────────────────────────
    // Every month that has already finished. `to` in the future is a 400.
    const lastMonthEnd = ymd(new Date(now.getFullYear(), now.getMonth(), 0));
    const run = await api.runRecognition(pctx, lastMonthEnd);
    expect(run.posted).toBeGreaterThan(0);

    const cil = await api.getJournals(pctx, { docType: 'CIL', leaseId: lease.id });
    expect(cil.content.length, 'eight months of this contract have ended').toBeGreaterThan(0);
    // The run is tenant-wide, so it also closes whatever the earlier specs
    // left behind: this lease's share of it can only be smaller.
    expect(run.posted).toBeGreaterThanOrEqual(cil.content.length);
    for (const j of cil.content) {
      expect(j.entryDate.endsWith('-01'), `CIL ${j.entryNumber} is dated the 1st`).toBeFalsy();
      expect(j.entryDate <= lastMonthEnd, `CIL ${j.entryNumber} is dated past the close`).toBeTruthy();
    }

    // ── Invariant 4: the trial balance balances ─────────────────────────────
    const tb = await api.getTrialBalance(pctx, today);
    const debit = tb.reduce((a, r) => a + Number(r.debit), 0);
    const credit = tb.reduce((a, r) => a + Number(r.credit), 0);
    expect(debit).toBeCloseTo(credit, 2);
    expect(debit).toBeGreaterThan(0);

    // ── Invariant 5: a document's entry is corrected where it was created ───
    // `JournalService.requireManual` refuses the contract's own TCO and names
    // the screen that owns the correction, rather than leaving a POSTED lease
    // standing over an empty ledger.
    const tco = journals.content.find((j) => j.docType === 'TCO')!;
    await expect(
      api.reverseJournal(pctx, tco.id, today, 'e2e reversal check'),
      'the journal endpoint must refuse a lease document',
    ).rejects.toThrow(/amend or terminate the lease/i);

    // A manual voucher is the one entry it does reverse. Watch the two
    // accounts it touches across all three states: a reversal that quietly did
    // nothing would leave the bank 100 up, and Σdebit = Σcredit — which holds
    // for any set of balanced entries, reversed or not — would not notice.
    const balanceOf = (rows: Awaited<ReturnType<typeof api.getTrialBalance>>, code: string) =>
      Number(rows.find((r) => r.code === code)?.balance ?? 0);
    const bankBefore = balanceOf(tb, bankCode);
    const receivableBefore = balanceOf(tb, receivableCode);

    const voucher = await api.postManualJournal(pctx, {
      entryDate: today,
      narration: `TEST-E2E manual voucher ${ctx.runSuffix}`,
      propertyId: ctx.property.id,
      lines: [
        { accountId: byRole.get('BANK')!.accountId!, debit: 100, narration: 'TEST-E2E debit' },
        { accountId: byRole.get('RENT_RECEIVABLE')!.accountId!, credit: 100, narration: 'TEST-E2E credit' },
      ],
    });
    expect(voucher.entryNumber).toMatch(/^JV-/);

    // Posted: the bank is 100 up and the receivable 100 down, debit-positive.
    const tbWithVoucher = await api.getTrialBalance(pctx, today);
    expect(balanceOf(tbWithVoucher, bankCode)).toBeCloseTo(bankBefore + 100, 2);
    expect(balanceOf(tbWithVoucher, receivableCode)).toBeCloseTo(receivableBefore - 100, 2);

    const reversal = await api.reverseJournal(pctx, voucher.id, today, 'e2e reversal check');
    expect(reversal.reversalOfId).toBe(voucher.id);
    // Only a document path maps TCO→TCR; a voucher's mirror keeps its own type.
    expect(reversal.entryNumber).toMatch(/^JV-/);

    // Reversed: the mirror swapped the sides, so both accounts are back where
    // they stood before the voucher — and the books still balance.
    const tbAfter = await api.getTrialBalance(pctx, today);
    expect(balanceOf(tbAfter, bankCode)).toBeCloseTo(bankBefore, 2);
    expect(balanceOf(tbAfter, receivableCode)).toBeCloseTo(receivableBefore, 2);
    expect(tbAfter.reduce((a, r) => a + Number(r.debit), 0)).toBeCloseTo(
      tbAfter.reduce((a, r) => a + Number(r.credit), 0),
      2,
    );
  } finally {
    await pctx.request.dispose();
  }
});
