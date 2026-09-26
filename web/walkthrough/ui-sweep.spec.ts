import { test, expect, type Browser, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { DASHBOARD_ROUTES, routeAllows } from '../src/lib/nav/routeRegistry';
import { ROUTE_MOVES } from '../src/lib/nav/routeMap';
import type { UserRole } from '../src/lib/rbac';
import {
    assignTicket, createBuilding, createLease, createProperty, createRenter, createTicket, createUnit,
    generateCheques, postLease,
} from '../e2e/helpers/api-client';

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
    { url: /\/(properties\/[^/]+\/units|dashboard\/leases|dashboard\/tickets)$/, text: /status of 403/, roles: ['ACCOUNTANT'],
      why: 'S16-02: GET /buildings/property/{id} does not admit ACCOUNTANT (BuildingController); the Tower filter degrades to hidden' },
    { url: /\/dashboard\/tickets$/, text: /status of 403/, roles: ['TENANT_USER'],
      why: 'the list also loads GET /properties, /units and /renters for the create-ticket form (SA/TA/PM/ACCOUNTANT only); a TENANT_USER never opens that form but the background reads still run and fail quietly' },
    { url: /\/leases\/[^/]+\/settlement$/, text: /status of 404/,
      why: 'GET /leases/{id}/settlement is 404 until a settlement exists; the swept contract is active, not ending' },
    { url: /\/dashboard\/listings$/, text: /status of 404/,
      why: 'GET /listings is 404 while the organisation has the LISTINGS feature off (a fresh organisation does); the page is swept by URL anyway' },
    { url: /\/leases\/[^/]+\?tab=journals$/, text: /status of 403/, roles: ['PROPERTY_MANAGER'],
      why: 'the Journal Vouchers section (the old Journals tab, open to every role before PR 3 too) reads GET /leases/{id}/journals and the renter ledger, which the backend refuses a property manager; the section shows its error' },
    { url: /\/finance\/(opening-balances|reconciliation)$/, text: /status of 400/,
      why: 'GET /finance/opening-balances is 400 until the cut-over (books-start) date is set, which a fresh organisation has not done' },
];

type Actor = { id: string; role: string; tenantId: string | null };
type Fx = {
    tenantId: string; creds: Record<string, { email: string; password: string }>; propertyId: string; renterId: string;
    leaseId: string; journalId: string;
    // S16-02/S16-03: a tower of the property, a unit inside it, a staff user
    // (TENANT_USER) and a ticket assigned to them.
    buildingId: string; towerUnitId: string; staffUserId: string; ticketId: string;
};
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

    // S16-02: a tower of the property, and a second unit inside it — the
    // original leased unit stays outside any tower, so the sweep also covers
    // the "No building" column/row a property with towers still carries.
    const building = await createBuilding(admin.id, admin.role, tenant.id, { propertyId: property.id, nameEn: `Tower A ${SUFFIX}`, nameAr: `برج أ ${SUFFIX}` });
    const towerUnit = await createUnit(admin.id, admin.role, tenant.id, {
        propertyId: property.id, unitNumber: `SW-TW-${SUFFIX}`, expectedRent: 40_000, buildingId: building.id,
    });

    // S16-03: a staff user (TENANT_USER, the maintenance team) and a ticket
    // assigned to them, reported against the original (non-tower) unit.
    const staffEmail = `sweep-staff-${SUFFIX}@example.invalid`;
    const staff = await api<{ id: string }>(scoped, 'POST', '/api/admin/users', {
        name: `Sweep staff`, email: staffEmail, password, role: 'TENANT_USER', tenantId: tenant.id,
    });
    creds.TENANT_USER = { email: staffEmail, password };
    const ticket = await createTicket(admin.id, admin.role, tenant.id, { propertyId: property.id, unitId: unit.id, title: `Sweep ticket ${SUFFIX}` });
    await assignTicket(admin.id, admin.role, tenant.id, ticket.id, staff.id);

    fx = {
        tenantId: tenant.id, creds, propertyId: property.id, renterId: renter.id, leaseId: draft.id, journalId: posted.tcoJournalId,
        buildingId: building.id, towerUnitId: towerUnit.id, staffUserId: staff.id, ticketId: ticket.id,
    };
    for (const { role } of ROLES) await bankSession(browser, creds[role].email, creds[role].password, path.join(STATE_DIR, `${role}.json`));
    await bankSession(browser, staffEmail, password, path.join(STATE_DIR, 'TENANT_USER.json'));
    // The dev bootstrap system admin (DataInitializer), for the header switcher check.
    await bankSession(browser, 'admin@rentaxis.com', 'admin123', path.join(STATE_DIR, 'SUPER_ADMIN.json'));
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

