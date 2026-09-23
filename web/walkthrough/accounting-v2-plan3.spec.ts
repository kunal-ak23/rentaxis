import { test, expect, type Browser, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

/**
 * Accounting v2, plan 3 (per-day rent recognition, termination, settlement) —
 * every scenario the feature can express, one recording each. Modelled exactly
 * on `accounting-v2-plan2.spec.ts` (read that file's own header first).
 *
 * The recording and the proof are the same run: each scenario asserts its own
 * outcome, and a take only exists because those assertions passed. There is no
 * "GAP" fallback anywhere in this file — a scenario that cannot prove its point
 * fails.
 *
 * Runs against the LOCAL dev stack (Next on 3001, backend on 8081) and
 * provisions ONE disposable tenant of its own through the API — a tenant admin,
 * an accountant, a property manager assigned to one of the two buildings, and a
 * renter. Nothing here touches production.
 *
 * Two properties, because the month-end close groups by building: an accountant
 * closing a month works one property at a time and a flat list of every lease's
 * rows is not a worklist. `recognition-group-{propertyId}` is what that renders,
 * and a single-property fixture could not tell a subtotal from a grand total.
 *
 * Fixture leases, and why there are five:
 *   - LEASE_MAIN (Marina): the spine (01 → 09). Term started three months ago,
 *     so month-ends have already passed and there is real income to recognise.
 *     Terminated mid-month, settled, then closed by the last clearance.
 *   - LEASE_PALM (Palm): a second posted lease in the second building, whose
 *     rent divides to exactly 100.00 a day — it is what makes scenario 02's
 *     grouping and its two subtotals real rather than decorative.
 *   - LEASE_LOCKED (Palm): created in 04 purely so a period lock has something
 *     to skip. Its first month falls inside the closed period.
 *   - LEASE_AMEND (Marina): amended (05) and then extended (06), and the ACTIVE
 *     contract the property manager prices in 08 and Arabic reads in 12.
 *   - LEASE_BALANCE (Palm): the settlement that collects instead of refunding
 *     (10) — a deduction larger than the deposit plus the credit.
 *
 * ORDER MATTERS, and one ordering is load-bearing rather than tidy: the period
 * lock in scenario 04 can only ever move FORWARDS
 * (`TenantFiscalSettingsService.lockThrough`), so it is set to the end of the
 * leases' FIRST month and every later scenario posts after it. Scenario 02 has
 * already closed that month for MAIN and PALM by then, which is why the lock
 * costs those two leases nothing.
 */

const BACKEND = process.env.WT_BACKEND_URL || 'http://localhost:8081';
const BASE_URL = process.env.WT_BASE_URL || 'http://localhost:3001';

const TAKES_DIR = path.join(__dirname, 'takes', 'accounting-v2-plan3');
const STATE = path.join(__dirname, 'raw', 'accounting-v2-plan3-state.json');
const SUFFIX = Math.random().toString(36).slice(2, 7);
const MANIFEST = path.join(__dirname, `run-manifest-accounting-v2-plan3-${SUFFIX}.json`);

const MARINA = `WT3 Marina ${SUFFIX}`;
const PALM = `WT3 Palm ${SUFFIX}`;
const RENTER = `WT3 Renter ${SUFFIX}`;

// ── dates ───────────────────────────────────────────────────────────────────
//
// The whole plan is about periods that have ENDED, so every fixture date is
// derived from the wall clock rather than written down. The term starts on the
// first of the month three months ago: three month-ends have passed, the first
// slice is a partial-rate month (30 or 31 days at the contract's day rate, not
// a twelfth of the rent), and the termination date in scenario 07 still falls
// comfortably inside the term.

const pad = (n: number) => String(n).padStart(2, '0');
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
/** `yyyy-MM-dd` as a LOCAL date — `new Date("2027-05-31")` is UTC midnight and drifts. */
const parseIso = (s: string) => {
    const [y, m, d] = s.split('-').map(Number);
    return new Date(y, m - 1, d);
};
const NOW = new Date();
const today = () => iso(new Date());

/** First day of the month `n` months back. */
const firstOfMonthsAgo = (n: number) => iso(new Date(NOW.getFullYear(), NOW.getMonth() - n, 1));
/** Last day of the month before this one — the close's own default. */
const lastMonthEnd = () => iso(new Date(NOW.getFullYear(), NOW.getMonth(), 0));
/** A day in the current month. */
const thisMonthDay = (d: number) => iso(new Date(NOW.getFullYear(), NOW.getMonth(), d));

/** A twelve-month term starting on the first of the month `n` months back. */
const termFrom = (monthsAgo: number) => {
    const s = new Date(NOW.getFullYear(), NOW.getMonth() - monthsAgo, 1);
    return { start: iso(s), end: iso(new Date(s.getFullYear() + 1, s.getMonth(), 0)) };
};

const TERM_START = firstOfMonthsAgo(3);
const TERM_END = termFrom(3).end;
/** The end of the term's FIRST month — what scenario 04 closes the books through. */
const FIRST_MONTH_END = (() => {
    const s = new Date(NOW.getFullYear(), NOW.getMonth() - 3, 1);
    return iso(new Date(s.getFullYear(), s.getMonth() + 1, 0));
})();
/** Mid-month, in the past, after the lock and inside every fixture term. */
const TERMINATION_DATE = thisMonthDay(15);

const DAYS_IN_TERM = Math.round(
    (Date.parse(`${TERM_END}T00:00:00Z`) - Date.parse(`${TERM_START}T00:00:00Z`)) / 86_400_000,
) + 1;

const nf = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
/** The same string `fmtAmount` renders (web/src/lib/api/ledger.ts). */
const money = (n: number) => nf.format(n);
const round2 = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100;
/** `fmtIsoDate(iso, 'en')` — en-GB, which is what the screens print. */
const ukDate = (isoDate: string) => {
    const [y, m, d] = isoDate.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString('en-GB');
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

function headersFor(actor: Actor | null): Record<string, string> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (actor) {
        headers['X-User-Id'] = actor.id;
        headers['X-User-Role'] = actor.role;
        if (actor.tenantId) {
            headers['X-Tenant-Id'] = actor.tenantId;
            headers['X-User-Tenant-Id'] = actor.tenantId;
        }
    }
    return headers;
}

/** The raw answer, for the scenarios whose point IS the status code. */
async function rawApi(actor: Actor | null, method: string, apiPath: string, body?: unknown) {
    const res = await fetch(`${BACKEND}${apiPath}`, {
        method,
        headers: headersFor(actor),
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const text = await res.text();
    let parsed: unknown = text;
    try {
        parsed = JSON.parse(text);
    } catch {
        /* a non-JSON body is its own answer */
    }
    return { status: res.status, body: parsed as Record<string, unknown> };
}

async function api<T>(actor: Actor | null, method: string, apiPath: string, body?: unknown): Promise<T> {
    const res = await rawApi(actor, method, apiPath, body);
    if (res.status >= 400) {
        throw new Error(`${method} ${apiPath} failed (${res.status}): ${JSON.stringify(res.body).slice(0, 500)}`);
    }
    return res.body as T;
}

type Fixtures = {
    tenantId: string;
    admin: { id: string; email: string; password: string };
    accountant: { id: string; email: string; password: string };
    manager: { id: string; email: string; password: string };
    renter: { id: string; email: string; password: string };
    marinaId: string;
    palmId: string;
};

let fx: Fixtures;

/** LEASE_MAIN — filled in by 01, read by 02 and 07-09 and 12. */
let leaseMainId = '';
let mainUnitNumber = '';
/** The kept instrument: uncleared, dated before T, so §9.1 keeps it (09). */
let keptChequeId = '';
/** LEASE_PALM — filled in by 01, read by 02. */
let leasePalmId = '';
/** LEASE_LOCKED — filled in by 04. */
let leaseLockedId = '';
/** LEASE_AMEND — filled in by 05, read by 06, 08 and 12. */
let leaseAmendId = '';
/** LEASE_BALANCE — filled in by 10. */
let leaseBalanceId = '';
let balanceUnitNumber = '';

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

// ── the admin's own API client ──────────────────────────────────────────────

let adminActor: Actor;

async function ensureAdmin(): Promise<Actor> {
    if (!adminActor) {
        const admin = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', {
            email: fx.admin.email,
            password: fx.admin.password,
        });
        adminActor = { id: admin.id, role: admin.role, tenantId: fx.tenantId };
    }
    return adminActor;
}

async function adminApi<T>(method: string, apiPath: string, body?: unknown): Promise<T> {
    return api<T>(await ensureAdmin(), method, apiPath, body);
}

/** The same call as {@link adminApi} when the STATUS CODE is the assertion. */
async function adminRaw(method: string, apiPath: string, body?: unknown) {
    return rawApi(await ensureAdmin(), method, apiPath, body);
}

// ── shared assertions ───────────────────────────────────────────────────────

type TrialBalanceRow = { debit: number; credit: number };

/**
 * The invariant every scenario that posts a journal ends on. Read through the
 * API rather than off the trial-balance page: the assertion is about the books,
 * not about a table, and a page that failed to render would otherwise read as a
 * balanced ledger.
 */
async function assertTrialBalanceBalances(label: string) {
    const rows = await adminApi<TrialBalanceRow[]>('GET', `/api/v1/finance/trial-balance?asOf=${today()}`);
    const debit = round2(rows.reduce((s, r) => s + (r.debit || 0), 0));
    const credit = round2(rows.reduce((s, r) => s + (r.credit || 0), 0));
    expect(debit, `${label}: the trial balance must not be empty`).toBeGreaterThan(0);
    expect(credit, `${label}: the trial balance must balance`).toBeCloseTo(debit, 2);
}

type RecognitionEntry = {
    id: string;
    leaseId: string;
    propertyId: string | null;
    propertyName: string | null;
    unitName: string | null;
    periodStart: string;
    periodEnd: string;
    days: number;
    amount: number;
    status: 'PLANNED' | 'POSTED' | 'REVERSED' | 'CANCELLED';
    journalId: string | null;
    journalNumber: string | null;
};

type ChequeRow = {
    id: string;
    seqNo: number;
    amount: number;
    mode: string;
    status: string;
    chequeDate: string | null;
};

const scheduleOf = (leaseId: string) =>
    adminApi<RecognitionEntry[]>('GET', `/api/v1/leases/${leaseId}/recognition`);
const chequesOf = (leaseId: string) =>
    adminApi<ChequeRow[]>('GET', `/api/v1/leases/${leaseId}/cheques`);

type LeaseDetail = {
    id: string;
    status: string;
    unitId: string;
    contractValue: number | null;
    terminatedOn: string | null;
    terminationJournalId: string | null;
};
const leaseOf = (leaseId: string) => adminApi<LeaseDetail>('GET', `/api/v1/leases/${leaseId}`);

/** Bank a register row outright — the two-step an accountant does in the UI. */
async function depositAndClear(chequeId: string) {
    await adminApi('PUT', `/api/v1/cheques/${chequeId}/deposit`, {});
    await adminApi('PUT', `/api/v1/cheques/${chequeId}/clear`, {});
}

type MakeLeaseOpts = {
    propertyId: string;
    label: string;
    rent: number;
    lines?: { chargeTypeCode: string; grossAmount: number }[];
    start?: string;
    end?: string;
    installments?: number;
};

/** A unit, a draft with lines, its cheque grid and the posting, all through the API. */
async function makePostedLease(o: MakeLeaseOpts) {
    const unitNumber = `WT3-${SUFFIX}-${o.label}`;
    const unit = await adminApi<{ id: string; unitNumber: string }>('POST', '/api/v1/units', {
        property: { id: o.propertyId },
        unitNumber,
        type: 'BHK1',
        sizeSqft: 900,
        expectedRent: o.rent,
    });
    record('unit', unit.id, unitNumber);

    const draft = await adminApi<{ id: string }>('POST', '/api/v1/leases', {
        unitId: unit.id,
        renterId: fx.renter.id,
        startDate: o.start ?? TERM_START,
        endDate: o.end ?? TERM_END,
        paymentTerms: o.installments ?? 4,
        paymentMethod: 'CHEQUE',
        depositPaymentMethod: 'CHEQUE',
        lines: o.lines ?? [{ chargeTypeCode: 'RENT', grossAmount: o.rent }],
    });
    // `foldDepositsAndFeesIntoFirst` defaults to TRUE (GenerateChequesRequest's
    // own doc): without this the deposit and the admin fee ride along inside
    // cheque 1 and there is no deposit row of its own to hand back.
    await adminApi('POST', `/api/v1/leases/${draft.id}/cheques/generate`, {
        installments: o.installments ?? 4,
        foldDepositsAndFeesIntoFirst: false,
    });
    const posted = await adminApi<{ lease: { id: string; status: string }; tcoEntryNumber: string }>(
        'POST',
        `/api/v1/leases/${draft.id}/post`,
    );
    expect(posted.lease.status, `${o.label} must post`).toBe('ACTIVE');
    record('lease', draft.id, `LEASE_${o.label} (${posted.tcoEntryNumber})`);
    return { leaseId: draft.id, unitId: unit.id, unitNumber };
}

// ── 00 ──────────────────────────────────────────────────────────────────────

test.describe.configure({ mode: 'serial' });

test('00 provision a tenant, two buildings and the four roles a move-out needs', async ({ browser }) => {
    const su = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', {
        email: 'admin@rentaxis.com',
        password: 'admin123',
    });
    const superAdmin: Actor = { id: su.id, role: su.role, tenantId: null };

    const tenantName = `WALKTHROUGH-ACCOUNTING-V2-PLAN3 ${today()} ${SUFFIX}`;
    const tenant = await api<{ id: string }>(superAdmin, 'POST', '/api/admin/tenants', { name: tenantName });
    expect(tenant.id, 'tenant must be created').toBeTruthy();
    record('tenant', tenant.id, tenantName);

    const scoped: Actor = { ...superAdmin, tenantId: tenant.id };
    const password = `Walk!${SUFFIX}9`;
    const make = async (role: string, slug: string, name: string) => {
        const email = `wt3-${slug}-${SUFFIX}@example.invalid`;
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
    // chained server-side (AccountController#seedDefaultAccounts). RENT,
    // ADMIN_FEE and SECURITY_DEPOSIT lines all need this to resolve a credit
    // account, and so does the ADVANCE_RENT leaf every CIL debits.
    await api(scoped, 'POST', '/api/v1/finance/accounts/seed');

    const property = async (nameEn: string, address: string) => {
        const p = await api<{ id: string }>(scoped, 'POST', '/api/v1/properties', {
            nameEn,
            nameAr: nameEn,
            address,
            emirate: 'DUBAI',
            type: 'RESIDENTIAL',
        });
        record('property', p.id, nameEn);
        return p.id;
    };
    const marinaId = await property(MARINA, '1 Recognition Street, Dubai');
    const palmId = await property(PALM, '2 Recognition Street, Dubai');

    // Scenario 08 needs a manager who can genuinely OPEN a contract before it
    // can show which act they are refused: LeaseAccessPolicy scopes them to
    // their assigned buildings, and an unassigned one is refused everything,
    // for the wrong reason. Marina only — Palm is deliberately not theirs.
    await api(scoped, 'POST', `/api/admin/users/${manager.id}/properties/${marinaId}`);

    const renterEmail = `wt3-renter-${SUFFIX}@example.invalid`;
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
        marinaId,
        palmId,
    };

    // The books must be open all the way back, or scenario 01's term — which
    // starts three months ago — could not be recognised at all. A fresh
    // TenantFiscalSettings row carries no books-start date and no lock
    // (TenantFiscalSettingsService.get's default), and this asserts it rather
    // than assuming it: `setBooksStartDate` would have closed everything before
    // the start date, which is exactly the state this plan cannot demonstrate in.
    const fiscal = await api<{ booksStartDate: string | null; booksLockedThrough: string | null }>(
        scoped,
        'GET',
        '/api/v1/finance/fiscal-settings',
    );
    expect(fiscal.booksLockedThrough, 'a fresh tenant must allow back-dated posting').toBeNull();
    expect(fiscal.booksStartDate, 'a fresh tenant has no books-start floor either').toBeNull();

    const { page, close } = await recorded(browser, '00-provision-tenant-and-actors', { signedIn: false });
    try {
        await signIn(page, admin.email, admin.password);
        await page.context().storageState({ path: STATE });

        await page.goto('/en/dashboard/properties');
        await expect(page.getByText(MARINA).first()).toBeVisible({ timeout: 20_000 });
        await expect(page.getByText(PALM).first()).toBeVisible();
        await hold(page);
    } finally {
        await close();
    }
});

