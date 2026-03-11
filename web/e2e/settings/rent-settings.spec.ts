import { test, expect } from '../fixtures/auth.fixture';

test.describe('Rent Collection Settings', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }
    await page.goto('/en/dashboard/settings/rent-settings');
    await page.waitForLoadState('networkidle');
  });

  test('rent settings page loads', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;
    await expect(page.getByText(/rent|settings/i).first()).toBeVisible({ timeout: 10_000 });
  });

  test('configure grace period and penalty rate', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // Look for grace period input
    const graceInput = page.locator('input[name*="grace" i], input[placeholder*="grace" i]').first();
    if (await graceInput.isVisible({ timeout: 3000 })) {
      await graceInput.clear();
      await graceInput.fill('5');
    }

    // Look for penalty rate input
    const penaltyInput = page.locator('input[name*="penalty" i], input[placeholder*="penalty" i]').first();
    if (await penaltyInput.isVisible({ timeout: 2000 })) {
      await penaltyInput.clear();
      await penaltyInput.fill('2');
    }

    // Save settings
    const saveBtn = page.getByRole('button', { name: /save|update|apply/i });
    if (await saveBtn.isVisible({ timeout: 2000 })) {
      await saveBtn.click();
      await page.waitForTimeout(1000);
    }
  });
});
