import { test, expect, request as playwrightRequest, type Locator, type Page, type Browser } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

import { api, setActiveTenant, type ProdContext } from '../e2e-prod/helpers/prod-client';

/**
 * Lease creation with cheques — all, none, and some.
 *
 * The recording and the proof are the same run: each scenario asserts the lease
 * was actually created and that its payment schedule carries the cheque state
 * the scenario is named for. A video only exists because those assertions
 * passed. A take of a broken flow is worse than no take.
 *
 * Provisions ONE disposable tenant with three vacant units — one per scenario —
 * rather than three tenants. Fewer records created, and the three leases sit
 * side by side in the same lease list at the end, which is the shot worth having.
 *
 * Data is synthetic. Deliberately NOT reusing 01-provision's renter, which uses
 * a real Gmail +alias so invite mail lands in a real inbox: sensible for
 * testing, wrong for a video, where it would put a personal address on screen.
 */

const AUTH = path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json');
// Per-run filename. A single shared manifest would be overwritten by the next
// run, orphaning the records the previous one created — the manifest is the
// cleanup boundary, so losing it loses the ability to clean up.
const TAKES_DIR = path.join(__dirname, 'takes');
const MANIFEST = path.join(__dirname, `run-manifest-${new Date().toISOString().slice(0, 10)}-${Math.random().toString(36).slice(2, 7)}.json`);
const STAMP = new Date().toISOString().slice(0, 10);
const SUFFIX = Math.random().toString(36).slice(2, 7);

type Manifest = {
  startedAt: string;
  baseURL: string;
  environment: 'production';
  tenantCleanupAuthorized: false;
  created: { kind: string; id: string; label: string }[];
};

const manifest: Manifest = {
  startedAt: new Date().toISOString(),
  baseURL: process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com',
  environment: 'production',
  // Never flipped by this spec. Inventory only — deletion is the operator's call.
  tenantCleanupAuthorized: false,
  created: [],
};

/** Append-as-created. The manifest is the cleanup boundary, not a summary written at the end. */
function record(kind: string, id: string, label: string) {
  manifest.created.push({ kind, id, label });
  fs.writeFileSync(MANIFEST, JSON.stringify(manifest, null, 2));
  console.log(`  created ${kind}: ${label} (${id})`);
}

type Fixtures = {
  tenantId: string;
  adminEmail: string;
  adminPassword: string;
  renterName: string;
  units: { id: string; unitNumber: string }[];
};

let fx: Fixtures;

test.describe.configure({ mode: 'serial' });

test('provision a disposable tenant with three vacant units', async () => {
  const request = await playwrightRequest.newContext({ baseURL: manifest.baseURL, storageState: AUTH });
  const su: ProdContext = { baseURL: manifest.baseURL, request, user: { role: 'SUPER_ADMIN' } as never };

  const tenant = await api.createTenant(su, `TUTORIAL-LEASE-CHEQUES ${STAMP} ${SUFFIX}`);
  expect(tenant.id, 'tenant must be created').toBeTruthy();
  record('tenant', tenant.id, `TUTORIAL-LEASE-CHEQUES ${STAMP} ${SUFFIX}`);

  const adminEmail = `tutorial-admin-${SUFFIX}@example.invalid`;
  const adminPassword = `Tutorial!${SUFFIX}9`;
  // Pivot SUPER_ADMIN's effective tenant before creating anything scoped to it,
  // exactly as 01-provision does.
  await setActiveTenant(su, tenant.id);

  const admin = await api.createUser(su, tenant.id, {
    name: `Tutorial Admin ${SUFFIX}`,
    email: adminEmail,
    password: adminPassword,
    role: 'TENANT_ADMIN',
  });
  record('user', admin?.id ?? 'unknown', adminEmail);

  const ta = su;

  const property = await api.createProperty(ta, { nameEn: `Tutorial Residences ${SUFFIX}`, emirate: 'DUBAI' });
  record('property', property.id, `Tutorial Residences ${SUFFIX}`);

  const units: { id: string; unitNumber: string }[] = [];
  for (const n of ['1201', '1202', '1203']) {
    const u = await api.createUnit(ta, { propertyId: property.id, unitNumber: n, expectedRent: 5000 });
    units.push({ id: u.id, unitNumber: u.unitNumber });
    record('unit', u.id, `Unit ${n}`);
  }

  const renterName = `Aisha Tutorial ${SUFFIX}`;
  const renter = await api.createRenter(ta, {
    nameEn: renterName,
    email: `tutorial-renter-${SUFFIX}@example.invalid`,
  });
  record('renter', renter.id, renterName);

  fx = { tenantId: tenant.id, adminEmail, adminPassword, renterName, units };
});

