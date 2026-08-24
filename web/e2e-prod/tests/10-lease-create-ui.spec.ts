/**
 * 10 — Real browser: TENANT_ADMIN creates a lease via the LeaseWizard UI.
 *
 * Drives the actual 5-step wizard in
 * web/src/app/[locale]/dashboard/leases/LeaseWizard.tsx:
 *
 *   1. parties  — pick unit + renter
 *   2. terms    — startDate, endDate, rentAmount, depositAmount
 *   3. charges  — accept defaults
 *   4. plan     — accept defaults (paymentTerms=4, CHEQUE)
 *   5. finalize — click "Save draft", confirm lease created
 *
 * This catches integration bugs the API spec misses: wizard step
 * navigation, dropdown population (the unit picker filters to VACANT-only),
 * date input formatting, and the post-save state where the button morphs
 * to "Open lease detail".
 *
 * Provisions its own fresh property + unit + renter via API so the unit
 * is genuinely VACANT (01-provision's unit is occupied by an active lease).
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('TENANT_ADMIN creates a lease via the wizard UI', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.adminEmail).toBeTruthy();

  // 1. Seed a fresh property/unit/renter via API so the wizard's
  //    VACANT-only unit dropdown has something to pick.
  const taApi = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(taApi, ctx.tenant.id);

  const uiSuffix = `${ctx.runSuffix}-leaseui`;
  const property = await api.createProperty(taApi, { nameEn: `TEST-LeaseUI Property ${uiSuffix}` });
  const unit = await api.createUnit(taApi, {
    propertyId: property.id,
    unitNumber: `LU-${uiSuffix}`,
    expectedRent: 60000,
  });
  const renter = await api.createRenter(taApi, {
    nameEn: `TEST-LeaseUI Renter ${uiSuffix}`,
    email: `test-leaseui-renter-${uiSuffix}@e2e.rentaxis.test`,
  });
  expect(renter.portalPassword, 'portal account password must be issued for contract signing').toBeTruthy();
  await taApi.request.dispose();

  // 2. Real browser: login as TA, navigate to leases, open the wizard.
  const browserCtx = await browser.newContext({ baseURL: ctx.baseURL });
  const page = await browserCtx.newPage();

  await page.goto('/en/auth/login');
  await page.locator('#login-email').fill(ctx.adminEmail);
  await page.locator('#login-password').fill(ctx.adminPassword);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });

  await page.goto('/en/dashboard/leases');
  // Trigger label is "Draft Lease" (en.json: "draftLease": "Draft Lease").
  await page.getByRole('button', { name: /draft lease/i }).first().click();

  // Wizard dialog. Step 1: parties.
  // The unit picker filters to VACANT — pick our freshly-created unit by its number.
  // Renter picker shows all renters in the tenant.
  // Wizard dialog is the only `div.fixed` containing the step indicator —
  // match across all 5 steps with a generic regex so the locator remains
  // valid as we navigate forward.
  const wizardDialog = page.locator('div.fixed').filter({ hasText: /Step \d of 5/ });
  await expect(wizardDialog).toBeVisible();

  // SearchableSelect renders ARIA combobox + option controls rather than a
  // native <select>. Pick by visible entity text so this follows the same
  // interaction path a user takes and remains independent of UUID markup.
  await wizardDialog.getByRole('combobox').nth(0).click();
  await wizardDialog.getByRole('option').filter({ hasText: unit.unitNumber }).click();
  await wizardDialog.getByRole('combobox').nth(1).click();
  await wizardDialog.getByRole('option').filter({ hasText: renter.nameEn }).click();

  await wizardDialog.getByRole('button', { name: /^next$/i }).click();

  // Step 2: terms — fill start/end dates + amounts.
  const today = new Date();
  const startDate = today.toISOString().slice(0, 10);
  const endDate = new Date(today.getFullYear() + 1, today.getMonth(), today.getDate())
    .toISOString()
    .slice(0, 10);
  // Scope everything to the dialog so we don't pick up unrelated date inputs
  // elsewhere on the page (e.g., a Search/Filter date in the leases table).
  await wizardDialog.locator('input[type="date"]').nth(0).fill(startDate);
  await wizardDialog.locator('input[type="date"]').nth(1).fill(endDate);
  // Rent + deposit. First two number inputs on this step.
  await wizardDialog.locator('input[type="number"]').nth(0).fill('60000');
  // The payment scheduler caps the largest cheque at the deposit amount.
  // A 13-month inclusive lease at AED 60,000/month split quarterly needs a
  // deposit of at least AED 195,000 for the default LAST_LARGER strategy.
  await wizardDialog.locator('input[type="number"]').nth(1).fill('195000');

  await wizardDialog.getByRole('button', { name: /^next$/i }).click();

  // Step 3 (charges): accept defaults, advance.
  await wizardDialog.getByRole('button', { name: /^next$/i }).click();

  // Step 4 (plan): accept defaults, advance.
  await wizardDialog.getByRole('button', { name: /^next$/i }).click();

  // Step 5 (finalize): Save draft.
  const [createLeaseResponse] = await Promise.all([
    // Match path-with-or-without query string — endsWith('/leases') would
    // break the moment the wizard adds e.g. ?action=draft to the request.
    page.waitForResponse(
      (r) =>
        /\/api\/proxy\/v1\/leases(\?|$)/.test(r.url()) &&
        r.request().method() === 'POST',
      { timeout: 20_000 },
    ),
    wizardDialog.getByRole('button', { name: /save draft/i }).click(),
  ]);
  expect(createLeaseResponse.ok()).toBeTruthy();
  const createdLease = await createLeaseResponse.json();
  expect(createdLease.id).toBeTruthy();

  // Persist the UI-created draft for the next serial spec, which validates
  // the complete contract generation -> reject -> regenerate -> accept flow.
  ctx.contractLease = {
    id: createdLease.id,
    renterEmail: renter.email,
    renterPassword: renter.portalPassword,
  };
  fs.writeFileSync(CONTEXT_FILE, JSON.stringify(ctx, null, 2));

  // After save, the wizard reveals "Open lease detail" and "Generate contract"
  // buttons — that's our success signal.
  await expect(wizardDialog.getByRole('button', { name: /open lease detail/i })).toBeVisible({
    timeout: 10_000,
  });

  await browserCtx.close();
});