// PR #363 R1: at a 1024 px laptop/tablet width the rail opens a flyout that
// survives navigation, and the organisation switcher (header, one instance)
// is reachable — for a super admin it lists the organisations.
for (const locale of LOCALES) {
    test(`shell ${locale} 1024px: flyout survives navigation; header switcher reachable`, async ({ browser }) => {
        const ta = await browser.newContext({ baseURL: BASE_URL, viewport: { width: 1024, height: 800 }, storageState: path.join(STATE_DIR, 'TENANT_ADMIN.json') });
        const page = await ta.newPage();
        await page.goto(`/${locale}/dashboard`);
        await page.getByTestId('rail-accounting').click();
        const flyout = page.getByTestId('nav-flyout');
        await expect(flyout).toBeVisible();
        expect(new URL(page.url()).pathname).toBe(`/${locale}/dashboard`);
        await flyout.getByTestId('sidebar-trial-balance').click();
        await page.waitForURL(/\/finance\/trial-balance/);
        await expect(flyout).toBeVisible();
        await flyout.getByTestId('sidebar-general-ledger').click();
        await page.waitForURL(/\/finance\/general-ledger/);
        await page.keyboard.press('Escape');
        await expect(flyout).toHaveCount(0);
        await page.getByTestId('rail-leasing').click();
        await expect(flyout).toBeVisible();
        // Outside click: page content at x=700 is clear of the flyout in LTR (64–304) and RTL (720–960).
        await page.mouse.click(700, 400);
        await expect(flyout).toHaveCount(0);
        await ta.close();

        const sa = await browser.newContext({ baseURL: BASE_URL, viewport: { width: 1024, height: 800 }, storageState: path.join(STATE_DIR, 'SUPER_ADMIN.json') });
        const saPage = await sa.newPage();
        await saPage.goto(`/${locale}/dashboard`);
        const switcher = saPage.getByTestId('header-org-switcher').getByTestId('org-switcher-button');
        await expect(switcher).toBeVisible();
        await expect(saPage.getByTestId('org-switcher-button')).toHaveCount(1);
        await switcher.click();
        const msgs = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'messages', `${locale}.json`), 'utf8'));
        await saPage.getByLabel(msgs.TenantSwitcher.searchOrganizations).fill(`UI-SWEEP ${SUFFIX}`);
        await expect(saPage.getByRole('button', { name: new RegExp(`UI-SWEEP ${SUFFIX}`) })).toBeVisible();
        await sa.close();
    });
}

// PR #363 R1: the panel's item list scrolls in its own area above a pinned status
// card (no overlap at 900 px tall or less); at ≥ 1280 px the one org control tops
// the panel and the header has none.
for (const [width, height] of [[1440, 900], [1280, 720]] as const) {
    test(`panel ${width}x${height}: list scrolls above the pinned status card; one org control, in the panel`, async ({ browser }) => {
        const sa = await browser.newContext({ baseURL: BASE_URL, viewport: { width, height }, storageState: path.join(STATE_DIR, 'SUPER_ADMIN.json') });
        const page = await sa.newPage();
        await page.goto('/en/dashboard/finance/journals');
        const list = page.getByTestId('nav-panel-list');
        const card = page.getByTestId('nav-status-card');
        await expect(card).toBeVisible();
        const lb = (await list.boundingBox())!;
        const cb = (await card.boundingBox())!;
        expect(lb.y + lb.height, 'list ends above the card').toBeLessThanOrEqual(cb.y + 0.5);
        const last = list.locator('a:visible').last();
        await last.scrollIntoViewIfNeeded();
        const ib = (await last.boundingBox())!;
        expect(ib.y + ib.height, 'last item reachable above the card').toBeLessThanOrEqual(cb.y + 0.5);
        await expect(page.getByTestId('org-switcher-button')).toHaveCount(1);
        await expect(page.getByTestId('panel-org').getByTestId('org-switcher-button')).toBeVisible();
        await expect(page.getByTestId('header-org-switcher')).toHaveCount(0);
        await sa.close();
    });
}