// ── 01 ──────────────────────────────────────────────────────────────────────

test('01 a contract whose term began three months ago — the schedule is cut by days, not by twelfths', async ({ browser }) => {
    const { page, close } = await recorded(browser, '01-recognition-schedule-tab');
    try {
        // 120,000 of rent, a 2,000 admin fee and a 10,000 deposit, on four
        // quarterly PDCs with the fee and the deposit on rows of their own.
        const main = await makePostedLease({
            propertyId: fx.marinaId,
            label: 'MAIN',
            rent: 120_000,
            lines: [
                { chargeTypeCode: 'RENT', grossAmount: 120_000 },
                { chargeTypeCode: 'ADMIN_FEE', grossAmount: 2_000 },
                { chargeTypeCode: 'SECURITY_DEPOSIT', grossAmount: 10_000 },
            ],
        });
        leaseMainId = main.leaseId;
        mainUnitNumber = main.unitNumber;

        // A second building with a second contract, so scenario 02's grouping
        // has two groups to total. 36,500 over 365 days is exactly 100.00 a
        // day, which makes every figure on that screen checkable by eye.
        const palm = await makePostedLease({
            propertyId: fx.palmId,
            label: 'PALM',
            rent: 36_500,
            lines: [
                { chargeTypeCode: 'RENT', grossAmount: 36_500 },
                { chargeTypeCode: 'SECURITY_DEPOSIT', grossAmount: 3_000 },
            ],
        });
        leasePalmId = palm.leaseId;

        // Money actually received on MAIN: the first rent instalment, the admin
        // fee and the deposit. The SECOND rent cheque is left alone on purpose —
        // it is dated before the termination date, so §9.1 keeps it, and it is
        // what makes scenario 09's acknowledgement gate fire.
        const mainCheques = await chequesOf(leaseMainId);
        expect(mainCheques.length, 'four rent PDCs, the fee and the deposit').toBe(6);
        const rentRows = mainCheques.filter(c => c.amount === 30_000).sort((a, b) => a.seqNo - b.seqNo);
        expect(rentRows.length, 'four quarterly rent cheques of 30,000').toBe(4);
        keptChequeId = rentRows[1].id;
        for (const c of [rentRows[0], ...mainCheques.filter(c => c.amount === 2_000 || c.amount === 10_000)]) {
            await depositAndClear(c.id);
        }

        await page.goto(`/en/dashboard/leases/${leaseMainId}`);
        await expect(page.getByTestId('lease-status')).toHaveText(/active/i, { timeout: 20_000 });
        await page.getByTestId('lease-tab-recognition').click();
        await expect(page.getByTestId('recognition-schedule')).toBeVisible({ timeout: 20_000 });

        // Twelve calendar-month slices, Σ = the contract's RENT (the fee and
        // the deposit are not rent and are not recognised over time).
        const rows = page.locator('[data-testid^="recognition-row-"]');
        await expect(rows).toHaveCount(12);
        await expect(page.getByTestId('recognition-schedule-total')).toHaveText(money(120_000));
        await expect(page.getByTestId('recognition-schedule-check')).toContainText('Matches the contract rent');

        // The first slice is the point of the whole plan: a twelfth of 120,000
        // is 10,000.00, and that is NOT what the row says. It says the day rate
        // times the days that month actually has.
        const schedule = await scheduleOf(leaseMainId);
        expect(schedule.length).toBe(12);
        const dayRate = 120_000 / DAYS_IN_TERM;
        const first = schedule[0];
        expect(first.periodStart).toBe(TERM_START);
        expect(first.status).toBe('PLANNED');
        expect(first.amount, 'the first slice is the day rate times its own days').toBeCloseTo(
            round2(dayRate * first.days),
            2,
        );
        expect(first.amount, 'and it is therefore not a twelfth of the rent').not.toBeCloseTo(10_000, 2);
        await expect(rows.first()).toContainText(money(first.amount));
        expect(
            round2(schedule.reduce((s, r) => s + r.amount, 0)),
            'the schedule adds back up to the contract rent',
        ).toBe(120_000);
        expect(schedule.every(r => r.status === 'PLANNED'), 'nothing is posted until the close runs').toBe(true);

        await assertTrialBalanceBalances('01');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 02 ──────────────────────────────────────────────────────────────────────

test('02 the month-end close: preview writes nothing, Run posts the CILs, a second Run posts none', async ({ browser }) => {
    const { page, close } = await recorded(browser, '02-month-end-preview-and-run');
    try {
        await page.goto('/en/dashboard/finance/recognition');
        await expect(page.getByTestId('recognition-to-date')).toHaveValue(lastMonthEnd(), { timeout: 20_000 });

        const pendingBefore = await adminApi<RecognitionEntry[]>(
            'GET',
            `/api/v1/finance/recognition/pending?to=${lastMonthEnd()}`,
        );
        expect(pendingBefore.length, 'three ended months on each of the two contracts').toBe(6);
        const marinaRows = pendingBefore.filter(r => r.propertyId === fx.marinaId);
        const palmRows = pendingBefore.filter(r => r.propertyId === fx.palmId);
        const marinaTotal = round2(marinaRows.reduce((s, r) => s + r.amount, 0));
        const palmTotal = round2(palmRows.reduce((s, r) => s + r.amount, 0));
        expect(marinaRows.every(r => r.leaseId === leaseMainId), 'Marina holds only MAIN').toBe(true);
        expect(palmRows.every(r => r.leaseId === leasePalmId), 'Palm holds only PALM').toBe(true);
        expect(palmTotal, 'Palm runs at exactly 100.00 a day').toBe(
            round2(palmRows.reduce((s, r) => s + r.days * 100, 0)),
        );

        // ── grouped by property, each with its own subtotal ──
        await expect(page.getByTestId(`recognition-group-${fx.marinaId}`)).toBeVisible();
        await expect(page.getByTestId(`recognition-group-${fx.palmId}`)).toBeVisible();
        await expect(page.getByTestId(`recognition-group-total-${fx.marinaId}`)).toContainText(money(marinaTotal));
        await expect(page.getByTestId(`recognition-group-total-${fx.palmId}`)).toContainText(money(palmTotal));
        await expect(page.getByTestId('recognition-pending-total')).toContainText(
            money(round2(marinaTotal + palmTotal)),
        );
        await expect(page.getByTestId(`recognition-row-unit-${marinaRows[0].id}`)).toHaveText(mainUnitNumber);

        // ── Preview: a count, an amount, and nothing written ──
        await page.getByTestId('recognition-preview').click();
        await expect(page.getByTestId('recognition-result-title')).toContainText('nothing was written');
        await expect(page.getByTestId('recognition-would-post')).toHaveText('6');
        await expect(page.getByTestId('recognition-posted')).toHaveCount(0);
        await expect(page.getByTestId('recognition-result-amount')).toHaveText(
            money(round2(marinaTotal + palmTotal)),
        );

        const stillPlanned = await adminApi<RecognitionEntry[]>(
            'GET',
            `/api/v1/finance/recognition/pending?to=${lastMonthEnd()}`,
        );
        expect(stillPlanned.length, 'a preview leaves every row where it found it').toBe(6);
        expect(stillPlanned.every(r => r.status === 'PLANNED' && r.journalId === null)).toBe(true);
        await hold(page, 1500);

        // ── Run: the same six rows, posted ──
        await page.getByTestId('recognition-run').click();
        await page.getByTestId('recognition-run-confirm').click();
        await expect(page.getByTestId('recognition-result-title')).toContainText('Recognition run', { timeout: 30_000 });
        await expect(page.getByTestId('recognition-posted')).toHaveText('6');
        await expect(page.getByTestId('recognition-result-amount')).toHaveText(
            money(round2(marinaTotal + palmTotal)),
        );
        await expect(page.getByTestId('recognition-failed')).toHaveCount(0);
        await expect(page.getByTestId('recognition-no-pending')).toBeVisible({ timeout: 20_000 });

        // ── the ledger: one CIL per closed month, dated its period end ──
        type Journal = { id: string; entryNumber: string; entryDate: string; status: string };
        const cils = await adminApi<{ content: Journal[] }>(
            'GET',
            `/api/v1/finance/journals?docType=CIL&leaseId=${leaseMainId}&page=0&size=50`,
        );
        const posted = await scheduleOf(leaseMainId);
        const postedRows = posted.filter(r => r.status === 'POSTED');
        expect(postedRows.length, 'three closed months on MAIN').toBe(3);
        expect(cils.content.length, 'one CIL per closed month').toBe(3);
        const cilDates = cils.content.map(j => j.entryDate).sort();
        expect(cilDates, 'each CIL is dated its own period end (D13)').toEqual(
            postedRows.map(r => r.periodEnd).sort(),
        );

        type Line = { accountName: string; debit: number; credit: number };
        const firstCil = await adminApi<{ lines: Line[] }>(
            'GET',
            `/api/v1/finance/journals/${postedRows[0].journalId}`,
        );
        const debit = firstCil.lines.find(l => l.debit > 0)!;
        const credit = firstCil.lines.find(l => l.credit > 0)!;
        expect(debit.accountName, 'Dr Advance Rent').toMatch(/Advance Rent/i);
        expect(credit.accountName, 'Cr Rental Income').toMatch(/Rental Income/i);
        expect(debit.debit).toBeCloseTo(postedRows[0].amount, 2);
        expect(credit.credit).toBeCloseTo(postedRows[0].amount, 2);

        // …and the same rows on the tenant ledger the accountant actually
        // reads, scoped to this one contract so nothing else can be mistaken
        // for them. Asserted on the rendered page, not only through the API:
        // the CIL that is invisible on the ledger is the one nobody reconciles.
        //
        // The From box has to be moved back. `defaultLedgerRange`
        // (LedgerFilters.tsx:18-21) opens every ledger on the CURRENT month,
        // and a close run today posts income dated the month that just ended —
        // so the default view of a freshly closed month is empty. That is a
        // real trap rather than a bug in this plan's code, and it is written up
        // in task-9-report.md under "Findings"; here the filter is driven the
        // way an accountant would drive it.
        await page.goto(
            `/en/dashboard/finance/tenant-ledger?renterId=${fx.renter.id}&leaseId=${leaseMainId}`,
        );
        await expect(page).toHaveURL(/tenant-ledger/);
        await page.locator('#ledger-from').fill(TERM_START);
        await page.getByRole('button', { name: 'Apply' }).click();
        const ledgerBody = page.locator('body');
        await expect(ledgerBody).toContainText('Advance Rent', { timeout: 20_000 });
        await expect(ledgerBody).toContainText('Rental Income');
        for (const j of cils.content) {
            await expect(ledgerBody, `the ledger must carry ${j.entryNumber}`).toContainText(j.entryNumber);
        }
        await expect(ledgerBody).toContainText(ukDate(postedRows[0].periodEnd));

        // ── idempotent: the same close, run twice, posts once ──
        await page.goto('/en/dashboard/finance/recognition');
        await expect(page.getByTestId('recognition-no-pending')).toBeVisible({ timeout: 20_000 });
        await page.getByTestId('recognition-run').click();
        await page.getByTestId('recognition-run-confirm').click();
        await expect(page.getByTestId('recognition-posted')).toHaveText('0', { timeout: 30_000 });
        await expect(page.getByTestId('recognition-nothing')).toBeVisible();

        await assertTrialBalanceBalances('02');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 03 ──────────────────────────────────────────────────────────────────────

test('03 a period that has not ended is refused in the UI and at the API', async ({ browser }) => {
    const { page, close } = await recorded(browser, '03-future-date-refused');
    try {
        const future = iso(new Date(NOW.getFullYear(), NOW.getMonth() + 1, 15));

        await page.goto('/en/dashboard/finance/recognition');
        await expect(page.getByTestId('recognition-to-date')).toHaveValue(lastMonthEnd(), { timeout: 20_000 });
        await expect(page.getByTestId('recognition-preview')).toBeEnabled();

        await page.getByTestId('recognition-to-date').fill(future);
        await expect(page.getByTestId('recognition-future-warning')).toBeVisible();
        await expect(page.getByTestId('recognition-preview')).toBeDisabled();
        await expect(page.getByTestId('recognition-run')).toBeDisabled();

        // The disabled button is a courtesy; the rule is the server's. Called
        // directly, both endpoints answer 400 with the same sentence
        // (RecognitionController#notInTheFuture).
        const refusedRun = await adminRaw('POST', `/api/v1/finance/recognition/run?to=${future}&preview=true`);
        expect(refusedRun.status, 'a future close must be refused at the API too').toBe(400);
        expect(String(refusedRun.body.message)).toContain('periods that have not ended');
        const refusedList = await adminRaw('GET', `/api/v1/finance/recognition/pending?to=${future}`);
        expect(refusedList.status, 'and so must the list that feeds it').toBe(400);

        // Today itself is allowed — the boundary is "has not ended", not "is not now".
        const allowed = await adminRaw('POST', `/api/v1/finance/recognition/run?to=${today()}&preview=true`);
        expect(allowed.status, 'to == today is inside the rule').toBe(200);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 04 ──────────────────────────────────────────────────────────────────────

test('04 a closed period is skipped, and reported apart from a failure', async ({ browser }) => {
    const { page, close } = await recorded(browser, '04-locked-period-skipped');
    try {
        // A third contract on the same three-month-old term, created BEFORE the
        // books close so its own posting is never the thing being refused. Its
        // first month is what the lock will catch.
        const locked = await makePostedLease({
            propertyId: fx.palmId,
            label: 'LOCKED',
            rent: 12_000,
            lines: [
                { chargeTypeCode: 'RENT', grossAmount: 12_000 },
                { chargeTypeCode: 'SECURITY_DEPOSIT', grossAmount: 1_000 },
            ],
        });
        leaseLockedId = locked.leaseId;

        // Plan 1's fiscal API. The lock only moves forwards, so this is the one
        // scenario that changes the tenant's books for every scenario after it —
        // it is deliberately set to the end of the term's FIRST month, which
        // scenario 02 has already closed for MAIN and PALM.
        const fiscal = await adminApi<{ booksLockedThrough: string }>(
            'POST',
            '/api/v1/finance/fiscal-settings/lock',
            { through: FIRST_MONTH_END },
        );
        expect(fiscal.booksLockedThrough).toBe(FIRST_MONTH_END);
        record('fiscal-lock', FIRST_MONTH_END, 'books closed through the term\'s first month');

        await page.goto('/en/dashboard/finance/recognition');
        await expect(page.getByTestId('recognition-locked-through')).toContainText(ukDate(FIRST_MONTH_END), {
            timeout: 20_000,
        });

        const before = await scheduleOf(leaseLockedId);
        const inLockedPeriod = before.filter(r => r.periodEnd <= FIRST_MONTH_END);
        expect(inLockedPeriod.length, 'one month of this contract falls inside the closed period').toBe(1);

        await page.getByTestId('recognition-run').click();
        await page.getByTestId('recognition-run-confirm').click();
        await expect(page.getByTestId('recognition-result-title')).toContainText('Recognition run', { timeout: 30_000 });

        // Skipped is NOT failed, and the screen says so in its own place: the
        // warning names the count and the lock date, and the error list — which
        // is where a row the LEDGER refused would appear — is absent.
        await expect(page.getByTestId('recognition-skipped')).toContainText('1 entry falls');
        await expect(page.getByTestId('recognition-skipped')).toContainText(ukDate(FIRST_MONTH_END));
        await expect(page.getByTestId('recognition-errors')).toHaveCount(0);
        await expect(page.getByTestId('recognition-failed')).toHaveCount(0);
        // The months after the lock still closed.
        await expect(page.getByTestId('recognition-posted')).toHaveText('2');

        const after = await scheduleOf(leaseLockedId);
        const skipped = after.find(r => r.id === inLockedPeriod[0].id)!;
        expect(skipped.status, 'a locked row stays PLANNED — it posts itself when the period reopens').toBe('PLANNED');
        expect(skipped.journalId).toBeNull();
        expect(
            after.filter(r => r.status === 'POSTED').length,
            'the two months after the lock did post',
        ).toBe(2);

        await assertTrialBalanceBalances('04');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 05 ──────────────────────────────────────────────────────────────────────

test('05 amending the lines rebuilds the schedule — the old rows are reversed, not edited', async ({ browser }) => {
    const { page, close } = await recorded(browser, '05-amend-rebuilds-the-schedule');
    try {
        // A contract of its own, starting last month so its first slice is
        // already closeable and comfortably after the period lock. One RENT
        // line, four equal cheques — every row still REGISTERED, which is what
        // `amendBlockedBy` requires.
        const amendTerm = termFrom(1);
        const amend = await makePostedLease({
            propertyId: fx.marinaId,
            label: 'AMEND',
            rent: 60_000,
            lines: [{ chargeTypeCode: 'RENT', grossAmount: 60_000 }],
            start: amendTerm.start,
            end: amendTerm.end,
        });
        leaseAmendId = amend.leaseId;

        // Close its first month, so the rebuild has a POSTED row to reverse
        // rather than only PLANNED rows to cancel.
        await adminApi('POST', `/api/v1/finance/recognition/run?to=${lastMonthEnd()}&preview=false`);
        const before = await scheduleOf(leaseAmendId);
        const postedBefore = before.filter(r => r.status === 'POSTED');
        expect(postedBefore.length, 'the amended contract has a closed month behind it').toBe(1);
        const beforeIds = new Set(before.map(r => r.id));

        await page.goto(`/en/dashboard/leases/${leaseAmendId}`);
        await expect(page.getByTestId('lease-status')).toHaveText(/active/i, { timeout: 20_000 });
        await page.getByTestId('lease-amend').click();
        await expect(page.getByTestId('amend-blocked')).toHaveCount(0);

        // An amend REDISTRIBUTES; it cannot re-price the contract (the cheque
        // grid is not part of this dialog and the server refuses a total that
        // no longer matches). 5,000 of what was booked as rent was really an
        // admin fee — Σ stays 60,000, and the rent the schedule is cut from
        // drops to 55,000.
        await page.getByTestId('lease-line-amount-0').fill('55000');
        await page.getByTestId('lease-lines-add').click();
        await page.getByTestId('lease-line-type-1').selectOption({ label: 'Admin Fee' });
        await page.getByTestId('lease-line-amount-1').fill('5000');
        await page.getByTestId('amend-reason').fill(`WT3 rent reclassified as admin fee ${SUFFIX}`);

        const [amendRes] = await Promise.all([
            page.waitForResponse(r => /\/leases\/[0-9a-f-]{36}\/amend-lines$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('amend-lines-confirm').click(),
        ]);
        expect(amendRes.status()).toBe(200);
        const amended = await amendRes.json();
        expect(amended.lease.contractValue, 'an amend reclassifies, it does not re-price').toBe(60_000);

        // The schedule: every old row retired, a fresh one planned for the new rent.
        await page.reload();
        await page.getByTestId('lease-tab-recognition').click();
        await expect(page.getByTestId('recognition-schedule')).toBeVisible({ timeout: 20_000 });

        const after = await scheduleOf(leaseAmendId);
        const old = after.filter(r => beforeIds.has(r.id));
        const fresh = after.filter(r => !beforeIds.has(r.id));
        expect(old.length, 'the old rows are still on the schedule, retired').toBe(before.length);
        expect(
            old.every(r => r.status === 'REVERSED' || r.status === 'CANCELLED'),
            'a rebuild retires every old row rather than editing one',
        ).toBe(true);
        expect(
            old.filter(r => r.status === 'REVERSED').map(r => r.id).sort(),
            'the POSTED month is REVERSED; the PLANNED ones are CANCELLED',
        ).toEqual(postedBefore.map(r => r.id).sort());
        expect(fresh.length, 'and a whole new schedule is planned').toBeGreaterThan(0);
        expect(fresh.every(r => r.status === 'PLANNED')).toBe(true);
        expect(
            round2(fresh.reduce((s, r) => s + r.amount, 0)),
            'the new schedule is cut from the new rent',
        ).toBe(55_000);
        // The footer adds the LIVE plan, not every version the lease has ever
        // had: Σ over `after` is 111,000-style arithmetic across two schedules,
        // which is not a figure about this contract. The retired rows get their
        // own struck subtotal beside it.
        await expect(page.getByTestId('recognition-schedule-total')).toHaveText(
            money(round2(fresh.reduce((s, r) => s + r.amount, 0))),
        );
        await expect(page.getByTestId('recognition-schedule-superseded')).toHaveText(
            money(round2(old.reduce((s, r) => s + r.amount, 0))),
        );

        await assertTrialBalanceBalances('05');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 06 ──────────────────────────────────────────────────────────────────────

test('06 extending appends a second segment and leaves the first one alone', async ({ browser }) => {
    const { page, close } = await recorded(browser, '06-extend-appends-a-segment');
    try {
        const before = await scheduleOf(leaseAmendId);
        const beforeById = new Map(before.map(r => [r.id, r]));
        const currentEnd = (await adminApi<{ endDate: string }>('GET', `/api/v1/leases/${leaseAmendId}`)).endDate;

        await page.goto(`/en/dashboard/leases/${leaseAmendId}`);
        await expect(page.getByTestId('lease-status')).toHaveText(/active/i, { timeout: 20_000 });
        await page.getByTestId('lease-extend').click();

        const end = parseIso(currentEnd);
        end.setMonth(end.getMonth() + 2);
        const newEndDate = iso(end);
        await page.getByTestId('extend-new-end-date').fill(newEndDate);

        // One extension line, one matching cheque: ExtendLeaseDialog's `matches`
        // gate wants Σ cheques == the extension lines' VAT-inclusive total, and
        // RENT is VAT-exempt by this tenant's default.
        await page.getByTestId('lease-line-type-0').selectOption({ label: 'Rent' });
        await page.getByTestId('lease-line-amount-0').fill('8000');
        const grid = page.getByTestId('extend-cheque-grid');
        await grid.getByLabel(/^Amount 1$/).fill('8000');
        const windowStart = parseIso(currentEnd);
        windowStart.setDate(windowStart.getDate() + 1);
        await grid.getByLabel(/^Date 1$/).fill(iso(windowStart));
        await expect(page.getByTestId('extend-match')).toHaveAttribute('data-match', 'true', { timeout: 20_000 });

        const [extendRes] = await Promise.all([
            page.waitForResponse(r => /\/leases\/[0-9a-f-]{36}\/extend$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('extend-lease-confirm').click(),
        ]);
        expect(extendRes.status()).toBe(200);
        const extended = await extendRes.json();
        expect(extended.lease.endDate).toBe(newEndDate);
        record('journal', extended.tcoJournalId, 'TCO — LEASE_AMEND extension');

        await page.reload();
        await page.getByTestId('lease-tab-recognition').click();
        await expect(page.getByTestId('recognition-schedule')).toBeVisible({ timeout: 20_000 });

        const after = await scheduleOf(leaseAmendId);
        const appended = after.filter(r => !beforeById.has(r.id));
        expect(appended.length, 'the extension brings its own slices').toBeGreaterThan(0);
        expect(appended.every(r => r.status === 'PLANNED')).toBe(true);
        expect(
            round2(appended.reduce((s, r) => s + r.amount, 0)),
            'and they add up to the extension rent, nothing else',
        ).toBe(8_000);
        expect(
            appended.every(r => r.periodStart > currentEnd),
            'every appended slice sits after the original term',
        ).toBe(true);

        for (const row of after.filter(r => beforeById.has(r.id))) {
            const was = beforeById.get(row.id)!;
            expect(row.status, `existing row ${row.periodStart} must be untouched`).toBe(was.status);
            expect(row.amount).toBeCloseTo(was.amount, 2);
            expect(row.periodEnd).toBe(was.periodEnd);
        }
        expect((await leaseOf(leaseAmendId)).status, 'an extension leaves the contract running').toBe('ACTIVE');

        await assertTrialBalanceBalances('06');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 07 ──────────────────────────────────────────────────────────────────────

test('07 pricing a termination: four figures, and the receivable moves as a cheque is flipped', async ({ browser }) => {
    const { page, close } = await recorded(browser, '07-termination-preview');
    try {
        const expected = await adminApi<{
            terminationDate: string;
            earnedRentThroughDate: number;
            recognisedSoFar: number;
            unearnedRent: number;
            receivableAfter: number;
            chequesToReturn: ChequeRow[];
            chequesToKeep: ChequeRow[];
        }>('GET', `/api/v1/leases/${leaseMainId}/terminate/preview?date=${TERMINATION_DATE}`);

        await page.goto(`/en/dashboard/leases/${leaseMainId}`);
        await page.getByTestId('lease-terminate').click();
        await page.waitForURL(/\/terminate$/, { timeout: 20_000 });

        await page.getByTestId('terminate-date').fill(TERMINATION_DATE);
        await expect(page.getByTestId('terminate-earned')).toHaveText(money(expected.earnedRentThroughDate), {
            timeout: 20_000,
        });
        await expect(page.getByTestId('terminate-recognised')).toHaveText(money(expected.recognisedSoFar));
        await expect(page.getByTestId('terminate-unearned')).toHaveText(money(expected.unearnedRent));
        await expect(page.getByTestId('terminate-receivable-after')).toHaveText(money(expected.receivableAfter));

        // Earned is what the day rate says; recognised is what the ledger has
        // seen. The gap is precisely the slice the truncation will post.
        expect(expected.earnedRentThroughDate).toBeGreaterThan(expected.recognisedSoFar);
        expect(
            round2(expected.earnedRentThroughDate + expected.unearnedRent),
            'earned + unearned is the whole contract rent',
        ).toBe(120_000);

        // The default split, as the table renders it.
        expect(expected.chequesToKeep.length, 'the second rent cheque is dated before T, so it is kept').toBe(1);
        expect(expected.chequesToKeep[0].id).toBe(keptChequeId);
        expect(expected.chequesToReturn.length, 'the two later rent cheques go back').toBe(2);
        for (const c of expected.chequesToReturn) {
            await expect(page.getByTestId(`terminate-decision-${c.id}`)).toHaveAttribute('data-decision', 'RETURN');
        }
        await expect(page.getByTestId(`terminate-decision-${keptChequeId}`)).toHaveAttribute('data-decision', 'KEEP');

        // Flip one row and watch the receivable move by exactly that row's
        // amount, with no request in flight: `receivableAfterForSplit` mirrors
        // `LeaseTerminationService.receivableAfter` client-side.
        const flipped = expected.chequesToReturn[0];
        await page.getByTestId(`terminate-keep-${flipped.id}`).click();
        await expect(page.getByTestId(`terminate-decision-${flipped.id}`)).toHaveAttribute('data-decision', 'KEEP');
        await expect(page.getByTestId(`terminate-keep-${flipped.id}`)).toHaveAttribute('aria-pressed', 'true');
        await expect(page.getByTestId('terminate-receivable-after')).toHaveText(
            money(round2(expected.receivableAfter - flipped.amount)),
        );

        // …and back, because the preview is a decision, not a filter.
        await page.getByTestId(`terminate-return-${flipped.id}`).click();
        await expect(page.getByTestId('terminate-receivable-after')).toHaveText(money(expected.receivableAfter));

        // Nothing was written by any of it.
        expect((await leaseOf(leaseMainId)).status, 'pricing a termination performs none of it').toBe('ACTIVE');
        const untouched = await chequesOf(leaseMainId);
        expect(untouched.some(c => c.status === 'RETURNED'), 'no cheque was handed back').toBe(false);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 08 ──────────────────────────────────────────────────────────────────────

test('08 terminating: the paper goes back, the schedule is cut to T, and a manager may not', async ({ browser }) => {
    const { page, close } = await recorded(browser, '08-terminate-and-the-manager-refused');
    try {
        const preview = await adminApi<{
            unearnedRent: number;
            chequesToReturn: ChequeRow[];
            chequesToKeep: ChequeRow[];
        }>('GET', `/api/v1/leases/${leaseMainId}/terminate/preview?date=${TERMINATION_DATE}`);
        const returnedIds = preview.chequesToReturn.map(c => c.id);
        const lease = await leaseOf(leaseMainId);

        await page.goto(`/en/dashboard/leases/${leaseMainId}/terminate`);
        await page.getByTestId('terminate-date').fill(TERMINATION_DATE);
        await expect(page.getByTestId('terminate-receivable-after')).toBeVisible({ timeout: 20_000 });
        await page.getByTestId('terminate-notes').fill(`WT3 renter relocating ${SUFFIX}`);

        await page.getByTestId('terminate-submit').click();
        const [terminateRes] = await Promise.all([
            page.waitForResponse(r => /\/leases\/[0-9a-f-]{36}\/terminate$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('terminate-confirm').click(),
        ]);
        expect(terminateRes.status()).toBe(200);
        // §9.1 hands the deposit straight to §9.2 — and the statement that
        // greets the accountant says, before anything else, that the slice the
        // truncation just planned has not been recognised yet. Settling on it
        // now would understate the earned rent by exactly that slice.
        await page.waitForURL(/\/settlement$/, { timeout: 30_000 });
        await expect(page.getByTestId('settlement-unrecognised')).toContainText(
            '1 period of rent has not been recognised',
            { timeout: 20_000 },
        );

        const terminated = await leaseOf(leaseMainId);
        expect(terminated.status).toBe('TERMINATED');
        expect(terminated.terminatedOn).toBe(TERMINATION_DATE);
        expect(terminated.terminationJournalId, 'unearned rent was reversed, so there is a TCR').toBeTruthy();
        record('journal', terminated.terminationJournalId!, 'TCR — LEASE_MAIN');

        const cheques = await chequesOf(leaseMainId);
        for (const id of returnedIds) {
            expect(cheques.find(c => c.id === id)?.status, 'a returned instrument is RETURNED').toBe('RETURNED');
        }
        expect(cheques.find(c => c.id === keptChequeId)?.status, 'a kept one stays on the register').toBe('REGISTERED');

        // The schedule: a replacement slice that ENDS on T, and nothing planned after it.
        const schedule = await scheduleOf(leaseMainId);
        const live = schedule.filter(r => r.status === 'PLANNED' || r.status === 'POSTED');
        const tail = live[live.length - 1];
        expect(tail.periodEnd, 'the last live slice ends on the termination date').toBe(TERMINATION_DATE);
        expect(tail.status).toBe('PLANNED');
        expect(
            schedule.filter(r => r.periodStart > TERMINATION_DATE).every(r => r.status === 'CANCELLED'),
            'every period after T is cancelled',
        ).toBe(true);

        // The TCR, line by line: Dr Advance Rent / Cr Rent Receivable.
        type Line = { accountName: string; debit: number; credit: number };
        const tcr = await adminApi<{ entryNumber: string; lines: Line[] }>(
            'GET',
            `/api/v1/finance/journals/${terminated.terminationJournalId}`,
        );
        const dr = tcr.lines.find(l => l.debit > 0)!;
        const cr = tcr.lines.find(l => l.credit > 0)!;
        expect(dr.accountName).toMatch(/Advance Rent/i);
        expect(cr.accountName).toMatch(/Rent Receivable/i);
        expect(dr.debit).toBeCloseTo(preview.unearnedRent, 2);

        // `UnitController` has no single-resource GET, so the flat is read off
        // its building's list.
        const units = await adminApi<{ id: string; status: string }[]>(
            'GET',
            `/api/v1/units/property/${fx.marinaId}`,
        );
        const unit = units.find(u => u.id === lease.unitId);
        expect(unit, 'the terminated contract still names a flat').toBeTruthy();
        expect(unit!.status, 'the flat is free again').toBe('VACANT');

        // ── a property manager prices it and is offered nothing else ──
        await page.context().clearCookies();
        await signIn(page, fx.manager.email, fx.manager.password);
        await page.goto(`/en/dashboard/leases/${leaseAmendId}/terminate`);
        await expect(page.getByTestId('terminate-preview-only')).toBeVisible({ timeout: 20_000 });
        await expect(page.getByTestId('terminate-earned')).toBeVisible();
        await expect(page.getByTestId('terminate-submit')).toHaveCount(0);
        await expect(page.getByTestId('terminate-notes')).toHaveCount(0);

        // The hidden button is a convenience; the API is the gate.
        const managerLogin = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', {
            email: fx.manager.email,
            password: fx.manager.password,
        });
        const managerActor: Actor = { id: managerLogin.id, role: managerLogin.role, tenantId: fx.tenantId };
        const refused = await rawApi(managerActor, 'POST', `/api/v1/leases/${leaseAmendId}/terminate`, {
            terminationDate: TERMINATION_DATE,
        });
        expect(refused.status, 'a manager is refused Terminate at the API, not merely hidden from it').toBe(403);
        expect((await leaseOf(leaseAmendId)).status, 'and the contract is untouched').toBe('ACTIVE');

        await assertTrialBalanceBalances('08');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 09 ──────────────────────────────────────────────────────────────────────

test('09 the settlement statement, the acknowledgement, the STL — and the clearance that closes the contract', async ({ browser }) => {
    const { page, close } = await recorded(browser, '09-settlement-refund-and-closure');
    try {
        // The truncated slice ends on T, which has passed, so the close posts it.
        await page.goto('/en/dashboard/finance/recognition');
        await page.getByTestId('recognition-to-date').fill(today());
        await expect(page.getByTestId('recognition-run')).toBeEnabled({ timeout: 20_000 });
        await page.getByTestId('recognition-run').click();
        await page.getByTestId('recognition-run-confirm').click();
        await expect(page.getByTestId('recognition-result-title')).toContainText('Recognition run', { timeout: 30_000 });

        const schedule = await scheduleOf(leaseMainId);
        const tail = schedule.filter(r => r.status === 'POSTED').slice(-1)[0];
        expect(tail.periodEnd, 'the truncated slice is now posted').toBe(TERMINATION_DATE);

        const statement = await adminApi<{
            earnedRent: number;
            receivedTotal: number;
            receivableBalance: number;
            depositsHeld: number;
            instrumentsOutstanding: number;
            outstandingInstruments: { id: string; amount: number }[];
            netRefund: number;
            unrecognisedEntries: number;
        }>('GET', `/api/v1/leases/${leaseMainId}/settlement/preview`);
        expect(statement.unrecognisedEntries, 'nothing is left unrecognised on this contract').toBe(0);
        expect(statement.instrumentsOutstanding, 'the kept cheque is still in PDC receivable').toBeGreaterThan(0);
        expect(statement.outstandingInstruments.map(i => i.id)).toContain(keptChequeId);

        await page.goto(`/en/dashboard/leases/${leaseMainId}/settlement`);
        await expect(page.getByTestId('settlement-earned-rent')).toHaveText(money(statement.earnedRent), {
            timeout: 20_000,
        });
        await expect(page.getByTestId('settlement-received')).toHaveText(money(statement.receivedTotal));
        await expect(page.getByTestId('settlement-receivable-balance')).toHaveText(money(statement.receivableBalance));
        await expect(page.getByTestId('settlement-deposits-held')).toHaveText(money(statement.depositsHeld));
        await expect(page.getByTestId('settlement-instruments')).toHaveText(money(statement.instrumentsOutstanding));
        await expect(page.getByTestId(`settlement-outstanding-${keptChequeId}`)).toBeVisible();
        await expect(page.getByTestId('settlement-unrecognised')).toHaveCount(0);

        // ── a damage deduction ──
        const damage = 1_500;
        await page.getByTestId('settlement-add-deduction').click();
        await page.getByTestId('settlement-category-0').selectOption('PROPERTY_DAMAGE');
        await page.getByTestId('settlement-description-0').fill(`WT3 broken patio door ${SUFFIX}`);
        await page.getByTestId('settlement-amount-0').fill(String(damage));
        const netRefund = round2(statement.netRefund - damage);
        await expect(page.getByTestId('settlement-total-deductions')).toContainText(money(damage));
        await expect(page.getByTestId('settlement-net-refund')).toHaveText(money(netRefund));

        // Finalise posts the STORED lines, so an unsaved edit blocks it.
        await expect(page.getByTestId('settlement-unsaved')).toBeVisible();
        await expect(page.getByTestId('settlement-finalize')).toBeDisabled();
        const [saveRes] = await Promise.all([
            page.waitForResponse(r => /\/settlement\/draft$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('settlement-save-draft').click(),
        ]);
        expect(saveRes.status()).toBe(200);
        await expect(page.getByTestId('settlement-unsaved')).toHaveCount(0, { timeout: 20_000 });
        await expect(page.getByTestId('settlement-status')).toHaveText('Draft');

        // ── the refund bank, then the acknowledgement ──
        await expect(page.getByTestId('settlement-finalize')).toBeDisabled();
        // The picker's search box; `SettlementAccountPicker` narrows the chart
        // to active BANK/CASH asset leaves, and the seeded property bank is the
        // one a refund is actually paid from.
        const bank = page.getByLabel('Refund paid from');
        await bank.fill('Emirates');
        await page.getByRole('button', { name: /Emirates Islamic/ }).first().click();
        await expect(bank).toHaveValue(/Emirates Islamic/);

        // A refund paid over a register that is still holding paper is a
        // deliberate act (`requireOutstandingAcknowledged`), and the checkbox is
        // the only thing between here and the STL.
        await expect(page.getByTestId('settlement-finalize')).toBeDisabled();
        await page.getByTestId('settlement-acknowledge').check();
        await expect(page.getByTestId('settlement-finalize')).toBeEnabled();

        await page.getByTestId('settlement-finalize').click();
        const [finalizeRes] = await Promise.all([
            page.waitForResponse(r => /\/settlement\/finalize$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('settlement-finalize-confirm').click(),
        ]);
        expect(finalizeRes.status()).toBe(200);

        await expect(page.getByTestId('settlement-status')).toHaveText('Finalized', { timeout: 20_000 });
        await expect(page.getByTestId('settlement-journal')).toContainText('STL');
        // Finalise does NOT close a contract whose register still holds paper.
        await expect(page.getByTestId('settlement-closure')).toContainText('stays open');
        expect((await leaseOf(leaseMainId)).status, 'still TERMINATED while a cheque is out').toBe('TERMINATED');

        const finalized = await adminApi<{ journalNumber: string; refundAmount: number }>(
            'GET',
            `/api/v1/leases/${leaseMainId}/settlement`,
        );
        expect(finalized.journalNumber).toMatch(/^STL/);
        expect(finalized.refundAmount).toBeCloseTo(netRefund, 2);
        record('journal', finalized.journalNumber, 'STL — LEASE_MAIN');
        await assertTrialBalanceBalances('09 (finalise)');

        // ── the last clearance closes it ──
        await page.goto(`/en/dashboard/finance/cheques?search=${encodeURIComponent(mainUnitNumber)}`);
        await expect(page.getByTestId(`cheque-row-${keptChequeId}`)).toBeVisible({ timeout: 20_000 });
        await page.getByTestId(`cheque-row-action-deposit-${keptChequeId}`).click();
        await page.getByTestId('cheque-deposit-confirm').click();
        await expect(page.getByTestId(`cheque-row-action-clear-${keptChequeId}`)).toBeVisible({ timeout: 20_000 });
        await page.getByTestId(`cheque-row-action-clear-${keptChequeId}`).click();
        const [clearRes] = await Promise.all([
            page.waitForResponse(r => /\/cheques\/[0-9a-f-]{36}\/clear$/.test(r.url()) && r.request().method() === 'PUT'),
            page.getByTestId('cheque-clear-confirm').click(),
        ]);
        expect(clearRes.status()).toBe(200);

        expect((await leaseOf(leaseMainId)).status, 'the register is empty, so the contract closes').toBe('CLOSED');
        await assertTrialBalanceBalances('09 (closure)');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 10 ──────────────────────────────────────────────────────────────────────

test('10 a settlement that collects: no bank line, a CASH row on the register, and closure when it is received', async ({ browser }) => {
    const { page, close } = await recorded(browser, '10-settlement-balance-due');
    try {
        // A term that starts AFTER the period lock of scenario 04, so its whole
        // schedule is closeable and the statement can be asserted to have
        // nothing left unrecognised. (LEASE_LOCKED, which starts inside the
        // closed period, is the opposite case and has its own scenario.)
        const balanceTerm = termFrom(1);
        const balance = await makePostedLease({
            propertyId: fx.palmId,
            label: 'BAL',
            rent: 12_000,
            lines: [
                { chargeTypeCode: 'RENT', grossAmount: 12_000 },
                { chargeTypeCode: 'SECURITY_DEPOSIT', grossAmount: 1_000 },
            ],
            start: balanceTerm.start,
            end: balanceTerm.end,
        });
        leaseBalanceId = balance.leaseId;
        balanceUnitNumber = balance.unitNumber;

        // Bank every instrument dated on or before T, so §9.1 keeps nothing and
        // the only thing left on this register will be the collection row the
        // settlement itself raises.
        const rows = await chequesOf(leaseBalanceId);
        for (const c of rows.filter(c => c.chequeDate !== null && c.chequeDate <= TERMINATION_DATE)) {
            await depositAndClear(c.id);
        }

        await adminApi('POST', `/api/v1/finance/recognition/run?to=${lastMonthEnd()}&preview=false`);
        const preview = await adminApi<{ chequesToReturn: ChequeRow[]; chequesToKeep: ChequeRow[] }>(
            'GET',
            `/api/v1/leases/${leaseBalanceId}/terminate/preview?date=${TERMINATION_DATE}`,
        );
        expect(preview.chequesToKeep.length, 'nothing is kept on this contract').toBe(0);
        await adminApi('POST', `/api/v1/leases/${leaseBalanceId}/terminate`, {
            terminationDate: TERMINATION_DATE,
            returnChequeIds: preview.chequesToReturn.map(c => c.id),
            keepChequeIds: [],
            notes: `WT3 balance-due move-out ${SUFFIX}`,
        });
        await adminApi('POST', `/api/v1/finance/recognition/run?to=${today()}&preview=false`);

        const statement = await adminApi<{
            netRefund: number;
            depositsHeld: number;
            instrumentsOutstanding: number;
            unrecognisedEntries: number;
        }>('GET', `/api/v1/leases/${leaseBalanceId}/settlement/preview`);
        expect(statement.netRefund, 'before any deduction this one would refund').toBeGreaterThan(0);
        expect(statement.instrumentsOutstanding, 'and its register is empty').toBe(0);
        expect(statement.unrecognisedEntries, 'and its rent is fully recognised to the termination date').toBe(0);

        // A deduction larger than the deposit plus the credit: the renter owes.
        const owed = 2_000;
        const deduction = round2(statement.netRefund + owed);

        await page.goto(`/en/dashboard/leases/${leaseBalanceId}/settlement`);
        await expect(page.getByTestId('settlement-deposits-held')).toHaveText(money(statement.depositsHeld), {
            timeout: 20_000,
        });
        await page.getByTestId('settlement-add-deduction').click();
        await page.getByTestId('settlement-category-0').selectOption('PROPERTY_DAMAGE');
        await page.getByTestId('settlement-description-0').fill(`WT3 kitchen refit ${SUFFIX}`);
        await page.getByTestId('settlement-amount-0').fill(String(deduction));

        await expect(page.getByTestId('settlement-balance-due')).toHaveText(money(owed));
        await expect(page.getByTestId('settlement-net-refund')).toHaveCount(0);
        // Nothing is being paid out, so there is no bank to name and nothing to
        // acknowledge — both controls are absent, not merely optional.
        await expect(page.getByLabel('Refund paid from')).toHaveCount(0);
        await expect(page.getByTestId('settlement-acknowledge')).toHaveCount(0);

        const [saveRes] = await Promise.all([
            page.waitForResponse(r => /\/settlement\/draft$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('settlement-save-draft').click(),
        ]);
        expect(saveRes.status()).toBe(200);
        await expect(page.getByTestId('settlement-unsaved')).toHaveCount(0, { timeout: 20_000 });

        await page.getByTestId('settlement-finalize').click();
        const [finalizeRes] = await Promise.all([
            page.waitForResponse(r => /\/settlement\/finalize$/.test(r.url()) && r.request().method() === 'POST'),
            page.getByTestId('settlement-finalize-confirm').click(),
        ]);
        expect(finalizeRes.status()).toBe(200);
        await expect(page.getByTestId('settlement-status')).toHaveText('Finalized', { timeout: 20_000 });

        const stored = await adminApi<{ balanceDue: number; refundAmount: number; collectionChequeId: string | null; journalId: string }>(
            'GET',
            `/api/v1/leases/${leaseBalanceId}/settlement`,
        );
        expect(stored.balanceDue).toBeCloseTo(owed, 2);
        expect(stored.refundAmount).toBe(0);
        expect(stored.collectionChequeId, 'a balance due raises its own register row').toBeTruthy();

        type Line = { accountName: string; debit: number; credit: number };
        const stl = await adminApi<{ lines: Line[] }>('GET', `/api/v1/finance/journals/${stored.journalId}`);
        expect(
            stl.lines.some(l => /Emirates Islamic|Bank|Cash Account/i.test(l.accountName) && l.credit > 0),
            'a settlement that collects pays nothing out, so the STL has no bank credit',
        ).toBe(false);

        const collection = await adminApi<{ mode: string; status: string; amount: number; narration: string | null }>(
            'GET',
            `/api/v1/cheques/${stored.collectionChequeId}`,
        );
        expect(collection.mode).toBe('CASH');
        expect(collection.status).toBe('REGISTERED');
        expect(collection.amount).toBeCloseTo(owed, 2);
        expect(collection.narration).toContain('Settlement balance due');
        await assertTrialBalanceBalances('10 (finalise)');

        // ── receive it on the register ──
        await page.goto(`/en/dashboard/finance/cheques?search=${encodeURIComponent(balanceUnitNumber)}`);
        await expect(page.getByTestId(`cheque-row-${stored.collectionChequeId}`)).toBeVisible({ timeout: 20_000 });
        await page.getByTestId(`cheque-row-action-receive-${stored.collectionChequeId}`).click();
        const [receiveRes] = await Promise.all([
            page.waitForResponse(r => /\/cheques\/[0-9a-f-]{36}\/receive$/.test(r.url()) && r.request().method() === 'PUT'),
            page.getByTestId('cheque-receive-confirm').click(),
        ]);
        expect(receiveRes.status()).toBe(200);

        expect((await leaseOf(leaseBalanceId)).status, 'nothing is left to collect, so it closes').toBe('CLOSED');
        type AccountLedger = { accountName: string; closingBalance: number };
        const ledger = await adminApi<AccountLedger[]>('GET', `/api/v1/finance/ledger?leaseId=${leaseBalanceId}`);
        const receivable = ledger.find(l => /Rent Receivable/i.test(l.accountName));
        expect(receivable, 'the contract has a rent receivable line').toBeTruthy();
        expect(receivable!.closingBalance, 'and it is settled to nothing').toBeCloseTo(0, 2);

        await assertTrialBalanceBalances('10 (closure)');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 11 ──────────────────────────────────────────────────────────────────────
//
// SKIPPED, deliberately and with the reason in the open.
//
// The expiry path (spec §9.2's "a tenancy that simply ran its course") is
// driven by `LeaseExpirationJob.evaluateExpiredLeases`, a `@Scheduled(cron =
// "0 0 0 * * ?")` method behind ShedLock. `runFor(LocalDate)` is public, but
// nothing exposes either over HTTP: `grep -rn "ExpirationJob" backend/src/main/
// java/com/datagami/rentaxis/api/` matches no controller, and the task brief
// forbids adding one. The only other way to reach EXPIRED is to write the
// status straight into the database, which is exactly the kind of faking a
// walkthrough exists to avoid — a lease poked to EXPIRED would prove that the
// settlement screen renders, and nothing at all about the job that is supposed
// to put it there.
//
// What IS covered without it: `SettlementService.SETTLEABLE` admits EXPIRED
// alongside TERMINATED and CLOSED, and `SettlementServiceIT
// .anExpiredLeaseIsSettledByTheSameStatement` /
// `.anExpiredLeaseCannotBeSettledBeforeItEnded` exercise that path end to end
// against a lease expired through `markExpired`. See task-9-report.md,
// "Skipped scenarios".
test.skip('11 a lease that simply ran its course is settled by the same statement', async () => {
    /* unreachable from HTTP — see the comment above */
});

// ── 12 ──────────────────────────────────────────────────────────────────────

test('12 the four new screens read right-to-left in Arabic', async ({ browser }) => {
    const { page, close } = await recorded(browser, '12-arabic-rtl');
    try {
        const ar = JSON.parse(
            fs.readFileSync(path.join(__dirname, '..', 'messages', 'ar.json'), 'utf8'),
        ) as Record<string, Record<string, string>>;

        const intlErrors: string[] = [];
        page.on('console', msg => {
            if (msg.type() !== 'error') return;
            const text = msg.text();
            if (/MISSING_MESSAGE|IntlError|next-intl/i.test(text)) intlErrors.push(text);
        });

        /**
         * A rendered translation key is the failure this scenario exists to
         * catch: `Settlement.earnedRent` on screen means next-intl fell through.
         * Scoped to the namespaces this plan added plus the ones its screens
         * borrow, so a unit number or a file name cannot be mistaken for one.
         */
        const RAW_KEY = /\b(Recognition|Termination|Settlement|Ledger|Leasing|Common|MasterData|Navigation)\.[a-zA-Z]/;

        const rtlPage = async (url: string, expectations: string[], label: string) => {
            await page.goto(url);
            await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
            for (const text of expectations) {
                await expect(page.locator('body'), `${label} must show "${text}"`).toContainText(text, {
                    timeout: 20_000,
                });
            }
            const body = await page.locator('body').innerText();
            const leaked = body.match(RAW_KEY);
            expect(leaked?.[0] ?? null, `${label} must render no raw translation key`).toBeNull();
            await hold(page, 1400);
        };

        // The recognition tab on a contract that still has a live schedule.
        await page.goto(`/ar/dashboard/leases/${leaseAmendId}`);
        await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
        await page.getByTestId('lease-tab-recognition').click();
        await expect(page.getByTestId('recognition-schedule')).toBeVisible({ timeout: 20_000 });
        await expect(page.locator('body')).toContainText(ar.Recognition.schedule);
        await expect(page.locator('body')).toContainText(ar.Recognition.scheduleTotal);
        expect(
            (await page.locator('body').innerText()).match(RAW_KEY)?.[0] ?? null,
            'the recognition tab must render no raw translation key',
        ).toBeNull();
        // Amounts stay in Western digits with the same grouping as the English
        // screens — a page that reformatted its numbers per locale could not be
        // reconciled against the English one.
        await expect(page.getByTestId('recognition-schedule-total')).toHaveText(/^\d{1,3}(,\d{3})*\.\d{2}$/);
        await hold(page, 1400);

        await rtlPage(
            '/ar/dashboard/finance/recognition',
            [ar.Recognition.title, ar.Recognition.toDate, ar.Recognition.run],
            'the month-end screen',
        );

        await rtlPage(
            `/ar/dashboard/leases/${leaseAmendId}/terminate`,
            [ar.Termination.title, ar.Termination.earnedThroughDate, ar.Termination.uncleared],
            'the termination screen',
        );

        await rtlPage(
            `/ar/dashboard/leases/${leaseMainId}/settlement`,
            [ar.Settlement.title, ar.Settlement.earnedRent, ar.Settlement.depositsHeld],
            'the settlement screen',
        );

        expect(intlErrors, 'no next-intl error may reach the console on any of the four screens').toEqual([]);
    } finally {
        await close();
    }

    console.log(`\n  manifest: ${manifest.created.length} records created — ${MANIFEST}`);
});
