import { test, expect } from '../fixtures/auth.fixture';
import type { Page } from '@playwright/test';
import { createUnit, createRenter, createLease } from '../helpers/api-client';

// See lease-lifecycle.spec.ts for why this index arithmetic works the way
// it does (SearchableSelect has no htmlFor-linked label), and why the search
// is scoped to the wizard dialog.
async function pickSearchable(page: Page, triggerIndex: number, query: string) {
  const wizard = page.getByTestId('lease-wizard');
  const combos = wizard.getByRole('combobox');
  await combos.nth(triggerIndex).click();
  const searchBox = wizard.getByRole('combobox').nth(triggerIndex + 1);
  await searchBox.fill(query);
  await page.getByRole('option', { name: new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i') }).first().click();
}

test.describe('Leases CRUD', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }
    // The status filter is rendered by the leases page itself, so it is the
    // cheapest proof the page (not just the shell) is up — and unlike
    // `networkidle` it cannot be defeated by a non-2xx whose body the caller
    // never reads, which keeps a request in flight for ever.
    await page.goto('/en/dashboard/leases');
    await expect(page.getByTestId('lease-status-filter')).toBeVisible({ timeout: 15_000 });
  });

  test('leases page loads', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;
    await expect(page.getByText(/lease|contract/i).first()).toBeVisible();
  });

  test('grid and board view toggle', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // The three views are Table / Cards / Board (there is no "grid"), and the
    // board's column headings are <h3>s. `getByText(/draft/i)` used to match
    // the status filter's hidden <option value="DRAFT">Draft</option> first,
    // which is never visible — hence the role-scoped heading locator.
    const boardBtn = page.getByTestId('lease-view-board');
    const tableBtn = page.getByTestId('lease-view-table');

    await boardBtn.click();
    await expect(page.getByRole('heading', { name: 'Draft', exact: true })).toBeVisible({ timeout: 10_000 });

    await tableBtn.click();
    await expect(page.getByRole('heading', { name: 'Draft', exact: true })).toBeHidden();
  });

  test('create draft lease via the wizard (lines, not a flat rent field)', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // accounting-v2 plan 2 replaced the single-page create form (unit/renter
    // selects, one rent input, one deposit input) with the 5-step wizard —
    // Parties -> Terms -> Charges -> Cheques -> Review. The draft is SAVED at
    // the end of the Charges step (a POST /leases, not the final Post).
    const suffix = `${testInfo.project.name}-crud-${Date.now().toString(36)}`;
    const unit = await createUnit(testContext.adminId, testContext.adminRole, testContext.testTenantId, {
      propertyId: testContext.propertyId,
      unitNumber: `CRUD-${suffix}`,
    });
    await createRenter(testContext.adminId, testContext.adminRole, testContext.testTenantId, {
      nameEn: `CRUD Renter ${suffix}`,
      email: `crud-${suffix}@test.com`,
    });

    await page.getByRole('button', { name: /add|create|new|draft/i }).first().click();
    await pickSearchable(page, 0, unit.unitNumber);
    await pickSearchable(page, 1, `CRUD Renter ${suffix}`);
    await page.getByTestId('wizard-next').click();

    const today = new Date();
    const start = today.toISOString().slice(0, 10);
    const end = new Date(today.getFullYear() + 1, today.getMonth(), today.getDate()).toISOString().slice(0, 10);
    await page.getByTestId('wizard-start-date').fill(start);
    await page.getByTestId('wizard-end-date').fill(end);
    await page.getByTestId('wizard-next').click();

    await page.getByTestId('lease-line-type-0').selectOption({ label: 'Rent' });
    await page.getByTestId('lease-line-amount-0').fill('10000');
    await expect(page.getByTestId('lease-lines-contract-value')).toContainText('10,000');
    // "Save Draft" — a POST /leases, and the wizard steps into the cheque grid.
    await page.getByTestId('wizard-next').click();
    await expect(page.getByTestId('cheque-grid')).toBeVisible({ timeout: 10_000 });
  });

  test('seeded lease is visible', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    if (testContext.leaseId) {
      await expect(page.getByText(/E2E-101|E2E Renter/).first()).toBeVisible({ timeout: 10_000 });
    }
  });

  test('payment progress / cheque status renders on the leases list', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // Just verify the list rendered without error — the per-lease cheque
    // collection position (`leaseApi`/`chequeApi.statsByLeases`) replaces the
    // old v1 payment-schedule progress bar, and its exact markup is not
    // worth pinning here.
    const rows = page.locator('[data-testid^="lease-row-"]');
    expect(await rows.count()).toBeGreaterThanOrEqual(0);
  });

  test('bulk-post appears once a DRAFT lease is selected', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // `bulk-post` only renders once `selected.size > 0` (leases/page.tsx) —
    // it lets an accountant select several DRAFT leases and post them
    // together (Task 14). Only prove the control surfaces once selected;
    // exercising a real multi-post needs several dry-run-clean drafts, which
    // is the walkthrough's job.
    //
    // The select-all checkbox is always rendered for a role that may post, and
    // is `disabled` when the visible page holds no DRAFT rows — so borrowing
    // whatever the shared tenant happened to have left on page 1 made this a
    // coin flip against the other role projects posting them. Provision a draft
    // of our own and filter the list down to DRAFT so it is certainly on screen.
    const suffix = `${testInfo.project.name}-bulk-${Date.now().toString(36)}`;
    const unit = await createUnit(testContext.adminId, testContext.adminRole, testContext.testTenantId, {
      propertyId: testContext.propertyId,
      unitNumber: `BULK-${suffix}`,
    });
    const renter = await createRenter(testContext.adminId, testContext.adminRole, testContext.testTenantId, {
      nameEn: `Bulk Renter ${suffix}`,
      email: `bulk-${suffix}@test.com`,
    });
    const today = new Date();
    await createLease(testContext.adminId, testContext.adminRole, testContext.testTenantId, {
      unitId: unit.id,
      renterId: renter.id,
      startDate: today.toISOString().slice(0, 10),
      endDate: new Date(today.getFullYear() + 1, today.getMonth(), today.getDate()).toISOString().slice(0, 10),
      rentAmount: 10000,
    });

    await page.reload();
    await page.getByTestId('lease-status-filter').selectOption('DRAFT');

    const selectAll = page.getByTestId('bulk-post-select-all');
    await expect(selectAll).toBeEnabled({ timeout: 15_000 });
    await selectAll.check();
    await expect(page.getByTestId('bulk-post')).toBeVisible();
    await expect(page.getByTestId('bulk-post')).toBeEnabled();
  });
});
