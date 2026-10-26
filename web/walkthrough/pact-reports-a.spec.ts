import { test, expect, type Browser, type Locator, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as zlib from 'node:zlib';

/**
 * PACT report parity, PR A — the shared report engine and R1 Tenant Ledger
 * (docs/superpowers/specs/2026-10-26-pact-reports-design.md §1, §2; plan
 * docs/superpowers/plans/2026-10-26-pact-reports-pr-a-engine-tenant-ledger.md).
 * One recording per scenario, modelled on `accounting-v2-plan2.spec.ts` (the
 * provisioning and cheque lifecycle) and `accounting-v2-plan4.spec.ts` (one
 * banked session per role, `recorded()` takes).
 *
 * The recording and the proof are the same run: each scenario asserts its own
 * outcome, and a take only exists because those assertions passed.
 *
 * Runs against a LOCAL dev stack (WT_BASE_URL, default Next on 3003;
 * WT_BACKEND_URL, default backend on 8083) and provisions ONE disposable tenant
 * of its own through the API, with synthetic data only — "Sample Tower",
 * "Tenant A", "Tenant B". No client name, unit or amount from the PACT exports
 * appears here. Nothing touches production.
 *
 * Written without a running stack (a backend suite was running in the same
 * clone). Every selector and endpoint is read off the source on
 * feat/pact-reports — `web/src/app/[locale]/dashboard/finance/tenant-ledger/**`,
 * `web/src/components/reports/**`, `web/src/app/[locale]/dashboard/reports/
 * page.tsx`, `FinanceRegisterReportController`, `TenantLedgerGrid`,
 * `ChequeService`, `ChequeGenerationService` — but none of it has been exercised
 * end to end. Places genuinely uncertain are marked `// VERIFY:`.
 *
 * ── The calendar ──
 *
 * Everything happens on the server's business day: TODAY is the date in
 * Asia/Dubai (`ManualPostingDates.BUSINESS_ZONE`), because a deposit, clear or
 * bounce dated after it is refused as "in the future" (break-it R2 money2 F1).
 * Both leases start, are dated and post on TODAY; the cheques that move are
 * dated TODAY so a deposit on TODAY is not "presented early"; the bounce comes
 * after the clear on the same day, which `ChequeService.bounce` allows for a
 * PDC (the CBR then credits the bank the CRT debited).
 *
 * ── What Tenant A's receivable should read (spec §2, and the IT's own table) ──
 *
 *   TCO        Dr Rent Receivable the contract value (deposit, advance rent, admin fee)
 *   PDR × n    Cr Rent Receivable each row, Dr PDC Receivable — one per row, TRANSFER included
 *   CBR        Dr Rent Receivable the bounced cheque (it had cleared, so Cr bank)
 *
 * so Tenant A's Sub Total is Debit = contract + bounced, Credit = contract,
 * Balance = bounced Dr; Tenant B's is 0.00Dr; the REPORT TOTAL balance is the
 * bounced amount Dr. The contract value is read as Σ of the posted grid (a lease
 * cannot post unless its cheques add up to it), never typed here.
 */

const BACKEND = process.env.WT_BACKEND_URL || 'http://localhost:8083';
const BASE_URL = process.env.WT_BASE_URL || 'http://localhost:3003';

const TAKES_DIR = path.join(__dirname, 'takes', 'pact-reports-a');
const DOWNLOADS_DIR = path.join(__dirname, 'raw', 'pact-reports-a-downloads');
const STATE_ADMIN = path.join(__dirname, 'raw', 'pact-reports-a-admin.json');
const STATE_ACCOUNTANT = path.join(__dirname, 'raw', 'pact-reports-a-accountant.json');
const STATE_MANAGER = path.join(__dirname, 'raw', 'pact-reports-a-manager.json');
const SUFFIX = Math.random().toString(36).slice(2, 7);
const MANIFEST = path.join(__dirname, `run-manifest-pact-reports-a-${SUFFIX}.json`);

const PROPERTY = `Sample Tower ${SUFFIX}`;
const PROPERTY_AR = `برج نموذجي ${SUFFIX}`;
const TENANT_A = `Tenant A ${SUFFIX}`;
const TENANT_B = `Tenant B ${SUFFIX}`;
/** Cheque numbers for this run: digits only, distinct per run so no index can collide across runs. */
const CHQ_BASE = 100_000 + (parseInt(SUFFIX, 36) % 800_000);

// ── dates ───────────────────────────────────────────────────────────────────

const pad = (n: number) => String(n).padStart(2, '0');
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
/** The server's business day: today in Asia/Dubai. */
const TODAY = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Dubai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
}).format(new Date());
const shift = (isoDay: string, years: number, days: number) => {
    const [y, m, d] = isoDay.split('-').map(Number);
    return new Date(Date.UTC(y + years, m - 1, d + days)).toISOString().slice(0, 10);
};
/** A one-year term: TODAY to the day before its anniversary. */
const TERM_END = shift(TODAY, 1, -1);
/**
 * The window the screen opens on (`defaultLedgerRange`, browser-local): the
 * last twelve months to today. Scenarios that deep-link pass it explicitly so
 * the export file names are known in advance.
 */
const WINDOW = (() => {
    const n = new Date();
    return { from: iso(new Date(n.getFullYear() - 1, n.getMonth(), n.getDate() + 1)), to: iso(n) };
})();

// ── amounts, in the screen's own spelling ───────────────────────────────────

const nf = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
/** `fmtMoney` (lib/reports/format.ts). */
const money = (n: number) => nf.format(n);
/** `fmtDrCr`: PACT's form, no space, zero is Dr. */
const pact = (n: number) => `${nf.format(Math.abs(n))}${n < -0.004 ? 'Cr' : 'Dr'}`;
/** The CSV's plain decimal (`CsvGridSink`): no thousands separator. */
const plain = (n: number) => n.toFixed(2);
const PACT_BALANCE = /^\d{1,3}(,\d{3})*\.\d{2}(Dr|Cr)$/;
const SCREEN_DATE = /^\d{2}\/\d{2}\/\d{4}$/;
const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

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

/**
 * The direct backend calls assert identity with X-User-* headers, which the
 * backend accepts only while `app.auth.internal-proxy-secret` is unset (the
 * local default) or when the request carries that secret. A stack started with
 * the secret set takes it from WT_INTERNAL_AUTH (ApiSecurityFilter).
 */
const INTERNAL_AUTH = process.env.WT_INTERNAL_AUTH;

function headersFor(actor: Actor | null): Record<string, string> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (INTERNAL_AUTH) headers['X-Internal-Auth'] = INTERNAL_AUTH;
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
    return { status: res.status, body: parsed };
}

async function api<T>(actor: Actor | null, method: string, apiPath: string, body?: unknown): Promise<T> {
    const res = await rawApi(actor, method, apiPath, body);
    if (res.status >= 400) {
        throw new Error(`${method} ${apiPath} failed (${res.status}): ${JSON.stringify(res.body).slice(0, 500)}`);
    }
    return res.body as T;
}

async function login(email: string, password: string, tenantId: string): Promise<Actor> {
    const who = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', { email, password });
    return { id: who.id, role: who.role, tenantId };
}

