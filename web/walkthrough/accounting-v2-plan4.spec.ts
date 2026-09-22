import { test, expect, type Browser, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { buildXlsx } from './minimal-xlsx';

/**
 * Accounting v2, plan 4 (vouchers, opening balances, reconciliation, the
 * cut-over import and its bulk post) — every scenario the feature can express,
 * one recording each. Modelled exactly on `accounting-v2-plan3.spec.ts` (read
 * that file's own header first).
 *
 * The recording and the proof are the same run: each scenario asserts its own
 * outcome, and a take only exists because those assertions passed. There is no
 * "GAP" fallback anywhere in this file — a scenario that cannot prove its point
 * fails.
 *
 * Runs against the LOCAL dev stack (Next on 3001, backend on 8081) and
 * provisions ONE disposable tenant of its own through the API — a tenant admin,
 * an accountant and a property manager assigned to the one property that exists
 * before the cut-over. Nothing here touches production.
 *
 * ── The calendar, which is the one thing to understand before reading on ──
 *
 * The books open on **2025-10-01**, so `books_locked_through` is 2025-09-30 the
 * moment the date is set (`TenantFiscalSettingsService.setBooksStartDate`), and
 * the whole imported portfolio lives *before* that line while every voucher the
 * walkthrough types lives after it. Concretely:
 *
 *   - the cut-over contract runs 2025-07-01 → 2026-06-30 (365 days) on a
 *     contract dated 2025-06-20, with its cheques cleared and bounced through
 *     July and August 2025;
 *   - the opening-balance journal is dated 2025-09-30, inside the locked period
 *     by construction — which is why `PostingService` exempts `docType = OB`;
 *   - every PISR/BPV is dated today, comfortably after the lock, so scenario 04
 *     can close a *later* period (the end of last month) and prove a draft
 *     inside it cannot post;
 *   - the bulk post writes journals dated June–September 2025, every one of them
 *     inside the lock and every one of them exempt because it carries the
 *     batch id.
 *
 * The portfolio's figures are not invented here: they are the hand-derived table
 * in `task-11-review.md` §3, shifted back one year so the term has ended in the
 * past rather than running into the future. The shift is exact — July 31 days,
 * August 31, September 30, a 365-day term either way — so every figure below is
 * the reviewer's, to the fil:
 *
 *   Rent Receivable 15,000.00 Dr · PDC Receivable 30,000.00 Dr · Bank 20,000.00 Dr
 *   Advance Rent 44,876.71 Cr · Security Deposit 5,000.00 Cr · Rental Income 15,123.29 Cr
 *
 * ── Why the workbook's six account columns name the FIRST property's leaves ──
 *
 * The Properties sheet may name an account that already exists, and this one
 * does: the imported building posts into the ledger leaves the walkthrough's
 * first property already owns. That is what makes the reconciliation scenario
 * real — PACT's uploaded trial balance can carry figures for accounts that exist
 * *before* the import, so the derived column moving from 0.00 to the hand-derived
 * figures is observable on the same rows.
 *
 * ORDER MATTERS. The period lock in scenario 04 only ever moves forwards, the
 * books-start date cannot change once an opening-balance journal is live, and the
 * import refuses a property name the organisation already holds — so scenarios
 * 04, 05 and 07 are each one-shot facts about this tenant.
 *
 * ── THE ORDER HAS TO CHANGE ON THE NEXT STACK ──
 *
 * This file was written and recorded against the jar built from `da2bd454`. Three
 * backend commits landed on the branch while it ran, and two of them move the
 * ground under the order above:
 *
 *   - `2aefd796` refuses **Post batch, Reverse batch and Post again while an
 *     opening-balance journal is live** — opening balances become the LAST step
 *     of a cut-over. Scenarios 08 and 09 would be refused as this file stands,
 *     because 05 posts the OB journal first.
 *   - `d59ad4be` makes the OB journal post **PACT minus what step 1 already
 *     left on the books**, so 05's posted figures and the deliberate omission of
 *     the bank line from the uploaded trial balance both change meaning.
 *   - `cbfb558a` pins each batch-reversal mirror to the entry it reverses and
 *     drops `date` from `ReverseBatchDTO`, so 09's date picker becomes a
 *     no-op the web can remove.
 *
 * What to do when the jar is rebuilt: run the cut-over first (07 → 08 → 09),
 * upload the PACT snapshot before them but POST the opening balances after, and
 * re-derive 05's trial-balance expectations from the delta rule (PACT's bank
 * figure belongs in the CSV again, and the journal posts the difference). The
 * task-17 report's "Hand-off" section spells it out line by line.
 */

const BACKEND = process.env.WT_BACKEND_URL || 'http://localhost:8081';
const BASE_URL = process.env.WT_BASE_URL || 'http://localhost:3001';

const TAKES_DIR = path.join(__dirname, 'takes', 'accounting-v2-plan4');
const STATE_ADMIN = path.join(__dirname, 'raw', 'accounting-v2-plan4-admin.json');
const STATE_ACCOUNTANT = path.join(__dirname, 'raw', 'accounting-v2-plan4-accountant.json');
const SUFFIX = Math.random().toString(36).slice(2, 7);
const MANIFEST = path.join(__dirname, `run-manifest-accounting-v2-plan4-${SUFFIX}.json`);

// ── dates ───────────────────────────────────────────────────────────────────

const pad = (n: number) => String(n).padStart(2, '0');
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const NOW = new Date();
const today = () => iso(new Date());
/** The last day of the month before this one — what scenario 04 closes. */
const LAST_MONTH_END = iso(new Date(NOW.getFullYear(), NOW.getMonth(), 0));
/** A day inside that closed month, for the draft that cannot post. */
const INSIDE_LOCKED = iso(new Date(NOW.getFullYear(), NOW.getMonth() - 1, 15));

/** Spec §10.3's `D`: the day the tenant's books open. */
const BOOKS_START = '2025-10-01';
/** `D − 1`: the opening-balance date, and the as-at of the reconciliation. */
const AS_OF = '2025-09-30';

const CONTRACT_DATE = '2025-06-20';
const TERM_START = '2025-07-01';
const TERM_END = '2026-06-30';

const nf = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
/** The same string `fmtAmount` renders (web/src/lib/api/ledger.ts). */
const money = (n: number) => nf.format(n);
/** The same string `fmtBalance` renders. */
const balance = (n: number) => (Math.abs(n) < 0.005 ? '0.00' : n > 0 ? `${nf.format(n)} Dr` : `${nf.format(-n)} Cr`);
const round2 = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100;

/** The hand-derived lease-dimension balances, debit-positive (task-11-review §3). */
const EXPECTED = {
    rentReceivable: 15_000,
    pdcReceivable: 30_000,
    bank: 20_000,
    advanceRent: -44_876.71,
    securityDeposit: -5_000,
    rentalIncome: -15_123.29,
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

type Cred = { id: string; email: string; password: string };
type RoleMapping = { role: string; accountId: string; accountCode: string; accountName: string };
type AccountRow = { id: string; code: string; name: string; group: boolean; accountType: string };

type Fixtures = {
    tenantId: string;
    admin: Cred;
    accountant: Cred;
    manager: Cred;
    propertyId: string;
    propertyName: string;
    units: { id: string; unitNumber: string }[];
    vendorId: string;
    vendorName: string;
    vendorPayable: AccountRow;
    expense: AccountRow;
    cash: AccountRow;
    capital: AccountRow;
    /** OPENING_BALANCE_DIFFERENCE — the row the server computes for itself. */
    difference: AccountRow;
    roles: Record<string, RoleMapping>;
};

let fx: Fixtures;

/** Filled in by 01, read by 02, 03 and 04. */
let invoiceId = '';
let invoiceJournalId = '';
let invoiceNumber = '';
/** The replacement voucher 03 posts. */
let amendedVoucherId = '';
/** The DRAFT batch 07 imports, and the successor 09 posts. */
let batchId = '';
let successorBatchId = '';
/** The lease the import creates. */
let importedLeaseId = '';
/** The trial balance before the bulk post, so 09 can prove the reverse restores it. */
let trialBalanceBeforePost: Record<string, number> = {};
/** The lease-dimension balances the first post produced, so the re-post can be compared with them. */
let leaseBalancesAfterPost: Record<string, number> = {};

// ── recording ───────────────────────────────────────────────────────────────

type Take = { page: Page; close: () => Promise<void> };

async function recorded(browser: Browser, takeName: string, opts: { state?: string | null } = {}): Promise<Take> {
    fs.mkdirSync(TAKES_DIR, { recursive: true });
    const state = opts.state === undefined ? STATE_ADMIN : opts.state;
    const context = await browser.newContext({
        baseURL: BASE_URL,
        recordVideo: { dir: TAKES_DIR, size: { width: 1280, height: 720 } },
        ...(state ? { storageState: state } : {}),
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

/** Sign in once in a throwaway context purely to bank the session for later takes. */
async function bankSession(browser: Browser, email: string, password: string, statePath: string) {
    const context = await browser.newContext({ baseURL: BASE_URL });
    const page = await context.newPage();
    await signIn(page, email, password);
    await context.storageState({ path: statePath });
    await context.close();
}

async function hold(page: Page, ms = 2200) {
    await page.waitForTimeout(ms);
}

// ── the admin's own API client ──────────────────────────────────────────────

let adminActor: Actor;
let managerActor: Actor;

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

type TrialBalanceRow = { accountId: string; code: string; name: string; debit: number; credit: number; balance: number };

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

/** Account code → signed debit-positive balance, as at `asOf`. */
async function trialBalanceAt(asOf: string): Promise<Record<string, number>> {
    const rows = await adminApi<TrialBalanceRow[]>('GET', `/api/v1/finance/trial-balance?asOf=${asOf}`);
    return Object.fromEntries(rows.map(r => [r.code, round2(r.balance)]));
}

type AccountLedger = {
    accountId: string;
    accountCode: string;
    accountName: string;
    totalDebit: number;
    totalCredit: number;
    closingBalance: number;
};

/** One contract's own ledger: account code → closing balance, debit-positive. */
async function leaseBalances(leaseId: string, asOf: string): Promise<Record<string, number>> {
    const ledgers = await adminApi<AccountLedger[]>(
        'GET',
        `/api/v1/finance/ledger?leaseId=${leaseId}&from=2000-01-01&to=${asOf}`,
    );
    return Object.fromEntries(ledgers.map(l => [l.accountCode, round2(l.closingBalance)]));
}

type JournalLine = { accountId: string; accountCode: string; accountName: string; debit: number; credit: number };
type Journal = {
    id: string;
    entryNumber: string;
    docType: string;
    entryDate: string;
    status: string;
    leaseId: string | null;
    importBatchId: string | null;
    reversedById: string | null;
    sourceType: string | null;
    sourceId: string | null;
    total: number;
    lines: JournalLine[];
};

const journalOf = (id: string) => adminApi<Journal>('GET', `/api/v1/finance/journals/${id}`);

type Voucher = {
    id: string;
    docType: string;
    docDate: string;
    status: string;
    voucherNumber: string | null;
    journalId: string | null;
    amendedFromId: string | null;
    netTotal: number;
    vatTotal: number;
    grossTotal: number;
    lines: { accountId: string; amount: number; vatRate: number; vatAmount: number }[];
};

const voucherOf = (id: string) => adminApi<Voucher>('GET', `/api/v1/finance/vouchers/${id}`);

type ImportBatch = {
    id: string;
    status: string;
    label: string | null;
    leasesImported: number;
    journalsPosted: number;
    repostOf: string | null;
};

const batchesOf = () => adminApi<ImportBatch[]>('GET', '/api/v1/finance/import-batches');

// ── UI helpers ──────────────────────────────────────────────────────────────

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * `AccountPicker` is a search box, not a select: type enough of the code for the
 * option list to appear, then click the option that carries it.
 */
async function pickAccount(page: Page, label: string, index: number, code: string) {
    const input = page.getByLabel(label, { exact: true }).nth(index);
    await input.click();
    await input.fill(code);
    await page.getByRole('button', { name: new RegExp(escapeRe(code)) }).first().click();
    await expect(input).toHaveValue(new RegExp(escapeRe(code)));
}

/** A file the browser will hand to a hidden `<input type=file>`. */
const asUpload = (name: string, mimeType: string, buffer: Buffer) => ({ name, mimeType, buffer });

/** A real PDF, because `VoucherAttachmentService` checks the file's signature. */
const PDF_BYTES = Buffer.from(
    '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n' +
        '2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n' +
        '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n' +
        'trailer<</Root 1 0 R>>\n%%EOF\n',
    'latin1',
);

// ── the cut-over workbooks ──────────────────────────────────────────────────

type WorkbookOptions = {
    property: string;
    units: string[];
    renterName: string;
    renterEmail: string;
    ref: string;
    /** Names the six ledger accounts; a deliberately unknown bank name is an error. */
    bankAccountName: string;
    /** The rent line, the deposit line and the five instruments, or a single simple pair. */
    shape: 'handDerived' | 'simple';
    /** Adds a second Contracts row under the SAME number but a different unit. */
    duplicateContractNumber?: boolean;
};

/**
 * The workbook the cut-over screen accepts, built to the exact header rows
 * `PortfolioTemplateService.generateCutOverTemplate` emits.
 *
 * The `handDerived` shape is `task-11-review.md` §3's contract: rent 60,000 and a
 * 5,000 deposit against five instruments of which one cleared early, one cleared
 * late, one bounced from DEPOSITED and two are still registered.
 */
function cutoverWorkbook(o: WorkbookOptions): Buffer {
    const r = fx.roles;
    const properties: string[][] = [
        [
            'PropertyName', 'PropertyNameAr', 'Emirate', 'Address', 'Type', 'MakaniNumber',
            'RentalIncomeAccount', 'RentalReceivableAccount', 'AdvanceRentAccount',
            'BankAccount', 'PdcReceivableAccount', 'SecurityDepositAccount',
        ],
        [
            o.property, '', 'DUBAI', '2 Cut-over Street, Dubai', 'RESIDENTIAL', '',
            r.RENTAL_INCOME.accountName, r.RENT_RECEIVABLE.accountName, r.ADVANCE_RENT.accountName,
            o.bankAccountName, r.PDC_RECEIVABLE.accountName, r.SECURITY_DEPOSIT.accountName,
        ],
    ];

    const units: string[][] = [
        ['PropertyName', 'BuildingName', 'UnitNumber', 'UnitType', 'SizeSqft', 'ExpectedRent'],
        ...o.units.map(u => [o.property, '', u, 'BHK1', '900', '60000']),
    ];

    const renters: string[][] = [
        ['Name', 'NameAr', 'Email', 'Phone'],
        [o.renterName, '', o.renterEmail, '+971500000009'],
    ];

    const contractHeader = [
        'ContractNumber', 'EjariNumber', 'PropertyName', 'BuildingName', 'UnitNumber', 'RenterEmail',
        'ContractDate', 'StartDate', 'EndDate', 'GracePeriodDays', 'LineNo', 'ChargeTypeCode',
        'CreditAccount', 'GrossAmount', 'DiscountAmount', 'VatApplicable', 'Narration',
    ];
    const rent = o.shape === 'handDerived' ? '60000.00' : '12000.00';
    const contracts: string[][] = [
        contractHeader,
        [
            o.ref, `EJ-${SUFFIX}`, o.property, '', o.units[0], o.renterEmail,
            CONTRACT_DATE, TERM_START, TERM_END, '5', '1', 'RENT', '', rent, '0', 'false', 'Annual rent',
        ],
    ];
    if (o.shape === 'handDerived') {
        contracts.push([
            o.ref, '', '', '', '', '', '', '', '', '', '2', 'SECURITY_DEPOSIT', '', '5000.00', '0', 'false',
            'Security deposit',
        ]);
    }
    if (o.duplicateContractNumber) {
        // Two DIFFERENT contracts typed under one number: the second row names
        // another unit, which `ContractImportValidator.HEADER_COLUMNS` refuses.
        contracts.push([
            o.ref, `EJ-${SUFFIX}-B`, o.property, '', o.units[1], o.renterEmail,
            CONTRACT_DATE, TERM_START, TERM_END, '5', '3', 'RENT', '', '18000.00', '0', 'false',
            'A second contract under the first one’s number',
        ]);
    }

    const chequeHeader = [
        'ContractNumber', 'SeqNo', 'PostingDate', 'ChequeNumber', 'ChequeDate', 'PayeeBank',
        'DebitAccount', 'Amount', 'Narration', 'Mode', 'Status', 'DepositedDate', 'ClearedDate', 'BouncedDate',
    ];
    const cheques: string[][] =
        o.shape === 'handDerived'
            ? [
                  chequeHeader,
                  [o.ref, '1', CONTRACT_DATE, '900101', '2025-07-01', 'Cut-over Bank', '', '5000.00',
                      'Deposit cheque', 'PDC', 'CLEARED', '2025-07-01', '2025-07-03', ''],
                  [o.ref, '2', CONTRACT_DATE, '900102', '2025-07-01', 'Cut-over Bank', '', '15000.00',
                      'Instalment 1', 'PDC', 'CLEARED', '2025-07-01', '2025-07-05', ''],
                  [o.ref, '3', CONTRACT_DATE, '900103', '2025-08-01', 'Cut-over Bank', '', '15000.00',
                      'Instalment 2', 'PDC', 'BOUNCED', '2025-08-01', '', '2025-08-05'],
                  [o.ref, '4', CONTRACT_DATE, '900104', '2025-10-01', 'Cut-over Bank', '', '15000.00',
                      'Instalment 3', 'PDC', 'REGISTERED', '', '', ''],
                  [o.ref, '5', CONTRACT_DATE, '900105', '2026-01-01', 'Cut-over Bank', '', '15000.00',
                      'Instalment 4', 'PDC', 'REGISTERED', '', '', ''],
              ]
            : [
                  chequeHeader,
                  [o.ref, '1', CONTRACT_DATE, '900201', '2025-07-01', 'Cut-over Bank', '', '12000.00',
                      'Annual rent', 'PDC', 'REGISTERED', '', '', ''],
              ];

    return buildXlsx([
        { name: 'Properties', rows: properties },
        { name: 'Units', rows: units },
        { name: 'Renters', rows: renters },
        { name: 'Contracts', rows: contracts },
        { name: 'Cheques', rows: cheques },
    ]);
}

// ── 00 ──────────────────────────────────────────────────────────────────────

test.describe.configure({ mode: 'serial' });

test('00 provision a tenant, its chart, the three finance roles and a vendor with a payable', async ({ browser }) => {
    const su = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', {
        email: 'admin@rentaxis.com',
        password: 'admin123',
    });
    const superAdmin: Actor = { id: su.id, role: su.role, tenantId: null };

    const tenantName = `WALKTHROUGH-ACCOUNTING-V2-PLAN4 ${today()} ${SUFFIX}`;
    const tenant = await api<{ id: string }>(superAdmin, 'POST', '/api/admin/tenants', { name: tenantName });
    expect(tenant.id, 'tenant must be created').toBeTruthy();
    record('tenant', tenant.id, tenantName);

    const scoped: Actor = { ...superAdmin, tenantId: tenant.id };
    const password = `Walk!${SUFFIX}9`;
    const make = async (role: string, slug: string, name: string): Promise<Cred> => {
        const email = `wt4-${slug}-${SUFFIX}@example.invalid`;
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
    // chained server-side (AccountController#seedDefaultAccounts). Everything
    // downstream — the voucher's input VAT, the opening-balance difference, the
    // six roles the imported contract posts through — comes from this one call.
    await api(scoped, 'POST', '/api/v1/finance/accounts/seed');

    const propertyName = `WT4 Ledger House ${SUFFIX}`;
    const property = await api<{ id: string }>(scoped, 'POST', '/api/v1/properties', {
        nameEn: propertyName,
        nameAr: propertyName,
        address: '1 Cut-over Street, Dubai',
        emirate: 'DUBAI',
        type: 'RESIDENTIAL',
    });
    record('property', property.id, propertyName);

    const units: { id: string; unitNumber: string }[] = [];
    for (const unitNumber of [`WT4-${SUFFIX}-A`, `WT4-${SUFFIX}-B`]) {
        const unit = await api<{ id: string; unitNumber: string }>(scoped, 'POST', '/api/v1/units', {
            property: { id: property.id },
            unitNumber,
            type: 'BHK1',
            sizeSqft: 900,
            expectedRent: 60_000,
        });
        record('unit', unit.id, unitNumber);
        units.push({ id: unit.id, unitNumber });
    }

    // The manager is assigned to the one property that exists, so scenario 04's
    // refusal is about the voucher module rather than about an unassigned user
    // being refused everything for the wrong reason.
    await api(scoped, 'POST', `/api/admin/users/${manager.id}/properties/${property.id}`);

    // Creating the property generated one leaf per property-scoped role
    // (PropertyAccountService.generateMissing). Those six names are what the
    // cut-over workbook will name in scenario 07.
    const mappings = await api<RoleMapping[]>(scoped, 'GET', `/api/v1/properties/${property.id}/accounts`);
    const roles = Object.fromEntries(mappings.filter(m => m.accountId).map(m => [m.role, m])) as Record<string, RoleMapping>;
    for (const role of ['RENT_RECEIVABLE', 'ADVANCE_RENT', 'RENTAL_INCOME', 'PDC_RECEIVABLE', 'BANK', 'SECURITY_DEPOSIT']) {
        expect(roles[role], `${role} must be mapped to a generated leaf`).toBeTruthy();
    }

    const accounts = await api<AccountRow[]>(scoped, 'GET', '/api/v1/finance/accounts');
    const byCode = (code: string) => {
        const a = accounts.find(x => x.code === code);
        expect(a, `the seeded chart must carry ${code}`).toBeTruthy();
        return a as AccountRow;
    };
    const cash = byCode('A-02-05-001');
    const capital = byCode('F-01');
    // OPENING_BALANCE_DIFFERENCE: the row scenario 05's journal balances against.
    const difference = byCode('F-02');

    // A maintenance expense leaf for the purchase invoice: the seeded chart has
    // no direct-expense leaf of its own, only the D-01 group to hang one under.
    const expense = await api<AccountRow>(scoped, 'POST', '/api/v1/finance/accounts', {
        code: `D-01-${SUFFIX.toUpperCase()}`,
        nameEn: `Maintenance Expense ${SUFFIX}`,
        nameAr: `Maintenance Expense ${SUFFIX}`,
        accountType: 'EXPENSE',
        accountSubType: 'OTHER_EXPENSE',
        parentId: byCode('D-01').id,
        group: false,
    });
    record('account', expense.id, `${expense.code} ${expense.name}`);

    const vendorName = `WT4 Facilities ${SUFFIX}`;
    const vendor = await api<{ id: string; payableAccount: AccountRow }>(scoped, 'POST', '/api/v1/vendors', {
        nameEn: vendorName,
        nameAr: vendorName,
        email: `wt4-vendor-${SUFFIX}@example.invalid`,
        phone: '+971500000002',
    });
    expect(vendor.payableAccount, 'a vendor is created with its own payable leaf').toBeTruthy();
    record('vendor', vendor.id, `${vendorName} (${vendor.payableAccount.code})`);

    // Auto-proposal ON with a threshold of one, so scenario 08's "no penalty was
    // raised for imported history" is a real refusal rather than a switch that
    // happened to be off.
    await api(scoped, 'PUT', '/api/v1/settings/fines', {
        bounceAmount: 500,
        signatureMismatchAmount: 500,
        accountClosedAmount: 500,
        graceDays: 0,
        perDayRate: 0,
        bouncesBeforePenalty: 1,
        autoProposeChequeReturn: true,
        autoProposeLatePayment: true,
    });

    // The books open on D; the lock follows to D − 1 on its own, which is the
    // whole reason the cut-over needs its exemptions.
    const fiscal = await api<{ booksStartDate: string; booksLockedThrough: string }>(
        scoped,
        'PUT',
        '/api/v1/finance/fiscal-settings',
        { booksStartDate: BOOKS_START },
    );
    expect(fiscal.booksStartDate, 'the books open on D').toBe(BOOKS_START);
    expect(fiscal.booksLockedThrough, 'and everything before D is closed by construction').toBe(AS_OF);

    fx = {
        tenantId: tenant.id,
        admin,
        accountant,
        manager,
        propertyId: property.id,
        propertyName,
        units,
        vendorId: vendor.id,
        vendorName,
        vendorPayable: vendor.payableAccount,
        expense,
        cash,
        capital,
        difference,
        roles,
    };

    await bankSession(browser, accountant.email, accountant.password, STATE_ACCOUNTANT);

    const { page, close } = await recorded(browser, '00-provision-tenant-and-chart', { state: null });
    try {
        await signIn(page, admin.email, admin.password);
        await page.context().storageState({ path: STATE_ADMIN });

        await page.goto('/en/dashboard/properties');
        await expect(page.getByText(propertyName).first()).toBeVisible({ timeout: 20_000 });

        // The six leaves the cut-over workbook will name in scenario 07, as the
        // property's own Ledger accounts tab shows them.
        await page.goto(`/en/dashboard/properties/${property.id}`);
        await page.getByRole('button', { name: 'Ledger accounts' }).click();
        const pdcRow = page.locator('[data-testid="property-account-row"][data-role="PDC_RECEIVABLE"]');
        await expect(pdcRow).toContainText(fx.roles.PDC_RECEIVABLE.accountName, { timeout: 20_000 });
        await expect(
            page.locator('[data-testid="property-account-row"][data-role="ADVANCE_RENT"]'),
        ).toContainText(fx.roles.ADVANCE_RENT.accountName);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 01 ──────────────────────────────────────────────────────────────────────

test('01 a purchase invoice: VAT is rounded per line, the paperwork is attached, the journal is exact', async ({ browser }) => {
    const { page, close } = await recorded(browser, '01-purchase-invoice-pisr');
    try {
        await page.goto('/en/dashboard/finance/vouchers');
        await expect(page.getByTestId('vouchers-empty')).toBeVisible({ timeout: 20_000 });
        await page.getByTestId('new-purchase-invoice').click();

        await expect(page.getByTestId('voucher-form')).toHaveAttribute('data-voucher-type', 'PISR');
        await expect(page.getByTestId('doc-date')).toHaveValue(today());
        await expect(page.getByTestId('voucher-status')).toHaveAttribute('data-status', 'DRAFT');

        await page.getByTestId('vendor').selectOption(fx.vendorId);
        await page.getByTestId('invoice-number').fill(`INV-${SUFFIX}-01`);
        await page.getByTestId('narration').fill('Quarterly lift maintenance');
        await page.getByTestId('property').selectOption(fx.propertyId);

        // Three lines of 100.10 at 5%. The point of the fixture: 5% of the 300.30
        // total is 15.015 → 15.02, but VoucherMath rounds PER LINE — 5.01 three
        // times — so the header agrees with the vendor's own invoice at 15.03.
        for (let i = 0; i < 3; i++) {
            if (i > 0) await page.getByTestId('add-line').click();
            await pickAccount(page, 'Account', i, fx.expense.code);
            await page.getByTestId(`line-description-${i}`).fill(`Lift ${i + 1}`);
            await page.getByTestId(`line-amount-${i}`).fill('100.10');
            await page.getByTestId(`line-vat-rate-${i}`).selectOption('5');
            await expect(page.getByTestId(`line-vat-amount-${i}`)).toHaveText(money(5.01));
            await expect(page.getByTestId(`line-total-${i}`)).toHaveText(money(105.11));
        }
        // Line dimensions travel per line: the first line is priced to a unit.
        await page.getByTestId('line-property-0').selectOption(fx.propertyId);
        await page.getByTestId('line-unit-0').selectOption(fx.units[0].id);

        await expect(page.getByTestId('net-total')).toHaveText(money(300.3));
        await expect(page.getByTestId('vat-total')).toHaveText(money(15.03));
        await expect(page.getByTestId('vat-total')).not.toHaveText(money(15.02));
        await expect(page.getByTestId('gross-total')).toHaveText(money(315.33));
        await hold(page, 1200);

        // Wait for the draft to exist before attaching to it: the form hangs an
        // attachment off `savedId`, and firing the file picker while the create
        // is still in flight makes a SECOND draft rather than attaching to the
        // first.
        const saved = page.waitForResponse(
            r => r.url().includes('/v1/finance/vouchers') && r.request().method() === 'POST' && r.status() < 400,
        );
        await page.getByTestId('save-draft').click();
        await saved;
        await expect(page.getByTestId('voucher-status')).toHaveAttribute('data-status', 'DRAFT');

        await page.getByTestId('attachment-input').setInputFiles(
            asUpload(`supplier-invoice-${SUFFIX}.pdf`, 'application/pdf', PDF_BYTES),
        );
        await expect(page.locator('[data-testid^="attachment-row-"]')).toHaveCount(1, { timeout: 20_000 });
        await expect(page.getByTestId('attachments-panel')).toContainText(`supplier-invoice-${SUFFIX}.pdf`);

        await expect(page.getByTestId('voucher-blocker')).toHaveCount(0);
        await page.getByTestId('post-voucher').click();
        await page.getByTestId('confirm-post').click();
        await page.waitForURL(/finance\/vouchers$/, { timeout: 30_000 });

        // ── the document, as the server holds it ──
        const listed = await adminApi<{ content: Voucher[] }>('GET', '/api/v1/finance/vouchers?docType=PISR');
        expect(listed.content.length, 'one purchase invoice').toBe(1);
        const posted = await voucherOf(listed.content[0].id);
        invoiceId = posted.id;
        invoiceJournalId = posted.journalId as string;
        invoiceNumber = posted.voucherNumber as string;
        record('voucher', posted.id, invoiceNumber);
        expect(posted.status).toBe('POSTED');
        expect(invoiceNumber, 'the number is the journal entry number, taken at posting').toMatch(/^PISR-/);
        expect(posted.netTotal).toBeCloseTo(300.3, 2);
        expect(posted.vatTotal, 'the server rounds per line too').toBeCloseTo(15.03, 2);
        expect(posted.grossTotal).toBeCloseTo(315.33, 2);

        await expect(page.getByTestId(`voucher-number-${posted.id}`)).toHaveText(invoiceNumber);
        await expect(page.getByTestId(`voucher-status-${posted.id}`)).toHaveAttribute('data-status', 'POSTED');

        // ── the journal: Dr expense, Dr input VAT, Cr the vendor's payable ──
        const journal = await journalOf(invoiceJournalId);
        expect(journal.docType).toBe('PISR');
        expect(journal.sourceType, 'a voucher-sourced entry').toBe('VOUCHER');
        const debits = journal.lines.filter(l => l.debit > 0);
        const credits = journal.lines.filter(l => l.credit > 0);
        expect(round2(debits.filter(l => l.accountId === fx.expense.id).reduce((s, l) => s + l.debit, 0)))
            .toBeCloseTo(300.3, 2);
        const inputVat = debits.find(l => /input vat/i.test(l.accountName));
        expect(inputVat, 'the summed per-line VAT is debited to INPUT_VAT').toBeTruthy();
        expect(inputVat?.debit).toBeCloseTo(15.03, 2);
        expect(credits.length, 'one credit: the vendor').toBe(1);
        expect(credits[0].accountId).toBe(fx.vendorPayable.id);
        expect(credits[0].credit).toBeCloseTo(315.33, 2);

        await page.getByTestId(`view-journal-${posted.id}`).click();
        await expect(page.locator('body')).toContainText(invoiceNumber, { timeout: 20_000 });
        await expect(page.locator('body')).toContainText(fx.expense.name);
        await expect(page.locator('body')).toContainText(fx.vendorPayable.name);
        await expect(page.locator('body')).toContainText(money(15.03));
        await hold(page, 1400);

        // ── and the vendor's own ledger ──
        await page.goto(`/en/dashboard/finance/general-ledger?vendorId=${fx.vendorId}`);
        await expect(page.locator('body')).toContainText(invoiceNumber, { timeout: 20_000 });
        await expect(page.locator('body')).toContainText(money(315.33));

        await assertTrialBalanceBalances('01');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 02 ──────────────────────────────────────────────────────────────────────

test('02 a payment voucher carries no VAT, settles only from bank or cash, and clears the vendor', async ({ browser }) => {
    const { page, close } = await recorded(browser, '02-payment-voucher-bpv');
    try {
        await page.goto('/en/dashboard/finance/vouchers');
        await page.getByTestId('new-payment-voucher').click();
        await expect(page.getByTestId('voucher-form')).toHaveAttribute('data-voucher-type', 'BPV');

        // No VAT anywhere on a payment: VoucherService.BPV_VAT_REFUSAL refuses a
        // rate on a payment line, and a field whose only legal value is zero is
        // not a field.
        await expect(page.getByTestId('line-vat-rate-0')).toHaveCount(0);
        await expect(page.getByTestId('line-vat-amount-0')).toHaveCount(0);
        await expect(page.getByTestId('vat-total')).toHaveCount(0);

        await page.getByTestId('vendor').selectOption(fx.vendorId);
        await page.getByTestId('narration').fill(`Settlement of ${invoiceNumber}`);
        await page.getByTestId('cheque-number').fill(`CHQ-${SUFFIX}`);
        await page.getByTestId('cheque-date').fill(today());

        // The payment account picker offers bank and cash leaves and nothing
        // else (SettlementAccountPicker's ASSET + BANK/CASH filter, mirroring
        // ChequeService.requireSettlementAccount).
        const payment = page.getByLabel('Select a bank or cash account', { exact: true });
        await payment.click();
        await payment.fill(fx.expense.code);
        await expect(
            page.getByRole('button', { name: new RegExp(escapeRe(fx.expense.code)) }),
            'an expense leaf is not somewhere cleared funds can leave from',
        ).toHaveCount(0);
        await payment.fill(fx.cash.code);
        await page.getByRole('button', { name: new RegExp(escapeRe(fx.cash.code)) }).first().click();
        await expect(payment).toHaveValue(new RegExp(escapeRe(fx.cash.code)));

        await pickAccount(page, 'Account', 0, fx.vendorPayable.code);
        await page.getByTestId('line-description-0').fill(`Paid ${invoiceNumber}`);
        await page.getByTestId('line-amount-0').fill('315.33');
        await expect(page.getByTestId('gross-total')).toHaveText(money(315.33));

        await expect(page.getByTestId('voucher-blocker')).toHaveCount(0);
        await page.getByTestId('post-voucher').click();
        await page.getByTestId('confirm-post').click();
        await page.waitForURL(/finance\/vouchers$/, { timeout: 30_000 });

        const listed = await adminApi<{ content: Voucher[] }>('GET', '/api/v1/finance/vouchers?docType=BPV');
        expect(listed.content.length, 'one payment voucher').toBe(1);
        const bpv = await voucherOf(listed.content[0].id);
        record('voucher', bpv.id, bpv.voucherNumber as string);
        expect(bpv.status).toBe('POSTED');
        expect(bpv.voucherNumber).toMatch(/^BPV-/);
        expect(bpv.vatTotal, 'no VAT on a payment voucher, whatever was typed').toBeCloseTo(0, 2);

        const journal = await journalOf(bpv.journalId as string);
        const debit = journal.lines.find(l => l.debit > 0);
        const credit = journal.lines.find(l => l.credit > 0);
        expect(debit?.accountId, 'Dr the vendor payable').toBe(fx.vendorPayable.id);
        expect(credit?.accountId, 'Cr the chosen cash account').toBe(fx.cash.id);
        expect(credit?.credit).toBeCloseTo(315.33, 2);

        // ── the vendor ledger: invoice, payment, nothing left ──
        const vendorLedger = await adminApi<AccountLedger>(
            'GET',
            `/api/v1/finance/ledger/vendor/${fx.vendorId}?from=2000-01-01&to=${today()}`,
        );
        expect(round2(vendorLedger.closingBalance), 'the vendor is square').toBeCloseTo(0, 2);
        expect(round2(vendorLedger.totalCredit)).toBeCloseTo(315.33, 2);
        expect(round2(vendorLedger.totalDebit)).toBeCloseTo(315.33, 2);

        await page.goto(`/en/dashboard/finance/general-ledger?vendorId=${fx.vendorId}`);
        await expect(page.locator('body')).toContainText(invoiceNumber, { timeout: 20_000 });
        await expect(page.locator('body')).toContainText(bpv.voucherNumber as string);

        await assertTrialBalanceBalances('02');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 03 ──────────────────────────────────────────────────────────────────────

test('03 amending a posted invoice reverses it and posts the corrected figures', async ({ browser }) => {
    const { page, close } = await recorded(browser, '03-amend-a-posted-invoice');
    try {
        await page.goto(`/en/dashboard/finance/vouchers/purchase-invoice?id=${invoiceId}`);
        await expect(page.getByTestId('voucher-status')).toHaveAttribute('data-status', 'POSTED', { timeout: 20_000 });
        await expect(page.getByTestId('voucher-number')).toHaveText(invoiceNumber);
        // A posted document is read-only until Amend is pressed.
        await expect(page.getByTestId('save-draft')).toHaveCount(0);
        await expect(page.getByTestId('line-amount-0')).toBeDisabled();

        await page.getByTestId('amend-voucher').click();
        await expect(page.getByTestId('amend-banner')).toBeVisible();
        await expect(page.getByTestId('line-amount-0')).toBeEnabled();
        // Nothing has changed yet, so there is nothing to post.
        await expect(page.getByTestId('post-amendment')).toBeDisabled();
        await expect(page.getByTestId('voucher-blocker')).toContainText('Nothing has changed');

        // 200.10 at 5% is 10.005 — the HALF_UP boundary VoucherMath and the
        // client's own vatOf are both pinned on. Net 200.10 + 100.10 + 100.10 =
        // 400.30, VAT 10.01 + 5.01 + 5.01 = 20.03, gross 420.33.
        await page.getByTestId('line-amount-0').fill('200.10');
        await expect(page.getByTestId('line-vat-amount-0')).toHaveText(money(10.01));
        await expect(page.getByTestId('net-total')).toHaveText(money(400.3));
        await expect(page.getByTestId('vat-total')).toHaveText(money(20.03));
        await expect(page.getByTestId('gross-total')).toHaveText(money(420.33));
        await expect(page.getByTestId('post-amendment')).toBeEnabled();

        await page.getByTestId('post-amendment').click();
        await page.getByTestId('amend-date').fill(today());
        await page.getByTestId('amend-reason').fill('Supplier re-issued the invoice with the correct lift count');
        await page.getByTestId('confirm-amend').click();

        await expect(page.getByTestId('voucher-posted')).toBeVisible({ timeout: 30_000 });
        await expect(page.getByTestId('amended-from')).toBeVisible();
        const replacementNumber = await page.getByTestId('voucher-number').innerText();
        expect(replacementNumber).toMatch(/^PISR-/);
        expect(replacementNumber).not.toBe(invoiceNumber);

        // ── the ledger: the original reversed, the replacement posted ──
        const original = await voucherOf(invoiceId);
        expect(original.status, 'a posted voucher is never edited').toBe('REVERSED');
        const vouchers = await adminApi<{ content: Voucher[] }>('GET', '/api/v1/finance/vouchers?docType=PISR');
        const replacement = vouchers.content.find(v => v.amendedFromId === invoiceId);
        expect(replacement, 'the amendment posts a NEW voucher pointing back at the original').toBeTruthy();
        amendedVoucherId = (replacement as Voucher).id;
        record('voucher', amendedVoucherId, replacementNumber);
        expect((replacement as Voucher).status).toBe('POSTED');
        expect((replacement as Voucher).grossTotal).toBeCloseTo(420.33, 2);

        const originalJournal = await journalOf(invoiceJournalId);
        expect(originalJournal.status, 'the original journal is reversed').toBe('REVERSED');
        expect(originalJournal.reversedById, 'and it names its mirror').toBeTruthy();
        const mirror = await journalOf(originalJournal.reversedById as string);
        expect(round2(mirror.total)).toBeCloseTo(round2(originalJournal.total), 2);

        // ── Reverse is offered on a MANUAL journal and on nothing else ──
        await page.goto(`/en/dashboard/finance/journals/${invoiceJournalId}`);
        await expect(page.locator('body')).toContainText(invoiceNumber, { timeout: 20_000 });
        await expect(page.getByTestId('reverse-journal'), 'a document-sourced journal is corrected on its document')
            .toHaveCount(0);
        await expect(page.getByTestId('source-voucher')).toBeVisible();

        const manual = await adminApi<{ id: string; entryNumber: string }>('POST', '/api/v1/finance/journals', {
            entryDate: today(),
            narration: `Walkthrough manual journal ${SUFFIX}`,
            lines: [
                { accountId: fx.cash.id, debit: 100, credit: null },
                { accountId: fx.capital.id, debit: null, credit: 100 },
            ],
        });
        record('journal', manual.id, manual.entryNumber);
        await page.goto(`/en/dashboard/finance/journals/${manual.id}`);
        await expect(page.getByTestId('reverse-journal')).toBeVisible({ timeout: 20_000 });
        await expect(page.getByTestId('source-voucher')).toHaveCount(0);

        await assertTrialBalanceBalances('03');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 04 ──────────────────────────────────────────────────────────────────────

test('04 the screen never offers what the server refuses: a role, a reversed document, a closed period', async ({ browser }) => {
    const { page, close } = await recorded(browser, '04-refusals-role-reversed-locked', { state: null });
    try {
        // ── a property manager has no vouchers at all ──
        await signIn(page, fx.manager.email, fx.manager.password);
        await expect(page.locator('a[href*="/dashboard/finance/vouchers"]'), 'no voucher entry in the navigation')
            .toHaveCount(0);
        await page.goto('/en/dashboard/finance/vouchers');
        await expect(page.getByTestId('voucher-access-denied')).toBeVisible({ timeout: 20_000 });
        await page.goto('/en/dashboard/finance/vouchers/purchase-invoice');
        await expect(page.getByTestId('voucher-access-denied')).toBeVisible({ timeout: 20_000 });
        await hold(page, 1400);

        const mgr = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', {
            email: fx.manager.email,
            password: fx.manager.password,
        });
        managerActor = { id: mgr.id, role: mgr.role, tenantId: fx.tenantId };
        const refusedList = await rawApi(managerActor, 'GET', '/api/v1/finance/vouchers');
        expect(refusedList.status, 'and the API refuses them too').toBe(403);
        const refusedPost = await rawApi(managerActor, 'POST', `/api/v1/finance/vouchers/${invoiceId}/post`);
        expect(refusedPost.status).toBe(403);

        // ── back as the admin, for the two document-level refusals ──
        await page.context().clearCookies();
        await signIn(page, fx.admin.email, fx.admin.password);

        await page.goto(`/en/dashboard/finance/vouchers/purchase-invoice?id=${invoiceId}`);
        await expect(page.getByTestId('voucher-status')).toHaveAttribute('data-status', 'REVERSED', { timeout: 20_000 });
        await expect(page.getByTestId('amend-voucher'), 'a reversed voucher is not amended again').toHaveCount(0);
        await expect(page.getByTestId('post-voucher')).toHaveCount(0);
        await expect(page.getByTestId('save-draft')).toHaveCount(0);
        await expect(page.getByTestId('attachments-frozen')).toBeVisible();
        await hold(page, 1400);

        // ── a draft dated into a period that has since been closed ──
        const locked = await adminApi<{ booksLockedThrough: string }>('POST', '/api/v1/finance/fiscal-settings/lock', {
            through: LAST_MONTH_END,
        });
        expect(locked.booksLockedThrough).toBe(LAST_MONTH_END);

        const draft = await adminApi<Voucher>('POST', '/api/v1/finance/vouchers', {
            docType: 'PISR',
            docDate: INSIDE_LOCKED,
            vendorId: fx.vendorId,
            invoiceNumber: `INV-${SUFFIX}-LOCKED`,
            narration: 'Dated inside a period that has since been closed',
            lines: [{ accountId: fx.expense.id, amount: 50, vatRate: 0 }],
        });
        record('voucher', draft.id, `draft dated ${INSIDE_LOCKED}`);

        await page.goto(`/en/dashboard/finance/vouchers/purchase-invoice?id=${draft.id}`);
        await expect(page.getByTestId('voucher-status')).toHaveAttribute('data-status', 'DRAFT', { timeout: 20_000 });
        await expect(page.getByTestId('doc-date')).toHaveValue(INSIDE_LOCKED);
        await expect(page.getByTestId('post-voucher')).toBeDisabled();
        await expect(page.getByTestId('voucher-blocker')).toContainText(`Books are locked through ${LAST_MONTH_END}`);

        const refusedByServer = await adminRaw('POST', `/api/v1/finance/vouchers/${draft.id}/post`);
        expect(refusedByServer.status, 'the disabled button is a courtesy; the rule is the server’s').toBe(400);
        expect(String(refusedByServer.body.message)).toContain('books are locked through');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 05 ──────────────────────────────────────────────────────────────────────

/**
 * PACT's trial balance as at D − 1, in the shape its report engine exports: a
 * two-line banner, a blank row, the header, the figures with thousands
 * separators, a row whose code our chart has never heard of, a row whose amount
 * is not an amount, and a Grand Total the parser skips.
 *
 * **The bank line is deliberately absent.** PACT's own trial balance carries one
 * — 20,000 by 30 Sep 2025 — but `OpeningBalanceService.DERIVED_ROLES` (spec
 * §10.3) does not list BANK, so the grid treats the bank leaf as a MANUAL row
 * and the OB journal would post the figure typed into it. The contract import
 * *also* produces that balance, by replaying two clearances dated before the
 * books open. Entering it here would put 40,000 in the bank on a portfolio that
 * holds 20,000. Scenario 08 asserts the gap this leaves on the reconciliation
 * rather than papering over it; it is written up in the task report.
 */
function trialBalanceCsv(cashDebit: string): string {
    const r = fx.roles;
    return [
        'Walkthrough Landlord LLC',
        `Trial Balance as at 30-09-2025`,
        '',
        'Account Code,Account Name,Debit,Credit',
        `${fx.cash.code},${fx.cash.name},"${cashDebit}",`,
        `${fx.capital.code},${fx.capital.name},,"5,000.00"`,
        `${r.RENT_RECEIVABLE.accountCode},${r.RENT_RECEIVABLE.accountName},"15,000.00",`,
        `${r.PDC_RECEIVABLE.accountCode},${r.PDC_RECEIVABLE.accountName},"30,000.00",`,
        `${r.ADVANCE_RENT.accountCode},${r.ADVANCE_RENT.accountName},,"44,876.71"`,
        `${r.RENTAL_INCOME.accountCode},${r.RENTAL_INCOME.accountName},,"15,123.29"`,
        `${r.SECURITY_DEPOSIT.accountCode},${r.SECURITY_DEPOSIT.accountName},,"5,000.00"`,
        '999999,Legacy Suspense (PACT),"777.00",',
        'PACTBAD,Unreadable Figure,abc,',
        'Grand Total,,"57,777.00","65,000.00"',
        '',
    ].join('\n');
}

test('05 opening balances: a PACT trial balance, a computed difference, and a replacement', async ({ browser }) => {
    const { page, close } = await recorded(browser, '05-opening-balances');
    try {
        await page.goto('/en/dashboard/finance/opening-balances');
        await expect(page.getByTestId('ob-grid')).toBeVisible({ timeout: 20_000 });
        // The as-at date is the server's (booksStartDate − 1), never the page's.
        await expect(page.getByTestId('ob-as-of')).toContainText('30/09/2025');
        await expect(page.getByTestId('ob-difference')).toHaveText(money(0));

        await page.getByTestId('ob-upload').setInputFiles(
            asUpload('pact-trial-balance.csv', 'text/csv', Buffer.from(trialBalanceCsv('12,000.00'), 'utf8')),
        );

        // Eight rows are stored — the seven our chart knows plus the code it does
        // not, which is KEPT and reported rather than dropped. The ninth could
        // not be read at all and says which line it was on.
        await expect(page.getByTestId('ob-upload-stored')).toContainText('8 rows read', { timeout: 20_000 });
        await expect(page.getByTestId('ob-upload-unmatched')).toContainText('999999');
        await expect(page.getByTestId('ob-upload-problems')).toContainText('line 13');
        await expect(page.getByTestId('ob-upload-problems')).toContainText('abc');
        await hold(page, 1600);

        // ── derived rows are read-only, and say why ──
        const pdcId = fx.roles.PDC_RECEIVABLE.accountId;
        await expect(page.getByTestId(`ob-debit-${pdcId}`), 'the contract import produces this balance')
            .toHaveCount(0);
        await expect(page.getByTestId(`ob-readonly-${pdcId}`)).toContainText('PDC_RECEIVABLE');
        // …and the manual ones are not.
        await expect(page.getByTestId(`ob-debit-${fx.cash.id}`)).toHaveValue('12000');

        // ── the difference row is computed, not typed ──
        // 12,000 Dr against 5,000 Cr leaves 7,000, and the server puts that gap
        // on OPENING_BALANCE_DIFFERENCE itself — read-only, and carrying the
        // figure the journal will post, so the grid adds up the way the journal
        // does rather than showing a total nobody can tie out.
        await expect(page.getByTestId(`ob-debit-${fx.difference.id}`), 'the gap is not hand-entered')
            .toHaveCount(0);
        await expect(page.getByTestId(`ob-readonly-${fx.difference.id}`))
            .toContainText('worked out when the journal is posted');
        await expect(page.getByTestId(`ob-row-${fx.difference.id}`)).toContainText(money(7_000));
        await expect(page.getByTestId('ob-total-debit')).toHaveText(money(12_000));
        await expect(page.getByTestId('ob-total-credit')).toHaveText(money(12_000));
        await expect(page.getByTestId('ob-difference'), 'the grid balances once the gap is placed')
            .toHaveText(money(0));

        await page.getByTestId('ob-post').click();
        await page.getByTestId('confirm-ob-post').click();
        await expect(page.getByTestId('ob-success')).toContainText('OB-', { timeout: 30_000 });
        await expect(page.getByTestId('ob-posted-banner')).toBeVisible();

        const firstGrid = await adminApi<{ posted: boolean; journalId: string; journalNumber: string }>(
            'GET',
            '/api/v1/finance/opening-balances',
        );
        expect(firstGrid.posted).toBe(true);
        record('journal', firstGrid.journalId, firstGrid.journalNumber);

        let tb = await trialBalanceAt(AS_OF);
        expect(tb[fx.cash.code], 'the cash figure the accountant uploaded').toBeCloseTo(12_000, 2);
        expect(tb[fx.capital.code]).toBeCloseTo(-5_000, 2);
        expect(tb['F-02'], 'the gap opens the books balanced').toBeCloseTo(-7_000, 2);

        // ── a corrected figure: the grid says so, and Replace is the way back ──
        await page.getByTestId(`ob-debit-${fx.cash.id}`).fill('12500');
        await expect(page.getByTestId(`ob-unsaved-${fx.cash.id}`)).toBeVisible();
        await expect(page.getByTestId('ob-blocker')).toContainText('1');
        await page.getByTestId(`ob-save-${fx.cash.id}`).click();
        await expect(page.getByTestId('ob-changed-since-posted')).toBeVisible({ timeout: 20_000 });
        // The computed row follows the correction: 12,500 against 5,000 is 7,500.
        await expect(page.getByTestId(`ob-row-${fx.difference.id}`)).toContainText(money(7_500));
        await expect(page.getByTestId('ob-total-debit')).toHaveText(money(12_500));

        await page.getByTestId('ob-replace').click();
        await expect(page.getByTestId('ob-replace-blocker')).toBeVisible();
        await page.getByTestId('ob-replace-reason').fill('Corrected cash balance from the bank statement');
        await page.getByTestId('confirm-ob-replace').click();
        await expect(page.getByTestId('ob-success')).toContainText('OB-', { timeout: 30_000 });
        await expect(page.getByTestId('ob-changed-since-posted')).toHaveCount(0);

        const secondGrid = await adminApi<{ posted: boolean; journalId: string; journalNumber: string }>(
            'GET',
            '/api/v1/finance/opening-balances',
        );
        expect(secondGrid.journalId, 'a replacement is a different journal').not.toBe(firstGrid.journalId);
        const reversedOb = await journalOf(firstGrid.journalId);
        expect(reversedOb.status, 'the first opening journal is off the books').toBe('REVERSED');

        tb = await trialBalanceAt(AS_OF);
        expect(tb[fx.cash.code], 'the corrected cash balance').toBeCloseTo(12_500, 2);
        expect(tb[fx.capital.code]).toBeCloseTo(-5_000, 2);
        expect(tb['F-02'], 'and the recomputed difference').toBeCloseTo(-7_500, 2);

        await assertTrialBalanceBalances('05');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 06 ──────────────────────────────────────────────────────────────────────

test('06 reconciliation before the contracts are imported, with the banner that explains the zeroes', async ({ browser }) => {
    const { page, close } = await recorded(browser, '06-reconciliation-before-the-import');
    try {
        await page.goto('/en/dashboard/finance/reconciliation');
        await expect(page.getByTestId('rec-table')).toBeVisible({ timeout: 20_000 });
        await expect(page.getByTestId('rec-as-of')).toContainText('30/09/2025');

        // Every derived account reads 0.00 on our side against a real PACT
        // figure, because step 1 has not run — which is exactly what the banner
        // says, and why it exists.
        const r = fx.roles;
        await expect(page.getByTestId(`rec-derived-${r.PDC_RECEIVABLE.accountCode}`)).toHaveText('0.00');
        await expect(page.getByTestId(`rec-pact-${r.PDC_RECEIVABLE.accountCode}`)).toHaveText(balance(30_000));
        await expect(page.getByTestId(`rec-difference-${r.PDC_RECEIVABLE.accountCode}`))
            .toHaveAttribute('data-differs', 'true');
        await expect(page.getByTestId(`rec-derived-${r.ADVANCE_RENT.accountCode}`)).toHaveText('0.00');
        await expect(page.getByTestId(`rec-pact-${r.ADVANCE_RENT.accountCode}`)).toHaveText(balance(-44_876.71));
        await expect(page.getByTestId('rec-derived-notice')).toBeVisible();
        await expect(page.getByTestId('rec-reconciled')).toHaveCount(0);

        // A PACT code our chart has no account for is reported, not dropped.
        await expect(page.getByTestId('rec-row-999999')).toContainText('Legacy Suspense');
        await expect(page.getByTestId('rec-pact-999999')).toHaveText(balance(777));

        // ── the report is the uploaded file plus whatever our books hold ──
        // Eight rows: the seven codes the upload matched and the one it did not.
        // An account nobody has a figure for on either side is not reported at
        // all (`OpeningBalanceService.reconcile` walks the snapshot and the
        // non-zero derived balances), which is why the vendor's payable leaf is
        // absent here and the bank appears only in scenario 08.
        const all = await page.locator('[data-testid^="rec-row-"]').count();
        expect(all, 'seven matched codes and one that is not ours').toBe(8);
        await expect(page.getByTestId(`rec-row-${fx.vendorPayable.code}`)).toHaveCount(0);
        await expect(page.getByTestId('rec-out-of-balance')).toContainText('8');

        // Nothing agrees yet, so the filter hides nothing — which is the honest
        // state of a cut-over whose contracts have not been imported. Scenario 08
        // presses the same box once five of these rows have come into line.
        await page.getByTestId('rec-differences-only').check();
        await expect(page.locator('[data-testid^="rec-row-"]')).toHaveCount(all);
        await expect(page.getByTestId(`rec-row-${r.PDC_RECEIVABLE.accountCode}`)).toBeVisible();
        await hold(page);
    } finally {
        await close();
    }
});

// ── 07 ──────────────────────────────────────────────────────────────────────

const IMPORT_PROPERTY = `WT4 Cut-over Tower ${SUFFIX}`;
const IMPORT_UNITS = [`CT-${SUFFIX}-101`, `CT-${SUFFIX}-102`];
const IMPORT_RENTER = `WT4 Cut-over Renter ${SUFFIX}`;
const IMPORT_EMAIL = `wt4-cutover-renter-${SUFFIX}@example.invalid`;
const CONTRACT_REF = `WT4-${SUFFIX}-C1`;

test('07 the cut-over template, a workbook that is refused whole, and the corrected one', async ({ browser }) => {
    const { page, close } = await recorded(browser, '07-cutover-template-and-import', { state: STATE_ACCOUNTANT });
    try {
        await page.goto('/en/dashboard/finance/import-batches');
        await expect(page.getByTestId('batches-empty')).toBeVisible({ timeout: 20_000 });

        // The accountant assembles the workbook, so the accountant can fetch the
        // template — PortfolioImportController's CUTOVER_ROLES, one role wider
        // than the v1 portfolio template.
        const templateHref = await page.getByTestId('download-template').getAttribute('href');
        expect(templateHref).toContain('/import/portfolio/cutover/template');
        const template = await page.request.get(`${BASE_URL}${templateHref}`);
        expect(template.status(), 'the template downloads for an accountant').toBe(200);
        expect(template.headers()['content-disposition']).toContain('contract-import-template.xlsx');
        const templateBody = await template.body();
        expect(templateBody.subarray(0, 2).toString('latin1'), 'a real .xlsx is a zip').toBe('PK');
        expect(templateBody.length).toBeGreaterThan(2_000);
        await hold(page, 1200);

        const batchesBefore = await batchesOf();

        // ── a workbook with two deliberate faults ──
        const bad = cutoverWorkbook({
            property: IMPORT_PROPERTY,
            units: IMPORT_UNITS,
            renterName: IMPORT_RENTER,
            renterEmail: IMPORT_EMAIL,
            ref: CONTRACT_REF,
            bankAccountName: 'Bank Of Nowhere',
            shape: 'handDerived',
            duplicateContractNumber: true,
        });
        await page.getByTestId('upload-cutover').setInputFiles(
            asUpload('cutover-with-errors.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', bad),
        );
        await expect(page.getByTestId('import-job-status'))
            .toHaveAttribute('data-status', 'VALIDATION_FAILED', { timeout: 60_000 });
        await expect(page.getByTestId('import-validation-failed')).toBeVisible();

        const errors = page.getByTestId('import-errors-table');
        await expect(errors).toContainText("No ledger account is named 'Bank Of Nowhere'");
        await expect(errors).toContainText('Properties');
        await expect(errors).toContainText('BankAccount');
        await expect(errors, 'one number, one contract').toContainText('A second contract needs its own number');
        await expect(errors).toContainText('UnitNumber');
        await hold(page, 1800);

        // Nothing is written until the whole workbook validates.
        const batchesAfterFailure = await batchesOf();
        expect(batchesAfterFailure.length, 'a refused workbook leaves no batch behind').toBe(batchesBefore.length);
        const propertiesNow = await adminApi<{ content?: { nameEn: string }[] }>('GET', '/api/v1/properties');
        const names = (propertiesNow.content ?? []).map(p => p.nameEn);
        expect(names, 'and no property either').not.toContain(IMPORT_PROPERTY);

        // ── the corrected workbook ──
        const good = cutoverWorkbook({
            property: IMPORT_PROPERTY,
            units: IMPORT_UNITS,
            renterName: IMPORT_RENTER,
            renterEmail: IMPORT_EMAIL,
            ref: CONTRACT_REF,
            bankAccountName: fx.roles.BANK.accountName,
            shape: 'handDerived',
        });
        await page.getByTestId('upload-cutover').setInputFiles(
            asUpload('cutover-corrected.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', good),
        );
        await expect(page.getByTestId('import-job-status'))
            .toHaveAttribute('data-status', 'COMPLETED', { timeout: 60_000 });
        await expect(page.getByTestId('import-success')).toBeVisible();
        await expect(page.getByTestId('import-counts')).toContainText('1');

        const batchesAfter = await batchesOf();
        expect(batchesAfter.length).toBe(batchesBefore.length + 1);
        const batch = batchesAfter[batchesAfter.length - 1];
        batchId = batch.id;
        record('import batch', batch.id, batch.label ?? 'contract import');
        expect(batch.status, 'nothing is posted by an import').toBe('DRAFT');
        expect(batch.leasesImported).toBe(1);
        expect(batch.journalsPosted).toBe(0);

        await expect(page.getByTestId(`batch-row-${batchId}`)).toBeVisible();
        await expect(page.getByTestId(`batch-status-${batchId}`)).toHaveAttribute('data-status', 'DRAFT');
        await expect(page.getByTestId(`batch-row-${batchId}`)).toHaveAttribute('data-imported', 'true');

        const imported = await adminApi<{ id: string; status: string; externalContractRef: string | null }[]>(
            'GET',
            '/api/v1/leases',
        );
        const lease = imported.find(l => l.externalContractRef === CONTRACT_REF);
        expect(lease, 'the contract is a DRAFT lease carrying its PACT reference').toBeTruthy();
        importedLeaseId = (lease as { id: string }).id;
        expect((lease as { status: string }).status).toBe('DRAFT');
        record('lease', importedLeaseId, CONTRACT_REF);

        const batchJournals = await adminApi<{ totalElements: number }>(
            'GET',
            `/api/v1/finance/journals?importBatchId=${batchId}&page=0&size=1`,
        );
        expect(batchJournals.totalElements, 'an import writes no journals at all').toBe(0);

        // ── a reload rejoins the job it was watching ──
        // The panel's job id lives in sessionStorage under a key scoped by kind,
        // tenant and user (`useImportJobPolling.importJobStorageKey`); the hook
        // clears it the moment the job reaches a terminal state, so it is put
        // back here deliberately to drive the resume path the same way a reload
        // mid-import would.
        const jobId = await adminApi<{ importJobId: string }>(
            'GET',
            `/api/v1/finance/import-batches/${batchId}`,
        ).then(b => b.importJobId);
        await page.getByTestId('import-dismiss').click();
        await expect(page.getByTestId('import-job-status')).toHaveCount(0);
        await page.evaluate(
            ([key, value]) => window.sessionStorage.setItem(key, value),
            [`rentaxis.cutover.job.contract-import.${fx.tenantId}.${fx.accountant.id}`, jobId],
        );
        await page.reload();
        await expect(page.getByTestId('import-job-status'), 'a reload rejoins the job')
            .toHaveAttribute('data-status', 'COMPLETED', { timeout: 30_000 });
        await hold(page);
    } finally {
        await close();
    }
});

// ── 08 ──────────────────────────────────────────────────────────────────────

test('08 bulk post: twelve journals, the hand-derived balances, and nothing said to anybody', async ({ browser }) => {
    const { page, close } = await recorded(browser, '08-bulk-post-the-batch', { state: STATE_ACCOUNTANT });
    try {
        trialBalanceBeforePost = await trialBalanceAt(today());
        const penaltiesBefore = await adminApi<{ totalElements: number }>('GET', '/api/v1/penalties?page=0&size=50');
        const notificationsBefore = await adminApi<unknown[]>('GET', '/api/v1/notifications?page=0&size=50');

        await page.goto('/en/dashboard/finance/import-batches');
        await expect(page.getByTestId(`batch-row-${batchId}`)).toBeVisible({ timeout: 20_000 });

        await page.getByTestId(`post-batch-${batchId}`).click();
        await page.getByTestId('confirm-post-batch').click();
        await expect(page.getByTestId('post-job-status')).toBeVisible({ timeout: 30_000 });
        await expect(page.getByTestId('post-job-status'))
            .toHaveAttribute('data-status', 'COMPLETED', { timeout: 120_000 });

        await expect(page.getByTestId('post-summary')).toContainText('Posted 1');
        await expect(page.getByTestId('post-summary')).toContainText('Journals 12');
        await expect(page.getByTestId('post-result-0')).toHaveAttribute('data-outcome', 'POSTED');
        await expect(page.getByTestId('post-results-table')).toContainText(CONTRACT_REF);
        await hold(page, 1600);

        await expect(page.getByTestId(`batch-status-${batchId}`))
            .toHaveAttribute('data-status', 'POSTED', { timeout: 20_000 });

        // ── the drill-through: the journals this batch, and only this batch, wrote ──
        await page.getByTestId(`view-journals-${batchId}`).click();
        await expect(page.getByTestId('import-batch-chip')).toBeVisible({ timeout: 20_000 });
        const journalBody = page.locator('body');
        await expect(journalBody).toContainText('TCO-25/');
        await expect(journalBody).toContainText('PDR-25/');
        await expect(journalBody).toContainText('CIL-25/');
        await hold(page, 1400);

        const posted = await adminApi<{ content: Journal[]; totalElements: number }>(
            'GET',
            `/api/v1/finance/journals?importBatchId=${batchId}&page=0&size=100`,
        );
        expect(posted.totalElements, 'one TCO, five PDRs, two CRTs, one CBR and three CILs').toBe(12);
        const byType = posted.content.reduce<Record<string, number>>((acc, j) => {
            acc[j.docType] = (acc[j.docType] ?? 0) + 1;
            return acc;
        }, {});
        expect(byType).toEqual({ TCO: 1, PDR: 5, CRT: 2, CBR: 1, CIL: 3 });
        expect(
            posted.content.every(j => j.importBatchId === batchId),
            'every journal the import wrote carries the batch id',
        ).toBe(true);
        const cils = posted.content.filter(j => j.docType === 'CIL').map(j => j.entryDate).sort();
        expect(cils, 'recognition catches up to the day before the books open, and stops').toEqual([
            '2025-07-31', '2025-08-31', '2025-09-30',
        ]);

        // ── the hand-derived figures (task-11-review.md §3), to the fil ──
        const r = fx.roles;
        leaseBalancesAfterPost = await leaseBalances(importedLeaseId, AS_OF);
        expect(leaseBalancesAfterPost[r.RENT_RECEIVABLE.accountCode]).toBeCloseTo(EXPECTED.rentReceivable, 2);
        expect(leaseBalancesAfterPost[r.PDC_RECEIVABLE.accountCode]).toBeCloseTo(EXPECTED.pdcReceivable, 2);
        expect(leaseBalancesAfterPost[r.ADVANCE_RENT.accountCode]).toBeCloseTo(EXPECTED.advanceRent, 2);
        expect(leaseBalancesAfterPost[r.BANK.accountCode]).toBeCloseTo(EXPECTED.bank, 2);
        expect(leaseBalancesAfterPost[r.SECURITY_DEPOSIT.accountCode]).toBeCloseTo(EXPECTED.securityDeposit, 2);
        expect(leaseBalancesAfterPost[r.RENTAL_INCOME.accountCode]).toBeCloseTo(EXPECTED.rentalIncome, 2);
        expect(
            round2(Object.values(leaseBalancesAfterPost).reduce((s, n) => s + n, 0)),
            'and the contract balances to zero on its own',
        ).toBeCloseTo(0, 2);

        // ── reconciliation now agrees with PACT on every derived account ──
        await page.goto('/en/dashboard/finance/reconciliation');
        await expect(page.getByTestId('rec-table')).toBeVisible({ timeout: 20_000 });
        for (const role of ['RENT_RECEIVABLE', 'PDC_RECEIVABLE', 'ADVANCE_RENT', 'RENTAL_INCOME', 'SECURITY_DEPOSIT']) {
            const code = r[role].accountCode;
            await expect(page.getByTestId(`rec-difference-${code}`), `${role} must reconcile`)
                .toHaveAttribute('data-differs', 'false');
        }
        await expect(page.getByTestId(`rec-derived-${r.PDC_RECEIVABLE.accountCode}`)).toHaveText(balance(30_000));
        await expect(page.getByTestId(`rec-derived-${r.ADVANCE_RENT.accountCode}`)).toHaveText(balance(-44_876.71));
        await expect(page.getByTestId('rec-derived-notice'), 'the step it was waiting for has run')
            .toHaveCount(0);

        // The bank is the account this design leaves in the middle: the import
        // produced 20,000 by replaying two clearances dated before the books
        // open, but BANK is not one of the nine DERIVED_ROLES, so the grid would
        // have taken PACT's own bank figure as a manual opening balance and the
        // books would hold both. The uploaded trial balance deliberately omits
        // it, and this is the gap that leaves.
        await expect(page.getByTestId(`rec-derived-${r.BANK.accountCode}`)).toHaveText(balance(20_000));
        await expect(page.getByTestId(`rec-pact-${r.BANK.accountCode}`)).toHaveText('0.00');
        await expect(page.getByTestId(`rec-difference-${r.BANK.accountCode}`))
            .toHaveAttribute('data-differs', 'true');

        // ── and now the filter has something to hide ──
        const allRows = await page.locator('[data-testid^="rec-row-"]').count();
        expect(allRows, 'the eight uploaded rows plus the bank the import filled').toBe(9);
        await expect(page.getByTestId('rec-out-of-balance')).toContainText('4');
        await page.getByTestId('rec-differences-only').check();
        await expect(page.locator('[data-testid^="rec-row-"]')).toHaveCount(4);
        await expect(page.getByTestId(`rec-row-${r.PDC_RECEIVABLE.accountCode}`), 'the rows that agree are gone')
            .toHaveCount(0);
        await expect(page.getByTestId(`rec-row-${r.BANK.accountCode}`)).toBeVisible();
        await hold(page, 1600);

        // ── imported history raises no fines and tells nobody ──
        const penaltiesAfter = await adminApi<{ totalElements: number }>('GET', '/api/v1/penalties?page=0&size=50');
        expect(penaltiesAfter.totalElements, 'the bounce replayed by the import proposes no penalty')
            .toBe(penaltiesBefore.totalElements);
        expect(penaltiesAfter.totalElements).toBe(0);
        const notificationsAfter = await adminApi<unknown[]>('GET', '/api/v1/notifications?page=0&size=50');
        expect(notificationsAfter.length, 'and nobody is told about a cheque that bounced last August')
            .toBe(notificationsBefore.length);

        await assertTrialBalanceBalances('08');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 09 ──────────────────────────────────────────────────────────────────────

test('09 reverse the batch, post it again as a successor, and discard a draft one whole', async ({ browser }) => {
    const { page, close } = await recorded(browser, '09-reverse-repost-and-discard', { state: STATE_ACCOUNTANT });
    try {
        await page.goto('/en/dashboard/finance/import-batches');
        await expect(page.getByTestId(`batch-row-${batchId}`)).toBeVisible({ timeout: 20_000 });

        // ── reverse ──
        // Dated on the cut-over itself, not on today. The dialog offers today by
        // default and the server accepts it — a batch journal is exempt from the
        // period lock either way — but a mirror dated after the books open leaves
        // the OPENING position standing: the reconciliation reads `books start −
        // 1`, where a reversal dated later has not happened yet. Written up in
        // the task report; here the accountant does the right thing.
        await page.getByTestId(`reverse-batch-${batchId}`).click();
        await page.getByTestId('batch-reverse-date').fill(AS_OF);
        await page.getByTestId('batch-reverse-reason').fill('The September cut-over was loaded against the wrong bank');
        await page.getByTestId('confirm-reverse-batch').click();
        await expect(page.getByTestId('batch-reversed-banner')).toBeVisible({ timeout: 60_000 });
        await expect(page.getByTestId(`batch-status-${batchId}`)).toHaveAttribute('data-status', 'REVERSED');

        const reversedJournals = await adminApi<{ content: Journal[] }>(
            'GET',
            `/api/v1/finance/journals?importBatchId=${batchId}&page=0&size=100`,
        );
        const originals = reversedJournals.content.filter(j => j.reversedById !== null || j.status === 'REVERSED');
        expect(originals.length, 'every journal the batch wrote is reversed').toBe(12);
        expect(originals.every(j => j.status === 'REVERSED')).toBe(true);

        const lease = await adminApi<{ status: string; postingJournalId: string | null }>(
            'GET',
            `/api/v1/leases/${importedLeaseId}`,
        );
        expect(lease.status, 'and the contract is a clean draft again').toBe('DRAFT');
        expect(lease.postingJournalId).toBeNull();

        const tbAfterReverse = await trialBalanceAt(today());
        for (const [code, before] of Object.entries(trialBalanceBeforePost)) {
            expect(tbAfterReverse[code] ?? 0, `${code} is back where the post found it`).toBeCloseTo(before, 2);
        }
        // …and the opening position the reconciliation reads is flat again too.
        const openingAfterReverse = await leaseBalances(importedLeaseId, AS_OF);
        for (const [code, amount] of Object.entries(openingAfterReverse)) {
            expect(amount, `${code} carries nothing of the reversed batch at ${AS_OF}`).toBeCloseTo(0, 2);
        }

        // A reversed batch keeps its contracts, so Discard is not on offer — the
        // way back is to post it again.
        await expect(page.getByTestId(`discard-batch-${batchId}`)).toHaveCount(0);
        await expect(page.getByTestId(`post-batch-${batchId}`)).toContainText('Post again');
        await hold(page, 1400);

        // ── post again: a successor batch over the same contracts ──
        await page.getByTestId(`post-batch-${batchId}`).click();
        await page.getByTestId('confirm-post-batch').click();
        await expect(page.getByTestId('post-job-status'))
            .toHaveAttribute('data-status', 'COMPLETED', { timeout: 120_000 });
        await expect(page.getByTestId('post-successor')).toBeVisible();
        await expect(page.getByTestId('post-summary')).toContainText('Posted 1');

        const batchesNow = await batchesOf();
        const successor = batchesNow.find(b => b.repostOf === batchId);
        expect(successor, 'the re-post lands on a NEW batch naming the one it replaces').toBeTruthy();
        successorBatchId = (successor as ImportBatch).id;
        record('import batch', successorBatchId, `re-post of ${batchId}`);
        expect((successor as ImportBatch).status).toBe('POSTED');

        const rePosted = await leaseBalances(importedLeaseId, AS_OF);
        for (const [code, before] of Object.entries(leaseBalancesAfterPost)) {
            expect(rePosted[code] ?? 0, `${code} is identical to the first post`).toBeCloseTo(before, 2);
        }
        const successorJournals = await adminApi<{ content: Journal[]; totalElements: number }>(
            'GET',
            `/api/v1/finance/journals?importBatchId=${successorBatchId}&page=0&size=100`,
        );
        expect(successorJournals.totalElements).toBe(12);
        const crt = successorJournals.content.filter(j => j.docType === 'CRT').map(j => j.entryDate).sort();
        expect(crt, 'a re-post files each instrument on the day the money really moved').toEqual([
            '2025-07-03', '2025-07-05',
        ]);
        await hold(page, 1400);

        // ── a DRAFT batch discards whole ──
        const second = cutoverWorkbook({
            property: `WT4 Annex ${SUFFIX}`,
            units: [`AN-${SUFFIX}-1`, `AN-${SUFFIX}-2`],
            renterName: `WT4 Annex Renter ${SUFFIX}`,
            renterEmail: `wt4-annex-renter-${SUFFIX}@example.invalid`,
            ref: `WT4-${SUFFIX}-C2`,
            bankAccountName: fx.roles.BANK.accountName,
            shape: 'simple',
        });
        await page.getByTestId('post-dismiss').click();
        await page.getByTestId('upload-cutover').setInputFiles(
            asUpload('cutover-annex.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', second),
        );
        await expect(page.getByTestId('import-job-status'))
            .toHaveAttribute('data-status', 'COMPLETED', { timeout: 60_000 });

        const withAnnex = await batchesOf();
        const draftBatch = withAnnex.find(b => b.status === 'DRAFT');
        expect(draftBatch, 'the second workbook is a DRAFT batch of its own').toBeTruthy();
        const draftBatchId = (draftBatch as ImportBatch).id;
        record('import batch', draftBatchId, 'annex import (discarded)');

        await expect(page.getByTestId(`discard-batch-${draftBatchId}`)).toBeVisible({ timeout: 20_000 });
        await page.getByTestId(`discard-batch-${draftBatchId}`).click();
        await page.getByTestId('confirm-discard-batch').click();
        await expect(page.getByTestId('discard-panel')).toBeVisible({ timeout: 30_000 });
        await expect(page.getByTestId('discard-summary')).toContainText('contracts 1');
        await expect(page.getByTestId('discard-summary')).toContainText('units 2');
        await expect(page.getByTestId('discard-summary')).toContainText('renters 1');
        await expect(page.getByTestId('discard-summary')).toContainText('properties 1');

        const afterDiscard = await batchesOf();
        expect(afterDiscard.find(b => b.id === draftBatchId)?.status, 'the batch row survives as the record')
            .toBe('DISCARDED');
        const propertiesAfter = await adminApi<{ content?: { nameEn: string }[] }>('GET', '/api/v1/properties');
        expect((propertiesAfter.content ?? []).map(p => p.nameEn), 'and everything it made is gone')
            .not.toContain(`WT4 Annex ${SUFFIX}`);

        await assertTrialBalanceBalances('09');
        await hold(page);
    } finally {
        await close();
    }
});

// ── 10 ──────────────────────────────────────────────────────────────────────

test('10 the five new screens read right-to-left in Arabic', async ({ browser }) => {
    const { page, close } = await recorded(browser, '10-arabic-rtl');
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
         * catch: `Cutover.postBatch` on screen means next-intl fell through.
         * Scoped to the namespaces this plan added plus the ones its screens
         * borrow, so an account code or a file name cannot be mistaken for one.
         */
        const RAW_KEY = /\b(Vouchers|Cutover|Ledger|Common|Navigation|MasterData)\.[a-zA-Z]/;

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

        await rtlPage(
            '/ar/dashboard/finance/vouchers/purchase-invoice',
            [ar.Vouchers.purchaseInvoice, ar.Vouchers.vendor, ar.Vouchers.netTotal, ar.Ledger.account],
            'the purchase invoice form',
        );
        await rtlPage(
            '/ar/dashboard/finance/vouchers',
            [ar.Vouchers.vouchers, ar.Vouchers.newPurchaseInvoice, ar.Ledger.status],
            'the voucher list',
        );
        await rtlPage(
            '/ar/dashboard/finance/opening-balances',
            [ar.Cutover.openingBalances, ar.Cutover.uploadTrialBalance, ar.Cutover.difference],
            'the opening-balance grid',
        );
        await rtlPage(
            '/ar/dashboard/finance/reconciliation',
            [ar.Cutover.reconciliation, ar.Cutover.derivedBalance, ar.Cutover.differencesOnly],
            'the reconciliation report',
        );
        await rtlPage(
            '/ar/dashboard/finance/import-batches',
            [ar.Cutover.importBatches, ar.Cutover.downloadCutoverTemplate, ar.Cutover.leasesImported],
            'the import batches screen',
        );

        // Amounts stay in Western digits with the same grouping as the English
        // screens — a page that reformatted its numbers per locale could not be
        // reconciled against the English one.
        await expect(page.getByTestId(`batch-status-${batchId}`)).toBeVisible();
        await expect(page.locator('body')).toContainText('12');

        expect(intlErrors, 'no next-intl error may reach the console on any of the five screens').toEqual([]);
    } finally {
        await close();
    }

    console.log(`\n  manifest: ${manifest.created.length} records created — ${MANIFEST}`);
});
