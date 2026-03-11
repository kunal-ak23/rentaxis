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

    const nameInput = page.locator('input[placeholder*="name" i], input[name*="name" i]').first();
    await nameInput.fill(`E2E Org ${Date.now()}`);

    await page.getByRole('button', { name: /create|save|submit/i }).click();

    await page.waitForTimeout(1000);
    await expect(page.getByText(/E2E Org/)).toBeVisible({ timeout: 10_000 });
  });
});
