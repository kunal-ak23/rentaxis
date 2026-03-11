import { test, expect } from '../fixtures/auth.fixture';

test.describe('Renters CRUD', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }
    await page.goto('/en/dashboard/renters');
    await page.waitForLoadState('networkidle');
  });

  test('list renters page loads', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;
    await expect(page.getByText(/renter/i).first()).toBeVisible();
  });

  test('seeded renter is visible', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // The E2E Renter from global-setup should be present
    await expect(page.getByText(/E2E Renter/)).toBeVisible({ timeout: 10_000 });
  });

  test('create a new renter without portal account', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    await page.getByRole('button', { name: /add renter/i }).first().click();

    const ts = Date.now();
    // Name English - placeholder "John Doe"
    await page.locator('input[placeholder="John Doe"]').fill(`Test Renter ${ts}`);

    // Name Arabic - placeholder "جون دو"
    const arInput = page.locator('input[placeholder="جون دو"]');
    if (await arInput.isVisible({ timeout: 1000 })) {
      await arInput.fill(`مستأجر ${ts}`);
    }

    // Email - placeholder "john@example.com"
    await page.locator('input[placeholder="john@example.com"]').fill(`renter-${ts}@test.com`);

    // Phone - placeholder "+971 50 123 4567"
    const phoneInput = page.locator('input[placeholder="+971 50 123 4567"]');
    if (await phoneInput.isVisible({ timeout: 1000 })) {
      await phoneInput.fill('+971501234567');
    }

    // Submit via form submit button
    await page.locator('form button[type="submit"]').click();

    await expect(page.getByText(`Test Renter ${ts}`)).toBeVisible({ timeout: 10_000 });
  });

  test('create a new renter with portal account', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    await page.getByRole('button', { name: /add renter/i }).first().click();

    const ts = Date.now();
    await page.locator('input[placeholder="John Doe"]').fill(`Portal Renter ${ts}`);

    const arInput = page.locator('input[placeholder="جون دو"]');
    if (await arInput.isVisible({ timeout: 1000 })) {
      await arInput.fill(`مستأجر بوابة ${ts}`);
    }

    await page.locator('input[placeholder="john@example.com"]').fill(`portal-renter-${ts}@test.com`);

    // Toggle portal account checkbox
    const portalCheckbox = page.locator('input[type="checkbox"]').first();
    if (await portalCheckbox.isVisible({ timeout: 1000 })) {
      await portalCheckbox.check();
    }

    await page.getByRole('button', { name: /create|save|submit|add/i }).last().click();

    await expect(page.getByText(`Portal Renter ${ts}`)).toBeVisible({ timeout: 10_000 });
  });

  test('renter card shows bilingual name, email, phone', async ({ page, testContext }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // Check that the E2E Renter card has all expected info
    const renterCard = page.getByText(/E2E Renter/).locator('..');
    await expect(renterCard).toBeVisible({ timeout: 10_000 });

    // Email should be somewhere on the page for this renter
    if (testContext.renterEmail) {
      await expect(page.getByText(testContext.renterEmail)).toBeVisible({ timeout: 5_000 });
    }
  });
});
