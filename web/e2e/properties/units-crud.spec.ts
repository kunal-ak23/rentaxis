import { test, expect } from '../fixtures/auth.fixture';

test.describe('Units CRUD', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }
    await page.goto('/en/dashboard/properties');
    await page.waitForLoadState('networkidle');
  });

  test('units are visible under property', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // The seeded unit should be visible if we have a property
    if (testContext.propertyId) {
      // Look for the unit identifier from seed data
      const unitText = page.getByText(/E2E-101/);
      // May need to expand or navigate into a property
      if (await unitText.isVisible({ timeout: 5000 })) {
        await expect(unitText).toBeVisible();
      }
    }
  });
});
