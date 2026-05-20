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

  // The wizard's Field component renders <label> without `htmlFor`, so
  // Playwright's getByLabel can't bind them to the select. Use positional
  // selectors scoped to the dialog body instead — step 1 has exactly two
  // <select> elements (Unit, Renter) in that order. selectOption(value)
  // matches the <option value=...> attribute, which is the entity UUID.
  await wizardDialog.locator('select').nth(0).selectOption(unit.id);
  await wizardDialog.locator('select').nth(1).selectOption(renter.id);

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
  await wizardDialog.locator('input[type="number"]').nth(1).fill('5000');

  await wizardDialog.getByRole('button', { name: /^next$/i }).click();

  // Step 3 (charges): accept defaults, advance.
  await wizardDialog.getByRole('button', { name: /^next$/i }).click();

  // Step 4 (plan): accept defaults, advance.
  await wizardDialog.getByRole('button', { name: /^next$/i }).click();

  // Step 5 (finalize): Save draft.
  await Promise.all([
    page.waitForResponse(
      (r) =>
        r.url().endsWith('/api/proxy/v1/leases') &&
        r.request().method() === 'POST',
      { timeout: 20_000 },
    ),
    wizardDialog.getByRole('button', { name: /save draft/i }).click(),
  ]);

  // After save, the wizard reveals "Open lease detail" and "Generate contract"
  // buttons — that's our success signal.
  await expect(wizardDialog.getByRole('button', { name: /open lease detail/i })).toBeVisible({
    timeout: 10_000,
  });

  await browserCtx.close();
});