type Cred = { id: string; email: string; password: string };

type Cheque = {
    id: string;
    seqNo: number;
    postingDate: string | null;
    chequeNumber: string | null;
    chequeDate: string | null;
    payeeBank: string | null;
    payerName: string | null;
    debitAccountId: string | null;
    amount: number;
    narration: string | null;
    mode: 'PDC' | 'CASH' | 'TRANSFER' | 'ONLINE';
    status: string;
    rowKind: 'RENT' | 'FEE' | 'DEPOSIT' | 'MIXED' | null;
    vatAmount: number | null;
    pdrJournalId: string | null;
    cbrJournalId: string | null;
};

type LeaseFixture = {
    id: string;
    renterId: string;
    /** Σ of the posted grid — the contract value, since a lease only posts when the two agree. */
    contractValue: number;
    cheques: Cheque[];
};

type Fixtures = {
    tenantId: string;
    admin: Cred;
    accountant: Cred;
    manager: Cred;
    propertyId: string;
    leaseA: LeaseFixture;
    leaseB: LeaseFixture;
    /** Tenant A's rent cheque that was deposited and cleared, and stayed cleared. */
    cleared: Cheque;
    /** Tenant A's rent cheque that was deposited, cleared and then bounced. */
    bounced: Cheque;
    /** Tenant A's row turned into a bank transfer: no cheque number, Chq No "TT". */
    transfer: Cheque;
};

let fx: Fixtures;

let adminActor: Actor;
let accountantActor: Actor;
let managerActor: Actor;

const adminApi = <T>(method: string, apiPath: string, body?: unknown) => api<T>(adminActor, method, apiPath, body);
const chequesOf = (leaseId: string) =>
    adminApi<Cheque[]>('GET', `/api/v1/leases/${leaseId}/cheques`).then(cs => [...cs].sort((a, b) => a.seqNo - b.seqNo));
const sum = (cs: Cheque[]) => Math.round(cs.reduce((s, c) => s + c.amount, 0) * 100) / 100;

const tenantLedgerQuery = (extra: Record<string, string> = {}) =>
    new URLSearchParams({ from: WINDOW.from, to: WINDOW.to, propertyId: fx.propertyId, ...extra }).toString();

// ── recording ───────────────────────────────────────────────────────────────

type Take = { page: Page; close: () => Promise<void> };

/** Every tour that could auto-start on a first visit (TourProvider), marked done. */
const TOURS_DONE = [
    'admin-onboarding',
    'company-user-onboarding',
    'finance-overview',
    'lease-workflow',
    'property-workflow',
    'renter-portal',
    'super-admin',
];

async function recorded(
    browser: Browser,
    takeName: string,
    opts: { state?: string | null; viewport?: { width: number; height: number } } = {},
): Promise<Take> {
    fs.mkdirSync(TAKES_DIR, { recursive: true });
    const state = opts.state === undefined ? STATE_ACCOUNTANT : opts.state;
    const viewport = opts.viewport ?? { width: 1280, height: 720 };
    const context = await browser.newContext({
        baseURL: BASE_URL,
        viewport,
        acceptDownloads: true,
        recordVideo: { dir: TAKES_DIR, size: viewport },
        ...(state ? { storageState: state } : {}),
    });
    await context.addInitScript((done: string[]) => {
        try {
            window.localStorage.setItem('rentaxis_tours_completed', JSON.stringify(done));
        } catch {
            /* storage unavailable — nothing to suppress */
        }
    }, TOURS_DONE);
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

/**
 * Sign in once in a throwaway context purely to bank the session for later
 * takes — only once the NextAuth session cookie exists (plan 4's lesson: the
 * client redirect can beat the Set-Cookie).
 */
async function bankSession(browser: Browser, cred: Cred, statePath: string) {
    const context = await browser.newContext({ baseURL: BASE_URL });
    const page = await context.newPage();
    await signIn(page, cred.email, cred.password);
    await expect
        .poll(async () => (await context.cookies()).some(c => c.name.endsWith('next-auth.session-token')), {
            message: `${cred.email}: the session cookie must exist before it is banked`,
            timeout: 20_000,
        })
        .toBe(true);
    await context.storageState({ path: statePath });
    await context.close();
}

async function hold(page: Page, ms = 2200) {
    await page.waitForTimeout(ms);
}

/**
 * A full-page still beside the take, taken once the scenario's assertions have passed.
 *
 * The dashboard shell is one screen tall and scrolls inside its own content
 * area, so `fullPage` on the recorded 720 px page captures 720 px — most of the
 * report cut off. The still is taken instead in its own context, signed in with
 * the same session, on the same URL, in a viewport tall enough for the whole
 * report, and only after `ready` has re-checked what the shot is meant to show.
 * The recorded take is never resized.
 */
async function still(
    browser: Browser,
    name: string,
    opts: { state: string; url: string; ready: (page: Page) => Promise<void>; width?: number; height?: number },
) {
    fs.mkdirSync(TAKES_DIR, { recursive: true });
    const context = await browser.newContext({
        baseURL: BASE_URL,
        viewport: { width: opts.width ?? 1280, height: opts.height ?? 2200 },
        storageState: opts.state,
    });
    await context.addInitScript((done: string[]) => {
        try {
            window.localStorage.setItem('rentaxis_tours_completed', JSON.stringify(done));
        } catch {
            /* storage unavailable — nothing to suppress */
        }
    }, TOURS_DONE);
    try {
        const page = await context.newPage();
        await page.goto(opts.url);
        await opts.ready(page);
        await page.waitForTimeout(600);
        await page.screenshot({ path: path.join(TAKES_DIR, `${name}.png`), fullPage: true });
        console.log(`  still saved: ${name}.png`);
    } finally {
        await context.close();
    }
}

const gridReady = async (page: Page) => {
    await expect(page.getByTestId('grid-total')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('grid-band-2')).toHaveCount(2);
};

// ── reading the report grid off the screen ──────────────────────────────────

/** Each `<tr>`'s own cells, text trimmed. */
const cellsOf = (rows: Locator) =>
    rows.evaluateAll(trs =>
        trs.map(tr => Array.from(tr.querySelectorAll(':scope > td')).map(td => (td.textContent ?? '').trim())),
    );

type Grid = {
    headers: string[];
    rows: Record<string, string>[];
    accountBands: string[];
    tenantBands: string[];
    openings: string[][];
    subtotals: { label: string; values: Record<string, string> }[];
    total: { label: string; values: Record<string, string> };
};

/**
 * A totals row prints its label across the leading columns that carry no
 * figure (GroupedReportTable.totalsRow), so its cells after the label line up
 * with the LAST columns of the header.
 */
function totalsByHeader(headers: string[], cells: string[]) {
    const values = cells.slice(1);
    const keys = headers.slice(headers.length - values.length);
    return { label: cells[0] ?? '', values: Object.fromEntries(keys.map((k, i) => [k, values[i] ?? ''])) };
}

async function readGrid(page: Page): Promise<Grid> {
    const total = page.getByTestId('grid-total');
    await expect(total, 'the grid must end on its REPORT TOTAL').toHaveCount(1);
    const table = page.locator('table').filter({ has: total });
    const headers = (await table.locator('thead th').allTextContents()).map(s => s.trim());
    const rowCells = await cellsOf(page.getByTestId('grid-row'));
    const rows = rowCells.map(cells => Object.fromEntries(headers.map((h, i) => [h, cells[i] ?? ''])));
    const subtotalCells = await cellsOf(page.locator('[data-testid^="grid-subtotal-"]'));
    const [totalCells] = await cellsOf(total);
    return {
        headers,
        rows,
        accountBands: (await page.getByTestId('grid-band-1').allTextContents()).map(s => s.trim()),
        tenantBands: (await page.getByTestId('grid-band-2').allTextContents()).map(s => s.trim()),
        openings: await cellsOf(page.getByTestId('grid-opening')),
        subtotals: subtotalCells.map(c => totalsByHeader(headers, c)),
        total: totalsByHeader(headers, totalCells),
    };
}

const PACT_HEADERS = ['Doc Date', 'Particular', 'Debit', 'Credit', 'Balance', 'Chq No', 'Payee Bank', 'BDDays', 'ConvertedDate', 'Doc No'];

// ── reading the exported files ──────────────────────────────────────────────

/**
 * The entries of a ZIP archive (an .xlsx is one), by name. Read off the central
 * directory, so POI's streamed entries (sizes in a trailing data descriptor)
 * read as well as stored ones. The repo carries no spreadsheet reader and
 * adding one for a walkthrough would be a dependency paid for by a test.
 */
function unzip(buf: Buffer): Map<string, Buffer> {
    let eocd = -1;
    for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65_557); i--) {
        if (buf.readUInt32LE(i) === 0x06054b50) {
            eocd = i;
            break;
        }
    }
    if (eocd < 0) throw new Error('not a ZIP archive: no end-of-central-directory record');
    const count = buf.readUInt16LE(eocd + 10);
    let p = buf.readUInt32LE(eocd + 16);
    const out = new Map<string, Buffer>();
    for (let n = 0; n < count; n++) {
        if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error(`bad central directory entry at ${p}`);
        const method = buf.readUInt16LE(p + 10);
        const compressed = buf.readUInt32LE(p + 20);
        const nameLen = buf.readUInt16LE(p + 28);
        const extraLen = buf.readUInt16LE(p + 30);
        const commentLen = buf.readUInt16LE(p + 32);
        const local = buf.readUInt32LE(p + 42);
        const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
        const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
        const data = buf.subarray(start, start + compressed);
        if (method === 0) out.set(name, Buffer.from(data));
        else if (method === 8) out.set(name, zlib.inflateRawSync(data));
        else throw new Error(`${name}: unsupported ZIP method ${method}`);
        p += 46 + nameLen + extraLen + commentLen;
    }
    return out;
}

