import { test, expect, type Browser, type Locator, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as crypto from 'node:crypto';

/**
 * Accounting v2, plan 2 (lease posting + the PDC register) — every scenario
 * the feature can express, one recording each. Modelled exactly on
 * `accounting-v2-plan1.spec.ts` (read that file's own header first).
 *
 * The recording and the proof are the same run: each scenario asserts its
 * own outcome, and a take only exists because those assertions passed.
 *
 * Runs against the LOCAL dev stack (Next on 3001, backend on 8081) and
 * provisions ONE disposable tenant of its own through the API — a tenant
 * admin, an accountant, a property manager and a renter with a portal
 * login. Nothing here touches production.
 *
 * This spec was written without a running stack (the backend was being
 * changed by a parallel task in the same repo). Every selector and endpoint
 * below is read off the actual source — `web/src/app/[locale]/dashboard/
 * leases/**`, `web/src/components/leases/**`, `web/src/components/cheques/
 * **`, `web/src/lib/api/leasing.ts`, and the matching backend controllers —
 * but none of it has been exercised end to end. Places genuinely uncertain
 * (an exact index, a field name, a status this spec could not confirm by
 * reading) are marked `// VERIFY:`; task-17a-report.md lists every one.
 *
 * Fixture leases, and why there are four:
 *   - LEASE_MAIN: the spine of the story (01-10) — draft -> generate ->
 *     post -> a deposit batch -> clear -> bounce -> replace -> a penalty ->
 *     a cash receipt — then renewed (11), extended (12).
 *   - LEASE_DRYRUN: a throwaway draft used only to force a dry-run error
 *     (03) without ever risking LEASE_MAIN's own grid.
 *   - LEASE_AMEND: a second, untouched posted lease (13) — amending
 *     LEASE_MAIN itself is blocked by then (its cheques left REGISTERED
 *     the moment the first one was deposited), which the scenario proves
 *     before it moves to a lease where amending actually succeeds.
 *   - LEASE_ONLINE: a third lease, generated with ONLINE as its payment
 *     method, for the renter's own Pay button (14).
 */

const BACKEND = process.env.WT_BACKEND_URL || 'http://localhost:8081';
const BASE_URL = process.env.WT_BASE_URL || 'http://localhost:3001';

const TAKES_DIR = path.join(__dirname, 'takes', 'accounting-v2-plan2');
const STATE = path.join(__dirname, 'raw', 'accounting-v2-plan2-state.json');
const SUFFIX = Math.random().toString(36).slice(2, 7);
const MANIFEST = path.join(__dirname, `run-manifest-accounting-v2-plan2-${SUFFIX}.json`);

const PROPERTY = `WT2 Tower ${SUFFIX}`;
const RENTER = `WT2 Renter ${SUFFIX}`;

const pad = (n: number) => String(n).padStart(2, '0');
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const today = () => iso(new Date());
const plusYear = () => {
    const d = new Date();
    d.setFullYear(d.getFullYear() + 1);
    return iso(d);
};

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

function record(kind: string, id: string, label: string) {
    manifest.created.push({ kind, id, label });
    fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2));
    console.log(`  created ${kind}: ${label} (${id})`);
}

// ── provisioning client ─────────────────────────────────────────────────────

type Actor = { id: string; role: string; tenantId: string | null };

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
    renter: { id: string; email: string; password: string };
    propertyId: string;
};

let fx: Fixtures;
/** Filled in by scenario 01, read by every scenario after it. */
let leaseMainId = '';
/** Filled in by scenario 04. */
let leaseMainTco = '';
/** Filled in by scenario 07/08 — the cheque that bounces, then is replaced. */
let bounceChequeId = '';

// ── recording ───────────────────────────────────────────────────────────────

type Take = { page: Page; close: () => Promise<void> };

