import { test, expect, type Browser, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { DASHBOARD_ROUTES, routeAllows } from '../src/lib/nav/routeRegistry';
import { ROUTE_MOVES } from '../src/lib/nav/routeMap';
import type { UserRole } from '../src/lib/rbac';
import { createLease, createProperty, createRenter, createUnit, generateCheques, postLease } from '../e2e/helpers/api-client';

const BACKEND = process.env.WT_BACKEND_URL || 'http://localhost:8081';
const BASE_URL = process.env.WT_BASE_URL || 'http://localhost:3001';
const SUFFIX = process.env.WT_SWEEP_SUFFIX ?? Math.random().toString(36).slice(2, 7);
// Outside the config's outputDir (emptied at the start of every run), so a
// re-run with WT_SWEEP_SUFFIX=<id> reuses that organisation and its sessions.
const STATE_DIR = path.join(__dirname, 'raw', 'ui-sweep-state', SUFFIX);
const ROLES: { role: UserRole; slug: string }[] = [
    { role: 'TENANT_ADMIN', slug: 'admin' }, { role: 'PROPERTY_MANAGER', slug: 'manager' }, { role: 'ACCOUNTANT', slug: 'accountant' },
];
const LOCALES = ['en', 'ar'] as const;
const WIDTHS = [{ width: 1366, height: 800 }, { width: 390, height: 844 }] as const;
/** [id] routes this run seeds no row for; each is listed so skipping is a decision, not an accident. */
const UNSEEDED = new Set([
    '/dashboard/tickets/[id]', '/dashboard/meetings/[id]', '/dashboard/listings/[id]', '/dashboard/finance/vendors/[id]',
    '/dashboard/finance/payables/payment-runs/[id]', '/dashboard/finance/bank-reconciliation/[id]',
]);
/**
 * Console errors that predate this change and are the page working as designed
 * for this fixture, each scoped to its URL and with its reason. Anything else
 * fails the sweep.
 */
const KNOWN_CONSOLE: { url: RegExp; text: RegExp; why: string; roles?: UserRole[] }[] = [
    { url: /\/leases\/[^/]+\/(terminate|settlement)$/, text: /status of 403/, roles: ['PROPERTY_MANAGER'],
      why: 'the page reads GET /finance/fiscal-settings for the period lock, which the backend refuses a property manager; the page carries on without it' },
    { url: /^\/dashboard$/, text: /status of 403/, roles: ['ACCOUNTANT'],
      why: 'DashboardController (summary, monthly collections) does not admit ACCOUNTANT; the home page shows its empty state' },
    { url: /\/leases\/[^/]+$/, text: /status of 403/, roles: ['ACCOUNTANT'],
      why: 'GET /leases/{id}/attachments does not admit ACCOUNTANT; the contract page shows no attachments' },
    { url: /\/leases\/[^/]+\/settlement$/, text: /status of 404/,
      why: 'GET /leases/{id}/settlement is 404 until a settlement exists; the swept contract is active, not ending' },
    { url: /\/dashboard\/listings$/, text: /status of 404/,
      why: 'GET /listings is 404 while the organisation has the LISTINGS feature off (a fresh organisation does); the page is swept by URL anyway' },
    { url: /\/finance\/(opening-balances|reconciliation)$/, text: /status of 400/,
      why: 'GET /finance/opening-balances is 400 until the cut-over (books-start) date is set, which a fresh organisation has not done' },
];

type Actor = { id: string; role: string; tenantId: string | null };
type Fx = { tenantId: string; creds: Record<string, { email: string; password: string }>; propertyId: string; renterId: string; leaseId: string; journalId: string };
let fx: Fx | undefined;
/** The organisation test 00 provisioned (read back from disk in a restarted worker). */
function fixture(): Fx {
    fx ??= JSON.parse(fs.readFileSync(path.join(STATE_DIR, 'fixture.json'), 'utf8')) as Fx;
    return fx;
}

async function api<T>(actor: Actor | null, method: string, apiPath: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (actor) {
        headers['X-User-Id'] = actor.id; headers['X-User-Role'] = actor.role;
        if (actor.tenantId) { headers['X-Tenant-Id'] = actor.tenantId; headers['X-User-Tenant-Id'] = actor.tenantId; }
    }
    const res = await fetch(`${BACKEND}${apiPath}`, { method, headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const text = await res.text();
    if (res.status >= 400) throw new Error(`${method} ${apiPath} → ${res.status}: ${text.slice(0, 300)}`);
    return (text ? JSON.parse(text) : {}) as T;
}

async function bankSession(browser: Browser, email: string, password: string, file: string) {
    const context = await browser.newContext({ baseURL: BASE_URL });
    const page = await context.newPage();
    await page.goto('/en/auth/login');
    await page.locator('#login-email').fill(email);
    await page.locator('#login-password').fill(password);
    await page.getByRole('button', { name: /sign in/i }).click();
    await page.waitForURL(/\/dashboard/, { timeout: 60_000 });
    await expect.poll(async () => (await context.cookies()).some(c => c.name.endsWith('next-auth.session-token')), { timeout: 20_000 }).toBe(true);
    await context.storageState({ path: file });
    await context.close();
}

function resolve(route: string): string | null {
    if (UNSEEDED.has(route)) return null;
    return route
        .replace('/properties/[id]', `/properties/${fixture().propertyId}`)
        .replace('/renters/[id]', `/renters/${fixture().renterId}`)
        .replace('/leases/[id]', `/leases/${fixture().leaseId}`)
        .replace('/journals/[id]', `/journals/${fixture().journalId}`)
        .replace('/help/[slug]', '/help/getting-started--welcome');
}

async function check(page: Page, url: string, role: UserRole, failures: string[]) {
    const errors: string[] = [];
    const onConsole = (m: { type(): string; text(): string }) => {
        const path = url.replace(/^\/(en|ar)/, '');
        const known = KNOWN_CONSOLE.some(k => k.url.test(path) && k.text.test(m.text()) && (!k.roles || k.roles.includes(role)));
        if (m.type() === 'error' && !known) errors.push(m.text());
    };
    const onPageError = (e: Error) => errors.push(`pageerror: ${e.message}`);
    page.on('console', onConsole);
    page.on('pageerror', onPageError);
    // 'load', then a bounded wait for the network to settle: some pages keep a
    // request open (notifications polling, 403 bodies never read), so a bare
    // 'networkidle' can wait for ever.
    const res = await page.goto(url, { waitUntil: 'load', timeout: 60_000 });
    await page.waitForLoadState('networkidle', { timeout: 8_000 }).catch(() => {});
    if (!res || res.status() !== 200) failures.push(`${url}: HTTP ${res?.status()}`);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (overflow > 1) failures.push(`${url}: page scrolls sideways by ${overflow}px`);
    for (const e of errors) failures.push(`${url}: console ${e.slice(0, 200)}`);
    page.off('console', onConsole);
    page.off('pageerror', onPageError);
}

// Not 'serial': one failing sweep must not skip the other eleven. Order still
// holds (one worker, not fullyParallel), so 00 provisions before any sweep.
test.describe.configure({ mode: 'default' });

test('00 provision an organisation, the three roles and a posted contract', async ({ browser }) => {
    fs.mkdirSync(STATE_DIR, { recursive: true });
    if (fs.existsSync(path.join(STATE_DIR, 'fixture.json'))) return; // re-run against the same organisation
    const su = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', { email: 'admin@rentaxis.com', password: 'admin123' });
    const superAdmin: Actor = { id: su.id, role: su.role, tenantId: null };
    const tenant = await api<{ id: string }>(superAdmin, 'POST', '/api/admin/tenants', { name: `WALKTHROUGH-UI-SWEEP ${SUFFIX}` });
    const scoped: Actor = { ...superAdmin, tenantId: tenant.id };
    const password = `Sweep!${SUFFIX}9`;
    const creds: Fx['creds'] = {};
    const ids: Record<string, string> = {};
    for (const { role, slug } of ROLES) {
        const email = `sweep-${slug}-${SUFFIX}@example.invalid`;
        const user = await api<{ id: string }>(scoped, 'POST', '/api/admin/users', { name: `Sweep ${slug}`, email, password, role, tenantId: tenant.id });
        creds[role] = { email, password };
        ids[role] = user.id;
    }
    await api(scoped, 'POST', '/api/v1/finance/accounts/seed');
    const admin = { id: ids.TENANT_ADMIN, role: 'TENANT_ADMIN', tenantId: tenant.id };
    const property = await createProperty(admin.id, admin.role, tenant.id, { nameEn: `Sweep House ${SUFFIX}`, emirate: 'DUBAI', type: 'RESIDENTIAL' });
    await api(scoped, 'POST', `/api/admin/users/${ids.PROPERTY_MANAGER}/properties/${property.id}`);
    const unit = await createUnit(admin.id, admin.role, tenant.id, { propertyId: property.id, unitNumber: `SW-${SUFFIX}`, expectedRent: 60_000 });
    const renter = await createRenter(admin.id, admin.role, tenant.id, { nameEn: `Sweep Tenant ${SUFFIX}`, email: `sweep-renter-${SUFFIX}@example.invalid` });
    const draft = await createLease(admin.id, admin.role, tenant.id, { unitId: unit.id, renterId: renter.id, startDate: '2026-01-01', endDate: '2026-12-31', rentAmount: 60_000 });
    await generateCheques(admin.id, admin.role, tenant.id, draft.id, { installments: 4 });
    // Posting refuses a post-dated cheque without its number and date. PUT
    // /cheques replaces each row wholesale, so every field goes back.
    type Row = Record<string, unknown> & { seqNo: number; chequeDate: string | null; dueDate?: string | null; postingDate: string | null };
    const rows = await api<Row[]>(admin, 'GET', `/api/v1/leases/${draft.id}/cheques`);
    await api(admin, 'PUT', `/api/v1/leases/${draft.id}/cheques`, rows.map(c => ({
        id: c.id, seqNo: c.seqNo, amount: c.amount, mode: c.mode, debitAccountId: c.debitAccountId, narration: c.narration,
        postingDate: c.postingDate, chequeNumber: `SW${SUFFIX}${c.seqNo}`,
        chequeDate: c.chequeDate ?? c.dueDate ?? c.postingDate, payeeBank: c.payeeBank ?? 'Emirates NBD', payerName: c.payerName,
    })));
    const posted = await postLease(admin.id, admin.role, tenant.id, draft.id);
    fx = { tenantId: tenant.id, creds, propertyId: property.id, renterId: renter.id, leaseId: draft.id, journalId: posted.tcoJournalId };
    for (const { role } of ROLES) await bankSession(browser, creds[role].email, creds[role].password, path.join(STATE_DIR, `${role}.json`));
    fs.writeFileSync(path.join(STATE_DIR, 'fixture.json'), JSON.stringify(fx, null, 2));
});

for (const { role } of ROLES) {
    for (const locale of LOCALES) {
        for (const viewport of WIDTHS) {
            test(`sweep ${role} ${locale} ${viewport.width}px`, async ({ browser }) => {
                const context = await browser.newContext({ baseURL: BASE_URL, viewport, storageState: path.join(STATE_DIR, `${role}.json`) });
                const page = await context.newPage();
                const failures: string[] = [];
                const skipped: string[] = [];
                for (const entry of DASHBOARD_ROUTES.filter(r => routeAllows(r, role))) {
                    const target = resolve(entry.path);
                    if (!target) { skipped.push(entry.path); continue; }
                    await check(page, `/${locale}${target}`, role, failures);
                }
                test.info().annotations.push({ type: 'skipped-unseeded', description: skipped.join(', ') });
                await context.close();
                expect(failures).toEqual([]);
            });
        }
    }
}

for (const locale of LOCALES) {
    test(`redirects ${locale}: every moved URL lands on its new home with the query kept`, async ({ browser }) => {
        const context = await browser.newContext({ baseURL: BASE_URL, storageState: path.join(STATE_DIR, 'TENANT_ADMIN.json') });
        const page = await context.newPage();
        for (const move of ROUTE_MOVES) {
            const from = move.from.replace(/:([A-Za-z]+)/g, 'x');
            await page.goto(`/${locale}${from}?probe=1&tab=keep`);
            const url = new URL(page.url());
            expect(url.pathname, move.from).toBe(`/${locale}${move.to.replace(/:([A-Za-z]+)/g, 'x')}`);
            expect(url.searchParams.get('probe'), move.from).toBe('1');
            for (const [k, v] of Object.entries(move.query ?? { tab: 'keep' })) expect(url.searchParams.get(k), `${move.from} ${k}`).toBe(v);
        }
        await context.close();
    });
}
