import { test, expect } from '../fixtures/auth.fixture';

test.describe('Tenants Management', () => {
  // Only actual SUPER_ADMIN can manage tenants
  test.beforeEach(async ({ page, testContext }, testInfo) => {
    if (testInfo.project.name !== 'super-admin' || testContext.adminRole !== 'SUPER_ADMIN') {
      test.skip();
      return;
    }
    await page.goto('/en/superadmin/tenants');
    await page.waitForLoadState('networkidle');
  });

  test('page loads with tenant table', async ({ page, testContext }, testInfo) => {
    if (testInfo.project.name !== 'super-admin' || testContext.adminRole !== 'SUPER_ADMIN') return;
    await expect(page.getByText(/tenant|organization/i).first()).toBeVisible();
    const rows = page.locator('table tbody tr, [class*="card"], [class*="row"]');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });
  });

  test('create new organization', async ({ page, testContext }, testInfo) => {
    if (testInfo.project.name !== 'super-admin' || testContext.adminRole !== 'SUPER_ADMIN') return;

    const addBtn = page.getByRole('button', { name: /add|create|new/i });
    await addBtn.click();

    const orgName = `E2E Org ${Date.now()}`;
    await page.getByTestId('org-name').fill(orgName);

    await page.getByRole('button', { name: /create organization/i }).click();

    // The list is paginated and sorted by creation time, so the new row may
    // sit on a later page: find it through the search box.
    await expect(page.getByTestId('org-name')).toBeHidden({ timeout: 15_000 });
    await page.locator('input[placeholder="Search..."]').fill(orgName);
    await expect(page.getByText(orgName)).toBeVisible({ timeout: 10_000 });
  });
});
