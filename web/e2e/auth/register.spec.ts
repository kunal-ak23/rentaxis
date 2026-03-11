import { test, expect } from '@playwright/test';

test.describe('Registration Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/en/auth/register');
  });

  test('renders registration form correctly', async ({ page }) => {
    await expect(page.getByRole('heading', { name: /create account/i })).toBeVisible();
    await expect(page.locator('input[type="email"]')).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
    // Full name and company name fields
    await expect(page.locator('input[placeholder*="John" i], input[placeholder*="name" i]').first()).toBeVisible();
    await expect(page.locator('input[placeholder*="company" i], input[placeholder*="Al Futtaim" i]').first()).toBeVisible();
  });

  test('has sign in link', async ({ page }) => {
    await expect(page.getByText(/already have an account/i)).toBeVisible();
    await expect(page.getByRole('link', { name: /sign in/i })).toBeVisible();
  });

  test('submit button is present', async ({ page }) => {
    await expect(page.getByRole('button', { name: /create organization/i })).toBeVisible();
  });

  test('form has required fields', async ({ page }) => {
    // Email, password, full name, company name should all be required
    await expect(page.locator('input[type="email"]')).toHaveAttribute('required', '');
    await expect(page.locator('input[type="password"]')).toHaveAttribute('required', '');
  });
});
