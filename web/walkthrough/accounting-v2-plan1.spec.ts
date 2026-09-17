import { test, expect, type Browser, type Locator, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

/**
 * Accounting v2, plan 1 (ledger core) — every scenario the feature can express,
 * one recording each.
 *
 * The recording and the proof are the same run: each scenario asserts its own
 * outcome, and a take only exists because those assertions passed. A take of a
 * broken flow is worse than no take.
 *
 * Runs against the LOCAL dev stack (Next on 3001, backend on 8081) and
 * provisions ONE disposable tenant of its own through the API — a tenant admin,
 * an accountant, a property manager and a renter. Nothing here touches
 * production and no real address or name goes on screen.
 *
 * Order is the story: the chart is seeded before a property needs it, the
 * property's bank leaf exists before a voucher can debit it, and the period
 * lock comes last because a lock can only ever move forwards (see
 * TenantFiscalSettingsService#lockThrough) — locking earlier would close the
 * books under every scenario that follows.
 */

const BACKEND = process.env.WT_BACKEND_URL || 'http://localhost:8081';
const BASE_URL = process.env.WT_BASE_URL || 'http://localhost:3001';

const TAKES_DIR = path.join(__dirname, 'takes', 'accounting-v2-plan1');
const STATE = path.join(__dirname, 'raw', 'accounting-v2-plan1-state.json');
const SUFFIX = Math.random().toString(36).slice(2, 7);
const MANIFEST = path.join(__dirname, `run-manifest-accounting-v2-plan1-${SUFFIX}.json`);

const PROPERTY = `WT Tower ${SUFFIX}`;
const VENDOR = `WT Facilities ${SUFFIX}`;
const RENTER = `Walkthrough Renter ${SUFFIX}`;
const NEW_LEAF = `WT Petty Cash ${SUFFIX}`;

/** Every role the property account template carries, and the name it generates. */
const TEMPLATE_ROLES: [string, string][] = [
    ['ADMIN_FEE', `Admin Fee - ${PROPERTY}`],
    ['ADVANCE_RENT', `Advance Rent - ${PROPERTY}`],
    ['BANK', `Emirates Islamic - ${PROPERTY}`],
    ['CHEQUE_RETURN_PENALTY', `Cheque Return Penalty - ${PROPERTY}`],
    ['COOLING_CHARGES', `Cooling Charges - ${PROPERTY}`],
    ['MAINTENANCE_CHARGES', `Maintenance Charges - ${PROPERTY}`],
    ['PARKING_DEPOSIT', `Parking Security Deposit ${PROPERTY}`],
    ['PARKING_INCOME', `Additional Parking - ${PROPERTY}`],
    ['PDC_RECEIVABLE', `PDC Receivable ${PROPERTY}`],
    ['RENT_PENALTY', `Rent Penalty - ${PROPERTY}`],
    ['RENT_RECEIVABLE', `Rent Receivable - ${PROPERTY}`],
    ['RENTAL_INCOME', `Rental Income ${PROPERTY}`],
    ['SECURITY_DEPOSIT', `Security Deposit ${PROPERTY}`],
];

const BANK_LEAF = `Emirates Islamic - ${PROPERTY}`;
const CAPITAL = 'Capital Account';

const FINANCE_NAV: [string, string][] = [
    ['Chart of Accounts', '/dashboard/finance/accounts'],
    ['Journal Vouchers', '/dashboard/finance/journals'],
    ['General Ledger', '/dashboard/finance/general-ledger'],
    ['Tenant Ledger', '/dashboard/finance/tenant-ledger'],
    ['Trial Balance', '/dashboard/finance/trial-balance'],
    ['Payments', '/dashboard/finance/payments'],
    ['Vendors', '/dashboard/finance/vendors'],
    ['Bank Accounts', '/dashboard/finance/bank-accounts'],
];

const pad = (n: number) => String(n).padStart(2, '0');
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const daysAgo = (n: number) => {
    const d = new Date();
    d.setDate(d.getDate() - n);
    return iso(d);
};
const today = () => iso(new Date());

// ── manifest ────────────────────────────────────────────────────────────────

type Manifest = {
    startedAt: string;
    baseURL: string;
    backendURL: string;
    environment: 'local-dev';
    created: { kind: string; id: string; label: string }[];
};

const manifest: Manifest = {
    startedAt: new Date().toISOString(),
    baseURL: BASE_URL,
    backendURL: BACKEND,
    environment: 'local-dev',
    created: [],
};

/** Append-as-created: the manifest is the inventory of what this run left behind, not a summary written at the end. */
function record(kind: string, id: string, label: string) {
    manifest.created.push({ kind, id, label });
    fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2));
    console.log(`  created ${kind}: ${label} (${id})`);
}

// ── provisioning client ─────────────────────────────────────────────────────

type Actor = { id: string; role: string; tenantId: string | null };

/**
 * Direct backend calls for provisioning only — never for an assertion a
 * scenario is supposed to make through the UI. Uses the same X-User-* headers
 * the dev e2e helpers use (`e2e/helpers/api-client.ts`).
 */