/** Signs a fresh browser context in as the tenant admin. One context per scenario => one video per scenario. */
async function signedInPage(browser: Browser, takeName: string): Promise<{ page: Page; close: () => Promise<void> }> {
  // recordVideo must be set explicitly here. The config's `video` option only
  // applies to contexts Playwright creates via its own fixtures — a context made
  // with browser.newContext() inherits none of it, which is why the first
  // passing run produced no footage at all.
  const context = await browser.newContext({
    baseURL: manifest.baseURL,
    recordVideo: { dir: TAKES_DIR, size: { width: 1280, height: 720 } },
  });
  // Suppress the first-login onboarding tour deterministically rather than
  // racing it. TourProvider auto-starts 'admin-onboarding' 1500ms after the
  // dashboard mounts unless this key lists it, and it renders a Shepherd modal
  // overlay that swallows every click underneath. Dismissing it reactively
  // worked only while the run was fast enough to get ahead of that timer; at
  // demo pace the overlay landed mid-wizard and blocked the Next button.
  //
  // This is a recording concern, not a product opinion: a real new admin does
  // get this tour, and that is worth knowing before demoing a fresh tenant.
  await context.addInitScript(() => {
    try {
      window.localStorage.setItem('rentaxis_tours_completed', JSON.stringify(['admin-onboarding']));
    } catch {
      /* storage unavailable — the reactive dismissal below is the fallback */
    }
  });

  const page = await context.newPage();
  await page.goto('/en/auth/login');
  await page.locator('#login-email').fill(fx.adminEmail);
  await page.locator('#login-password').fill(fx.adminPassword);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 });

  // Fallback for the case where the init script did not take: cancel the tour
  // and wait for its click-eating overlay to actually leave the DOM.
  const tourOverlay = page.locator('.shepherd-modal-overlay-container');
  if (await tourOverlay.isVisible().catch(() => false)) {
    const cancel = page.locator('.shepherd-element .shepherd-cancel-icon').first();
    if (await cancel.isVisible().catch(() => false)) await cancel.click();
    await expect(tourOverlay).toBeHidden({ timeout: 10_000 });
  }

  return {
    page,
    // The file only exists once the context is closed, and Playwright names it
    // with a random id — rename to the scenario so takes are identifiable.
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

/**
 * Drives the wizard to the payment-plan step for one unit.
 * Returns the wizard dialog so the caller can fill cheques its own way.
 */
async function openWizardToPlan(page: Page, unitNumber: string) {
  await page.goto('/en/dashboard/leases');
  await page.getByRole('button', { name: /draft lease/i }).first().click();

  // The wizard is a bare <div className="fixed inset-0 z-50">, NOT a
  // role="dialog" — so getByRole('dialog') matched the onboarding tour instead,
  // which is how the first attempt hung. (That missing role is issue #176: the
  // app's modals expose no dialog role and no focus trap, so a screen reader
  // gets no signal that a modal opened.) Anchor on the one stable hook the
  // component does provide, its aria-labelled close button.
  const wizard = page
    .locator('div.fixed.inset-0')
    .filter({ has: page.getByRole('button', { name: 'Close wizard' }) })
    .first();
  await expect(wizard).toBeVisible();

  // Step 1 — unit and renter.
  await wizard.getByRole('combobox').nth(0).click();
  await wizard.getByRole('option').filter({ hasText: unitNumber }).click();
  await wizard.getByRole('combobox').nth(1).click();
  await wizard.getByRole('option').filter({ hasText: fx.renterName }).click();
  await wizard.getByRole('button', { name: /^next$/i }).click();

  // Step 2 — term and money. A 4-month term keeps the plan short enough to read on screen.
  const start = new Date();
  const end = new Date(start);
  end.setMonth(end.getMonth() + 4);
  const iso = (d: Date) => d.toISOString().slice(0, 10);
  await wizard.locator('input[type="date"]').nth(0).fill(iso(start));
  await wizard.locator('input[type="date"]').nth(1).fill(iso(end));
  await wizard.locator('input[type="number"]').nth(0).fill('5000');   // monthly rent
  await wizard.locator('input[type="number"]').nth(1).fill('20000');  // total
  await wizard.getByRole('button', { name: /^next$/i }).click();

  // Step 3 — charges: defaults.
  await wizard.getByRole('button', { name: /^next$/i }).click();

  return wizard;
}

/** Saves the draft and returns the created lease id, asserting the POST succeeded. */
async function saveDraft(page: Page, wizard: ReturnType<Page['getByRole']>) {
  const [res] = await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/proxy\/v1\/leases(\?|$)/.test(r.url()) && r.request().method() === 'POST',
      { timeout: 30_000 },
    ),
    wizard.getByRole('button', { name: /save draft/i }).click(),
  ]);
  expect(res.ok(), 'lease creation must succeed').toBeTruthy();
  const lease = await res.json();
  expect(lease.id).toBeTruthy();
  return lease.id as string;
}

