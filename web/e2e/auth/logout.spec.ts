import { test, expect } from '../fixtures/auth.fixture';

test.describe('Logout', () => {
  // This test runs under the super-admin project (has storageState)
  test('clicking Logout redirects to login page', async ({ page }) => {
    await page.goto('/en/dashboard/properties');
    await page.waitForLoadState('networkidle');

    // Click the Logout button in the sidebar
    await page.getByRole('button', { name: /logout/i }).click();

    // Should redirect to login page
    await page.waitForURL(/\/auth\/login/, { timeout: 15_000 });
    expect(page.url()).toContain('/auth/login');
  });
});
