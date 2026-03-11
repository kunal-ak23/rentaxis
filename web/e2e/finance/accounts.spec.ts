import { test, expect } from '../fixtures/auth.fixture';

test.describe('Chart of Accounts', () => {
  test('page loads and shows account content', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }

    await page.goto('/en/dashboard/finance/accounts');
    await page.waitForLoadState('networkidle');

    await expect(page.getByText(/account/i).first()).toBeVisible({ timeout: 10_000 });
  });
});
