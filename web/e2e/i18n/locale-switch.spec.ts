import { test, expect } from '../fixtures/auth.fixture';

test.describe('Locale Switching', () => {
  test('switch to Arabic and verify RTL + Arabic content', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }

    // Start on English dashboard
    await page.goto('/en/dashboard/properties');
    await page.waitForLoadState('networkidle');

    // Navigate to Arabic version
    await page.goto('/ar/dashboard/properties');
    await page.waitForLoadState('networkidle');

    // Verify RTL direction on the html or body element
    const dir = await page.locator('html').getAttribute('dir');
    expect(dir).toBe('rtl');

    // Verify Arabic content is present (at least some Arabic text)
    const bodyText = await page.locator('body').textContent();
    // Arabic characters range check
    const hasArabic = /[\u0600-\u06FF]/.test(bodyText || '');
    expect(hasArabic).toBeTruthy();
  });

  test('English pages have LTR direction', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }

    await page.goto('/en/dashboard/properties');
    await page.waitForLoadState('networkidle');

    const dir = await page.locator('html').getAttribute('dir');
    // dir should be 'ltr' or not set (defaults to ltr)
    expect(dir === 'ltr' || dir === null).toBeTruthy();
  });
});