// UI PR 2: the Cheque / Cash Collection hub's pills and the ledgers opened with a
// pick (they load nothing without one), for the three roles, EN and AR, laptop and phone.
const HUB_TABS = ['deposit', 'due', 'overdue', 'returned', 'post-dated', 'penalties', 'all'] as const;
for (const { role } of ROLES) {
    for (const locale of LOCALES) {
        test(`collection + ledgers ${role} ${locale}`, async ({ browser }) => {
            const { creds, renterId, journalId, leaseId } = fixture();
            const me = await api<{ id: string; role: string; tenantId: string }>(null, 'POST', '/api/auth/login', creds[role]);
            const actor: Actor = { id: me.id, role: me.role, tenantId: fixture().tenantId };
            const failures: string[] = [];
            for (const viewport of WIDTHS) {
                const context = await browser.newContext({ baseURL: BASE_URL, viewport, storageState: path.join(STATE_DIR, `${role}.json`) });
                const page = await context.newPage();
                const hub = DASHBOARD_ROUTES.find(r => r.path === '/dashboard/collections')!;
                if (routeAllows(hub, role)) {
                    for (const tab of HUB_TABS) {
                        await check(page, `/${locale}/dashboard/collections?tab=${tab}`, role, failures);
                        await expect(page.getByTestId(`collections-pill-${tab}`), `${tab} pill`).toHaveAttribute('aria-current', 'page');
                    }
                    // The posted contract's matured cheques are waiting to be deposited: the pill counts them.
                    await page.goto(`/${locale}/dashboard/collections?tab=deposit`);
                    await expect(page.getByTestId('collections-count-deposit')).toHaveText(/^[1-9]/);
                    // An old register bookmark lands on the register with its filters.
                    await page.goto(`/${locale}/dashboard/finance/cheques?status=REGISTERED&leaseId=${leaseId}`);
                    expect(new URL(page.url()).pathname).toBe(`/${locale}/dashboard/collections`);
                    await expect(page.getByTestId('collections-pill-all')).toHaveAttribute('aria-current', 'page');
                    await expect(page.getByTestId('cheque-status-filter')).toHaveValue('REGISTERED');
                }
                const gl = DASHBOARD_ROUTES.find(r => r.path === '/dashboard/finance/general-ledger')!;
                if (routeAllows(gl, role)) {
                    await check(page, `/${locale}/dashboard/finance/general-ledger`, role, failures);
                    await expect(page.getByTestId('ledger-pick-prompt')).toBeVisible();
                    const journal = await api<{ lines: { accountId: string }[] }>(actor, 'GET', `/api/v1/finance/journals/${journalId}`);
                    const picks = [...new Set(journal.lines.map(l => l.accountId))].slice(0, 2);
                    await check(page, `/${locale}/dashboard/finance/general-ledger?accountIds=${picks.join(',')}&from=2026-01-01&to=2026-12-31`, role, failures);
                    await expect(page.getByTestId(`ledger-bf-${picks[0]}`)).toBeVisible();
                    await expect(page.getByTestId('ledger-report-total')).toBeVisible();
                    await expect(page.getByTestId('ledger-col-tower')).toBeVisible();
                    await check(page, `/${locale}/dashboard/finance/tenant-ledger?renterId=${renterId}`, role, failures);
                    await expect(page.getByTestId('ledger-report-total')).toBeVisible();
                }
                await context.close();
            }
            expect(failures).toEqual([]);
        });
    }
}

// UI PR 3: the contract page (four tabs, ≤ 3 primary buttons + More actions, the old
// ?tab= links), Home (pipeline, Needs you now, Unit Status board and its side panel),
// the contract list (pills, row menus) and the Properties More menu — three roles,
// EN and AR, laptop and phone. Every menu and panel must open inside the viewport.
async function inView(page: Page, testId: string, width: number, failures: string[], what: string) {
    const box = await page.getByTestId(testId).boundingBox();
    if (!box) { failures.push(`${what}: ${testId} has no box`); return; }
    if (box.x < -0.5 || box.x + box.width > width + 0.5) failures.push(`${what}: ${testId} spills out of the ${width}px viewport (${Math.round(box.x)}..${Math.round(box.x + box.width)})`);
}

