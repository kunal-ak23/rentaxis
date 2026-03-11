import { test, expect } from '../fixtures/auth.fixture';

test.describe('Financial Reports', () => {
  test('page loads and shows report content', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }

    await page.goto('/en/dashboard/finance/reports');
    await page.waitForLoadState('networkidle');

    await expect(page.getByText(/report/i).first()).toBeVisible({ timeout: 10_000 });
  });
});