async function api<T>(actor: Actor | null, method: string, apiPath: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (actor) {
        headers['X-User-Id'] = actor.id;
        headers['X-User-Role'] = actor.role;
        if (actor.tenantId) {
            headers['X-Tenant-Id'] = actor.tenantId;
            headers['X-User-Tenant-Id'] = actor.tenantId;
        }
    }
    const res = await fetch(`${BACKEND}${apiPath}`, {
        method,
        headers,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!res.ok) {
        throw new Error(`${method} ${apiPath} failed (${res.status}): ${await res.text().catch(() => '')}`);
    }
    return res.headers.get('content-type')?.includes('application/json') ? ((await res.json()) as T) : ({} as T);
}

type Fixtures = {
    tenantId: string;
    admin: { email: string; password: string };
    accountant: { email: string; password: string };
    manager: { email: string; password: string };
    renterName: string;
};

let fx: Fixtures;
/** Filled in by scenario 03 and read by everything that needs the property's own accounts. */
let propertyId = '';
/** Filled in by scenario 08 and reversed by scenario 10. */
let jvUrl = '';

// ── recording ───────────────────────────────────────────────────────────────

type Take = { page: Page; close: () => Promise<void> };

/**
 * One browser context per scenario, so one video per scenario.
 *
 * `recordVideo` has to be set here rather than left to the config's `video`
 * option: that option only applies to contexts Playwright creates through its
 * own fixtures, and a context made with browser.newContext() inherits none of
 * it — which is how a passing run ends up with no footage at all.
 */
async function recorded(browser: Browser, takeName: string, opts: { signedIn?: boolean } = {}): Promise<Take> {
    fs.mkdirSync(TAKES_DIR, { recursive: true });
    const context = await browser.newContext({
        baseURL: BASE_URL,
        recordVideo: { dir: TAKES_DIR, size: { width: 1280, height: 720 } },
        ...(opts.signedIn === false ? {} : { storageState: STATE }),
    });
    // The onboarding tour auto-starts 1500ms after any dashboard page mounts and
    // renders a Shepherd overlay that swallows every click underneath it.
    // Marking it completed up front is deterministic; dismissing it reactively
    // is a race that a slowMo'd run loses.
    await context.addInitScript(() => {
        try {
            window.localStorage.setItem('rentaxis_tours_completed', JSON.stringify(['admin-onboarding']));
        } catch {
            /* storage unavailable — nothing to suppress */
        }
    });
    const page = await context.newPage();
    return {
        page,
        // The file only exists once the context is closed, and Playwright names
        // it with a random id — rename to the scenario so takes are identifiable.
        close: async () => {
            const video = page.video();
            await context.close();
            if (!video) return;
            try {
                fs.renameSync(await video.path(), path.join(TAKES_DIR, `${takeName}.webm`));
                console.log(`  take saved: ${takeName}.webm`);
            } catch {
                /* a failed rename must never fail the scenario */
            }
        },
    };
}

/** Signs in through the real login page — the flow, not a cookie injection. */
async function signIn(page: Page, email: string, password: string) {
    await page.goto('/en/auth/login');
    await page.locator('#login-email').fill(email);
    await page.locator('#login-password').fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await page.waitForURL(/\/dashboard/, { timeout: 60_000 });
}

/**
 * Holds the end state on screen so a take finishes on the thing it proves
 * rather than cutting the instant the last assertion returns. Recording-only;
 * no assertion depends on it.
 */
async function hold(page: Page, ms = 2200) {
    await page.waitForTimeout(ms);
}

/**
 * The account picker is a search box over a list of buttons, not a select.
 * `label` is its aria-label (the component mirrors its placeholder into one).
 */
async function pickAccount(scope: Locator, label: string, query: string) {
    const input = scope.getByLabel(label, { exact: true }).first();
    await input.click();
    await input.fill(query);
    await scope.locator('ul li button').filter({ hasText: query }).first().click();
}

/** The chart of accounts tree row for one account code. */
function coaRow(page: Page, code: string) {
    return page.locator(`[data-testid="coa-row"][data-code="${code}"]`);
}

/** Seeds the PACT chart if the tenant has none. Idempotent. */
async function ensureChartSeeded(page: Page) {
    const seed = page.getByRole('button', { name: 'Seed Default Accounts' }).first();
    const assets = coaRow(page, 'A');
    // The seed button only renders once the initial GET resolves AND comes back
    // empty, so probing it straight after goto() finds nothing and silently
    // skips the seed. Wait for whichever of the two the chart's state produces.
    await expect(seed.or(assets).first()).toBeVisible({ timeout: 30_000 });
    if (await seed.isVisible().catch(() => false)) await seed.click();
    await expect(assets).toBeVisible({ timeout: 30_000 });
}

// ── provisioning ────────────────────────────────────────────────────────────

test.describe.configure({ mode: 'serial' });

test('provision a disposable tenant with an admin, an accountant, a manager and a renter', async () => {
    const su = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', {
        email: 'admin@rentaxis.com',
        password: 'admin123',
    });
    const superAdmin: Actor = { id: su.id, role: su.role, tenantId: null };

    const tenantName = `WALKTHROUGH-ACCOUNTING-V2 ${today()} ${SUFFIX}`;
    const tenant = await api<{ id: string }>(superAdmin, 'POST', '/api/admin/tenants', { name: tenantName });
    expect(tenant.id, 'tenant must be created').toBeTruthy();
    record('tenant', tenant.id, tenantName);

    const scoped: Actor = { ...superAdmin, tenantId: tenant.id };
    const password = `Walk!${SUFFIX}9`;
    const make = async (role: string, slug: string, name: string) => {
        const email = `wt-${slug}-${SUFFIX}@example.invalid`;
        const user = await api<{ id: string }>(scoped, 'POST', '/api/admin/users', {
            name,
            email,
            password,
            role,
            tenantId: tenant.id,
        });
        record('user', user.id, `${email} (${role})`);
        return { email, password };
    };

    const admin = await make('TENANT_ADMIN', 'admin', `Walkthrough Admin ${SUFFIX}`);
    const accountant = await make('ACCOUNTANT', 'accountant', `Walkthrough Accountant ${SUFFIX}`);
    const manager = await make('PROPERTY_MANAGER', 'manager', `Walkthrough Manager ${SUFFIX}`);

    // The tenant ledger needs a tenant to run for; plan 1 posts nothing against
    // one, which is exactly what scenario 12 records.
    const renter = await api<{ id: string }>(scoped, 'POST', '/api/v1/renters', {
        nameEn: RENTER,
        nameAr: RENTER,
        email: `wt-renter-${SUFFIX}@example.invalid`,
        phone: '+971500000000',
        primaryLanguage: 'EN',
        createPortalAccount: false,
    });
    record('renter', renter.id, RENTER);

    fx = { tenantId: tenant.id, admin, accountant, manager, renterName: RENTER };
});