for (const { role } of ROLES) {
    for (const locale of LOCALES) {
        test(`contract + home + lists ${role} ${locale}`, async ({ browser }) => {
            const { creds, leaseId } = fixture();
            const me = await api<{ id: string; role: string }>(null, 'POST', '/api/auth/login', creds.TENANT_ADMIN);
            const admin: Actor = { id: me.id, role: me.role, tenantId: fixture().tenantId };
            const lease = await api<{ unitId: string }>(admin, 'GET', `/api/v1/leases/${leaseId}`);
            const failures: string[] = [];
            for (const viewport of WIDTHS) {
                const context = await browser.newContext({ baseURL: BASE_URL, viewport, storageState: path.join(STATE_DIR, `${role}.json`) });
                // The first-visit welcome tour (TourProvider) opens a modal overlay 1.5 s after load
                // that would sit over every click below; mark it seen, as a returning user has it.
                await context.addInitScript(() => localStorage.setItem('rentaxis_tours_completed', JSON.stringify(['admin-onboarding'])));
                const page = await context.newPage();
                const at = `${role} ${locale} ${viewport.width}px`;

                // The contract page.
                await check(page, `/${locale}/dashboard/leases/${leaseId}`, role, failures);
                await expect(page.getByRole('tab')).toHaveCount(4);
                expect(await page.getByTestId('lease-actions-primary').locator('[data-testid]').count(), `${at} primary buttons`).toBeLessThanOrEqual(3);
                if (await page.getByTestId('lease-more-actions').count()) {
                    await page.getByTestId('lease-more-actions').click();
                    await expect(page.getByTestId('lease-more-actions-menu')).toBeVisible();
                    await inView(page, 'lease-more-actions-menu', viewport.width, failures, `${at} contract menu`);
                    await page.keyboard.press('Escape');
                    await expect(page.getByTestId('lease-more-actions-menu')).toBeHidden();
                }
                for (const [old, tab, section] of [['journals', 'payments', 'journals'], ['contract', 'documents', 'contract'], ['interactions', 'activity', 'interactions']] as const) {
                    await check(page, `/${locale}/dashboard/leases/${leaseId}?tab=${old}`, role, failures);
                    await expect(page.getByTestId(`lease-tab-${tab}`), `${at} ?tab=${old}`).toHaveAttribute('aria-selected', 'true');
                    await expect(page.getByTestId(`lease-section-${section}`), `${at} ?tab=${old}`).toHaveAttribute('open', '');
                }

                // Home. DashboardController does not admit an accountant (see KNOWN_CONSOLE).
                await check(page, `/${locale}/dashboard`, role, failures);
                if (role !== 'ACCOUNTANT') {
                    await expect(page.getByTestId('contract-pipeline')).toBeVisible();
                    await expect(page.getByTestId('pipeline-active')).toContainText(/[1-9]/);
                    await expect(page.getByTestId('today-list')).toBeVisible();
                    await expect(page.getByTestId('kpi-unit-status')).toBeVisible();
                    const tile = page.getByTestId(`unit-tile-${lease.unitId}`);
                    await expect(tile).toBeVisible();
                    await expect(tile).toHaveAttribute('data-status', /OCCUPIED|EXPIRING/);
                    await tile.click();
                    await expect(page.getByTestId('unit-board-drawer')).toBeVisible();
                    await inView(page, 'unit-board-drawer', viewport.width, failures, `${at} unit panel`);
                    await expect(page.getByTestId('unit-board-open-contract')).toHaveAttribute('href', new RegExp(`/leases/${leaseId}$`));
                    await page.keyboard.press('Escape');
                    await expect(page.getByTestId('unit-board-drawer')).toHaveCount(0);
                }

                // The contract list: pills, then one menu per row.
                await check(page, `/${locale}/dashboard/leases`, role, failures);
                await expect(page.getByTestId('contract-pills').getByRole('button')).toHaveCount(6);
                await page.getByTestId('contract-pill-active').click();
                await expect(page).toHaveURL(/status=ACTIVE/);
                // The pill re-reads the list (the table shows a skeleton meanwhile); let that land first.
                await page.waitForLoadState('networkidle', { timeout: 8_000 }).catch(() => {});
                await expect(page.getByTestId(`lease-row-${leaseId}`)).toBeVisible();
                await page.getByTestId(`lease-actions-menu-${leaseId}`).click();
                await expect(page.getByTestId(`lease-actions-menu-panel-${leaseId}`)).toBeVisible();
                await inView(page, `lease-actions-menu-panel-${leaseId}`, viewport.width, failures, `${at} row menu`);
                await page.keyboard.press('Escape');
                // The Filters panel (the all-status select) opens inside the screen too (R1 P2-1).
                await page.getByTestId('filters-button').click();
                await expect(page.getByTestId('filters-panel')).toBeVisible();
                await inView(page, 'filters-panel', viewport.width, failures, `${at} filters panel`);
                await page.getByTestId('filters-button').click();
                await check(page, `/${locale}/dashboard/leases?view=expiring`, role, failures);
                await expect(page.getByTestId('contract-pill-expiring')).toHaveAttribute('aria-pressed', 'true');

                // Properties: Add property up front, the rest under More (admins only create).
                if (role === 'TENANT_ADMIN') {
                    await check(page, `/${locale}/dashboard/properties`, role, failures);
                    await expect(page.getByTestId('properties-add-property')).toBeVisible();
                    await page.getByTestId('properties-more').click();
                    await expect(page.getByTestId('properties-more-menu')).toBeVisible();
                    await inView(page, 'properties-more-menu', viewport.width, failures, `${at} properties menu`);
                    await page.keyboard.press('Escape');
                }
                await context.close();
            }
            expect(failures).toEqual([]);
        });
    }
}