/** RFC 4180 lines → fields (quoted fields may hold commas and doubled quotes). */
function parseCsv(text: string): string[][] {
    const lines: string[][] = [];
    let field = '';
    let line: string[] = [];
    let quoted = false;
    for (let i = 0; i < text.length; i++) {
        const ch = text[i];
        if (quoted) {
            if (ch === '"' && text[i + 1] === '"') {
                field += '"';
                i++;
            } else if (ch === '"') quoted = false;
            else field += ch;
        } else if (ch === '"') quoted = true;
        else if (ch === ',') {
            line.push(field);
            field = '';
        } else if (ch === '\n' || ch === '\r') {
            if (ch === '\r' && text[i + 1] === '\n') i++;
            line.push(field);
            lines.push(line);
            line = [];
            field = '';
        } else field += ch;
    }
    if (field !== '' || line.length) {
        line.push(field);
        lines.push(line);
    }
    return lines;
}

/**
 * One file from the ExportMenu (components/reports/ExportMenu.tsx): it fetches
 * the file and saves the blob through an `<a download>`, so Playwright sees a
 * real download with the server's Content-Disposition name.
 */
async function exportVia(page: Page, itemName: string): Promise<{ name: string; bytes: Buffer }> {
    const trigger = page.getByTestId('report-export');
    const menuRoot = trigger.locator('..');
    await expect(trigger).toBeEnabled();
    await trigger.click();
    const item = page.getByRole('menuitem', { name: itemName, exact: true });
    await expect(item).toBeVisible();
    const [download] = await Promise.all([page.waitForEvent('download', { timeout: 90_000 }), item.click()]);
    expect(await download.failure(), `${itemName}: the download must complete`).toBeNull();
    const name = download.suggestedFilename();
    fs.mkdirSync(DOWNLOADS_DIR, { recursive: true });
    const target = path.join(DOWNLOADS_DIR, name);
    await download.saveAs(target);
    const bytes = fs.readFileSync(target);
    // The busy state ended and no 422/429 message replaced the file.
    await expect(trigger).toBeEnabled();
    await expect(menuRoot.getByRole('alert')).toHaveCount(0);
    console.log(`  ${itemName}: ${name} (${bytes.length} bytes)`);
    return { name, bytes };
}

// ── 00 ──────────────────────────────────────────────────────────────────────

test.describe.configure({ mode: 'serial' });