async function recorded(browser: Browser, takeName: string, opts: { signedIn?: boolean } = {}): Promise<Take> {
    fs.mkdirSync(TAKES_DIR, { recursive: true });
    const context = await browser.newContext({
        baseURL: BASE_URL,
        recordVideo: { dir: TAKES_DIR, size: { width: 1280, height: 720 } },
        ...(opts.signedIn === false ? {} : { storageState: STATE }),
    });
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

async function signIn(page: Page, email: string, password: string) {
    await page.goto('/en/auth/login');
    await page.locator('#login-email').fill(email);
    await page.locator('#login-password').fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await page.waitForURL(/\/dashboard/, { timeout: 60_000 });
}

async function hold(page: Page, ms = 2200) {
    await page.waitForTimeout(ms);
}

/**
 * Pick from a SearchableSelect (web/src/components/ui/SearchableSelect.tsx):
 * the trigger is a button with role="combobox", the search field inside the
 * open dropdown is another, and the results are role="option".
 *
 * `scope` matters. These indices are positional, and the leases page behind
 * the wizard has its own status-filter combobox, so an unscoped nth(0) picks
 * that one up - it sits under the modal overlay, never becomes clickable, and
 * the click hangs until the test times out rather than failing with anything
 * that names the cause. Scoping to the wizard keeps the count to its own
 * fields.
 */
async function pickSearchable(scope: Locator, triggerIndex: number, query: string) {
    const combos = scope.getByRole('combobox');
    await combos.nth(triggerIndex).click();
    const searchBox = scope.getByRole('combobox').nth(triggerIndex + 1);
    await searchBox.fill(query);
    await scope.getByRole('option').filter({ hasText: query }).first().click();
}

// AccountPicker (web/src/components/finance/AccountPicker.tsx) fields —
// e.g. the cheque generator's debitAccountId — are left untouched
// throughout: every one of them is optional and the service fills in a
// property/tenant default (see GenerateChequesRequest's own doc), so there
// is no picker interaction needed anywhere in this spec.

// ── provisioning ────────────────────────────────────────────────────────────

test.describe.configure({ mode: 'serial' });

test('provision a disposable tenant with an admin, an accountant, a manager and a renter', async () => {
    const su = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', {
        email: 'admin@rentaxis.com',
        password: 'admin123',
    });
    const superAdmin: Actor = { id: su.id, role: su.role, tenantId: null };

    const tenantName = `WALKTHROUGH-ACCOUNTING-V2-PLAN2 ${today()} ${SUFFIX}`;
    const tenant = await api<{ id: string }>(superAdmin, 'POST', '/api/admin/tenants', { name: tenantName });
    expect(tenant.id, 'tenant must be created').toBeTruthy();
    record('tenant', tenant.id, tenantName);

    const scoped: Actor = { ...superAdmin, tenantId: tenant.id };
    const password = `Walk!${SUFFIX}9`;
    const make = async (role: string, slug: string, name: string) => {
        const email = `wt2-${slug}-${SUFFIX}@example.invalid`;
        const user = await api<{ id: string }>(scoped, 'POST', '/api/admin/users', {
            name,
            email,
            password,
            role,
            tenantId: tenant.id,
        });
        record('user', user.id, `${email} (${role})`);
        return { id: user.id, email, password };
    };

    const admin = await make('TENANT_ADMIN', 'admin', `Walkthrough Admin ${SUFFIX}`);
    const accountant = await make('ACCOUNTANT', 'accountant', `Walkthrough Accountant ${SUFFIX}`);
    const manager = await make('PROPERTY_MANAGER', 'manager', `Walkthrough Manager ${SUFFIX}`);

    // Chart of accounts + property account template + charge-type catalogue,
    // chained server-side (AccountController#seedDefaultAccounts). Every
    // draft lease's `lines` (RENT, SECURITY_DEPOSIT) need this to resolve a
    // credit account.
    await api(scoped, 'POST', '/api/v1/finance/accounts/seed');

    const property = await api<{ id: string }>(scoped, 'POST', '/api/v1/properties', {
        nameEn: PROPERTY,
        nameAr: PROPERTY,
        address: '1 Register Street, Dubai',
        emirate: 'DUBAI',
        type: 'RESIDENTIAL',
    });
    record('property', property.id, PROPERTY);

    // A PROPERTY_MANAGER with no assignment sees no leases at all:
    // LeaseAccessPolicy scopes them to their assigned properties, and an empty
    // list reads as "nothing". Scenario 15 needs a manager who can genuinely
    // open a lease before it can show which actions they are refused - an
    // unassigned one would be refused everything, for the wrong reason. The
    // property does not exist when the users are created, so this is a second
    // step rather than propertyIds on the create call.
    await api(scoped, 'POST', `/api/admin/users/${manager.id}/properties/${property.id}`);

    const renterEmail = `wt2-renter-${SUFFIX}@example.invalid`;
    const renter = await api<{ id: string; userId: string | null; invitePending: boolean }>(scoped, 'POST', '/api/v1/renters', {
        nameEn: RENTER,
        nameAr: RENTER,
        email: renterEmail,
        phone: '+971500000001',
        primaryLanguage: 'EN',
        createPortalAccount: true,
    });
    // #7: no API returns a password any more; the renter is invited by email
    // and sets their own. The harness cannot read the emailed link (no API
    // exposes the invite token, deliberately), so it takes the admin route that
    // remains: set the renter's password on their user account. That also
    // retires the invite, as a real password would.
    expect(renter.userId, 'a portal account must be created').toBeTruthy();
    expect(renter.invitePending, 'the portal account starts on an emailed invite').toBe(true);
    const renterPassword = `Walk!${SUFFIX}9r`;
    await api(scoped, 'PUT', `/api/admin/users/${renter.userId}`, {
        email: renterEmail,
        password: renterPassword,
        name: RENTER,
        role: 'RENTER',
        tenantId: tenant.id,
        phoneNumber: '+971500000001',
    });
    record('renter', renter.id, RENTER);

    fx = {
        tenantId: tenant.id,
        admin,
        accountant,
        manager,
        renter: { id: renter.id, email: renterEmail, password: renterPassword },
        propertyId: property.id,
    };
});

// ── helper: create a unit + draft lease with lines, for a scenario's own use ──

let adminActor: Actor;

async function adminApi<T>(method: string, apiPath: string, body?: unknown): Promise<T> {
    if (!adminActor) {
        const admin = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', {
            email: fx.admin.email,
            password: fx.admin.password,
        });
        adminActor = { id: admin.id, role: admin.role, tenantId: fx.tenantId };
    }
    return api<T>(adminActor, method, apiPath, body);
}

async function makeUnit(unitNumber: string, expectedRent: number) {
    const unit = await adminApi<{ id: string; unitNumber: string }>('POST', '/api/v1/units', {
        property: { id: fx.propertyId },
        unitNumber,
        type: 'BHK1',
        sizeSqft: 900,
        expectedRent,
    });
    record('unit', unit.id, unitNumber);
    return unit;
}

// ── 01 ──────────────────────────────────────────────────────────────────────