// PR #370 web follow-up (towers): the Units, Contracts and Tickets pages'
// Tower/Building filter — hidden until a property with towers is picked, then
// narrows the list, URL-persisted, EN and AR, laptop and phone.
for (const { role } of ROLES) {
    for (const locale of LOCALES) {
        test(`tower filter ${role} ${locale}`, async ({ browser }) => {
            const { propertyId, buildingId, leaseId } = fixture();
            const failures: string[] = [];
            for (const viewport of WIDTHS) {
                const context = await browser.newContext({ baseURL: BASE_URL, viewport, storageState: path.join(STATE_DIR, `${role}.json`) });
                const page = await context.newPage();
                const at = `${role} ${locale} ${viewport.width}px`;

                // GET /buildings/property/{id} does not admit ACCOUNTANT (BuildingController): the
                // select degrades to hidden for them rather than erroring, on all three lists.
                const buildingsScoped = role !== 'ACCOUNTANT';

                // Units (route-scoped to the property already; the select is the tower).
                await check(page, `/${locale}/dashboard/properties/${propertyId}/units`, role, failures);
                const unitsTower = page.getByTestId('units-building-filter');
                if (buildingsScoped) {
                    await expect(unitsTower, `${at} units tower select`).toBeVisible();
                    expect(await unitsTower.locator('option').count(), `${at} units tower options`).toBe(2);
                    await expect(page.getByText(`SW-${SUFFIX}`), `${at} original unit card before filter`).toBeVisible();
                    await expect(page.getByText(`SW-TW-${SUFFIX}`), `${at} tower unit card before filter`).toBeVisible();
                    await unitsTower.selectOption(buildingId);
                    await expect(page.getByText(`SW-TW-${SUFFIX}`), `${at} tower unit card after filter`).toBeVisible();
                    expect(new URL(page.url()).searchParams.get('buildingId'), `${at} units URL`).toBe(buildingId);
                    await unitsTower.selectOption('');
                    await expect(page.getByText(`SW-${SUFFIX}`), `${at} original unit card after clearing`).toBeVisible();
                } else {
                    await expect(unitsTower, `${at} units tower select hidden for ACCOUNTANT`).toHaveCount(0);
                    await expect(page.getByText(`SW-${SUFFIX}`), `${at} original unit card, no filter`).toBeVisible();
                    await expect(page.getByText(`SW-TW-${SUFFIX}`), `${at} tower unit card, no filter`).toBeVisible();
                }

                // Contracts (leases list): property, then tower.
                await check(page, `/${locale}/dashboard/leases`, role, failures);
                await page.getByTestId('lease-property-filter').selectOption(propertyId);
                await expect(page.getByTestId(`lease-row-${leaseId}`), `${at} leased row before tower filter`).toBeVisible();
                const leaseTower = page.getByTestId('lease-building-filter');
                if (buildingsScoped) {
                    await expect(leaseTower, `${at} lease tower select`).toBeVisible();
                    await leaseTower.selectOption(buildingId);
                    await expect(page.getByTestId(`lease-row-${leaseId}`), `${at} leased row after tower filter (its unit is not in the tower)`).toHaveCount(0);
                    expect(new URL(page.url()).searchParams.get('buildingId'), `${at} leases URL`).toBe(buildingId);
                    await leaseTower.selectOption('');
                    await expect(page.getByTestId(`lease-row-${leaseId}`), `${at} leased row after clearing`).toBeVisible();
                } else {
                    await expect(leaseTower, `${at} lease tower select hidden for ACCOUNTANT`).toHaveCount(0);
                }

                // Tickets (staff-facing paged list — the three sweep roles all reach it).
                await check(page, `/${locale}/dashboard/tickets`, role, failures);
                await page.getByTestId('ticket-property-filter').selectOption(propertyId);
                await expect(page.getByText(`Sweep ticket ${SUFFIX}`), `${at} ticket before tower filter`).toBeVisible();
                const ticketTower = page.getByTestId('ticket-building-filter');
                if (buildingsScoped) {
                    await expect(ticketTower, `${at} ticket tower select`).toBeVisible();
                    await ticketTower.selectOption(buildingId);
                    await expect(page.getByText(`Sweep ticket ${SUFFIX}`), `${at} ticket after tower filter (its unit is not in the tower)`).toHaveCount(0);
                    await ticketTower.selectOption('');
                    await expect(page.getByText(`Sweep ticket ${SUFFIX}`), `${at} ticket after clearing`).toBeVisible();
                } else {
                    await expect(ticketTower, `${at} ticket tower select hidden for ACCOUNTANT`).toHaveCount(0);
                }

                await context.close();
            }
            expect(failures).toEqual([]);
        });
    }
}