test('00 provision a tenant, three roles, Sample Tower, two tenants, two posted leases and a cheque history', async ({ browser }) => {
    const su = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', {
        email: 'admin@rentaxis.com',
        password: 'admin123',
    });
    const superAdmin: Actor = { id: su.id, role: su.role, tenantId: null };

    const tenantName = `WALKTHROUGH-PACT-REPORTS-A ${TODAY} ${SUFFIX}`;
    const tenant = await api<{ id: string }>(superAdmin, 'POST', '/api/admin/tenants', { name: tenantName });
    expect(tenant.id, 'tenant must be created').toBeTruthy();
    record('tenant', tenant.id, tenantName);

    const scoped: Actor = { ...superAdmin, tenantId: tenant.id };
    const password = `Walk!${SUFFIX}9`;
    const make = async (role: string, slug: string, name: string): Promise<Cred> => {
        const email = `wtpr-${slug}-${SUFFIX}@example.invalid`;
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
    // chained server-side (AccountController#seedDefaultAccounts).
    await api(scoped, 'POST', '/api/v1/finance/accounts/seed');

    const property = await api<{ id: string }>(scoped, 'POST', '/api/v1/properties', {
        nameEn: PROPERTY,
        nameAr: PROPERTY_AR,
        address: '1 Sample Street, Dubai',
        emirate: 'DUBAI',
        type: 'RESIDENTIAL',
    });
    record('property', property.id, PROPERTY);
    // The manager is assigned, so scenario 06's refusal is about the report's
    // role gate (D6) and not about an unassigned user being refused everything.
    await api(scoped, 'POST', `/api/admin/users/${manager.id}/properties/${property.id}`);

    adminActor = await login(admin.email, admin.password, tenant.id);
    accountantActor = await login(accountant.email, accountant.password, tenant.id);
    managerActor = await login(manager.email, manager.password, tenant.id);

    const makeUnit = async (unitNumber: string, expectedRent: number) => {
        const unit = await adminApi<{ id: string }>('POST', '/api/v1/units', {
            property: { id: property.id },
            unitNumber,
            type: 'BHK1',
            sizeSqft: 900,
            expectedRent,
        });
        record('unit', unit.id, unitNumber);
        return unit.id;
    };
    const makeRenter = async (name: string, slug: string, phone: string) => {
        const renter = await adminApi<{ id: string }>('POST', '/api/v1/renters', {
            nameEn: name,
            nameAr: name,
            email: `wtpr-${slug}-${SUFFIX}@example.invalid`,
            phone,
            primaryLanguage: 'EN',
            createPortalAccount: false,
        });
        record('renter', renter.id, name);
        return renter.id;
    };
    const unitA = await makeUnit(`SAMPLE-${SUFFIX}-101`, 48_000);
    const unitB = await makeUnit(`SAMPLE-${SUFFIX}-102`, 24_000);
    const renterA = await makeRenter(TENANT_A, 'tenant-a', '+971500000101');
    const renterB = await makeRenter(TENANT_B, 'tenant-b', '+971500000102');

    // ── lease 1, Tenant A: rent + deposit + admin fee, four rent instalments and
    // the deposit and fee on their own rows (not folded into cheque 1) ──
    const draftA = await adminApi<{ id: string }>('POST', '/api/v1/leases', {
        unitId: unitA,
        renterId: renterA,
        startDate: TODAY,
        endDate: TERM_END,
        contractDate: TODAY,
        firstDueDate: TODAY,
        paymentTerms: 4,
        paymentMethod: 'CHEQUE',
        depositPaymentMethod: 'CHEQUE',
        lines: [
            { chargeTypeCode: 'RENT', grossAmount: 48_000 },
            { chargeTypeCode: 'SECURITY_DEPOSIT', grossAmount: 5_000 },
            // Seeded by ChargeTypeService.DEFAULTS with VAT off by default, so the
            // post needs no organisation TRN and the contract value is 54,000.
            { chargeTypeCode: 'ADMIN_FEE', grossAmount: 1_000 },
        ],
    });
    record('lease', draftA.id, `Lease 1 — ${TENANT_A} (draft)`);
    await adminApi('POST', `/api/v1/leases/${draftA.id}/cheques/generate`, {
        installments: 4,
        firstDueDate: TODAY,
        payeeBank: 'Sample Bank',
        foldDepositsAndFeesIntoFirst: false,
    });
    const generated = await chequesOf(draftA.id);
    // VERIFY: the generator labels each row's rowKind (RENT / DEPOSIT / FEE / MIXED).
    const rentRows = generated.filter(c => c.rowKind === 'RENT');
    const extraRows = generated.filter(c => c.rowKind !== 'RENT');
    expect(rentRows.length, 'four rent instalments').toBe(4);
    expect(extraRows.length, 'the deposit and the fee on rows of their own').toBeGreaterThanOrEqual(1);

    // The grid as PACT's client keeps it: numbered PDCs on "Sample Bank", the last
    // instalment paid by bank transfer (no number, no bank — Chq No "TT"). The
    // second instalment and the deposit are dated TODAY so they can be banked
    // today: a PDC may not be presented before the date written on it.
    const transferRowId = rentRows[3].id;
    // With folding off the generator writes one DEPOSIT row and one FEE row (ChequeGenerationService.buildRows).
    const depositRowId = (extraRows.find(c => c.rowKind === 'DEPOSIT') ?? extraRows[0]).id;
    const dueToday = new Set([rentRows[0].id, rentRows[1].id, depositRowId]);
    let n = 0;
    const editedA = generated.map(c => {
        const transfer = c.id === transferRowId;
        return {
            id: c.id,
            seqNo: c.seqNo,
            postingDate: c.postingDate,
            amount: c.amount,
            narration: c.narration,
            debitAccountId: c.debitAccountId,
            payerName: c.payerName,
            rowKind: c.rowKind,
            vatAmount: c.vatAmount,
            mode: transfer ? 'TRANSFER' : 'PDC',
            chequeNumber: transfer ? null : String(CHQ_BASE + n++),
            chequeDate: dueToday.has(c.id) ? TODAY : c.chequeDate,
            payeeBank: transfer ? null : 'Sample Bank',
        };
    });
    await adminApi('PUT', `/api/v1/leases/${draftA.id}/cheques`, editedA);

    const postedA = await adminApi<{ lease: { id: string; status: string } }>('POST', `/api/v1/leases/${draftA.id}/post`);
    expect(postedA.lease.status, 'lease 1 must be on the books').toBe('ACTIVE');
    let chequesA = await chequesOf(draftA.id);
    expect(chequesA.every(c => c.status === 'REGISTERED'), 'every row registered by its PDR').toBe(true);
    expect(chequesA.every(c => !!c.pdrJournalId), 'one PDR per row, the transfer included').toBe(true);
    const transfer = chequesA.find(c => c.id === transferRowId)!;
    expect(transfer.mode).toBe('TRANSFER');
    expect(transfer.chequeNumber).toBeNull();

    // ── the cheque history: two rent cheques and the deposit banked and cleared
    // today, then the second rent cheque returned by the bank after clearing ──
    const [cleared, toBounce, deposit] = [rentRows[0].id, rentRows[1].id, depositRowId];
    await adminApi('POST', '/api/v1/cheques/deposit-batch', { chequeIds: [cleared, toBounce, deposit], date: TODAY });
    for (const id of [cleared, toBounce, deposit]) {
        await adminApi('PUT', `/api/v1/cheques/${id}/clear`, { date: TODAY });
    }
    await adminApi('PUT', `/api/v1/cheques/${toBounce}/bounce`, {
        date: TODAY,
        failureReason: 'BOUNCE',
        notes: `Synthetic return for the walkthrough ${SUFFIX}`,
    });
    chequesA = await chequesOf(draftA.id);
    const byId = (id: string) => chequesA.find(c => c.id === id)!;
    expect(byId(cleared).status).toBe('CLEARED');
    expect(byId(deposit).status).toBe('CLEARED');
    expect(byId(toBounce).status, 'returned after it had cleared').toBe('BOUNCED');
    expect(byId(toBounce).cbrJournalId, 'the bounce posted its CBR').toBeTruthy();
    expect(byId(transferRowId).status).toBe('REGISTERED');

    // ── lease 2, Tenant B: rent only, two numbered cheques, posted and untouched ──
    const draftB = await adminApi<{ id: string }>('POST', '/api/v1/leases', {
        unitId: unitB,
        renterId: renterB,
        startDate: TODAY,
        endDate: TERM_END,
        contractDate: TODAY,
        firstDueDate: TODAY,
        paymentTerms: 2,
        paymentMethod: 'CHEQUE',
        depositPaymentMethod: 'CHEQUE',
        lines: [{ chargeTypeCode: 'RENT', grossAmount: 24_000 }],
    });
    record('lease', draftB.id, `Lease 2 — ${TENANT_B} (draft)`);
    await adminApi('POST', `/api/v1/leases/${draftB.id}/cheques/generate`, {
        installments: 2,
        firstDueDate: TODAY,
        payeeBank: 'Sample Bank',
    });
    await adminApi('POST', `/api/v1/leases/${draftB.id}/cheques/numbers`, { startingNumber: String(CHQ_BASE + 50) });
    const postedB = await adminApi<{ lease: { id: string; status: string } }>('POST', `/api/v1/leases/${draftB.id}/post`);
    expect(postedB.lease.status, 'lease 2 must be on the books').toBe('ACTIVE');
    const chequesB = await chequesOf(draftB.id);
    expect(chequesB.length).toBe(2);
    expect(chequesB.every(c => !!c.chequeNumber), 'lease 2 cheques are numbered').toBe(true);

    // The report's account picker offers this property's receivable leaf (sanity
    // for every scenario after this one: the band it will print).
    const offered = await api<{ id: string; name: string; propertyId: string | null }[]>(
        accountantActor,
        'GET',
        `/api/v1/finance/reports/tenant-ledger/accounts?propertyId=${property.id}`,
    );
    expect(offered.map(a => a.name), 'the property has its own Rent Receivable leaf').toContain(`Rent Receivable - ${PROPERTY}`);

    fx = {
        tenantId: tenant.id,
        admin,
        accountant,
        manager,
        propertyId: property.id,
        leaseA: { id: draftA.id, renterId: renterA, contractValue: sum(chequesA), cheques: chequesA },
        leaseB: { id: draftB.id, renterId: renterB, contractValue: sum(chequesB), cheques: chequesB },
        cleared: byId(cleared),
        bounced: byId(toBounce),
        transfer: byId(transferRowId),
    };
    console.log(
        `  lease 1: ${money(fx.leaseA.contractValue)} over ${chequesA.length} rows; bounced #${fx.bounced.chequeNumber} ` +
            `(${money(fx.bounced.amount)}); lease 2: ${money(fx.leaseB.contractValue)}`,
    );

    await bankSession(browser, admin, STATE_ADMIN);
    await bankSession(browser, accountant, STATE_ACCOUNTANT);
    await bankSession(browser, manager, STATE_MANAGER);
});

