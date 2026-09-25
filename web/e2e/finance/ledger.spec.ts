import type { Locator, Page } from '@playwright/test';
import { test, expect } from '../fixtures/auth.fixture';

/**
 * Ledger core smoke — the accounting-v2 plan 1 path end to end, through the UI:
 * seed the chart, create a property and get its account set, post a manual JV,
 * read it back in the general ledger and the trial balance, then reverse it and
 * watch the balance go to zero.
 *
 * TENANT_ADMIN only. The role projects all run every spec, and the four other
 * roles either cannot reach these pages (PROPERTY_MANAGER/TENANT_USER/RENTER
 * have no `canAccessFinance`) or have no tenant at all (SUPER_ADMIN's session
 * carries none until it pivots), so running this under them would assert
 * against an Access Denied card. `accounts.spec.ts` skips the same way.
 */

/** Unique per run: this spec creates records and the dev DB is not reset between runs. */
const SUFFIX = Math.random().toString(36).slice(2, 7);
const PROPERTY = `E2E Tower ${SUFFIX}`;
/** From the account template's BANK row — `Emirates Islamic - {property}`. */
const BANK_LEAF = `Emirates Islamic - ${PROPERTY}`;
const CAPITAL = 'F-01';

const pad = (n: number) => String(n).padStart(2, '0');
const today = () => {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

/**
 * The onboarding tour auto-starts 1500ms after any dashboard page mounts and
 * renders a modal overlay that eats every click underneath it. Marking it
 * completed before the first navigation is deterministic; dismissing it
 * reactively is a race.
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

/**
 * The account picker is a search box that reveals a list of buttons; it has no
 * select semantics, so the interaction is type-then-click. `query` has to match
 * `code name alias` the way the component filters.
 */
async function pickAccount(scope: Locator, query: string) {
    const input = scope.locator('input[aria-label]').first();
    await input.click();
    await input.fill(query);
    await scope.locator('ul li button').filter({ hasText: query }).first().click();
}

test.describe('Ledger core', () => {
    test.describe.configure({ mode: 'serial' });

    test.beforeEach(async ({ page }, testInfo) => {
        test.skip(testInfo.project.name !== 'tenant-admin', 'TENANT_ADMIN owns the ledger pages');
        await suppressTour(page);
    });

    test('seeds the chart of accounts', async ({ page }) => {
        await page.goto('/en/dashboard/finance/accounts');

        const seed = page.getByRole('button', { name: 'Seed Default Accounts' }).first();
        const assets = page.getByText('Assets', { exact: true }).first();
        // The seed button only renders once the initial GET has resolved AND
        // came back empty, so checking it straight after goto() finds nothing
        // and silently skips the seed. Wait for whichever of the two the chart's
        // state produces first.
        await expect(seed.or(assets).first()).toBeVisible({ timeout: 20_000 });
        if (await seed.isVisible().catch(() => false)) {
            await seed.click();
        }

        // The PACT chart's five roots are the proof the seed landed.
        for (const root of ['Assets', 'Liability', 'Income', 'Expense', 'Equity']) {
            await expect(page.getByText(root, { exact: true }).first()).toBeVisible();
        }
        await expect(page.getByText(/F-01/).first()).toBeVisible();
    });

    test('a new property gets its own account set', async ({ page }) => {
        await page.goto('/en/dashboard/properties');

        await page.getByRole('button', { name: 'Add Project' }).first().click();
        const form = page.locator('div.fixed.inset-0').filter({ hasText: 'Add Project' }).first();
        await form.locator('input[placeholder="Project Name (EN)"]').fill(PROPERTY);
        await form.locator('input[placeholder="Building name, street, area"]').fill('1 Ledger Street, Dubai');

        await form.getByRole('button', { name: 'Create' }).click();

        // Walk in through the list rather than reading the POST body: the id is
        // only needed to reach the detail page, and the row appearing is itself
        // the check that the create landed.
        const row = page.locator('tr').filter({ hasText: PROPERTY }).first();
        await expect(row).toBeVisible({ timeout: 20_000 });
        await row.getByRole('link', { name: 'Manage' }).click();
        await page.waitForURL(/\/dashboard\/properties\/[0-9a-f-]{36}/);

        await page.getByRole('button', { name: /ledger accounts/i }).click();

        // Generated from the template on create — no manual step in between.
        const mapping = page.locator('tr').filter({ hasText: 'RENT RECEIVABLE' }).first();
        await expect(mapping).toBeVisible();
        await expect(mapping).toContainText(`Rent Receivable - ${PROPERTY}`);
        await expect(page.locator('tr').filter({ hasText: 'BANK' }).first()).toContainText(BANK_LEAF);
    });

    test('posts a balanced manual journal voucher', async ({ page }) => {
        await page.goto('/en/dashboard/finance/journals/new');

        await page.locator('#jv-date').fill(today());
        await page.locator('#jv-narration').fill('E2E capital');

        const rows = page.locator('tbody tr');
        await pickAccount(rows.nth(0).locator('td').nth(0), BANK_LEAF);
        await rows.nth(0).getByLabel('Debit', { exact: true }).fill('1000');
        await pickAccount(rows.nth(1).locator('td').nth(0), CAPITAL);
        await rows.nth(1).getByLabel('Credit', { exact: true }).fill('1000');

        const post = page.getByRole('button', { name: 'Post', exact: true });
        await expect(post).toBeEnabled();
        await post.click();

        await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/);
        await expect(page.getByText(/JV-/).first()).toBeVisible();
        // Two lines plus the sub-total row.
        await expect(page.locator('tbody tr')).toHaveCount(2);
        await expect(page.getByText('Posted', { exact: true }).first()).toBeVisible();
    });

    test('the general ledger shows the entry and the trial balance balances', async ({ page }) => {
        await page.goto('/en/dashboard/finance/general-ledger');

        await pickAccount(page.locator('div').filter({ has: page.getByLabel('Search account code or name') }).last(), BANK_LEAF);
        await page.getByRole('button', { name: 'Apply' }).click();

        await expect(page.getByText(`Name :: ${BANK_LEAF}`)).toBeVisible();
        await expect(page.getByText('1,000.00 Dr').first()).toBeVisible();

        await page.goto('/en/dashboard/finance/trial-balance');
        await page.locator('#tb-as-of').fill(today());
        await page.getByRole('button', { name: 'Apply' }).click();

        // The red banner only renders when the two sides differ. Matched by its
        // text rather than role=alert: Next's dev overlay owns an empty
        // role="alert" region on every page, so a count of zero never holds.
        await expect(page.getByText(/does not balance/)).toHaveCount(0);
        const grand = page.locator('tr').filter({ hasText: 'Grand Total' }).first();
        await expect(grand).toBeVisible();
        const cells = await grand.locator('td').allInnerTexts();
        expect(cells.at(-2)?.trim(), 'trial balance debit must equal credit').toBe(cells.at(-1)?.trim());
    });

    test('reversing the voucher takes the account balance back to zero', async ({ page }) => {
        await page.goto('/en/dashboard/finance/journals');
        await page.getByRole('link', { name: /JV-/ }).first().click();
        await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/);
        const original = page.url();

        await page.getByRole('button', { name: 'Reverse', exact: true }).click();
        await page.locator('#jv-reverse-date').fill(today());
        await page.locator('#jv-reverse-reason').fill('e2e');
        // The dialog's own Reverse button, not the page header's.
        await page.locator('[role="dialog"], .fixed.inset-0')
            .getByRole('button', { name: 'Reverse', exact: true })
            .last()
            .click();

        // The narration reads "Reversal of JV-…" too — anchor on the link.
        await expect(page.getByRole('link', { name: /Reversal of JV-/ })).toBeVisible();

        await page.goto(original);
        await expect(page.getByText('Reversed', { exact: true }).first()).toBeVisible();

        await page.goto('/en/dashboard/finance/general-ledger');
        await pickAccount(page.locator('div').filter({ has: page.getByLabel('Search account code or name') }).last(), BANK_LEAF);
        await page.getByRole('button', { name: 'Apply' }).click();
        const subTotal = page.locator('tr').filter({ hasText: 'Sub Total' }).first();
        await expect(subTotal).toContainText('0.00');
    });
});