/**
 * Reads the lease's payment schedule back from the API — the proof, independent
 * of what the UI happens to render.
 *
 * Uses the signed-in page's own request context rather than a fresh one: it
 * already carries the tenant admin's session and tenant scope, and a separately
 * created context was being torn down before it could be used.
 */
async function chequeCountFor(page: Page, leaseId: string):
    Promise<{ total: number; withCheque: number; onChequeMethod: number }> {
  // Path convention: backend /api/<x> -> proxy /api/proxy/<x>.
  const res = await page.request.get(`/api/proxy/v1/payments/lease/${leaseId}`);
  expect(res.ok(), 'payment schedule must be readable').toBeTruthy();
  const rows: { chequeNumber?: string | null; paymentMethod?: string | null }[] = await res.json();
  return {
    total: rows.length,
    withCheque: rows.filter((r) => !!r.chequeNumber && String(r.chequeNumber).trim() !== '').length,
    // Counted separately from withCheque on purpose. Counting only cheque
    // numbers cannot tell "row kept method CHEQUE with details still blank"
    // apart from "row was quietly coerced to CASH" — and that coercion was
    // exactly the workaround this change exists to remove, so a test that
    // could not see the difference would pass either way.
    onChequeMethod: rows.filter((r) => String(r.paymentMethod ?? '').toUpperCase() === 'CHEQUE').length,
  };
}

/**
 * The schedule editor that appears AFTER the draft is saved.
 *
 * This is where cheques are actually recorded: the wizard's own copy says
 * "Saving will create the draft lease and auto-generate the installment
 * schedule. You can then adjust per-row dates, cheque numbers, banks, and
 * methods". Nothing on the earlier plan step captures a cheque number.
 */
function scheduleRows(wizard: ReturnType<Page['locator']>) {
  return wizard.locator('table tbody tr');
}