// ── 01 ──────────────────────────────────────────────────────────────────────

test('01 the Tenant Ledger opens on PACT’s receivable view and reads like PACT', async ({ browser }) => {
    const { page, close } = await recorded(browser, '01-tenant-ledger-pact-view', { state: STATE_ACCOUNTANT });
    try {
        const gridRequests: string[] = [];
        page.on('request', r => {
            if (/\/finance\/reports\/tenant-ledger\?/.test(r.url())) gridRequests.push(r.url());
        });

        await page.goto('/en/dashboard/finance/tenant-ledger');
        const viewSwitch = page.getByTestId('tl-view-switch');
        await expect(viewSwitch.getByRole('button', { name: 'Receivable (PACT)' })).toHaveAttribute('aria-pressed', 'true');
        await expect(page).toHaveURL(/view=receivable/);

        // Load on demand: nothing is fetched until a scope is chosen AND applied.
        await expect(page.getByTestId('report-idle')).toBeVisible();
        await expect(page.getByTestId('report-export')).toBeDisabled();
        const propertySelect = page.locator('#tl-property');
        await expect(propertySelect.locator(`option[value="${fx.propertyId}"]`)).toHaveCount(1);
        await propertySelect.selectOption(fx.propertyId);
        await hold(page, 800);
        await expect(page.getByTestId('report-idle'), 'picking is a draft; it does not load').toBeVisible();
        expect(gridRequests, 'no report request before Apply').toEqual([]);

        const [gridRes] = await Promise.all([
            page.waitForResponse(r => /\/api\/proxy\/v1\/finance\/reports\/tenant-ledger\?/.test(r.url()) && r.request().method() === 'GET'),
            page.getByTestId('tl-filters').getByRole('button', { name: 'Apply' }).click(),
        ]);
        expect(gridRes.status()).toBe(200);
        await expect(page).toHaveURL(new RegExp(`propertyId=${fx.propertyId}`));
        await expect(page.getByTestId('grid-total')).toBeVisible();
        await expect(page.getByTestId('report-export')).toBeEnabled();

        const g = await readGrid(page);
        expect(g.headers, 'PACT’s columns in PACT’s order, Doc No appended').toEqual(PACT_HEADERS);

        // Bands: the receivable account, then one "Tenant Name :" band per tenant, ordered by name.
        expect(g.accountBands, 'one account band, the property’s Rent Receivable').toEqual([`Rent Receivable - ${PROPERTY}`]);
        expect(g.tenantBands).toEqual([`Tenant Name : ${TENANT_A}`, `Tenant Name : ${TENANT_B}`]);
        expect(g.openings.length, 'a brought-forward row per tenant').toBe(2);
        for (const o of g.openings) {
            expect(o.join(' '), 'a lease that starts today brings nothing forward').toContain('0.00Dr');
        }

        // Particular: the line's own counter-account (spec §2) — the TCO's charges and every PDR's PDC Receivable.
        const particulars = g.rows.map(r => r['Particular']);
        expect(particulars.some(p => p.includes(`PDC Receivable ${PROPERTY}`)), 'PDR rows name PDC Receivable').toBe(true);
        expect(particulars.some(p => p.includes(`Security Deposit ${PROPERTY}`)), 'the TCO names the deposit').toBe(true);
        expect(particulars.some(p => p.includes(`Advance Rent - ${PROPERTY}`)), 'the TCO names advance rent').toBe(true);

        // Chq No: every numbered cheque of both leases, and "TT" for the transfer (paid to bank "TRANSFER").
        const chqNos = new Set(g.rows.map(r => r['Chq No']));
        for (const c of [...fx.leaseA.cheques, ...fx.leaseB.cheques].filter(c => c.chequeNumber)) {
            expect(chqNos.has(c.chequeNumber!), `cheque ${c.chequeNumber} must be on the ledger`).toBe(true);
        }
        const tt = g.rows.filter(r => r['Chq No'] === 'TT');
        expect(tt.length, 'the transfer row prints TT').toBeGreaterThanOrEqual(1);
        expect(tt.every(r => r['Payee Bank'] === 'TRANSFER')).toBe(true);

        // BDDays: 1 on every row of the cheque that bounced (its PDR and its CBR), blank on one that stayed cleared.
        const bouncedRows = g.rows.filter(r => r['Chq No'] === fx.bounced.chequeNumber);
        expect(bouncedRows.length, 'the bounced cheque has its PDR and its CBR rows').toBeGreaterThanOrEqual(2);
        expect(bouncedRows.map(r => r['BDDays'])).toEqual(bouncedRows.map(() => '1'));
        const cbr = bouncedRows.find(r => r['Doc No'].startsWith('CBR'));
        expect(cbr, 'the return re-debits the receivable').toBeTruthy();
        expect(cbr!['Debit']).toBe(money(fx.bounced.amount));
        const clearedRows = g.rows.filter(r => r['Chq No'] === fx.cleared.chequeNumber);
        expect(clearedRows.length).toBeGreaterThanOrEqual(1);
        expect(clearedRows.every(r => r['BDDays'] === '')).toBe(true);

        // ConvertedDate only on a cheque's registration (PDR) row; dates on screen read dd/mm/yyyy.
        const converted = g.rows.filter(r => r['ConvertedDate'] !== '');
        expect(converted.length).toBeGreaterThanOrEqual(1);
        for (const r of converted) {
            expect(r['Doc No'], 'ConvertedDate is printed on PDR rows only').toMatch(/^PDR/);
            expect(r['ConvertedDate']).toMatch(SCREEN_DATE);
        }
        for (const r of g.rows) {
            expect(r['Doc Date']).toMatch(SCREEN_DATE);
            expect(r['Balance'], 'a running balance in PACT form on every row').toMatch(PACT_BALANCE);
        }

        // Sub Totals: one per tenant, then the account's own (it has two tenants), then the REPORT TOTAL.
        const tenantSubs = g.subtotals.filter(s => s.label === 'Sub Total');
        expect(tenantSubs.length, 'a Sub Total per tenant').toBe(2);
        const a = fx.leaseA.contractValue;
        const b = fx.leaseB.contractValue;
        const x = fx.bounced.amount;
        expect(tenantSubs[0].values).toMatchObject({ Debit: money(a + x), Credit: money(a), Balance: pact(x) });
        expect(tenantSubs[1].values).toMatchObject({ Debit: money(b), Credit: money(b), Balance: '0.00Dr' });
        expect(tenantSubs[0].values['BDDays'], 'BDDays is never summed').toBe('');
        expect(g.subtotals.map(s => s.label)).toContain(`Sub Total (Rent Receivable - ${PROPERTY})`);

        expect(g.total.label).toBe('REPORT TOTAL');
        expect(g.total.values).toMatchObject({ Debit: money(a + x + b), Credit: money(a + b), Balance: pact(x) });
        expect(g.total.values['Balance'], 'Dr/Cr in PACT form, no space').toMatch(/\d\.\d{2}Dr$/);

        // The screen prints the server's figures: the same REPORT TOTAL straight from the API.
        const url = new URL(page.url());
        const fromApi = await api<{ items: { type: string; cells: Record<string, string> }[] }>(
            accountantActor,
            'GET',
            `/api/v1/finance/reports/tenant-ledger?from=${url.searchParams.get('from')}&to=${url.searchParams.get('to')}` +
                `&propertyId=${fx.propertyId}`,
        );
        const apiTotal = fromApi.items[fromApi.items.length - 1];
        expect(apiTotal.type).toBe('total');
        expect(pact(Number(apiTotal.cells['balance']))).toBe(g.total.values['Balance']);
        expect(money(Number(apiTotal.cells['debit']))).toBe(g.total.values['Debit']);
        await hold(page);
        await still(browser, '01-tenant-ledger-pact-view', { state: STATE_ACCOUNTANT, url: page.url(), ready: gridReady });
    } finally {
        await close();
    }
});

