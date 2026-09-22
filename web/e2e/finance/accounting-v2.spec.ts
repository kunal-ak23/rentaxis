import type { Locator, Page } from '@playwright/test';
import { test, expect } from '../fixtures/auth.fixture';

/**
 * Accounting v2, one contract end to end through the real UI: the seeded
 * chart and the property's account set, the draft lease's cheque grid cut and
 * numbered on the lease page, the Post that writes the TCO and one PDR per
 * cheque, the register clearing one instrument and returning another, the
 * renter's ledger before and after that, month-end recognition, and a manual
 * journal posted and reversed — with the trial balance still balancing at the
 * end of it.
 *
 * Serial on purpose: every step builds on the one before, and the closing
 * assertion only means something if all of them ran.
 *
 * TENANT_ADMIN only, and not only for the reason `ledger.spec.ts` gives (the
 * other role projects would assert against an Access Denied card). This is the
 * one spec in the suite that posts `testContext.leaseId` — the single draft
 * lease `global-setup.ts` seeds and every role project shares. A second project
 * running these same steps would meet a lease that is already ACTIVE and its
 * grid already registered, which is the race `lease-lifecycle.spec.ts`
 * documents and sidesteps by provisioning its own records. Here the shared
 * contract IS the subject, so the spec narrows to one project instead.
 *
 * Every assertion about this run is scoped to THIS lease (its own journals tab,
 * its own recognition schedule, its own cheque numbers). `cheques.spec.ts` runs
 * in the same tenant, in a different worker, and its move-out case runs
 * recognition tenant-wide — so a global count would be a coin flip, while
 * "this contract's periods are posted and carry CIL numbers" holds however the
 * two interleave.
 */

/** Unique per run: cheque numbers are unique per lease and the dev DB is not reset between runs. */
const SUFFIX = Math.random().toString(36).slice(2, 7);
const CHEQUE = {
    deposit: `E2E-D1-${SUFFIX}`,
    rent1: `E2E-R1-${SUFFIX}`,
    rent2: `E2E-R2-${SUFFIX}`,
    replacement: `E2E-R1A-${SUFFIX}`,
};

const pad = (n: number) => String(n).padStart(2, '0');
const isoOf = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const today = () => isoOf(new Date());
/**
 * The last day of LAST month. Recognition refuses a `to` in the future
 * (`RecognitionController#notInTheFuture`), and a period that has not ended has
 * nothing to close, so this is the furthest a run may reach.
 */
const lastMonthEnd = () => {
    const now = new Date();
    return isoOf(new Date(now.getFullYear(), now.getMonth(), 0));
};
const isMonthEnd = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(y, m, 0).getDate() === d;
};

/**
 * The onboarding tour auto-starts 1500ms after any dashboard page mounts and
 * renders a modal overlay that eats every click underneath it — the same
 * suppression `ledger.spec.ts` and `cutover.spec.ts` install.
 */
async function suppressTour(page: Page) {
    await page.addInitScript(() => {
        try {
            window.localStorage.setItem('rentaxis_tours_completed', JSON.stringify(['admin-onboarding']));
        } catch {
            /* storage unavailable — nothing to suppress */
        }
    });
}

/** Anything on the v1 API, through the proxy this browser session is signed in to. */
async function proxy<T>(page: Page, method: 'get' | 'post' | 'put', path: string, body?: unknown): Promise<T> {
    const res = await page.request[method](`/api/proxy/v1${path}`, body === undefined ? {} : { data: body });
    expect(res.ok(), `${method.toUpperCase()} ${path} failed (${res.status()}): ${await res.text().catch(() => '')}`).toBeTruthy();
    return (await res.json()) as T;
}

/**
 * The account picker is a search box that reveals a list of buttons; it has no
 * select semantics, so the interaction is type-then-click. `query` has to match
 * `code name alias` the way the component filters. Lifted from
 * `ledger.spec.ts`, which proved it against the journal entry form.
 */