// ── 01 ──────────────────────────────────────────────────────────────────────

test('01 login and the finance navigation', async ({ browser }) => {
    const { page, close } = await recorded(browser, '01-login-and-finance-nav', { signedIn: false });
    try {
        await signIn(page, fx.admin.email, fx.admin.password);

        const nav = page.locator('nav[data-tour="sidebar-nav"]');
        for (const [label, href] of FINANCE_NAV) {
            await expect(nav.getByRole('link', { name: label, exact: true })).toHaveAttribute('href', `/en${href}`);
        }
        // The two v1 pages the ledger replaced must be gone from the nav, not
        // merely unreachable.
        await expect(nav.locator('a[href*="/finance/transactions"]')).toHaveCount(0);
        await expect(nav.locator('a[href*="/finance/reports"]')).toHaveCount(0);

        await page.context().storageState({ path: STATE });
        await hold(page);
    } finally {
        await close();
    }
});

// ── 02 ──────────────────────────────────────────────────────────────────────

test('02 chart of accounts — seed, tree, create a leaf, guard the parent, edit an alias', async ({ browser }) => {
    const { page, close } = await recorded(browser, '02-chart-of-accounts-seed-and-tree');
    try {
        await page.goto('/en/dashboard/finance/accounts');
        await ensureChartSeeded(page);

        // Expand and collapse a group. The seed leaves every group expanded, so
        // the demonstrable move is to close one and re-open it.
        const assetsToggle = coaRow(page, 'A').getByRole('button').first();
        await assetsToggle.click();
        await expect(coaRow(page, 'A-02')).toHaveCount(0);
        await assetsToggle.click();
        await expect(coaRow(page, 'A-02')).toBeVisible();

        const leaves = page.locator('[data-testid="coa-row"][data-group="false"]');
        expect(await leaves.count(), 'the seeded chart must contain postable leaves').toBeGreaterThan(0);

        // ── create a leaf under a group, letting the backend assign the code ──
        await page.getByRole('button', { name: 'Add Account' }).click();
        const modal = page.locator('div.fixed.inset-0').filter({ hasText: 'Add Account' }).first();
        await modal.locator('input[placeholder="Account name in English"]').fill(NEW_LEAF);
        await pickAccount(modal, 'Parent account', 'A-02-05');
        await modal.getByRole('button', { name: 'Create', exact: true }).click();

        const created = page.locator('[data-testid="coa-row"]').filter({ hasText: NEW_LEAF }).first();
        await expect(created).toBeVisible();
        const code = await created.getAttribute('data-code');
        expect(code, 'the code must be auto-assigned when the field is left blank').toMatch(/^\d+$/);
        expect(await created.getAttribute('data-group')).toBe('false');

        // ── a leaf cannot be a parent ──
        // The picker is `groupOnly`, so the UI never offers one: searching for
        // the leaf we just made turns up the empty marker rather than an option.
        await page.getByRole('button', { name: 'Add Account' }).click();
        const guard = page.locator('div.fixed.inset-0').filter({ hasText: 'Add Account' }).first();
        const parentSearch = guard.getByLabel('Parent account', { exact: true }).first();
        await parentSearch.click();
        await parentSearch.fill(code as string);
        await expect(guard.locator('ul li').filter({ hasText: NEW_LEAF })).toHaveCount(0);

        // …and the rule is enforced on the server, not only hidden in the UI.
        const leafId = await page.evaluate(async c => {
            const r = await fetch(`/api/proxy/v1/finance/accounts/code/${c}`);
            return (await r.json()).id as string;
        }, code);
        const refused = await page.request.post('/api/proxy/v1/finance/accounts', {
            data: { name: 'Child of a leaf', nameEn: 'Child of a leaf', accountType: 'ASSET', parentId: leafId, group: false },
        });
        expect(refused.status(), 'posting under a leaf must be refused').toBe(400);
        expect((await refused.json()).message).toContain('must be a group account');

        // ── the modal surfaces a server refusal inline ──
        await guard.locator('input[placeholder="Account name in English"]').fill(`Duplicate ${SUFFIX}`);
        await guard.locator('input[placeholder="Auto (e.g. A-01)"]').fill('F-01');
        await pickAccount(guard, 'Parent account', 'A-02-05');
        await guard.getByRole('button', { name: 'Create', exact: true }).click();
        await expect(guard.getByRole('alert')).toBeVisible();
        await guard.getByRole('button', { name: 'Cancel', exact: true }).click();

        // ── edit an alias ──
        await created.getByTitle('Edit Account').click();
        const edit = page.locator('div.fixed.inset-0').filter({ hasText: 'Edit Account' }).first();
        await edit.locator('input[placeholder="Short name used in reports"]').fill('WT-CASH');
        await edit.getByRole('button', { name: /^(Save|Create)$/ }).click();
        await expect(edit).toHaveCount(0);

        // Read it back through the UI: the tree does not print the alias, the
        // edit form does, so re-opening the form is the visible proof.
        await created.getByTitle('Edit Account').click();
        const reopened = page.locator('div.fixed.inset-0').filter({ hasText: 'Edit Account' }).first();
        await expect(reopened.locator('input[placeholder="Short name used in reports"]')).toHaveValue('WT-CASH');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 03 ──────────────────────────────────────────────────────────────────────

test('03 creating a property generates its account set', async ({ browser }) => {
    const { page, close } = await recorded(browser, '03-property-creates-account-set');
    try {
        await page.goto('/en/dashboard/properties');
        await page.getByRole('button', { name: 'Add Project' }).first().click();
        const form = page.locator('div.fixed.inset-0').filter({ hasText: 'Add Project' }).first();
        await form.locator('input[placeholder="Project Name (EN)"]').fill(PROPERTY);
        await form.locator('input[placeholder="Building name, street, area"]').fill('1 Ledger Street, Dubai');
        await form.getByRole('button', { name: 'Create', exact: true }).click();

        const row = page.locator('tr').filter({ hasText: PROPERTY }).first();
        await expect(row).toBeVisible({ timeout: 30_000 });
        await row.getByRole('link', { name: 'Manage' }).click();
        await page.waitForURL(/\/dashboard\/properties\/[0-9a-f-]{36}/);
        propertyId = page.url().split('/properties/')[1].split(/[?#]/)[0];
        record('property', propertyId, PROPERTY);

        await page.getByRole('button', { name: 'Ledger accounts' }).click();
        // Every template role, generated on create — there is no manual step in
        // between, which is the point of the scenario.
        for (const [role, name] of TEMPLATE_ROLES) {
            await expect(page.locator(`[data-role="${role}"]`), `${role} must be mapped`).toContainText(name);
        }

        // The same leaves show up in the chart when it is filtered to this property.
        await page.goto('/en/dashboard/finance/accounts');
        await ensureChartSeeded(page);
        await page.getByLabel('Property', { exact: true }).selectOption(propertyId);
        const tagged = page.locator(`[data-testid="coa-row"][data-property="${propertyId}"]`);
        await expect(tagged.first()).toBeVisible();
        expect(await tagged.count()).toBeGreaterThanOrEqual(TEMPLATE_ROLES.length);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 04 ──────────────────────────────────────────────────────────────────────

test('04 a property mapping can be overridden and reset', async ({ browser }) => {
    const { page, close } = await recorded(browser, '04-property-mapping-override');
    try {
        await page.goto(`/en/dashboard/properties/${propertyId}`);
        await page.getByRole('button', { name: 'Ledger accounts' }).click();

        const rentalIncome = page.locator('[data-role="RENTAL_INCOME"]');
        await expect(rentalIncome).toContainText(`Rental Income ${PROPERTY}`);

        // Point the role at a tenant-wide income leaf instead of the generated one.
        await rentalIncome.getByRole('button', { name: 'Change' }).click();
        await pickAccount(rentalIncome, 'Account', 'Amount Forfeited');
        await expect(rentalIncome).toContainText('Amount Forfeited');
        await expect(rentalIncome).not.toContainText(`Rental Income ${PROPERTY}`);

        // "Use default" drops the property mapping. This tenant has no
        // tenant-wide default for the role — the seed leaves the defaults table
        // empty — so the honest result is "Not mapped", not a fallback account.
        await rentalIncome.getByRole('button', { name: 'Use default' }).click();
        await expect(rentalIncome).toContainText('Not mapped');

        // Put the generated account back so the rest of the story is unaffected.
        await rentalIncome.getByRole('button', { name: 'Change' }).click();
        await pickAccount(rentalIncome, 'Account', `Rental Income ${PROPERTY}`);
        await expect(rentalIncome).toContainText(`Rental Income ${PROPERTY}`);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 05 ──────────────────────────────────────────────────────────────────────

test('05 a vendor silently gets its own payable leaf', async ({ browser }) => {
    const { page, close } = await recorded(browser, '05-vendor-silent-leaf');
    try {
        await page.goto('/en/dashboard/finance/vendors');
        await page.getByRole('button', { name: 'Add Vendor' }).click();
        const modal = page.locator('div.fixed.inset-0').filter({ hasText: 'Add Vendor' }).first();
        // Name (English) is the first input in the form and the only required one.
        await modal.locator('form input').first().fill(VENDOR);
        await modal.getByRole('button', { name: 'Add Vendor', exact: true }).click();

        const row = page.locator('tr').filter({ hasText: VENDOR }).first();
        await expect(row).toBeVisible({ timeout: 30_000 });
        // Nobody chose this account: creating the vendor created it.
        await expect(row).toContainText(VENDOR);
        await expect(row.getByRole('link', { name: 'Ledger' })).toBeVisible();

        // The leaf is filed under the PACT vendors payable group, B-01-04.
        // The flat view is the one that prints each account's parent code.
        await page.goto('/en/dashboard/finance/accounts');
        await ensureChartSeeded(page);
        await page.getByRole('button', { name: 'Flat', exact: true }).click();
        const leaf = page.locator('tr').filter({ hasText: VENDOR }).first();
        await expect(leaf).toBeVisible();
        await expect(leaf).toContainText('B-01-04');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 06 ──────────────────────────────────────────────────────────────────────

test('06 a bank account defaults to its property bank leaf', async ({ browser }) => {
    const { page, close } = await recorded(browser, '06-bank-account-default-leaf');
    try {
        await page.goto('/en/dashboard/finance/bank-accounts');
        await page.getByRole('button', { name: 'Add Bank Account' }).click();
        const modal = page.locator('div.fixed.inset-0').filter({ hasText: 'Add Bank Account' }).first();
        await modal.locator('form input').first().fill('Emirates Islamic');
        await modal.locator('form input').nth(1).fill(`WT-${SUFFIX}`);
        // Linked Property is the first select; the COA Account select below it is
        // deliberately left on its placeholder — that is the whole scenario.
        await modal.locator('select').first().selectOption({ label: PROPERTY });
        await modal.getByRole('button', { name: 'Add Bank Account', exact: true }).click();

        const row = page.locator('tr').filter({ hasText: `WT-${SUFFIX}` }).first();
        await expect(row).toBeVisible({ timeout: 30_000 });
        await expect(row, 'the ledger account must default to the property bank leaf').toContainText(BANK_LEAF);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 07 ──────────────────────────────────────────────────────────────────────

test('07 the property account template and the fiscal settings persist', async ({ browser }) => {
    const { page, close } = await recorded(browser, '07-template-and-fiscal-settings');
    try {
        await page.goto('/en/dashboard/settings/account-template');
        for (const [role] of TEMPLATE_ROLES) {
            await expect(page.getByRole('row', { name: new RegExp(role.replaceAll('_', ' '), 'i') }).first()).toBeVisible();
        }

        const pattern = page.getByLabel('Name pattern ADMIN FEE', { exact: true });
        await pattern.fill('Administration Fee - {property}');
        await page.getByRole('button', { name: 'Save', exact: true }).first().click();
        await expect(page.getByText('Saved', { exact: true }).first()).toBeVisible();

        await page.reload();
        await expect(page.getByLabel('Name pattern ADMIN FEE', { exact: true })).toHaveValue('Administration Fee - {property}');

        // ── fiscal year & books start ──
        await page.goto('/en/dashboard/settings/fiscal');
        await page.locator('#fiscal-start-month').selectOption('4');
        const booksStart = `${new Date().getFullYear()}-01-01`;
        await page.locator('#fiscal-books-start').fill(booksStart);
        await page.getByRole('button', { name: 'Save', exact: true }).click();
        await expect(page.getByText('Saved', { exact: true })).toBeVisible();

        await page.reload();
        await expect(page.locator('#fiscal-start-month')).toHaveValue('4');
        await expect(page.locator('#fiscal-books-start')).toHaveValue(booksStart);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 08 ──────────────────────────────────────────────────────────────────────

test('08 a manual journal voucher will not post until it balances', async ({ browser }) => {
    const { page, close } = await recorded(browser, '08-manual-jv-post');
    try {
        await page.goto('/en/dashboard/finance/journals/new');
        await page.locator('#jv-date').fill(today());
        await page.locator('#jv-narration').fill('Owner capital introduced');
        await page.locator('#jv-property').selectOption({ label: PROPERTY });

        const rows = page.locator('tbody tr');
        await pickAccount(rows.nth(0).locator('td').first(), 'Account', BANK_LEAF);
        await rows.nth(0).getByLabel('Debit', { exact: true }).fill('1000');
        await pickAccount(rows.nth(1).locator('td').first(), 'Account', CAPITAL);
        await rows.nth(1).getByLabel('Credit', { exact: true }).fill('500');

        const post = page.getByRole('button', { name: 'Post', exact: true });
        await expect(page.getByText('Debits and credits must be equal')).toBeVisible();
        await expect(post).toBeDisabled();

        await rows.nth(1).getByLabel('Credit', { exact: true }).fill('1000');
        await expect(page.getByText('Debits and credits must be equal')).toHaveCount(0);
        await expect(post).toBeEnabled();
        await post.click();

        await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/, { timeout: 60_000 });
        jvUrl = page.url().split('?')[0];
        record('journal', jvUrl.split('/journals/')[1], 'Owner capital introduced');

        await expect(page.getByText(/JV-/).first()).toBeVisible();
        await expect(page.getByText('Posted', { exact: true }).first()).toBeVisible();
        await expect(page.locator('tbody tr')).toHaveCount(2);
        const totals = page.locator('tfoot tr').first();
        await expect(totals).toContainText('1,000.00');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 09 ──────────────────────────────────────────────────────────────────────

test('09 the general ledger and the trial balance agree with the voucher', async ({ browser }) => {
    const { page, close } = await recorded(browser, '09-general-ledger-and-trial-balance');
    try {
        await page.goto('/en/dashboard/finance/general-ledger');
        await pickAccount(page.locator('body'), 'All accounts with activity', BANK_LEAF);
        await page.getByRole('button', { name: 'Apply' }).click();

        await expect(page.getByText(`Name :: ${BANK_LEAF}`)).toBeVisible();
        const entryRow = page.locator('tr').filter({ hasText: /JV-/ }).first();
        // PACT prints the counter-account in the Particular column.
        await expect(entryRow).toContainText(CAPITAL);
        await expect(entryRow).toContainText('1,000.00 Dr');

        await page.goto('/en/dashboard/finance/trial-balance');
        await page.locator('#tb-as-of').fill(today());
        await page.getByRole('button', { name: 'Apply' }).click();

        // The out-of-balance banner is matched by its text, not by role=alert:
        // Next's dev overlay owns an empty role="alert" region on every page.
        await expect(page.getByText(/does not balance/)).toHaveCount(0);
        const grand = page.locator('tr').filter({ hasText: 'Grand Total' }).first();
        const cells = await grand.locator('td').allInnerTexts();
        expect(cells.at(-2)?.trim(), 'the trial balance must balance').toBe(cells.at(-1)?.trim());
        await expect(page.locator('tr').filter({ hasText: BANK_LEAF }).first()).toContainText('1,000.00');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 10 ──────────────────────────────────────────────────────────────────────

test('10 reversing the voucher leaves the original in place and the balance at zero', async ({ browser }) => {
    const { page, close } = await recorded(browser, '10-journal-reverse');
    try {
        await page.goto(jvUrl);
        await page.getByRole('button', { name: 'Reverse', exact: true }).click();
        await page.locator('#jv-reverse-date').fill(today());
        await page.locator('#jv-reverse-reason').fill('walkthrough');
        await page.locator('div.fixed.inset-0').getByRole('button', { name: 'Reverse', exact: true }).last().click();

        // Lands on the reversal, which points back at the original.
        // The narration also reads "Reversal of JV-…", so anchor on the link
        // back to the original rather than on the text.
        await expect(page.getByRole('link', { name: /Reversal of JV-/ })).toBeVisible({ timeout: 60_000 });
        const reversalUrl = page.url().split('?')[0];
        expect(reversalUrl).not.toBe(jvUrl);

        await page.getByRole('link', { name: /Reversal of JV-/ }).click();
        await page.waitForURL(jvUrl);
        await expect(page.getByText('Reversed', { exact: true }).first()).toBeVisible();
        await expect(page.getByRole('link', { name: /Reversed by JV-/ })).toBeVisible();

        await page.goto('/en/dashboard/finance/general-ledger');
        await pickAccount(page.locator('body'), 'All accounts with activity', BANK_LEAF);
        await page.getByRole('button', { name: 'Apply' }).click();
        const subTotal = page.locator('tr').filter({ hasText: 'Sub Total' }).first();
        await expect(subTotal, 'the reversal must take the account back to nil').toContainText('0.00');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 12 ──────────────────────────────────────────────────────────────────────

test('12 the tenant ledger is empty until lease posting arrives', async ({ browser }) => {
    const { page, close } = await recorded(browser, '12-tenant-ledger-empty-state');
    try {
        await page.goto('/en/dashboard/finance/tenant-ledger');
        await expect(page.getByText('Pick a tenant to see their ledger')).toBeVisible();

        await page.locator('#ledger-renter').selectOption({ label: fx.renterName });
        // The page renders the very same "No entries" card when the fetch FAILED
        // — the catch sets `ledgers = []` and adds a LoadErrorBanner above it —
        // so asserting that text alone would pass on a 403 or a 500 and hand
        // back a take of a broken page. Pin the read itself.
        const [ledgerRead] = await Promise.all([
            page.waitForResponse(
                r => /\/api\/proxy\/v1\/finance\/ledger\/renter\//.test(r.url()) && r.request().method() === 'GET',
                { timeout: 60_000 },
            ),
            page.getByRole('button', { name: 'Apply' }).click(),
        ]);
        expect(ledgerRead.status(), 'the tenant ledger must actually load').toBe(200);
        expect(await ledgerRead.json(), 'plan 1 posts nothing against a tenant').toEqual([]);

        // Plan 1 posts nothing against a tenant — lease charges, cheques and
        // receipts are plans 2 and 3 — so the honest state is the empty one.
        // The PACT "Tenant Name" band lives inside an account block, so with no
        // postings there is no block to carry it; the empty state is what shows.
        await expect(page.getByText('No entries for this selection')).toBeVisible();
        // LoadErrorBanner is a role="alert". Scoped to <main> because Next's dev
        // overlay owns an always-present empty role="alert" region of its own.
        await expect(page.locator('main [role="alert"]')).toHaveCount(0);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 13 ──────────────────────────────────────────────────────────────────────

test('13 the accountant owns the ledger and the property manager does not', async ({ browser }) => {
    const { page, close } = await recorded(browser, '13-accountant-role-access', { signedIn: false });
    try {
        // ── ACCOUNTANT ──
        await signIn(page, fx.accountant.email, fx.accountant.password);
        const nav = page.locator('nav[data-tour="sidebar-nav"]');
        for (const label of ['General Ledger', 'Trial Balance', 'Journal Vouchers']) {
            await expect(nav.getByRole('link', { name: label, exact: true })).toBeVisible();
        }
        await expect(nav.getByRole('link', { name: 'Property account template', exact: true })).toBeVisible();
        await expect(nav.getByRole('link', { name: 'Fiscal year & period lock', exact: true })).toBeVisible();
        // Gateway configuration is a tenant-admin concern, not an accountant's.
        await expect(nav.locator('a[href*="/settings/gateway"]')).toHaveCount(0);
        // Neither are the operational pages that merely live under /finance:
        // VendorController, BankAccountController, PaymentScheduleController and
        // StaffController all stop at TENANT_ADMIN, so offering these links would
        // only hand the accountant four 403s. They are gated on canAccessFinanceOps.
        for (const href of ['/finance/payments', '/finance/vendors', '/finance/bank-accounts', '/dashboard/staff']) {
            await expect(nav.locator(`a[href*="${href}"]`)).toHaveCount(0);
        }

        await page.goto('/en/dashboard/finance/journals');
        await expect(page.getByRole('link', { name: 'New Journal Voucher' })).toBeVisible();
        await page.goto('/en/dashboard/settings/account-template');
        await expect(page.getByRole('heading', { name: 'Property account template' }).first()).toBeVisible();
        await page.goto('/en/dashboard/settings/fiscal');
        await expect(page.getByRole('heading', { name: 'Fiscal year & period lock' })).toBeVisible();
        await hold(page, 1200);

        // ── PROPERTY_MANAGER ──
        await page.context().clearCookies();
        await signIn(page, fx.manager.email, fx.manager.password);
        const pmNav = page.locator('nav[data-tour="sidebar-nav"]');
        await expect(pmNav.locator('a[href*="/dashboard/finance/"]')).toHaveCount(0);
        await expect(pmNav.locator('a[href*="/settings/account-template"]')).toHaveCount(0);
        await expect(pmNav.locator('a[href*="/settings/fiscal"]')).toHaveCount(0);

        // A property manager has no `canAccessFinance`, so the journals page is
        // not a reduced view — it is refused outright.
        await page.goto('/en/dashboard/finance/journals');
        await expect(page.getByText('Access Denied')).toBeVisible();
        await expect(page.getByRole('link', { name: 'New Journal Voucher' })).toHaveCount(0);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 14 ──────────────────────────────────────────────────────────────────────

test('14 the ledger reads right-to-left in Arabic', async ({ browser }) => {
    const { page, close } = await recorded(browser, '14-arabic-rtl-general-ledger');
    try {
        await page.goto('/ar/dashboard/finance/general-ledger');
        await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
        await expect(page.getByRole('heading', { name: 'دفتر الأستاذ العام' })).toBeVisible();

        await pickAccount(page.locator('body'), 'كل الحسابات ذات الحركة', BANK_LEAF);
        await page.getByRole('button', { name: 'تطبيق' }).click();
        await expect(page.getByText(`Name :: ${BANK_LEAF}`)).toBeVisible();
        // Amounts stay in Western digits with the same grouping — a ledger that
        // reformatted its numbers per locale could not be reconciled against the
        // English one.
        await expect(page.locator('tr').filter({ hasText: /JV-/ }).first()).toContainText('1,000.00');

        await page.goto(jvUrl.replace('/en/', '/ar/'));
        await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
        await expect(page.getByText('قيد اليومية').first()).toBeVisible();
        await hold(page);
    } finally {
        await close();
    }
});

// ── 11 ──────────────────────────────────────────────────────────────────────
// Declared last on purpose: the period lock can only move forwards, so once the
// books are closed through yesterday every earlier-dated scenario would fail.
// The take is still numbered 11 — it is the eleventh scenario, not the last one
// in the story.

test('11 a locked period refuses back-dated postings and reversals', async ({ browser }) => {
    const { page, close } = await recorded(browser, '11-period-lock-blocks-posting');
    try {
        // ── a back-dated voucher, posted while the books are still open ──
        const backDate = daysAgo(5);
        await page.goto('/en/dashboard/finance/journals/new');
        await page.locator('#jv-date').fill(backDate);
        await page.locator('#jv-narration').fill('Before the lock');
        const rows = page.locator('tbody tr');
        await pickAccount(rows.nth(0).locator('td').first(), 'Account', BANK_LEAF);
        await rows.nth(0).getByLabel('Debit', { exact: true }).fill('250');
        await pickAccount(rows.nth(1).locator('td').first(), 'Account', CAPITAL);
        await rows.nth(1).getByLabel('Credit', { exact: true }).fill('250');
        await page.getByRole('button', { name: 'Post', exact: true }).click();
        await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/, { timeout: 60_000 });
        const backDatedUrl = page.url().split('?')[0];
        record('journal', backDatedUrl.split('/journals/')[1], 'Before the lock');

        // ── close the books through yesterday ──
        await page.goto('/en/dashboard/settings/fiscal');
        await page.locator('#fiscal-lock-through').fill(daysAgo(1));
        // The trigger says "Lock through" now, matching the dialog's confirm button —
        // it used to say "Lock period", which is the SECTION HEADING, not an action.
        // ConfirmDialog renders nothing while closed, so before this click there is
        // exactly one button with that name: the trigger. The confirm on the next
        // line stays scoped to the dialog, which is what disambiguates the two.
        await page.getByRole('button', { name: 'Lock through', exact: true }).first().click();
        await page.locator('div.fixed.inset-0').getByRole('button', { name: 'Lock through', exact: true }).click();
        await expect(page.getByText(daysAgo(1)).first()).toBeVisible();

        // ── a voucher inside the locked range is refused, inline ──
        await page.goto('/en/dashboard/finance/journals/new');
        await page.locator('#jv-date').fill(backDate);
        await page.locator('#jv-narration').fill('After the lock');
        const blocked = page.locator('tbody tr');
        await pickAccount(blocked.nth(0).locator('td').first(), 'Account', BANK_LEAF);
        await blocked.nth(0).getByLabel('Debit', { exact: true }).fill('100');
        await pickAccount(blocked.nth(1).locator('td').first(), 'Account', CAPITAL);
        await blocked.nth(1).getByLabel('Credit', { exact: true }).fill('100');
        await page.getByRole('button', { name: 'Post', exact: true }).click();
        await expect(page.getByText(/books are locked through/)).toBeVisible();
        expect(page.url(), 'a refused post must not navigate').toContain('/journals/new');

        // ── the same voucher dated today posts without complaint ──
        await page.locator('#jv-date').fill(today());
        await page.getByRole('button', { name: 'Post', exact: true }).click();
        await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/, { timeout: 60_000 });
        record('journal', page.url().split('?')[0].split('/journals/')[1], 'After the lock');
        await expect(page.getByText('Posted', { exact: true }).first()).toBeVisible();

        // ── reversing back into the locked range is refused too ──
        // PostingService#reverse checks the REVERSAL's date, so the refusal is
        // about where the reversing entry would land, not where the original sits.
        await page.goto(backDatedUrl);
        await page.getByRole('button', { name: 'Reverse', exact: true }).click();
        await page.locator('#jv-reverse-date').fill(backDate);
        await page.locator('#jv-reverse-reason').fill('walkthrough');
        await page.locator('div.fixed.inset-0').getByRole('button', { name: 'Reverse', exact: true }).last().click();
        await expect(page.getByText(/books are locked through/)).toBeVisible();
        await hold(page);

        console.log(`\n  manifest: ${manifest.created.length} records created — ${MANIFEST}`);
    } finally {
        await close();
    }
});