// ── 02 ──────────────────────────────────────────────────────────────────────

test('02 export the report — Excel in PACT layout, PDF in English and Arabic, CSV', async ({ browser }) => {
    const { page, close } = await recorded(browser, '02-exports', { state: STATE_ACCOUNTANT });
    try {
        // A deep link with the scope in the URL loads at once (useReportQuery reads the URL on mount).
        await page.goto(`/en/dashboard/finance/tenant-ledger?view=receivable&${tenantLedgerQuery()}`);
        await expect(page.getByTestId('grid-total')).toBeVisible();
        const stem = `tenant-ledger-${WINDOW.from}-${WINDOW.to}`;
        const a = fx.leaseA.contractValue;
        const b = fx.leaseB.contractValue;
        const x = fx.bounced.amount;

        // ── Excel (PACT layout) ──
        const xlsx = await exportVia(page, 'Excel (PACT layout)');
        expect(xlsx.name, 'the server’s RFC 5987 name').toBe(`${stem}.xlsx`);
        expect(xlsx.bytes.length).toBeGreaterThan(0);
        expect(xlsx.bytes.subarray(0, 4).toString('latin1'), 'an xlsx is a ZIP').toBe('PK\u0003\u0004');
        const parts = unzip(xlsx.bytes);
        const sheetName = [...parts.keys()].find(k => /^xl\/worksheets\/sheet\d+\.xml$/.test(k));
        expect(sheetName, 'the workbook has a worksheet').toBeTruthy();
        // SXSSF writes inline strings unless told otherwise; read both places a cell's text can be.
        const sheetText = parts.get(sheetName!)!.toString('utf8') + (parts.get('xl/sharedStrings.xml')?.toString('utf8') ?? '');
        for (const label of [...PACT_HEADERS, 'REPORT TOTAL', 'Sub Total', `Tenant Name : ${TENANT_A}`, `Tenant Name : ${TENANT_B}`,
            `Rent Receivable - ${PROPERTY}`]) {
            expect(sheetText, `the workbook carries "${label}"`).toContain(`>${label}<`);
        }
        expect(sheetText, 'the transfer prints TT').toContain('>TT<');
        expect(sheetText, `cheque ${fx.bounced.chequeNumber} is a text cell`).toContain(`>${fx.bounced.chequeNumber}<`);
        expect(sheetText, 'an English report is left to right').not.toMatch(/rightToLeft="(1|true)"/);
        // The running balance is a NUMBER formatted to read like PACT's text (spec §1.2).
        const styles = parts.get('xl/styles.xml')?.toString('utf8') ?? '';
        expect(styles, 'the Dr/Cr number format').toMatch(/#,##0\.00(&quot;|")Dr(&quot;|");#,##0\.00(&quot;|")Cr(&quot;|")/);

        // ── PDF — English ──
        const pdfEn = await exportVia(page, 'PDF — English');
        expect(pdfEn.name).toBe(`${stem}.pdf`);
        expect(pdfEn.bytes.length).toBeGreaterThan(0);
        expect(pdfEn.bytes.subarray(0, 5).toString('latin1')).toBe('%PDF-');

        // ── PDF — Arabic ──
        const pdfAr = await exportVia(page, 'PDF — Arabic');
        expect(pdfAr.name, 'an Arabic file says so in its name').toBe(`${stem}-ar.pdf`);
        expect(pdfAr.bytes.length).toBeGreaterThan(0);
        expect(pdfAr.bytes.subarray(0, 5).toString('latin1')).toBe('%PDF-');
        expect(pdfAr.bytes.equals(pdfEn.bytes), 'the Arabic PDF is its own rendering').toBe(false);

        // ── CSV ──
        const csv = await exportVia(page, 'CSV');
        expect(csv.name).toBe(`${stem}.csv`);
        expect(csv.bytes.length).toBeGreaterThan(0);
        const text = csv.bytes.toString('utf8');
        expect(text.charCodeAt(0), 'UTF-8 with a BOM, so Excel reads it as UTF-8').toBe(0xfeff);
        const lines = parseCsv(text.slice(1)).filter(l => l.some(f => f !== ''));
        expect(lines[0], 'PACT’s column labels, a Dr/Cr column after Balance').toEqual([
            'Doc Date', 'Particular', 'Debit', 'Credit', 'Balance', 'Dr/Cr', 'Chq No', 'Payee Bank', 'BDDays', 'ConvertedDate', 'Doc No',
        ]);
        expect(lines.some(l => l[0] === `Tenant Name : ${TENANT_A}`), 'tenant bands sit in the first column').toBe(true);
        expect(lines.some(l => l[6] === 'TT' && l[7] === 'TRANSFER'), 'the transfer row').toBe(true);
        const total = lines.find(l => l[0] === 'REPORT TOTAL');
        expect(total, 'a REPORT TOTAL line').toBeTruthy();
        // Plain decimals and a signed balance with Dr/Cr beside it (CsvGridSink), the screen's own figures.
        expect(total!.slice(0, 6)).toEqual(['REPORT TOTAL', '', plain(a + x + b), plain(a + b), plain(x), 'Dr']);

        await hold(page);

        // The still: the report with its export menu open on the four formats it offers.
        await still(browser, '02-exports-menu', {
            state: STATE_ACCOUNTANT,
            url: page.url(),
            ready: async p => {
                await gridReady(p);
                await p.getByTestId('report-export').click();
                await expect(p.getByRole('menuitem')).toHaveText(['Excel (PACT layout)', 'PDF — English', 'PDF — Arabic', 'CSV']);
            },
        });
    } finally {
        await close();
    }
});

// ── 03 ──────────────────────────────────────────────────────────────────────

test('03 the lease page’s Ledger link still opens the one-tenant statement across all accounts', async ({ browser }) => {
    const { page, close } = await recorded(browser, '03-all-accounts-deep-link', { state: STATE_ACCOUNTANT });
    try {
        await page.goto(`/en/dashboard/leases/${fx.leaseA.id}`);
        await expect(page.getByTestId('lease-status')).toHaveText(/active/i);
        // An ACTIVE lease keeps Ledger under "More actions" (leaseActions.PRIMARY_BY_STATUS).
        const ledgerLink = page.getByTestId('lease-ledger');
        if (!(await ledgerLink.isVisible())) await page.getByTestId('lease-more-actions').click();
        await ledgerLink.click();

        await page.waitForURL(/\/finance\/tenant-ledger\?/, { timeout: 20_000 });
        await expect(page).toHaveURL(new RegExp(`renterId=${fx.leaseA.renterId}`));
        await expect(page).toHaveURL(new RegExp(`leaseId=${fx.leaseA.id}`));
        // A contract in the URL resolves to "All accounts", pinned in the URL on arrival.
        await expect(page).toHaveURL(/view=all/);
        const viewSwitch = page.getByTestId('tl-view-switch');
        await expect(viewSwitch.getByRole('button', { name: 'All accounts' })).toHaveAttribute('aria-pressed', 'true');
        await expect(page.getByTestId('tl-filters'), 'not the PACT filter bar').toHaveCount(0);
        await expect(page.getByTestId('grid-total'), 'not the PACT grid').toHaveCount(0);

        // The old statement: one block per account the tenant carries, each with its Sub Total, then the report total.
        await expect(page.getByTestId('ledger-report-total')).toBeVisible({ timeout: 20_000 });
        const statement = await api<{ accountId: string; accountName: string; rows: unknown[] }[]>(
            accountantActor,
            'GET',
            `/api/v1/finance/ledger/renter/${fx.leaseA.renterId}?from=${WINDOW.from}&to=${WINDOW.to}`,
        );
        const moved = statement.filter(l => l.rows.length > 0);
        expect(moved.length, 'the tenant moved more than one account').toBeGreaterThan(1);
        for (const l of moved) {
            await expect(page.getByTestId(`ledger-subtotal-${l.accountId}`), `${l.accountName} has its block`).toBeVisible();
        }
        await expect(page.getByText(`Rent Receivable - ${PROPERTY}`).first()).toBeVisible();
        await expect(page.getByText(`PDC Receivable ${PROPERTY}`).first()).toBeVisible();
        await expect(page.getByText(`Security Deposit ${PROPERTY}`).first()).toBeVisible();
        await hold(page, 1500);

        // Switching keeps the tenant and contract: the receivable view of this one contract.
        await viewSwitch.getByRole('button', { name: 'Receivable (PACT)' }).click();
        await expect(page.getByTestId('grid-total')).toBeVisible({ timeout: 20_000 });
        await expect(page.getByTestId('grid-band-2')).toHaveText([`Tenant Name : ${TENANT_A}`]);
        await hold(page);
    } finally {
        await close();
    }
});

// ── 04 ──────────────────────────────────────────────────────────────────────

const ALL_REPORTS = [
    'balance-sheet', 'company-pl', 'general-ledger', 'payables-aging', 'property-pl', 'property-statement',
    'tenant-ledger', 'trial-balance', 'vat-return',
];
/** canViewPropertyReports + canViewPayablesAging: the property-scoped ones (lib/reports/catalog.ts). */
const PM_REPORTS = ['balance-sheet', 'payables-aging', 'property-pl', 'property-statement'];

async function reportCards(page: Page): Promise<string[]> {
    await expect(page.getByRole('heading', { name: 'Reports', exact: true })).toBeVisible();
    const cards = page.locator('[data-testid^="report-card-"]');
    await expect(cards.first()).toBeVisible();
    const ids = await cards.evaluateAll(els => els.map(e => e.getAttribute('data-testid') ?? ''));
    return ids.map(id => id.replace(/^report-card-/, '')).sort();
}

test('04 the Reports hub lists what each role may open', async ({ browser }) => {
    // ── ACCOUNTANT: every report, the Tenant Ledger card with its three formats ──
    {
        const { page, close } = await recorded(browser, '04a-reports-hub-accountant', { state: STATE_ACCOUNTANT });
        try {
            await page.goto('/en/dashboard/reports');
            expect(await reportCards(page)).toEqual(ALL_REPORTS);
            const card = page.getByTestId('report-card-tenant-ledger');
            await expect(card.getByRole('heading', { name: 'Tenant Ledger' })).toBeVisible();
            for (const f of ['Excel', 'PDF', 'CSV']) await expect(card.getByText(f, { exact: true })).toBeVisible();
            for (const k of ['general-ledger', 'trial-balance', 'company-pl', 'vat-return']) {
                await expect(page.getByTestId(`report-card-${k}`)).toBeVisible();
            }
            await still(browser, '04-reports-hub-accountant', {
                state: STATE_ACCOUNTANT,
                url: '/en/dashboard/reports',
                height: 1400,
                ready: async p => expect(await reportCards(p)).toEqual(ALL_REPORTS),
            });
            // Search narrows the cards by title and description.
            await page.getByRole('searchbox', { name: 'Search reports' }).fill('tenant');
            await expect(page.locator('[data-testid^="report-card-"]')).toHaveCount(1);
            await card.getByRole('link').click();
            await page.waitForURL(/\/finance\/tenant-ledger/);
            await expect(page.getByTestId('tl-view-switch')).toBeVisible();
            await hold(page, 1500);
        } finally {
            await close();
        }
    }

    // ── PROPERTY_MANAGER: reached from the Accounting nav; property reports only ──
    {
        const { page, close } = await recorded(browser, '04b-reports-hub-property-manager', { state: STATE_MANAGER });
        try {
            await page.goto('/en/dashboard');
            await page.getByTestId('rail-accounting').click();
            // ≥ 1280 px the panel sits inline (and the rail navigates to the section's home); below it, a
            // flyout opens. Both render one nav-panel. Wait for the Accounting section itself before
            // looking inside it — right after the click the inline panel can still show the previous one.
            const panel = page.getByTestId('nav-panel');
            const finalReports = panel.getByTestId('panel-group-toggle-finalReports');
            await expect(finalReports).toBeVisible();
            if ((await finalReports.getAttribute('aria-expanded')) !== 'true') await finalReports.click();
            const hubLink = panel.getByTestId('sidebar-reports-hub');
            await expect(hubLink).toBeVisible();
            await expect(panel.getByTestId('sidebar-tenant-ledger'), 'no Tenant Ledger in a manager’s nav').toHaveCount(0);
            await hubLink.click();
            await page.waitForURL(/\/dashboard\/reports$/);
            expect(await reportCards(page)).toEqual(PM_REPORTS);
            await expect(page.getByTestId('report-card-tenant-ledger')).toHaveCount(0);
            for (const k of PM_REPORTS) {
                await expect(page.getByTestId(`report-card-${k}`).getByText('Your assigned properties only')).toBeVisible();
            }
            await hold(page);
        } finally {
            await close();
        }
    }

    // ── TENANT_ADMIN: everything ──
    {
        const { page, close } = await recorded(browser, '04c-reports-hub-tenant-admin', { state: STATE_ADMIN });
        try {
            await page.goto('/en/dashboard/reports');
            expect(await reportCards(page)).toEqual(ALL_REPORTS);
            await expect(page.getByTestId('report-card-tenant-ledger').getByText('Your assigned properties only')).toHaveCount(0);
            await hold(page, 1500);
        } finally {
            await close();
        }
    }
});

// ── 05 ──────────────────────────────────────────────────────────────────────

test('05 Arabic at 390 px — right to left, no sideways page scroll, Dr/Cr left to right', async ({ browser }) => {
    const { page, close } = await recorded(browser, '05-arabic-390', {
        state: STATE_ACCOUNTANT,
        viewport: { width: 390, height: 844 },
    });
    try {
        await page.goto(`/ar/dashboard/finance/tenant-ledger?view=receivable&${tenantLedgerQuery()}`);
        await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
        await expect(page.getByTestId('grid-total')).toBeVisible({ timeout: 30_000 });

        // Arabic furniture from the server's reports_ar labels, Latin tenant names inside it.
        await expect(page.getByTestId('tl-view-switch').getByRole('button', { name: 'الذمم المدينة (PACT)' }))
            .toHaveAttribute('aria-pressed', 'true');
        await expect(page.getByTestId('grid-band-2')).toHaveText([`اسم المستأجر : ${TENANT_A}`, `اسم المستأجر : ${TENANT_B}`]);
        await expect(page.getByTestId('grid-band-1').first()).toContainText(PROPERTY_AR);
        await expect(page.getByTestId('grid-total')).toContainText('إجمالي التقرير');
        await expect(page.locator('[data-testid^="grid-subtotal-"]').filter({ hasText: 'المجموع الفرعي' }).first()).toBeVisible();
        const table = page.locator('table').filter({ has: page.getByTestId('grid-total') });
        await expect(table.locator('thead th').filter({ hasText: 'رقم الشيك' })).toHaveCount(1);
        await expect(page.getByTestId('report-export')).toBeVisible();

        // Balances keep Latin digits and PACT's Dr/Cr, isolated left to right inside the RTL page.
        const balance = page.getByTestId('grid-total').locator('bdi[dir="ltr"]').filter({ hasText: /(Dr|Cr)$/ });
        await expect(balance).toHaveCount(1);
        await expect(balance).toHaveText(new RegExp(`^${escapeRe(pact(fx.bounced.amount))}$`));
        expect(await balance.evaluate(el => getComputedStyle(el).direction)).toBe('ltr');

        // The page never scrolls sideways: the table scrolls inside its own box instead.
        await hold(page, 1000);
        const widths = await page.evaluate(() => ({
            page: document.scrollingElement!.scrollWidth,
            viewport: window.innerWidth,
        }));
        expect(widths.page, `page scrollWidth ${widths.page} must fit the ${widths.viewport}px viewport`).toBeLessThanOrEqual(widths.viewport);
        const box = await table.evaluate(t => ({ scroll: t.parentElement!.scrollWidth, client: t.parentElement!.clientWidth }));
        expect(box.scroll, 'the grid scrolls inside its own container').toBeGreaterThan(box.client);
        await hold(page);
        await still(browser, '05-arabic-390', {
            state: STATE_ACCOUNTANT,
            url: page.url(),
            width: 390,
            height: 3200,
            ready: async p => {
                await expect(p.locator('html')).toHaveAttribute('dir', 'rtl');
                await gridReady(p);
            },
        });
    } finally {
        await close();
    }
});

// ── 06 ──────────────────────────────────────────────────────────────────────

test('06 a property manager is refused the Tenant Ledger — on screen and at the API', async ({ browser }) => {
    const { page, close } = await recorded(browser, '06-access-denied-property-manager', { state: STATE_MANAGER });
    try {
        const reportRequests: string[] = [];
        page.on('request', r => {
            if (/\/finance\/reports\/tenant-ledger/.test(r.url())) reportRequests.push(r.url());
        });
        await page.goto(`/en/dashboard/finance/tenant-ledger?view=receivable&${tenantLedgerQuery()}`);
        await expect(page.getByTestId('page-access-denied')).toBeVisible();
        await expect(page.getByTestId('grid-total')).toHaveCount(0);
        await expect(page.getByTestId('tl-filters')).toHaveCount(0);
        await expect(page.getByTestId('report-export')).toHaveCount(0);
        await hold(page, 1000);
        expect(reportRequests, 'the screen does not even ask for the report').toEqual([]);

        // Through the proxy, as the signed-in manager (the session the browser holds).
        const q = tenantLedgerQuery();
        const viaProxy = await page.request.get(`/api/proxy/v1/finance/reports/tenant-ledger?${q}`);
        expect(viaProxy.status(), 'the JSON page through the proxy').toBe(403);
        const fileViaProxy = await page.request.get(`/api/proxy/v1/finance/reports/tenant-ledger.xlsx?${q}`);
        expect(fileViaProxy.status(), 'the Excel file through the proxy').toBe(403);

        // At the backend itself: the role gate (D6), not a broken URL — the accountant gets 200 for the same call.
        const asManager = await rawApi(managerActor, 'GET', `/api/v1/finance/reports/tenant-ledger?${q}`);
        expect(asManager.status, 'GET /api/v1/finance/reports/tenant-ledger as PROPERTY_MANAGER').toBe(403);
        const pickerAsManager = await rawApi(managerActor, 'GET', `/api/v1/finance/reports/tenant-ledger/accounts`);
        expect(pickerAsManager.status, 'the account picker too').toBe(403);
        const asAccountant = await rawApi(accountantActor, 'GET', `/api/v1/finance/reports/tenant-ledger?${q}`);
        expect(asAccountant.status, 'the same call as ACCOUNTANT').toBe(200);
        await hold(page);
    } finally {
        await close();
    }

    console.log(`\n  manifest: ${manifest.created.length} records created — ${MANIFEST}`);
});
