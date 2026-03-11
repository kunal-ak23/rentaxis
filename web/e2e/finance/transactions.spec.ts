import { test, expect } from '../fixtures/auth.fixture';

test.describe('Transactions', () => {
  test('page loads and shows transaction content', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }

    await page.goto('/en/dashboard/finance/transactions');
    await page.waitForLoadState('networkidle');

    await expect(page.getByText(/transaction/i).first()).toBeVisible({ timeout: 10_000 });
  });
});
