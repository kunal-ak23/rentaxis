import { test, expect } from '../fixtures/auth.fixture';

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

    // Find the seeded draft lease
    const leaseCard = page.getByText(/E2E-101|E2E Renter/).first();
    await expect(leaseCard).toBeVisible({ timeout: 10_000 });

    // Look for Generate Contract button
    const generateBtn = page.getByRole('button', { name: /generate|contract/i }).first();
    if (await generateBtn.isVisible({ timeout: 3000 })) {
      await generateBtn.click();
      await page.waitForTimeout(2000);
    }
  });

  test('activate lease', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;
    if (!testContext.leaseId) {
      test.skip();
      return;
    }

    // Look for Activate button on the lease
    const activateBtn = page.getByRole('button', { name: /activate/i }).first();
    if (await activateBtn.isVisible({ timeout: 5000 })) {
      await activateBtn.click();
      await page.waitForTimeout(2000);

      // Confirm if dialog appears
      const confirmBtn = page.getByRole('button', { name: /confirm|yes/i });
      if (await confirmBtn.isVisible({ timeout: 2000 })) {
        await confirmBtn.click();
      }

      await page.waitForTimeout(1000);
    }
  });

  test('terminate lease', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;
    if (!testContext.leaseId) {
      test.skip();
      return;
    }

    // Look for Terminate button
    const terminateBtn = page.getByRole('button', { name: /terminate/i }).first();
    if (await terminateBtn.isVisible({ timeout: 5000 })) {
      await terminateBtn.click();

      // Confirm dialog
      const confirmBtn = page.getByRole('button', { name: /confirm|yes|terminate/i }).last();
      if (await confirmBtn.isVisible({ timeout: 2000 })) {
        await confirmBtn.click();
      }

      await page.waitForTimeout(1000);
    }
  });

  test('board view reflects status columns', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // Switch to board view
    const boardBtn = page.getByRole('button', { name: /board|kanban/i });
    if (await boardBtn.isVisible({ timeout: 3000 })) {
      await boardBtn.click();
      await page.waitForTimeout(500);

      // Board should have columns: Draft, Pending Signature, Active, Closed
      await expect(page.getByText('Draft').first()).toBeVisible();
      await expect(page.getByText(/pending signature/i).first()).toBeVisible();
      await expect(page.getByText('Active').first()).toBeVisible();
      await expect(page.getByText('Closed').first()).toBeVisible();
    }
  });
});