test('01 draft a lease with lines through the wizard', async ({ browser }) => {
    const { page, close } = await recorded(browser, '01-draft-lease-with-lines', { signedIn: false });
    try {
        await signIn(page, fx.admin.email, fx.admin.password);
        await page.context().storageState({ path: STATE });

        const unit = await makeUnit(`WT2-${SUFFIX}-A`, 12_000);

        await page.goto('/en/dashboard/leases');
        await page.getByRole('button', { name: /add|create|new|draft/i }).first().click();

        // Step 1: parties.
        // Scoped to the wizard: the leases page behind the modal has a status
        // filter that is also a combobox, and it would otherwise be nth(0).
        const wizard = page.getByTestId('lease-wizard');
        await pickSearchable(wizard, 0, unit.unitNumber);
        await pickSearchable(wizard, 1, RENTER);
        await page.getByTestId('wizard-next').click();

        // Step 2: terms.
        await page.getByTestId('wizard-start-date').fill(today());
        await page.getByTestId('wizard-end-date').fill(plusYear());
        await page.getByTestId('wizard-next').click();

        // Step 3: lines — RENT (48,000, four 12,000 cheques) + SECURITY_DEPOSIT
        // (10,000, one cheque) so the deposit is distinguishable by amount and
        // has something real to carry forward at renewal (scenario 11).
        await page.getByTestId('lease-line-type-0').selectOption({ label: 'Rent' });
        await page.getByTestId('lease-line-amount-0').fill('48000');
        await page.getByTestId('lease-lines-add').click();
        await page.getByTestId('lease-line-type-1').selectOption({ label: 'Security Deposit' });
        await page.getByTestId('lease-line-amount-1').fill('10000');
        await expect(page.getByTestId('lease-lines-contract-value')).toContainText('58,000');

        // "Save Draft" — POST /leases (the wizard is a modal over
        // /dashboard/leases, so the URL never carries the id; pin the
        // create response itself instead, plan 1's own pattern).
        const [createRes] = await Promise.all([
            page.waitForResponse((r) => /\/api\/proxy\/v1\/leases$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('wizard-next').click(),
        ]);
        // 201 Created: LeaseController.createDraftLease answers with
        // HttpStatus.CREATED, unlike post/renew/extend/amend which are 200.
        expect(createRes.status(), 'the draft must actually be created').toBe(201);
        const draft = await createRes.json();
        leaseMainId = draft.id;
        expect(leaseMainId, 'draft lease id').toBeTruthy();
        record('lease', leaseMainId, 'LEASE_MAIN (draft)');

        await expect(page.getByTestId('cheque-grid')).toBeVisible({ timeout: 10_000 });
        await expect(page.getByTestId('wizard-review')).toHaveCount(0);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 02 ──────────────────────────────────────────────────────────────────────

test('02 generate the cheque grid — rent split evenly, the deposit its own row', async ({ browser }) => {
    const { page, close } = await recorded(browser, '02-cheque-grid-generation');
    try {
        await page.goto(`/en/dashboard/leases/${leaseMainId}`);
        await expect(page.getByTestId('lease-status')).toBeVisible();

        await page.getByTestId('cheque-grid-generate').click();
        await expect(page.getByTestId('cheque-generate-form')).toBeVisible();
        // Installments already defaults to the lease's own paymentTerms (4).
        // No folding: the deposit gets its own dedicated row instead of
        // riding along with cheque #1 (ChequeGenerationService's own default
        // is to fold; unchecking is what asks for the opposite).
        await page.getByLabel('Fold deposits and fees into the first cheque').uncheck();
        await page.getByTestId('cheque-generate-confirm').click();

        const rows = page.locator('[data-testid^="cheque-row-"]');
        await expect(rows).toHaveCount(5, { timeout: 10_000 });

        // A freshly generated grid is editable, so each amount is a NumberInput
        // (type="number"). Its value is a property, not text, so hasText cannot
        // see it - the amounts have to be read off the inputs. Sorted, so the
        // deposit row's position stays irrelevant, which was the VERIFY here.
        const amountInputs = rows.locator('input[type="number"][aria-label*="mount"]');
        await expect(amountInputs).toHaveCount(5);
        const amounts = (
            await amountInputs.evaluateAll((els) => els.map((el) => Number((el as HTMLInputElement).value)))
        ).sort((a, b) => a - b);
        expect(amounts, 'four rent cheques of 12,000 and the deposit on its own 10,000 row').toEqual([
            10_000, 12_000, 12_000, 12_000, 12_000,
        ]);
        await expect(page.getByTestId('cheque-grid-total')).toContainText('58,000');
        await expect(page.getByTestId('cheque-grid-match')).toHaveAttribute('data-match', 'true');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 03 ──────────────────────────────────────────────────────────────────────

test('03 a dry run reports validation errors and writes nothing', async ({ browser }) => {
    const { page, close } = await recorded(browser, '03-dry-run-validation-errors');
    try {
        const unit = await makeUnit(`WT2-${SUFFIX}-B`, 12_000);
        const draft = await adminApi<{ id: string }>('POST', '/api/v1/leases', {
            unitId: unit.id,
            renterId: fx.renter.id,
            startDate: today(),
            endDate: plusYear(),
            paymentTerms: 4,
            paymentMethod: 'CHEQUE',
            depositPaymentMethod: 'CHEQUE',
            lines: [{ chargeTypeCode: 'RENT', grossAmount: 20_000 }],
        });
        record('lease', draft.id, 'LEASE_DRYRUN (draft, deliberately broken)');
        await adminApi('POST', `/api/v1/leases/${draft.id}/cheques/generate`, { installments: 4 });
        // Break the grid: one cheque short, so Σ cheques != contract value.
        //
        // PUT /cheques replaces each row wholesale, so every field the row
        // carries has to be sent back, not just the ones being changed - a row
        // without its chequeDate is refused outright ("a post-dated cheque needs
        // the date written on it") and the scenario never reaches its dry run.
        type ChequeRow = {
            id: string;
            seqNo: number;
            amount: number;
            mode: string;
            debitAccountId: string | null;
            narration: string | null;
            postingDate: string | null;
            chequeNumber: string | null;
            chequeDate: string | null;
            payeeBank: string | null;
            payerName: string | null;
        };
        const cheques = await adminApi<ChequeRow[]>('GET', `/api/v1/leases/${draft.id}/cheques`);
        const rows = cheques.map((c) => ({
            id: c.id,
            seqNo: c.seqNo,
            amount: c.seqNo === 1 ? c.amount - 500 : c.amount,
            debitAccountId: c.debitAccountId,
            narration: c.narration,
            mode: c.mode,
            postingDate: c.postingDate,
            chequeNumber: c.chequeNumber,
            chequeDate: c.chequeDate,
            payeeBank: c.payeeBank,
            payerName: c.payerName,
        }));
        await adminApi('PUT', `/api/v1/leases/${draft.id}/cheques`, rows);

        await page.goto(`/en/dashboard/leases/${draft.id}`);
        await expect(page.getByTestId('cheque-grid-match')).toHaveAttribute('data-match', 'false');
        await page.getByTestId('lease-post').click();
        await expect(page.getByTestId('post-dry-run-errors')).toBeVisible({ timeout: 10_000 });
        await expect(page.getByTestId('post-lease-confirm')).toBeDisabled();

        // Nothing was written: close the dialog and the lease is still DRAFT.
        // LeaseDialog's own footer cancel. Not getByRole('button', {name:
        // 'Cancel'}): the dialog's icon close carries the same accessible name,
        // and on a lease page every cheque row has its own Cancel action too -
        // six of them matched here. Only one LeaseDialog is open at a time, so
        // this testid is unambiguous.
        await page.getByTestId('lease-dialog-cancel').click();
        await page.reload();
        await expect(page.getByTestId('lease-status')).toHaveText(/draft/i);

        const stillDraft = await adminApi<{ status: string; postedAt: string | null }>('GET', `/api/v1/leases/${draft.id}`);
        expect(stillDraft.status).toBe('DRAFT');
        expect(stillDraft.postedAt).toBeNull();
        await hold(page);
    } finally {
        await close();
    }
});

// ── 04 ──────────────────────────────────────────────────────────────────────

test('04 post LEASE_MAIN — the TCO journal and the tenant ledger', async ({ browser }) => {
    const { page, close } = await recorded(browser, '04-post-journal-and-ledger');
    try {
        await page.goto(`/en/dashboard/leases/${leaseMainId}`);
        await page.getByTestId('lease-post').click();
        await expect(page.getByTestId('post-dry-run-ok')).toBeVisible({ timeout: 10_000 });

        const [postRes] = await Promise.all([
            page.waitForResponse((r) => /\/leases\/[0-9a-f-]{36}\/post$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('post-lease-confirm').click(),
        ]);
        expect(postRes.status()).toBe(200);
        const posted = await postRes.json();
        leaseMainTco = posted.tcoEntryNumber;
        expect(leaseMainTco).toBeTruthy();
        record('journal', posted.tcoJournalId, `TCO ${leaseMainTco} — LEASE_MAIN`);

        await expect(page.getByTestId('lease-banner')).toBeVisible({ timeout: 10_000 });
        await expect(page.getByTestId('lease-status')).toHaveText(/active/i);
        // The link reads "View posting journal · Posted <date>" - the entry
        // number is not in its text. What matters here is that it points at the
        // journal THIS posting created; the number itself is asserted on the
        // journal page below, after following it.
        await expect(page.getByTestId('lease-posting-journal')).toHaveAttribute(
            'href',
            new RegExp(posted.tcoJournalId),
        );

        await page.getByTestId('lease-posting-journal').click();
        await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/, { timeout: 15_000 });
        await expect(page.getByText(leaseMainTco).first()).toBeVisible();
        await expect(page.getByText('Posted', { exact: true }).first()).toBeVisible();

        // The tenant ledger — scoped to this one lease so no other fixture
        // data can be mistaken for it. The page is opened so the recording
        // shows it, but the assertion reads the API directly: a response
        // captured across page.reload() has its body evicted by the time it can
        // be read ("No resource with given identifier found").
        await page.goto(`/en/dashboard/finance/tenant-ledger?renterId=${fx.renter.id}&leaseId=${leaseMainId}`);
        await expect(page).toHaveURL(/tenant-ledger/);
        const ledgers = await adminApi<unknown[]>('GET', `/api/v1/finance/ledger/renter/${fx.renter.id}`);
        expect(Array.isArray(ledgers) ? ledgers.length : 0, 'posting must leave entries on the tenant ledger').toBeGreaterThan(0);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 05 ──────────────────────────────────────────────────────────────────────

test('05 a deposit batch — two REGISTERED rent cheques into the bank in one act', async ({ browser }) => {
    const { page, close } = await recorded(browser, '05-deposit-batch');
    try {
        const cheques = await adminApi<Array<{ id: string; amount: number; mode: string; seqNo: number }>>(
            'GET',
            `/api/v1/leases/${leaseMainId}/cheques`,
        );
        const rentRows = cheques.filter((c) => c.mode === 'PDC' && c.amount === 12_000).sort((a, b) => a.seqNo - b.seqNo);
        expect(rentRows.length, 'four 12,000 rent cheques from scenario 02').toBe(4);

        // Only matured paper reaches the deposit run: findToDeposit requires
        // chequeDate <= today, and a lease that starts today has exactly one
        // instalment mature, so there would be nothing to BATCH. Bring the
        // second instalment forward through the register's own edit endpoint -
        // a landlord may well hold a cheque dated today. Unlike PUT
        // /leases/{id}/cheques, which replaces a row wholesale, this one
        // patches the fields it is given.
        await adminApi('PUT', `/api/v1/cheques/${rentRows[1].id}/details`, { chequeDate: today() });

        await page.goto('/en/dashboard/finance/cheques/collection');
        await page.waitForLoadState('networkidle');
        await page.getByTestId(`collection-select-${rentRows[0].id}`).check();
        await page.getByTestId(`collection-select-${rentRows[1].id}`).check();
        await expect(page.getByTestId('collection-selected-total')).toContainText('24,000');

        await page.getByTestId('collection-deposit-selected').click();
        await page.getByTestId('deposit-batch-date').fill(today());
        await expect(page.getByTestId('deposit-batch-total')).toContainText('2');
        await page.getByTestId('deposit-batch-confirm').click();
        await expect(page.getByTestId(`collection-select-${rentRows[0].id}`)).toHaveCount(0, { timeout: 10_000 });

        const after = await adminApi<Array<{ id: string; status: string }>>('GET', `/api/v1/leases/${leaseMainId}/cheques`);
        expect(after.find((c) => c.id === rentRows[0].id)?.status).toBe('DEPOSITED');
        expect(after.find((c) => c.id === rentRows[1].id)?.status).toBe('DEPOSITED');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 06 ──────────────────────────────────────────────────────────────────────

test('06 clear a deposited cheque', async ({ browser }) => {
    const { page, close } = await recorded(browser, '06-cheque-clear');
    try {
        const cheques = await adminApi<Array<{ id: string; status: string; mode: string; amount: number }>>(
            'GET',
            `/api/v1/leases/${leaseMainId}/cheques`,
        );
        const deposited = cheques.filter((c) => c.status === 'DEPOSITED');
        expect(deposited.length, 'scenario 05 must leave two DEPOSITED cheques').toBeGreaterThanOrEqual(2);
        const clearRow = deposited[0];

        await page.goto('/en/dashboard/finance/cheques');
        await page.waitForLoadState('networkidle');
        await page.getByTestId(`cheque-row-action-clear-${clearRow.id}`).click();
        // Wait for the clear itself rather than for a button. `bounce` is
        // offered on a DEPOSITED row too, so its visibility proves nothing, and
        // the API read below can otherwise beat the request - the first run's
        // trace ends with this PUT still in flight and the row still DEPOSITED.
        const [clearRes] = await Promise.all([
            page.waitForResponse((r) => /\/cheques\/[0-9a-f-]{36}\/clear$/.test(r.url()) && r.request().method() === 'PUT'),
            page.getByTestId('cheque-clear-confirm').click(),
        ]);
        expect(clearRes.status()).toBe(200);
        await expect(page.getByTestId(`cheque-row-action-bounce-${clearRow.id}`)).toBeVisible({ timeout: 10_000 });

        const after = await adminApi<{ status: string }>('GET', `/api/v1/cheques/${clearRow.id}`);
        expect(after.status).toBe('CLEARED');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 07 ──────────────────────────────────────────────────────────────────────

test('07 bounce the second deposited cheque', async ({ browser }) => {
    const { page, close } = await recorded(browser, '07-cheque-bounce');
    try {
        const cheques = await adminApi<Array<{ id: string; status: string }>>('GET', `/api/v1/leases/${leaseMainId}/cheques`);
        const deposited = cheques.find((c) => c.status === 'DEPOSITED');
        expect(deposited, 'one DEPOSITED cheque must remain after scenario 06').toBeTruthy();
        bounceChequeId = deposited!.id;

        await page.goto('/en/dashboard/finance/cheques');
        await page.waitForLoadState('networkidle');
        await page.getByTestId(`cheque-row-action-bounce-${bounceChequeId}`).click();
        // BounceChequeDialog defaults failureReason to BOUNCE already.
        // Same reasoning as scenario 06: pin the transition, not a button.
        const [bounceRes] = await Promise.all([
            page.waitForResponse((r) => /\/cheques\/[0-9a-f-]{36}\/bounce$/.test(r.url()) && r.request().method() === 'PUT'),
            page.getByTestId('cheque-bounce-confirm').click(),
        ]);
        expect(bounceRes.status()).toBe(200);
        await expect(page.getByTestId(`cheque-row-action-replace-${bounceChequeId}`)).toBeVisible({ timeout: 10_000 });

        const after = await adminApi<{ status: string }>('GET', `/api/v1/cheques/${bounceChequeId}`);
        expect(after.status).toBe('BOUNCED');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 08 ──────────────────────────────────────────────────────────────────────

test('08 replace the bounced cheque with a fresh instrument', async ({ browser }) => {
    const { page, close } = await recorded(browser, '08-cheque-replace');
    try {
        await page.goto('/en/dashboard/finance/cheques');
        await page.waitForLoadState('networkidle');
        await page.getByTestId(`cheque-row-action-replace-${bounceChequeId}`).click();
        // ReplaceChequeDialog seeds row 0's amount to the bounced cheque's own
        // amount already (blankRow(0, cheque.amount)) — a like-for-like
        // replacement needs only the confirm.
        // Same reasoning as scenario 06. replace returns the pair of rows
        // (the superseded one and its replacement), so 200 with a list.
        const [replaceRes] = await Promise.all([
            page.waitForResponse((r) => /\/cheques\/[0-9a-f-]{36}\/replace$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('replace-confirm').click(),
        ]);
        expect(replaceRes.status()).toBe(200);
        await expect(page.getByTestId(`cheque-row-action-replace-${bounceChequeId}`)).toHaveCount(0, { timeout: 10_000 });

        const after = await adminApi<{ status: string; replacedById: string | null }>('GET', `/api/v1/cheques/${bounceChequeId}`);
        expect(after.status).toBe('REPLACED');
        expect(after.replacedById, 'the bounce must be superseded by a new row, not edited in place').toBeTruthy();
        await hold(page);
    } finally {
        await close();
    }
});

// ── 09 ──────────────────────────────────────────────────────────────────────

test('09 propose, approve and collect a penalty for the bounce', async ({ browser }) => {
    const { page, close } = await recorded(browser, '09-penalty-propose-approve-collect');
    try {
        await page.goto(`/en/dashboard/leases/${leaseMainId}`);
        await page.getByTestId('lease-tab-penalties').click();
        await page.getByTestId('penalty-propose-open').click();
        // The tab opens RaisePenaltyDialog (#12), whose fields carry plain
        // `id`s, not data-testids — #raise-penalty-reason/-amount/-narration.
        // The incident date defaults to today.
        await page.locator('#raise-penalty-reason').selectOption({ label: 'Cheque Return' });
        await page.locator('#raise-penalty-amount').fill('500');
        await page.locator('#raise-penalty-narration').fill(`WT2 bounced cheque ${SUFFIX}`);

        const [proposeRes] = await Promise.all([
            page.waitForResponse((r) => /\/api\/proxy\/v1\/penalties$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('raise-penalty-confirm').click(),
        ]);
        expect(proposeRes.status()).toBe(201);
        const proposed = await proposeRes.json();
        expect(proposed.status).toBe('PROPOSED');
        record('penalty', proposed.id, `CHEQUE_RETURN 500 — LEASE_MAIN`);

        await expect(page.getByTestId('penalty-row-0')).toBeVisible({ timeout: 10_000 });

        // Approving is the finance worklist's own action (canApprovePenalties,
        // narrower than canProposePenalties) — same table, reached from
        // Finance -> Penalties rather than the lease's own tab.
        await page.goto('/en/dashboard/finance/penalties');
        await expect(page.getByTestId('penalty-queue')).toBeVisible();
        await page.getByTestId('penalty-tab-PROPOSED').click();
        // The queue's columns are renter, property, cheque number, reason,
        // amount and date - the proposer's description is NOT among them (see
        // PenaltyQueue.tsx), so the row cannot be found by it. The renter name
        // carries this run's random suffix, so it identifies the row uniquely.
        // `penalty-approve-${i}` is positional, which is why the row is located
        // first and its own Approve button taken from within it.
        const row = page.locator('[data-testid^="penalty-row-"]').filter({ hasText: RENTER }).first();
        await expect(row, 'the proposed penalty must reach the finance queue').toBeVisible({ timeout: 10_000 });
        await row.getByRole('button', { name: 'Approve' }).click();
        const [approveRes] = await Promise.all([
            page.waitForResponse((r) => /\/penalties\/[0-9a-f-]{36}\/approve$/.test(r.url())),
            page.getByTestId('penalty-approve-confirm').click(),
        ]);
        expect(approveRes.status()).toBe(200);

        // PenaltyAssessmentController has no single-resource GET — read the
        // decided row back off the list, filtered to this lease.
        const approvedList = await adminApi<{ content: Array<{ id: string; status: string; collectionChequeId: string | null }> }>(
            'GET',
            `/api/v1/penalties?leaseId=${leaseMainId}&status=APPROVED&size=50`,
        );
        const approvedFound = approvedList.content.find((p) => p.id === proposed.id);
        expect(approvedFound, 'the proposed penalty must show up APPROVED').toBeTruthy();
        const approved = approvedFound!;
        expect(approved.status).toBe('APPROVED');
        expect(approved.collectionChequeId, 'approving must open a CASH collection row').toBeTruthy();
        const collectionChequeId = approved.collectionChequeId!;

        // Collect the fine: the collection row is CASH, REGISTERED ->
        // CLEARED via `receive`, not `deposit`.
        await page.goto('/en/dashboard/finance/cheques');
        await page.waitForLoadState('networkidle');
        await page.getByTestId(`cheque-row-action-receive-${collectionChequeId}`).click();
        // receive is a PUT, like clear and bounce; wait for it rather than for
        // the row to disappear, which can be read before the write lands.
        const [receiveRes] = await Promise.all([
            page.waitForResponse((r) => /\/cheques\/[0-9a-f-]{36}\/receive$/.test(r.url()) && r.request().method() === 'PUT'),
            page.getByTestId('cheque-receive-confirm').click(),
        ]);
        expect(receiveRes.status()).toBe(200);
        await expect(page.getByTestId(`cheque-row-action-receive-${collectionChequeId}`)).toHaveCount(0, { timeout: 10_000 });

        const collection = await adminApi<{ status: string }>('GET', `/api/v1/cheques/${collectionChequeId}`);
        expect(collection.status).toBe('CLEARED');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 10 ──────────────────────────────────────────────────────────────────────

test('10 a cash receipt — a CASH row created and received in one act', async ({ browser }) => {
    const { page, close } = await recorded(browser, '10-cash-receipt');
    try {
        await page.goto('/en/dashboard/finance/cheques');
        await page.waitForLoadState('networkidle');
        await page.getByTestId('open-cash-receipt').click();
        await page.getByTestId('cash-receipt-lease-search').fill(RENTER);
        await page.getByTestId(`cash-receipt-lease-option-${leaseMainId}`).click();
        await expect(page.getByTestId('cash-receipt-selected-lease')).toBeVisible();
        await page.getByTestId('cash-receipt-amount').fill('12000');

        const [receiptRes] = await Promise.all([
            page.waitForResponse((r) => /\/cheques\/lease\/[0-9a-f-]{36}\/cash-receipt$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('cash-receipt-confirm').click(),
        ]);
        expect(receiptRes.status()).toBe(200);
        const receipt = await receiptRes.json();
        expect(receipt.status, 'a cash/transfer receipt is created and received in one call').toBe('CLEARED');
        expect(receipt.mode).toBe('CASH');
        await expect(page.getByTestId('cash-receipt-selected-lease')).toHaveCount(0, { timeout: 10_000 });
        await hold(page);
    } finally {
        await close();
    }
});

// ── 11 ──────────────────────────────────────────────────────────────────────

test('11 renew LEASE_MAIN, carrying the deposit forward', async ({ browser }) => {
    const { page, close } = await recorded(browser, '11-renew-carry-deposit-forward');
    try {
        await page.goto(`/en/dashboard/leases/${leaseMainId}`);
        await page.getByTestId('lease-renew').click();

        // RenewLeaseDialog defaults start/end to the day after the current
        // term and a year on; copyLines and carryDepositForward both default
        // checked — this scenario is exactly that default path.
        await expect(page.getByTestId('renew-copy-lines')).toBeChecked();
        await expect(page.getByTestId('renew-carry-deposit')).toBeChecked();

        const [renewRes] = await Promise.all([
            page.waitForResponse((r) => /\/leases\/[0-9a-f-]{36}\/renew$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('renew-lease-confirm').click(),
        ]);
        expect(renewRes.status()).toBe(200);
        const successor = await renewRes.json();
        expect(successor.status).toBe('DRAFT');
        expect(successor.renewedFromLeaseId).toBe(leaseMainId);
        record('lease', successor.id, 'LEASE_MAIN successor (renewal)');

        await page.waitForURL(new RegExp(`/dashboard/leases/${successor.id}$`), { timeout: 15_000 });
        await expect(page.getByTestId('lease-renewed-from')).toBeVisible();
        await expect(page.getByTestId('lease-status')).toHaveText(/draft/i);

        // The predecessor keeps running: `renew` only drafts the successor.
        // RENEWED is set by LeasePostingService.markPredecessorRenewed, and that
        // fires when the SUCCESSOR is posted (LeasePostingService:178) - which
        // this scenario never does, so ACTIVE is right. (`/renewal/mark-renewed`
        // closes the renewal OPPORTUNITY, not the lease status.) Scenario 12
        // depends on this holding: extend refuses anything but ACTIVE.
        const original = await adminApi<{ status: string }>('GET', `/api/v1/leases/${leaseMainId}`);
        expect(original.status, 'the original stays ACTIVE until the successor is posted').toBe('ACTIVE');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 12 ──────────────────────────────────────────────────────────────────────

test('12 extend LEASE_MAIN — a fresh TCO for the extension period alone', async ({ browser }) => {
    const { page, close } = await recorded(browser, '12-extend-lease');
    try {
        await page.goto(`/en/dashboard/leases/${leaseMainId}`);
        await page.getByTestId('lease-extend').click();

        const d = new Date(plusYear());
        d.setMonth(d.getMonth() + 2);
        const newEndDate = iso(d);
        await page.getByTestId('extend-new-end-date').fill(newEndDate);

        // One extension line, one matching cheque — ExtendLeaseDialog's own
        // `matches` gate needs Σ cheques == the extension lines' VAT-inclusive
        // total, and RENT is VAT-exempt by this tenant's default.
        await page.getByTestId('lease-line-type-0').selectOption({ label: 'Rent' });
        await page.getByTestId('lease-line-amount-0').fill('8000');
        const chequeTable = page.getByTestId('extend-cheque-grid');
        await chequeTable.getByLabel(/^Amount 1$/).fill('8000');
        // ExtendLeaseDialog seeds its row with a postingDate but no cheque
        // date, and its confirm gate checks only the dates, the match and the
        // lines - so submitting without one reaches the server and comes back
        // as a raw 400 ("a post-dated cheque needs the date written on it").
        // The instalment falls in the extension window, which opens the day
        // after the current end date. (Leasing.chequeDate renders as "Date".)
        const extWindowStart = new Date(plusYear());
        extWindowStart.setDate(extWindowStart.getDate() + 1);
        await chequeTable.getByLabel(/^Date 1$/).fill(iso(extWindowStart));
        await expect(page.getByTestId('extend-match')).toHaveAttribute('data-match', 'true', { timeout: 10_000 });

        const [extendRes] = await Promise.all([
            page.waitForResponse((r) => /\/leases\/[0-9a-f-]{36}\/extend$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('extend-lease-confirm').click(),
        ]);
        expect(extendRes.status()).toBe(200);
        const extended = await extendRes.json();
        expect(extended.lease.status).toBe('ACTIVE');
        expect(extended.lease.endDate).toBe(newEndDate);
        record('journal', extended.tcoJournalId, `TCO — LEASE_MAIN extension`);

        // The dialog closes and the page reloads the lease — the ribbon's own
        // End Date reads back the new figure.
        await expect(page.getByTestId('extend-lease-confirm')).toHaveCount(0, { timeout: 10_000 });
        await hold(page);
    } finally {
        await close();
    }
});

// ── 13 ──────────────────────────────────────────────────────────────────────

test('13 amend lines — blocked once a cheque has left REGISTERED, otherwise it succeeds', async ({ browser }) => {
    const { page, close } = await recorded(browser, '13-amend-lines-block-then-succeed');
    try {
        // LEASE_MAIN's grid has DEPOSITED/CLEARED/BOUNCED/REPLACED rows from
        // 05-08 — amendBlockedBy (AmendLinesDialog.tsx) refuses the moment any
        // cheque is not REGISTERED.
        await page.goto(`/en/dashboard/leases/${leaseMainId}`);
        await page.getByTestId('lease-amend').click();
        await expect(page.getByTestId('amend-blocked')).toBeVisible({ timeout: 10_000 });
        await expect(page.getByTestId('amend-lines-confirm')).toBeDisabled();
        // The dialog's footer cancel (see scenario 03) - this page also has a
        // Cancel action on every cheque row.
        await page.getByTestId('lease-dialog-cancel').click();

        // A second, untouched lease — every cheque still REGISTERED — is
        // where amending actually goes through.
        const unit = await makeUnit(`WT2-${SUFFIX}-C`, 9_000);
        const draft = await adminApi<{ id: string }>('POST', '/api/v1/leases', {
            unitId: unit.id,
            renterId: fx.renter.id,
            startDate: today(),
            endDate: plusYear(),
            paymentTerms: 1,
            paymentMethod: 'CHEQUE',
            depositPaymentMethod: 'CHEQUE',
            lines: [{ chargeTypeCode: 'RENT', grossAmount: 9_000 }],
        });
        await adminApi('POST', `/api/v1/leases/${draft.id}/cheques/generate`, { installments: 1 });
        const posted = await adminApi<{ lease: { id: string } }>('POST', `/api/v1/leases/${draft.id}/post`);
        record('lease', posted.lease.id, 'LEASE_AMEND');

        await page.goto(`/en/dashboard/leases/${posted.lease.id}`);

        await page.getByTestId('lease-amend').click();
        await expect(page.getByTestId('amend-blocked')).toHaveCount(0);

        // Amend REDISTRIBUTES the lines; it cannot change what the lease is
        // worth. The cheques are not part of this dialog, so raising the total
        // would leave the grid short and the server refuses ("Cheque grid
        // totals 9,000.00 but contract value is 9,500.00") - see issue #267.
        // The real correction amend is for: part of what was booked as rent was
        // actually an admin fee. Σ stays 9,000, so the single cheque still
        // covers it.
        await page.getByTestId('lease-line-amount-0').fill('8000');
        await page.getByTestId('lease-lines-add').click();
        await page.getByTestId('lease-line-type-1').selectOption({ label: 'Admin Fee' });
        await page.getByTestId('lease-line-amount-1').fill('1000');
        await page.getByTestId('amend-reason').fill(`WT2 rent reclassified as admin fee ${SUFFIX}`);

        const [amendRes] = await Promise.all([
            page.waitForResponse((r) => /\/leases\/[0-9a-f-]{36}\/amend-lines$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('amend-lines-confirm').click(),
        ]);
        expect(amendRes.status()).toBe(200);
        const amended = await amendRes.json();
        expect(amended.lease.contractValue, 'an amend reclassifies, it does not re-price').toBe(9_000);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 14 ──────────────────────────────────────────────────────────────────────

test('14 the renter pays a due cheque online', async ({ browser }) => {
    const { page, close } = await recorded(browser, '14-renter-pays-online', { signedIn: false });
    try {
        // A lease with a cheque due today. `onlinePayApi.createOrder` gates on
        // `due && onlineEnabled`, not on the row's own mode (PayOnlineButton
        // has no mode check either) — an ordinary PDC installment is payable
        // online same as one generated with mode ONLINE.
        const unit = await makeUnit(`WT2-${SUFFIX}-D`, 6_000);
        const draft = await adminApi<{ id: string }>('POST', '/api/v1/leases', {
            unitId: unit.id,
            renterId: fx.renter.id,
            startDate: today(),
            endDate: plusYear(),
            paymentTerms: 1,
            firstDueDate: today(),
            paymentMethod: 'CHEQUE',
            depositPaymentMethod: 'CHEQUE',
            lines: [{ chargeTypeCode: 'RENT', grossAmount: 6_000 }],
        });
        await adminApi('POST', `/api/v1/leases/${draft.id}/cheques/generate`, { installments: 1, firstDueDate: today() });
        const posted = await adminApi<{ lease: { id: string }; cheques: Array<{ id: string; amount: number }> }>(
            'POST',
            `/api/v1/leases/${draft.id}/post`,
        );
        record('lease', posted.lease.id, 'LEASE_ONLINE');
        const dueChequeId = posted.cheques[0].id;

        // Configure a gateway with a webhook secret this spec also holds —
        // the local tenant has no real Razorpay sandbox keys, so these are
        // synthetic, and the webhook capture below is only reachable because
        // the HMAC signature below is computed with the SAME secret this
        // POST sets, not because Razorpay itself is involved.
        const webhookSecret = `wt2-whsec-${SUFFIX}`;
        const gateways = await adminApi<Array<{ id: string; code: string }>>('GET', '/api/v1/gateway-config/gateways');
        const razorpay = gateways.find((g) => g.code === 'RAZORPAY');
        expect(razorpay, 'a RAZORPAY gateway must be registered').toBeTruthy();
        await adminApi('POST', '/api/v1/gateway-config', {
            gatewayId: razorpay!.id,
            apiKey: `rzp_test_wt2_${SUFFIX}`,
            apiSecret: `wt2_secret_${SUFFIX}`,
            webhookSecret,
            isActive: true,
            isTestMode: true,
        });
        // VERIFY: RentCollectionSettingsDTO may require its other fields
        // (grace days, penalty knobs) on every POST rather than merging a
        // partial body — if so this needs the full settings shape, read
        // back from GET first.
        await adminApi('POST', `/api/v1/rent-settings/${fx.propertyId}`, { onlinePaymentEnabled: true });

        await signIn(page, fx.renter.email, fx.renter.password);
        await page.goto('/en/dashboard/renter-portal/payments');
        await expect(page.getByTestId(`due-row-${dueChequeId}`)).toBeVisible({ timeout: 10_000 });

        // Asserted, not probed: this scenario exists to show a renter paying
        // online, and an early return on a missing button would let it "pass"
        // having proved nothing. The rent-settings POST above is what offers it.
        const payBtn = page.getByTestId(`pay-online-${dueChequeId}`);
        await expect(payBtn, 'the renter must be offered online payment').toBeVisible({ timeout: 10_000 });

        const [orderRes] = await Promise.all([
            page.waitForResponse((r) => /\/api\/proxy\/v1\/online-payments\/create-order$/.test(r.url()) && r.request().method() === 'POST'),
            payBtn.click(),
        ]);
        // The stack runs with rentaxis.gateway.stub.enabled=true, so
        // StubRazorpayProvider answers for RAZORPAY: order creation is synthetic
        // and nothing is sent to Razorpay. (The real RazorpayProvider calls
        // client.orders.create here, which would throw on these synthetic keys -
        // this scenario cannot run against it.) Signature verification is NOT
        // stubbed, so the webhook below still has to be signed correctly.
        expect(orderRes.status(), 'createOrder must succeed against the stubbed gateway').toBe(200);
        const order = await orderRes.json();
        expect(order.orderId).toBeTruthy();
        expect(order.amount).toBeTruthy();
        record('online-payment-order', order.orderId, `LEASE_ONLINE cheque ${dueChequeId}`);
        console.log(`  order created: ${order.orderId} — ${order.amount} ${order.currency}, gateway key ${order.gatewayKey}`);

        // The checkout script itself (checkout.razorpay.com) will not load a
        // real payment sheet for a synthetic key, so this cannot click
        // through to a real capture. Prove the rest of the path instead: a
        // webhook signed with the SAME secret the gateway config was just
        // given, the way WebhookService#verifyDelivery checks it.
        const payload = JSON.stringify({
            event: 'payment.captured',
            payload: {
                payment: {
                    entity: {
                        id: `pay_wt2_${SUFFIX}`,
                        order_id: order.orderId,
                        amount: order.amount,
                        currency: order.currency,
                    },
                },
            },
        });
        const signature = crypto.createHmac('sha256', webhookSecret).update(payload).digest('hex');
        const webhookRes = await fetch(`${BACKEND}/api/webhooks/razorpay`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Razorpay-Signature': signature },
            body: payload,
        });
        // The shape, the minor-unit amount and the currency are all checked
        // against the order: WebhookService reads payload.payment.entity
        // .{order_id,id,amount,currency} and OnlinePaymentService compares the
        // reported minor units against minorUnits(ordered) = amount * 100, which
        // is exactly what createOrder reported above. Signed with this tenant's
        // own webhook secret, so it passes the gate in WebhookService. This is
        // the money moving, so it is asserted rather than logged.
        expect(webhookRes.status, 'the signed webhook must be accepted').toBe(200);
        const afterWebhook = await adminApi<{ status: string }>('GET', `/api/v1/cheques/${dueChequeId}`);
        expect(afterWebhook.status, 'the captured payment must clear its register row').toBe('CLEARED');
        console.log(`  webhook accepted; cheque now ${afterWebhook.status}`);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 15 ──────────────────────────────────────────────────────────────────────

test('15 an accountant may post/extend a lease; a property manager may not', async ({ browser }) => {
    const { page, close } = await recorded(browser, '15-accountant-vs-property-manager', { signedIn: false });
    try {
        // A DRAFT lease with a clean cheque grid — canPostLeases (SA/TA/
        // ACCOUNTANT, not PROPERTY_MANAGER) is what this scenario tells apart.
        const unit = await makeUnit(`WT2-${SUFFIX}-E`, 7_000);
        const draft = await adminApi<{ id: string }>('POST', '/api/v1/leases', {
            unitId: unit.id,
            renterId: fx.renter.id,
            startDate: today(),
            endDate: plusYear(),
            paymentTerms: 1,
            paymentMethod: 'CHEQUE',
            depositPaymentMethod: 'CHEQUE',
            lines: [{ chargeTypeCode: 'RENT', grossAmount: 7_000 }],
        });
        await adminApi('POST', `/api/v1/leases/${draft.id}/cheques/generate`, { installments: 1 });
        record('lease', draft.id, 'LEASE_RBAC (draft)');

        // ── ACCOUNTANT ──
        await signIn(page, fx.accountant.email, fx.accountant.password);
        await page.goto(`/en/dashboard/leases/${draft.id}`);
        await expect(page.getByTestId('lease-post')).toBeVisible();

        await page.goto(`/en/dashboard/leases/${leaseMainId}`);
        await expect(page.getByTestId('lease-extend')).toBeVisible();
        await expect(page.getByTestId('lease-renew')).toBeVisible();
        await hold(page, 1200);

        // ── PROPERTY_MANAGER ──
        await page.context().clearCookies();
        await signIn(page, fx.manager.email, fx.manager.password);
        await page.goto(`/en/dashboard/leases/${draft.id}`);
        await expect(page.getByTestId('lease-post')).toHaveCount(0);
        await expect(page.getByTestId('lease-needs-accountant')).toBeVisible();

        await page.goto(`/en/dashboard/leases/${leaseMainId}`);
        await expect(page.getByTestId('lease-extend')).toHaveCount(0);
        // canRenewLeases admits PROPERTY_MANAGER — renewing is not posting.
        await expect(page.getByTestId('lease-renew')).toBeVisible();

        // The UI hiding the button is a convenience; the API is the real gate.
        const manager = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', {
            email: fx.manager.email,
            password: fx.manager.password,
        });
        const managerActor: Actor = { id: manager.id, role: manager.role, tenantId: fx.tenantId };
        const res = await fetch(`${BACKEND}/api/v1/leases/${draft.id}/post`, {
            method: 'POST',
            headers: {
                'X-User-Id': managerActor.id,
                'X-User-Role': managerActor.role,
                'X-Tenant-Id': fx.tenantId,
                'X-User-Tenant-Id': fx.tenantId,
            },
        });
        expect(res.status, 'a property manager must be refused Post at the API, not only hidden from it in the UI').toBe(403);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 16 ──────────────────────────────────────────────────────────────────────

test('16 the lease page and the cheque register read right-to-left in Arabic', async ({ browser }) => {
    const { page, close } = await recorded(browser, '16-arabic-rtl');
    try {
        await page.goto(`/ar/dashboard/leases/${leaseMainId}`);
        await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
        // leaseStatus.ACTIVE — "ساري".
        await expect(page.getByTestId('lease-status')).toHaveText('ساري');
        await expect(page.getByTestId('lease-ledger')).toContainText('دفتر الأستاذ');

        await page.goto('/ar/dashboard/finance/cheques');
        await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
        await expect(page.getByRole('heading', { name: 'سجل الشيكات' })).toBeVisible();
        // Amounts stay in Western digits with the same grouping as the
        // English register — a page that reformatted its numbers per locale
        // could not be reconciled against the English one.
        await expect(page.locator('body')).toContainText(/\d{1,3}(,\d{3})*\.\d{2}/);
        await hold(page);
    } finally {
        await close();
    }

    console.log(`\n  manifest: ${manifest.created.length} records created — ${MANIFEST}`);
});