/** Fills cheque number, cheque date and bank on one row. All three are required together. */
async function fillChequeRow(row: ReturnType<Page['locator']>, chequeNo: string) {
  // Each row has TWO date inputs: [0] is the installment's DUE date, [1] is the
  // cheque date. Filling [0] overwrites the generated due date and leaves the
  // cheque date empty — which both corrupts the schedule and fails validation.
  await row.locator('input[type="date"]').nth(1).fill(new Date().toISOString().slice(0, 10));
  await row.locator('input[placeholder="Cheque number"]').first().fill(chequeNo);
  // Bank is the input whose placeholder is "—" when a bank is required.
  await row.locator('input[placeholder="—"]').first().fill('Emirates NBD');
}


/**
 * Clicks Save schedule and waits for the write to actually land.
 *
 * Clicking and immediately reading the API reported 0 cheques on a schedule
 * that had just been filled in — the read raced the PUT. Waiting on the
 * response is what makes the assertion afterwards mean anything.
 */
async function saveSchedule(page: Page, wizard: ReturnType<Page['locator']>) {
  const [res] = await Promise.all([
    page.waitForResponse(
      (r) => /\/api\/proxy\/v1\/leases\/[^/]+\/payment-schedule/.test(r.url()) && r.request().method() !== 'GET',
      { timeout: 30_000 },
    ),
    wizard.getByRole('button', { name: /save schedule/i }).click(),
  ]);
  expect(res.ok(), `saving the schedule must succeed (got ${res.status()})`).toBeTruthy();
}

/**
 * Holds the final state on screen so the take ends on the thing it proves
 * rather than cutting the instant the last assertion returns. Recording-only;
 * no assertion depends on it.
 */
async function hold(page: Page, ms = 2500) {
  await page.waitForTimeout(ms);
}

/**
 * Waits for the payment-schedule editor to finish its post-save reload, then
 * holds on it. Saving triggers a refetch, and a plain timed hold ended every
 * cheque take on a "Loading payment schedule..." spinner — the take stopped one
 * moment before the thing it exists to show.
 *
 * The wait is also a real check: it fails if the editor never re-renders the
 * schedule it just persisted.
 */
async function settleOnSchedule(page: Page, wizard: Locator, ms = 2500) {
  const loading = wizard.getByText(/loading payment schedule/i);
  const started = Date.now();

  // The refetch is kicked off by the save and does not start synchronously, so
  // checking for the spinner immediately finds the pre-save rows still on
  // screen and passes against the wrong render. Give it a moment to appear
  // first — the earlier version of this check passed that way and every take
  // still ended on a spinner.
  await page.waitForTimeout(750);
  if (await loading.isVisible().catch(() => false)) {
    await expect(loading).toBeHidden({ timeout: 60_000 });
  }
  await expect(scheduleRows(wizard).first()).toBeVisible({ timeout: 60_000 });
  console.log(`  schedule re-rendered ${Date.now() - started}ms after save`);
  await hold(page, ms);
}

test('scenario A — every installment has a cheque', async ({ browser }) => {
  const { page, close } = await signedInPage(browser, '01-all-cheques');
  try {
    const wizard = await openWizardToPlan(page, fx.units[0].unitNumber);
    await wizard.getByRole('button', { name: /^next$/i }).click();
    const leaseId = await saveDraft(page, wizard);

    // Cheques are recorded after the draft exists, on the generated schedule.
    const rows = scheduleRows(wizard);
    await expect(rows.first()).toBeVisible({ timeout: 30_000 });
    const n = await rows.count();
    for (let i = 0; i < n; i++) await fillChequeRow(rows.nth(i), `${100200 + i}`);
    await saveSchedule(page, wizard);
    await expect(wizard.getByText(/cheque rows need/i)).toBeHidden();
    const filled = n;
    record('lease', leaseId, `Scenario A — all cheques (unit ${fx.units[0].unitNumber})`);

    const { total, withCheque } = await chequeCountFor(page, leaseId);
    console.log(`  scenario A: ${withCheque}/${total} installments carry a cheque (filled ${filled} field(s))`);
    expect(total, 'lease must generate installments').toBeGreaterThan(0);
    expect(withCheque, 'every installment should carry a cheque').toBe(total);
    await settleOnSchedule(page, wizard);
  } finally {
    await close();
  }
});

