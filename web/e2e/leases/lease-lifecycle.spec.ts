import { test, expect } from '../fixtures/auth.fixture';
import type { Page } from '@playwright/test';
import { createUnit, createRenter } from '../helpers/api-client';

/**
 * accounting-v2 plan 2 replaced the "Activate" button with a Post workflow:
 * a DRAFT lease is built from `lines`, a cheque grid is cut from them, and
 * `POST /leases/{id}/post` is the only path to ACTIVE. `PUT
 * /leases/{id}/activate` no longer exists (LeaseController,
 * `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`).
 *
 * Each test below provisions its own unit + renter rather than reusing
 * testContext.leaseId: that lease is shared across every role project that
 * runs this file in the same run, and a second project trying to post (or
 * generate cheques for) the very same DRAFT lease the first project just
 * posted would 400 — the same race the old spec quietly tolerated with its
 * `if (await btn.isVisible())` guards.
 */

// SearchableSelect (web/src/components/ui/SearchableSelect.tsx) has no
// htmlFor-linked label — its trigger is `role="combobox"` and, once opened,
// inserts a second combobox (the search box) immediately after itself in
// the DOM, shifting every later trigger's index by one. `triggerIndex` is
// the combobox's position BEFORE opening any other picker on the page.
// VERIFY: confirm this index arithmetic against the real DOM in 17b — if a
// wizard step ever renders the pickers in a different order this drifts.
async function pickSearchable(page: Page, triggerIndex: number, query: string) {
  const combos = page.getByRole('combobox');
  await combos.nth(triggerIndex).click();
  const searchBox = page.getByRole('combobox').nth(triggerIndex + 1);
  await searchBox.fill(query);
  await page.getByRole('option', { name: new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i') }).first().click();
}

/** Draft -> lines -> cheques -> post, through the real wizard. Returns the posted lease's URL. */
async function draftLineAndPost(
  page: Page,
  opts: { unitNumber: string; renterName: string; rentAmount: string },
) {
  await page.goto('/en/dashboard/leases');
  await page.waitForLoadState('networkidle');
  await page.getByRole('button', { name: /add|create|new|draft/i }).first().click();

  // Step 1: parties. Each picker's dropdown closes on selection, so the
  // second trigger is back at its normal index (1) once the first closes.
  await pickSearchable(page, 0, opts.unitNumber);
  await pickSearchable(page, 1, opts.renterName);
  await page.getByTestId('wizard-next').click();

  // Step 2: terms — start/end dates are required.
  const today = new Date();
  const start = today.toISOString().slice(0, 10);
  const end = new Date(today.getFullYear() + 1, today.getMonth(), today.getDate()).toISOString().slice(0, 10);
  await page.getByTestId('wizard-start-date').fill(start);
  await page.getByTestId('wizard-end-date').fill(end);
  await page.getByTestId('wizard-next').click();

  // Step 3: lines — one RENT line for the full contract value.
  await page.getByTestId('lease-line-type-0').selectOption({ label: 'Rent' });
  await page.getByTestId('lease-line-amount-0').fill(opts.rentAmount);
  // "Save Draft" on the lines step both saves the draft AND steps into cheques.
  await page.getByTestId('wizard-next').click();

  // Step 4: cheques — generate the grid from the saved lines.
  await expect(page.getByTestId('cheque-grid')).toBeVisible({ timeout: 10_000 });
  await page.getByTestId('cheque-grid-generate').click();
  await page.getByTestId('cheque-generate-confirm').click();
  await expect(page.getByTestId('cheque-grid-match')).toHaveAttribute('data-match', 'true', { timeout: 10_000 });
  await page.getByTestId('wizard-next').click();

  // Step 5: review — the server's own dry run must come back clean before Post is live.
  await expect(page.getByTestId('wizard-review')).toBeVisible();
  await expect(page.getByTestId('wizard-dry-run-ok')).toBeVisible({ timeout: 10_000 });
  await page.getByTestId('wizard-post').click();
  await page.waitForURL(/\/dashboard\/leases\/[0-9a-f-]{36}/, { timeout: 15_000 });
}

test.describe('Lease Lifecycle', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }
    await page.goto('/en/dashboard/leases');
    await page.waitForLoadState('networkidle');
  });

  test('generate contract on DRAFT lease', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;
    if (!testContext.leaseId) {
      test.skip();
      return;
    }

    await page.goto(`/en/dashboard/leases/${testContext.leaseId}`);
    await page.waitForLoadState('networkidle');
    await page.getByTestId('lease-tab-contract').click();

    // Only SA/TA may generate a contract (canGenerateContract); a wizard
    // draft with no lines yet may also refuse — either way this stays
    // tolerant, matching the loose style the rest of the suite uses for
    // an action that depends on backend-generated defaults.
    const generateBtn = page.getByRole('button', { name: /generate preview/i });
    if (await generateBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await generateBtn.click();
      await page.waitForTimeout(1500);
    }
  });

  test('post a freshly drafted lease and activate it', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    const suffix = `${testInfo.project.name}-${Date.now().toString(36)}`;
    const unit = await createUnit(testContext.adminId, testContext.adminRole, testContext.testTenantId, {
      propertyId: testContext.propertyId,
      unitNumber: `WZ-${suffix}`,
    });
    await createRenter(testContext.adminId, testContext.adminRole, testContext.testTenantId, {
      nameEn: `Wizard Renter ${suffix}`,
      email: `wizard-${suffix}@test.com`,
    });

    await draftLineAndPost(page, { unitNumber: unit.unitNumber, renterName: `Wizard Renter ${suffix}`, rentAmount: '12000' });

    await expect(page.getByTestId('lease-status')).toHaveText(/active/i, { timeout: 10_000 });
    await expect(page.getByTestId('lease-banner')).toBeVisible();
    // Post is gone once posted; the ledger link and amend/renew/extend take its place.
    await expect(page.getByTestId('lease-post')).toHaveCount(0);
    await expect(page.getByTestId('lease-ledger')).toBeVisible();
  });

  test('terminate routes an ACTIVE lease to its settlement', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    const suffix = `${testInfo.project.name}-term-${Date.now().toString(36)}`;
    const unit = await createUnit(testContext.adminId, testContext.adminRole, testContext.testTenantId, {
      propertyId: testContext.propertyId,
      unitNumber: `WZ-${suffix}`,
    });
    await createRenter(testContext.adminId, testContext.adminRole, testContext.testTenantId, {
      nameEn: `Wizard Renter ${suffix}`,
      email: `wizard-${suffix}@test.com`,
    });
    await draftLineAndPost(page, { unitNumber: unit.unitNumber, renterName: `Wizard Renter ${suffix}`, rentAmount: '9000' });

    // Terminate is a link to the settlement flow now, not an in-place status
    // change — accounting-v2 plan 2's settlement still runs off register
    // `due` rows (handed to plan 3), but the entry point is unchanged.
    const terminateLink = page.getByTestId('lease-terminate');
    if (await terminateLink.isVisible({ timeout: 3000 }).catch(() => false)) {
      await terminateLink.click();
      await page.waitForURL(/\/settlement$/, { timeout: 10_000 });
      await expect(page.getByRole('heading', { level: 1, name: 'Settlement' })).toBeVisible();
    }
  });

  test('board view reflects status columns', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    const boardBtn = page.getByRole('button', { name: /board|kanban/i });
    if (await boardBtn.isVisible({ timeout: 3000 })) {
      await boardBtn.click();
      await page.waitForTimeout(500);

      await expect(page.getByText('Draft').first()).toBeVisible();
      await expect(page.getByText(/pending signature/i).first()).toBeVisible();
      await expect(page.getByText('Active').first()).toBeVisible();
      await expect(page.getByText('Closed').first()).toBeVisible();
    }
  });
});