async function pickAccount(scope: Locator, query: string) {
    const input = scope.locator('input[aria-label]').first();
    await input.click();
    await input.fill(query);
    await scope.locator('ul li button').filter({ hasText: query }).first().click();
}

type GridRow = {
    id: string;
    seqNo: number;
    amount: number;
    status: string;
    mode: string;
    narration: string | null;
    chequeNumber: string | null;
};
type PropertyAccount = { role: string; accountId: string | null; accountCode: string | null; accountName: string | null };
type RecognitionEntry = { periodEnd: string; status: string; journalNumber: string | null };

/** Filled by the first tests and read by the later ones — the whole point of `serial`. */
let leaseHref = '';
let leaseStartDate = '';
let chequeIds: Record<'deposit' | 'rent1' | 'rent2', string> = { deposit: '', rent1: '', rent2: '' };
let rentReceivableCode = '';
let bankCode = '';

test.describe('Accounting v2 — a contract from draft to a balanced trial balance', () => {
    test.describe.configure({ mode: 'serial' });

    test.beforeEach(async ({ page }, testInfo) => {
        test.skip(
            testInfo.project.name !== 'tenant-admin',
            'TENANT_ADMIN owns the finance pages, and only one project may post the shared draft lease',
        );
        await suppressTour(page);
    });

    test('the chart of accounts is seeded and the property carries its account set', async ({ page, testContext }) => {
        await page.goto('/en/dashboard/finance/accounts');

        // The seed button only renders once the initial GET has resolved AND came
        // back empty, so checking straight after goto() finds nothing and
        // silently skips the seed. Wait for whichever the chart's state produces.
        const seed = page.getByRole('button', { name: 'Seed Default Accounts' }).first();
        const assets = page.getByText('Assets', { exact: true }).first();
        await expect(seed.or(assets).first()).toBeVisible({ timeout: 20_000 });
        if (await seed.isVisible().catch(() => false)) {
            await seed.click();
        }
        await expect(assets).toBeVisible({ timeout: 20_000 });

        await page.goto(`/en/dashboard/properties/${testContext.propertyId}`);
        await page.getByRole('button', { name: /ledger accounts/i }).click();

        // Every role a posted contract needs must resolve, or the post is refused
        // (`LeasePostingService` — the TCO's debits, every PDR's credit and the
        // CIL's income leg all read this table).
        for (const role of ['RENT_RECEIVABLE', 'ADVANCE_RENT', 'RENTAL_INCOME', 'PDC_RECEIVABLE', 'BANK']) {
            const row = page.locator(`[data-testid="property-account-row"][data-role="${role}"]`);
            await expect(row, `${role} must have a row`).toBeVisible({ timeout: 20_000 });
            await expect(row, `${role} must be mapped`).not.toContainText('Not mapped');
        }

        // Kept for the ledger and journal assertions further down: the generated
        // names carry the property's own name, so they are read rather than typed.
        const accounts = await proxy<PropertyAccount[]>(page, 'get', `/properties/${testContext.propertyId}/accounts`);
        rentReceivableCode = accounts.find(a => a.role === 'RENT_RECEIVABLE')?.accountCode ?? '';
        bankCode = accounts.find(a => a.role === 'BANK')?.accountCode ?? '';
        expect(rentReceivableCode, 'the property must name a rent receivable account').toBeTruthy();
        expect(bankCode, 'the property must name a bank account').toBeTruthy();
    });

    test('the draft contract takes a cheque grid and posts as one TCO plus one PDR per cheque', async ({ page, testContext }) => {
        expect(testContext.leaseId, 'global-setup must have seeded a draft lease').toBeTruthy();
        leaseHref = `/en/dashboard/leases/${testContext.leaseId}`;
        const lease = await proxy<{ startDate: string; status: string }>(page, 'get', `/leases/${testContext.leaseId}`);
        leaseStartDate = lease.startDate;
        expect(lease.status, 'the seeded lease is the DRAFT this spec posts').toBe('DRAFT');

        await page.goto(leaseHref);
        await expect(page.getByTestId('lease-status')).toHaveText(/draft/i, { timeout: 20_000 });

        // ── cut the grid ───────────────────────────────────────────────────────
        // `foldDepositsAndFeesIntoFirst` is on by default, which hides the deposit
        // inside cheque 1. Unfolded, the deposit is an instrument of its own — a
        // row the register can deposit and clear separately, which is what the
        // scenario below needs.
        await page.getByTestId('cheque-grid-generate').click();
        const genForm = page.getByTestId('cheque-generate-form');
        await genForm.getByLabel('Installments', { exact: true }).fill('2');
        await genForm.getByRole('checkbox').uncheck();
        await genForm.getByTestId('cheque-generate-confirm').click();

        const rows = page.locator('[data-testid^="cheque-row-"]');
        await expect(rows, 'two rent instalments and the deposit standing alone').toHaveCount(3, { timeout: 20_000 });

        // ── number the rows the way an accountant does ─────────────────────────
        // The grid renders in `seqNo` order, so the API's order is the page's
        // order; the deposit is the row the generator labelled from the charge
        // type rather than "Rent - Nth Installment".
        const grid = await proxy<GridRow[]>(page, 'get', `/leases/${testContext.leaseId}/cheques`);
        expect(grid).toHaveLength(3);
        let rentSeen = 0;
        const numbers = grid.map(r =>
            /^rent/i.test(r.narration ?? '') ? (++rentSeen === 1 ? CHEQUE.rent1 : CHEQUE.rent2) : CHEQUE.deposit,
        );
        expect(rentSeen, 'the grid must carry both rent instalments').toBe(2);
        for (const [i, number] of numbers.entries()) {
            await page.getByLabel(`Cheque No ${i + 1}`, { exact: true }).fill(number);
        }
        await page.getByTestId('lease-save-cheques').click();
        await expect(page.getByTestId('cheque-grid-row-errors')).toHaveCount(0, { timeout: 20_000 });
        await expect(page.getByTestId('cheque-grid-match')).toHaveAttribute('data-match', 'true', { timeout: 20_000 });

        // ── post ───────────────────────────────────────────────────────────────
        await page.getByTestId('lease-post').click();
        await expect(page.getByTestId('post-dry-run-ok')).toBeVisible({ timeout: 20_000 });
        await page.getByTestId('post-lease-confirm').click();
        await expect(page.getByTestId('lease-status')).toHaveText(/active/i, { timeout: 30_000 });
        await expect(page.getByTestId('lease-posting-journal')).toBeVisible();
        await expect(page.getByTestId('lease-post'), 'Post is gone once the contract is posted').toHaveCount(0);

        // ── what it wrote ──────────────────────────────────────────────────────
        // The tab renders the entry list AND the renter's ledger narrowed to this
        // lease; the counts below are about the entry list, so they are scoped to
        // its table rather than to the whole tab.
        await page.getByTestId('lease-tab-journals').click();
        const journals = page.getByTestId('lease-journals-tab').locator('table').first().locator('tbody tr');
        await expect(journals.filter({ hasText: 'TCO' })).toHaveCount(1, { timeout: 20_000 });
        await expect(journals.filter({ hasText: 'PDR' }), 'one PDR per cheque').toHaveCount(3);

        const registered = await proxy<GridRow[]>(page, 'get', `/leases/${testContext.leaseId}/cheques`);
        for (const r of registered) expect(r.status).toBe('REGISTERED');
        const byNumber = (n: string) => registered.find(r => r.chequeNumber === n)!.id;
        chequeIds = { deposit: byNumber(CHEQUE.deposit), rent1: byNumber(CHEQUE.rent1), rent2: byNumber(CHEQUE.rent2) };
    });

    test('the renter ledger nets the receivable to zero the moment the contract is posted', async ({ page, testContext }) => {
        await page.goto(`/en/dashboard/finance/tenant-ledger?renterId=${testContext.renterId}&leaseId=${testContext.leaseId}`);
        // `defaultLedgerRange` opens on the current month and this contract's
        // entries are dated its contract date, so the From box moves back first.
        await page.locator('#ledger-from').fill(leaseStartDate);
        await page.getByRole('button', { name: 'Apply' }).click();

        // Spec §6.4: the TCO debits rent receivable for the whole contract and
        // every PDR credits it back, so until an instrument moves the tenant owes
        // nothing that is not already promised on a cheque.
        const subTotal = page.locator(
            `xpath=//tr[td[contains(normalize-space(.), "Account Code :: ${rentReceivableCode}")]]` +
                `/following-sibling::tr[td[normalize-space(.)="Sub Total"]][1]`,
        );
        await expect(subTotal).toBeVisible({ timeout: 20_000 });
        // Cells: label (colspan 3), debit, credit, balance. `fmtBalance(0)` is
        // "0.00" with no Dr/Cr suffix — matched on the balance cell alone,
        // because "8,000.00" contains "0.00" too.
        await expect(subTotal.locator('td').nth(3), 'rent receivable nets to zero').toHaveText('0.00');
    });

    test('the register deposits and clears one cheque, returns another and replaces it', async ({ page }) => {
        await page.goto('/en/dashboard/finance/cheques');
        await page.getByTestId('cheque-search').fill(SUFFIX);
        await page.getByTestId('cheque-filter-apply').click();
        await expect(page.getByTestId(`cheque-row-${chequeIds.deposit}`)).toBeVisible({ timeout: 20_000 });

        // ── the deposit cheque: deposit -> clear ───────────────────────────────
        await page.getByTestId(`cheque-row-action-deposit-${chequeIds.deposit}`).click();
        await page.getByTestId('cheque-deposit-confirm').click();
        await expect(page.getByTestId(`cheque-row-action-clear-${chequeIds.deposit}`)).toBeVisible({ timeout: 20_000 });
        await page.getByTestId(`cheque-row-action-clear-${chequeIds.deposit}`).click();
        await page.getByTestId('cheque-clear-confirm').click();
        // A CLEARED row may still be returned late, so its own bounce action
        // appearing is the proof the clear landed.
        await expect(page.getByTestId(`cheque-row-action-bounce-${chequeIds.deposit}`)).toBeVisible({ timeout: 20_000 });

        // ── the first rent instalment: deposit -> return -> replace ────────────
        await page.getByTestId(`cheque-row-action-deposit-${chequeIds.rent1}`).click();
        await page.getByTestId('cheque-deposit-confirm').click();
        await expect(page.getByTestId(`cheque-row-action-bounce-${chequeIds.rent1}`)).toBeVisible({ timeout: 20_000 });
        await page.getByTestId(`cheque-row-action-bounce-${chequeIds.rent1}`).click();
        await page.getByTestId('cheque-bounce-confirm').click();
        await expect(page.getByTestId(`cheque-row-action-replace-${chequeIds.rent1}`)).toBeVisible({ timeout: 20_000 });

        // ReplaceChequeDialog seeds row 0's amount to the bounced cheque's own
        // amount, so a like-for-like swap only needs the new instrument's number.
        await page.getByTestId(`cheque-row-action-replace-${chequeIds.rent1}`).click();
        await page.getByTestId('replace-row-0-number').fill(CHEQUE.replacement);
        await page.getByTestId('replace-confirm').click();
        await expect(page.getByTestId(`cheque-row-action-replace-${chequeIds.rent1}`)).toHaveCount(0, { timeout: 20_000 });

        await page.getByTestId('cheque-search').fill(CHEQUE.replacement);
        await page.getByTestId('cheque-filter-apply').click();
        const replacement = page.locator('[data-testid^="cheque-row-"]').first();
        await expect(replacement).toBeVisible({ timeout: 20_000 });
        await expect(replacement, 'the replacement enters the register as a fresh instrument').toContainText(/registered/i);
    });

    test('the renter ledger carries the clearing and the return', async ({ page, testContext }) => {
        // The bounced instrument's own amount, read rather than typed: it is
        // whatever the generator cut the rent into.
        const bounced = (await proxy<GridRow[]>(page, 'get', `/leases/${testContext.leaseId}/cheques`))
            .find(r => r.chequeNumber === CHEQUE.rent1)!;
        // BOUNCED -> REPLACED: accepting the fresh instrument closes the returned
        // one out, so by now the row the register bounced reads REPLACED.
        expect(bounced?.status, 'the returned instrument was swapped, not written off').toBe('REPLACED');
        const returned = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
            .format(bounced.amount);

        await page.goto(`/en/dashboard/finance/tenant-ledger?renterId=${testContext.renterId}&leaseId=${testContext.leaseId}`);
        await page.locator('#ledger-from').fill(leaseStartDate);
        await page.getByRole('button', { name: 'Apply' }).click();

        // Spec §7.2: clearing posts a CRT (Dr bank, Cr PDC receivable); the bank
        // returning one posts a CBR that puts the amount back on the receivable.
        const ledger = page.locator('body');
        await expect(ledger.getByRole('link', { name: /^CRT-/ }).first()).toBeVisible({ timeout: 20_000 });
        const cbr = page.getByRole('row').filter({ has: page.getByRole('link', { name: /^CBR-/ }) }).first();
        await expect(cbr).toBeVisible();
        await expect(cbr, 'the return reopens exactly its own cheque amount').toContainText(returned);

        // And then the receivable is flat again — which is the whole point of a
        // like-for-like replacement. The CBR debits rent receivable back up and
        // the replacement's own PDR credits it straight down again: a SWAP, not a
        // write-off (the reading `cheques.spec.ts` pins on the same transition).
        // The plan expected this screen to show a reopened receivable; it only
        // does so between the return and the replacement, and the replacement was
        // accepted one step ago.
        const subTotal = page.locator(
            `xpath=//tr[td[contains(normalize-space(.), "Account Code :: ${rentReceivableCode}")]]` +
                `/following-sibling::tr[td[normalize-space(.)="Sub Total"]][1]`,
        );
        await expect(subTotal.locator('td').nth(3), 'a like-for-like replacement leaves the tenant owing no more than before')
            .toHaveText('0.00');
    });

    test('running recognition to last month-end posts this contract CIL by CIL', async ({ page, testContext }) => {
        const to = lastMonthEnd();

        await page.goto('/en/dashboard/finance/recognition');
        await page.getByTestId('recognition-to-date').fill(to);
        await expect(page.getByTestId('recognition-run')).toBeEnabled({ timeout: 20_000 });
        await expect(page.getByTestId('recognition-future-warning')).toHaveCount(0);

        // Preview first — it writes nothing, which is exactly why an accountant
        // runs it before the run.
        await page.getByTestId('recognition-preview').click();
        await expect(page.getByTestId('recognition-result-title')).toBeVisible({ timeout: 40_000 });

        await page.getByTestId('recognition-run').click();
        await page.getByTestId('recognition-run-confirm').click();
        await expect(page.getByTestId('recognition-result-title')).toContainText('Recognition run', { timeout: 40_000 });

        // Scoped to this contract: another worker's tenant-wide run may have
        // posted these periods first, and the claim is about the schedule, not
        // about who closed it.
        const schedule = await proxy<RecognitionEntry[]>(page, 'get', `/leases/${testContext.leaseId}/recognition`);
        const posted = schedule.filter(e => e.status === 'POSTED');
        expect(posted.length, 'every period of this term that has ended is closed').toBeGreaterThan(0);
        for (const e of posted) {
            expect(e.periodEnd <= to, `${e.periodEnd} must not be past the run date`).toBeTruthy();
            expect(e.journalNumber ?? '', 'income recognition posts as CIL').toMatch(/^CIL/);
            // Month-end, never the 1st of the next one (spec D13). This contract
            // runs to term, so every period of it closes on a month end — a
            // truncated final slice only exists for a terminated lease.
            expect(isMonthEnd(e.periodEnd), `${e.periodEnd} must be a month end`).toBeTruthy();
        }
        const latest = posted[posted.length - 1];

        await page.goto(leaseHref);
        await page.getByTestId('lease-tab-recognition').click();
        await expect(page.getByTestId('recognition-schedule')).toBeVisible({ timeout: 20_000 });

        // And on the register of entries, where the accountant would look for it.
        // Narrowed to this contract's own period end: `cheques.spec.ts` shares
        // this tenant and terminates a lease mid-month, so the newest CIL in the
        // tenant is somebody else's truncated slice and taking the first row
        // would be a coin flip.
        await page.goto('/en/dashboard/finance/journals');
        await page.locator('#jv-doc-type').selectOption('CIL');
        await page.locator('#jv-from').fill(latest.periodEnd);
        await page.locator('#jv-to').fill(latest.periodEnd);
        await page.getByRole('button', { name: 'Apply' }).click();
        const row = page.getByRole('row').filter({ hasText: latest.journalNumber! });
        await expect(row).toBeVisible({ timeout: 20_000 });
        await expect(row.locator('td').nth(1)).toHaveText(latest.periodEnd);
    });

    test('a manual journal posts, reverses, and the trial balance still balances', async ({ page }) => {
        await page.goto('/en/dashboard/finance/journals/new');
        await page.locator('#jv-date').fill(today());
        await page.locator('#jv-narration').fill(`E2E reversible entry ${SUFFIX}`);

        const rows = page.locator('tbody tr');
        await pickAccount(rows.nth(0).locator('td').nth(0), bankCode);
        await rows.nth(0).getByLabel('Debit', { exact: true }).fill('1000');
        await pickAccount(rows.nth(1).locator('td').nth(0), 'F-01');
        await rows.nth(1).getByLabel('Credit', { exact: true }).fill('1000');

        const post = page.getByRole('button', { name: 'Post', exact: true });
        await expect(post).toBeEnabled();
        await post.click();
        await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/, { timeout: 20_000 });
        const jvHref = page.url();
        await expect(page.getByText(/JV-/).first()).toBeVisible();

        await page.getByTestId('reverse-journal').click();
        await page.locator('#jv-reverse-date').fill(today());
        await page.locator('#jv-reverse-reason').fill('e2e');
        // The dialog's own Reverse button, not the page header's.
        await page.locator('[role="dialog"], .fixed.inset-0')
            .getByRole('button', { name: 'Reverse', exact: true })
            .last()
            .click();
        await expect(page.getByRole('link', { name: /Reversal of JV-/ })).toBeVisible({ timeout: 20_000 });

        await page.goto(jvHref);
        await expect(page.getByText('Reversed', { exact: true }).first()).toBeVisible();

        await page.goto('/en/dashboard/finance/trial-balance');
        await page.locator('#tb-as-of').fill(today());
        await page.getByRole('button', { name: 'Apply' }).click();

        // The red banner only renders when the two sides differ. Matched by its
        // text rather than role=alert: Next's dev overlay owns an empty
        // role="alert" region on every page, so a count of zero never holds.
        await expect(page.getByText(/does not balance/)).toHaveCount(0);
        const grand = page.locator('tr').filter({ hasText: 'Grand Total' }).first();
        await expect(grand).toBeVisible({ timeout: 20_000 });
        const cells = await grand.locator('td').allInnerTexts();
        expect(cells.at(-2)?.trim(), 'trial balance debit must equal credit').toBe(cells.at(-1)?.trim());
    });
});
