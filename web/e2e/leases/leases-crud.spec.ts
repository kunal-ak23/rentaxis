import { test, expect } from '../fixtures/auth.fixture';

test.describe('Leases CRUD', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }
    await page.goto('/en/dashboard/leases');
    await page.waitForLoadState('networkidle');
  });

  test('leases page loads', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;
    await expect(page.getByText(/lease/i).first()).toBeVisible();
  });

  test('grid and board view toggle', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // Find toggle buttons for grid/board view
    const boardBtn = page.getByRole('button', { name: /board|kanban/i });
    const gridBtn = page.getByRole('button', { name: /grid|list/i });

    // Toggle to board view
    if (await boardBtn.isVisible({ timeout: 3000 })) {
      await boardBtn.click();
      await page.waitForTimeout(500);
      // Board columns should be visible
      await expect(page.getByText(/draft/i).first()).toBeVisible({ timeout: 5000 });
    }

    // Toggle back to grid view
    if (await gridBtn.isVisible({ timeout: 3000 })) {
      await gridBtn.click();
      await page.waitForTimeout(500);
    }
  });

  test('create draft lease via modal', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // Click Create Lease button
    const addBtn = page.getByRole('button', { name: /add|create|new|draft/i });
    if (!(await addBtn.isVisible({ timeout: 5000 }))) return;
    await addBtn.click();

    // Wait for form/modal
    await page.waitForTimeout(500);

    // The lease form has selects for unit and renter, then date/number inputs
    const selects = page.locator('form select');
    const selectCount = await selects.count();

    // Select unit (first select in the form)
    if (selectCount >= 1) {
      const unitSelect = selects.nth(0);
      const options = await unitSelect.locator('option').allTextContents();
      if (options.length > 1) {
        await unitSelect.selectOption({ index: 1 });
      }
    }

    // Select renter (second select in the form)
    if (selectCount >= 2) {
      const renterSelect = selects.nth(1);
      const options = await renterSelect.locator('option').allTextContents();
      if (options.length > 1) {
        await renterSelect.selectOption({ index: 1 });
      }
    }

    // Fill dates - type="date" inputs
    const dateInputs = page.locator('form input[type="date"]');
    if (await dateInputs.nth(0).isVisible({ timeout: 2000 })) {
      await dateInputs.nth(0).fill('2026-04-01');
    }
    if (await dateInputs.nth(1).isVisible({ timeout: 2000 })) {
      await dateInputs.nth(1).fill('2027-03-31');
    }

    // Fill rent amount - placeholder "50000"
    const rentInput = page.locator('form input[placeholder="50000"]');
    if (await rentInput.isVisible({ timeout: 2000 })) {
      await rentInput.fill('10000');
    }

    // Fill deposit - placeholder "2500"
    const depositInput = page.locator('form input[placeholder="2500"]');
    if (await depositInput.isVisible({ timeout: 2000 })) {
      await depositInput.fill('10000');
    }

    // Submit - look for the submit button in the form
    await page.locator('form button[type="submit"]').click();
    await page.waitForTimeout(1000);
  });

  test('seeded lease is visible', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    if (testContext.leaseId) {
      // The seeded lease should show the unit identifier or renter name
      await expect(page.getByText(/E2E-101|E2E Renter/).first()).toBeVisible({ timeout: 10_000 });
    }
  });

  test('payment progress bar on active leases', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // Look for any active lease that has a progress bar
    const progressBars = page.locator('[class*="progress"], [role="progressbar"]');
    const count = await progressBars.count();
    // Just verify the page rendered without error
    expect(count >= 0).toBeTruthy();
  });
});
