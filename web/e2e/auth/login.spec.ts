import { test, expect } from '../fixtures/auth.fixture';

test.describe('Login Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/en/auth/login');
  });

  test('renders login form correctly', async ({ page }) => {
    await expect(page.getByRole('heading', { name: /welcome back/i })).toBeVisible();
    await expect(page.locator('input[type="email"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sign In', exact: true })).toBeVisible();
    await expect(page.getByText(/create account/i)).toBeVisible();
  });

  test('admin login redirects to /dashboard', async ({ page, testContext }) => {
    const admin = testContext.users.superAdmin;
    await page.locator('input[type="email"]').fill(admin.email);
    await page.locator('input[type="password"]').fill(admin.password);
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();

    // Admin redirects to dashboard (properties or home)
    await page.waitForURL(/\/dashboard/, { timeout: 15_000 });
    expect(page.url()).toContain('/dashboard');
  });

  test('RENTER login redirects to /dashboard/renter-portal', async ({ page, testContext }) => {
    const renter = testContext.users.renter;
    await page.locator('input[type="email"]').fill(renter.email);
    await page.locator('input[type="password"]').fill(renter.password);
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();

    await page.waitForURL(/\/dashboard\/renter-portal/, { timeout: 15_000 });
    expect(page.url()).toContain('/dashboard/renter-portal');
  });

  test('invalid credentials show error message', async ({ page }) => {
    await page.locator('input[type="email"]').fill('wrong@test.com');
    await page.locator('input[type="password"]').fill('wrong');
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();

    await expect(page.getByText(/invalid email or password/i)).toBeVisible({ timeout: 10_000 });
  });

  test('empty form triggers HTML5 validation', async ({ page }) => {
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
    // The email input should prevent submission via HTML5 required attribute
    const emailInput = page.locator('input[type="email"]');
    await expect(emailInput).toHaveAttribute('required', '');
  });
});