// PR #370 web follow-up (towers): the property P&L's "By tower" view — one
// column per Building, a "No building" column, a Total tied to the property
// P&L; EN and AR, laptop and phone.
for (const { role } of ROLES) {
    for (const locale of LOCALES) {
        test(`by-tower P&L ${role} ${locale}`, async ({ browser }) => {
            const failures: string[] = [];
            for (const viewport of WIDTHS) {
                const context = await browser.newContext({ baseURL: BASE_URL, viewport, storageState: path.join(STATE_DIR, `${role}.json`) });
                const page = await context.newPage();
                const at = `${role} ${locale} ${viewport.width}px`;
                const msgs = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'messages', `${locale}.json`), 'utf8'));

                await check(page, `/${locale}/dashboard/finance/reports/property-pl`, role, failures);
                await page.getByTestId('property-multiselect').locator('summary').click();
                await page.getByRole('checkbox').first().check();
                await page.locator('#pnl-period').selectOption('custom');
                await page.locator('#pnl-from').fill('2026-01-01');
                await page.locator('#pnl-to').fill('2026-12-31');
                await page.getByRole('button', { name: msgs.PropertyReports.apply }).click();
                await page.waitForLoadState('networkidle', { timeout: 8_000 }).catch(() => {});

                const towerToggle = page.getByTestId('pl-view-tower');
                if (role === 'ACCOUNTANT') {
                    // GET /buildings/property/{id} does not admit ACCOUNTANT: the toggle
                    // degrades to hidden (same as the tower filters) rather than erroring.
                    await expect(towerToggle, `${at} By tower toggle hidden for ACCOUNTANT`).toHaveCount(0);
                } else {
                    await expect(towerToggle, `${at} By tower toggle`).toBeVisible();
                    await towerToggle.click();
                    const table = page.getByTestId('pl-by-tower-table');
                    await expect(table, `${at} by-tower table`).toBeVisible();
                    await inView(page, 'pl-by-tower-table', viewport.width, failures, `${at} by-tower table`);
                    const towerName = locale === 'ar' ? `برج أ ${SUFFIX}` : `Tower A ${SUFFIX}`;
                    await expect(table.getByText(towerName), `${at} tower column`).toBeVisible();
                    await expect(table.getByText(msgs.PropertyReports.total), `${at} total column`).toBeVisible();

                    await page.getByTestId('pl-view-property').click();
                    await expect(page.getByTestId('pl-by-tower-table')).toHaveCount(0);
                }

                await context.close();
            }
            expect(failures).toEqual([]);
        });
    }
}

