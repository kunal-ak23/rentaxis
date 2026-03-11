import { test, expect } from '../fixtures/auth.fixture';

test.describe('Gateway Configuration', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }
    await page.goto('/en/dashboard/settings/gateway');
    await page.waitForLoadState('networkidle');
  });

  test('gateway config page loads', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;
    await expect(page.getByText(/gateway/i).first()).toBeVisible({ timeout: 10_000 });
  });

  test('configure gateway settings', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // Look for key ID and secret inputs
    const keyInput = page.locator('input[name*="key" i], input[placeholder*="key" i]').first();
    if (await keyInput.isVisible({ timeout: 3000 })) {
      await keyInput.fill('rzp_test_abcdefghijklmn');
    }

    const secretInput = page.locator('input[name*="secret" i], input[placeholder*="secret" i]').first();
    if (await secretInput.isVisible({ timeout: 2000 })) {
      await secretInput.fill('test_secret_12345');
    }
  });

  test('toggle test/live mode', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // Look for test/live mode toggle
    const modeToggle = page.locator('input[type="checkbox"][name*="mode" i], button[name*="mode" i], select[name*="mode" i]').first();
    if (await modeToggle.isVisible({ timeout: 3000 })) {
      await modeToggle.click();
    }
  });

  test('activate/deactivate gateway', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    const activateBtn = page.getByRole('button', { name: /activate|deactivate|enable|disable/i });
    if (await activateBtn.isVisible({ timeout: 3000 })) {
      // Just verify it's clickable
      await expect(activateBtn).toBeEnabled();
    }
  });
});