test('scenario B — no cheques captured', async ({ browser }) => {
  const { page, close } = await signedInPage(browser, '02-no-cheques');
  try {
    const wizard = await openWizardToPlan(page, fx.units[1].unitNumber);
    await wizard.getByRole('button', { name: /^next$/i }).click();
    const leaseId = await saveDraft(page, wizard);

    // Record nothing. The lease and its installments exist; no cheque has been
    // handed over yet. This is the state a lease sits in between signing and
    // the renter delivering their cheques.
    await expect(scheduleRows(wizard).first()).toBeVisible({ timeout: 30_000 });
    record('lease', leaseId, `Scenario B — no cheques (unit ${fx.units[1].unitNumber})`);

    const { total, withCheque } = await chequeCountFor(page, leaseId);
    console.log(`  scenario B: ${withCheque}/${total} installments carry a cheque`);
    expect(total, 'a lease with no cheques must still generate installments').toBeGreaterThan(0);
    expect(withCheque, 'no installment should carry a cheque').toBe(0);
    await settleOnSchedule(page, wizard);
  } finally {
    await close();
  }
});

test('scenario C — some installments have cheques', async ({ browser }) => {
  const { page, close } = await signedInPage(browser, '03-some-cheques');
  try {
    const wizard = await openWizardToPlan(page, fx.units[2].unitNumber);
    await wizard.getByRole('button', { name: /^next$/i }).click();
    const leaseId = await saveDraft(page, wizard);

    const rows = scheduleRows(wizard);
    await expect(rows.first()).toBeVisible({ timeout: 30_000 });
    const n = await rows.count();

    // Two cheques received so far.
    const filled = Math.min(2, n);
    for (let i = 0; i < filled; i++) await fillChequeRow(rows.nth(i), `${100300 + i}`);

    // The remaining rows stay on CHEQUE with their details blank — "cheque
    // expected, not yet received". This is the actual business case, and it is
    // what the run proves: before the fix the editor refused to save while any
    // CHEQUE row was incomplete, so "some cheques" could only be expressed by
    // relabelling the outstanding rows as CASH, recording a payment method
    // that was not true.
    await saveSchedule(page, wizard);
    record('lease', leaseId, `Scenario C — some cheques (unit ${fx.units[2].unitNumber})`);

    const { total, withCheque, onChequeMethod } = await chequeCountFor(page, leaseId);
    console.log(`  scenario C: ${withCheque}/${total} installments carry a cheque `
      + `(filled ${filled} field(s)); ${onChequeMethod}/${total} rows still on method CHEQUE`);
    expect(total).toBeGreaterThan(0);

    // The point of the fix: the rows without a cheque yet are still CHEQUE
    // rows. Before it, saving required relabelling them CASH.
    expect(onChequeMethod, 'every row should still be on method CHEQUE').toBe(total);
    // The point of this scenario: a partially-chequed plan is accepted, and the
    // rows without cheques are still real installments rather than being dropped.
    expect(withCheque, 'some but not all installments carry a cheque').toBeGreaterThan(0);
    expect(withCheque, 'some but not all installments carry a cheque').toBeLessThan(total);
    await settleOnSchedule(page, wizard);
  } finally {
    await close();
  }
});

test('all three leases are visible together in the lease list', async ({ browser }) => {
  const { page, close } = await signedInPage(browser, '04-all-three-leases');
  try {
    await page.goto('/en/dashboard/leases');
    for (const u of fx.units) {
      await expect(page.getByText(u.unitNumber, { exact: false }).first()).toBeVisible();
    }
    await hold(page, 3500);
    console.log(`\n  manifest: ${manifest.created.length} records created — ${MANIFEST}`);
  } finally {
    await close();
  }
});