// PR #370 web follow-up (towers/S16-03): a maintenance-team TENANT_USER sees
// the assignee picker's staff option, then the ticket in their own list and
// can move its status — nothing an admin/PM-only action (assign, close with a
// code, set an ETA) is offered. EN and AR, laptop and phone.
for (const locale of LOCALES) {
    test(`staff ticket assignment ${locale}`, async ({ browser }) => {
        const { ticketId } = fixture();
        const failures: string[] = [];

        // The admin sees the staff user among the assignees (S16-03: staff are now
        // assignable) — opens the "Assign To…" dropdown, not "Assign to Me" (its
        // sibling button, which would reassign the ticket away from the fixture's staff).
        const adminMsgs = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'messages', `${locale}.json`), 'utf8'));
        const admin = await browser.newContext({ baseURL: BASE_URL, storageState: path.join(STATE_DIR, 'TENANT_ADMIN.json') });
        // The first-visit welcome tour opens a modal overlay 1.5 s after load that
        // would sit over every click below; mark it seen, as a returning user has it.
        await admin.addInitScript(() => localStorage.setItem('rentaxis_tours_completed', JSON.stringify(['admin-onboarding'])));
        const adminPage = await admin.newPage();
        await check(adminPage, `/${locale}/dashboard/tickets/${ticketId}`, 'TENANT_ADMIN', failures);
        await adminPage.getByText(adminMsgs.Tickets.assignTo, { exact: true }).click();
        await expect(adminPage.getByRole('button', { name: /Sweep staff/ })).toBeVisible();
        await admin.close();

        const msgs = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'messages', `${locale}.json`), 'utf8'));
        // The ticket's status is shared backend state across both viewport passes below,
        // and across the EN and AR runs of this test (same fixture ticket) — so which of
        // Start Work / Mark Resolved is on screen depends on how far an earlier pass took
        // it. Read whichever is actually there; drive the transition only once (Start
        // Work → In Progress), wherever this pass finds it still at that stage.

        for (const viewport of WIDTHS) {
            const context = await browser.newContext({ baseURL: BASE_URL, viewport, storageState: path.join(STATE_DIR, 'TENANT_USER.json') });
            await context.addInitScript(() => localStorage.setItem('rentaxis_tours_completed', JSON.stringify(['admin-onboarding'])));
            const page = await context.newPage();
            const at = `TENANT_USER ${locale} ${viewport.width}px`;

            await check(page, `/${locale}/dashboard/tickets`, 'TENANT_USER', failures);
            const mine = page.getByTestId('ticket-filter-mine');
            const all = page.getByTestId('ticket-filter-all');
            await expect(mine, `${at} My tickets toggle`).toBeVisible();
            await expect(page.getByText(`Sweep ticket ${SUFFIX}`), `${at} ticket in All`).toBeVisible();
            await mine.click();
            await expect(page.getByText(`Sweep ticket ${SUFFIX}`), `${at} ticket in My tickets`).toBeVisible();
            await all.click();

            await check(page, `/${locale}/dashboard/tickets/${ticketId}`, 'TENANT_USER', failures);
            // A status action is offered — PUT /status, which the backend now admits
            // for the assignee — whichever stage the ticket is actually at.
            const startBtn = page.getByText(msgs.Tickets.startWork);
            const resolveBtn = page.getByText(msgs.Tickets.markResolved);
            await expect(startBtn.or(resolveBtn), `${at} a status action offered to the assignee`).toBeVisible();
            expect(await page.getByText(msgs.Tickets.assignToMe).count(), `${at} no Assign to Me`).toBe(0);
            expect(await page.getByText(msgs.Tickets.assignTo, { exact: true }).count(), `${at} no Assign To…`).toBe(0);
            expect(await page.getByText(msgs.Tickets.setEstimatedHours).count(), `${at} no ETA control`).toBe(0);
            if (await startBtn.count() > 0) {
                await startBtn.click();
                await expect(resolveBtn, `${at} status moved to In Progress`).toBeVisible();
            }

            await context.close();
        }
        expect(failures).toEqual([]);
    });
}
