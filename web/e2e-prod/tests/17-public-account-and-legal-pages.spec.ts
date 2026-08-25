/**
 * 17 — Anonymous account-entry and legal pages.
 *
 * This journey intentionally remains read-only: it proves the public routes,
 * form contract, accessibility labels, and legal cross-links without creating
 * an organisation or changing production state.
 */
import { test, expect } from '@playwright/test';

test('registration entry point is public and exposes its required controls', async ({
  browser,
  baseURL,
}) => {
  expect(baseURL, 'Playwright must provide the production base URL').toBeTruthy();
  const anonymous = await browser.newContext({ baseURL });
  const page = await anonymous.newPage();

  await page.goto('/en/auth/register');
  await expect(page.getByRole('heading', { name: 'Create Account' })).toBeVisible();
  await expect(page.getByLabel('Full Name')).toBeVisible();
  await expect(page.getByLabel('Company Name')).toBeVisible();
  await expect(page.getByLabel('Email Address')).toBeVisible();

  const password = page.getByLabel('Password', { exact: true });
  await expect(password).toHaveAttribute('type', 'password');
  await page.getByRole('button', { name: 'Show password' }).click();
  await expect(password).toHaveAttribute('type', 'text');
  await expect(page.getByRole('checkbox')).toBeVisible();
  await expect(page.getByRole('button', { name: /create organization/i })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Sign In' })).toHaveAttribute(
    'href',
    '/en/auth/login',
  );

  await page.getByRole('link', { name: 'Terms of Service' }).click();
  await expect(page).toHaveURL(/\/en\/terms$/);
  await expect(page.getByRole('heading', { name: 'Terms of Use' })).toBeVisible();

  await anonymous.close();
});

test('privacy, terms, and data-deletion pages are anonymously cross-linked', async ({
  browser,
  baseURL,
}) => {
  expect(baseURL, 'Playwright must provide the production base URL').toBeTruthy();
  const anonymous = await browser.newContext({ baseURL });
  const page = await anonymous.newPage();

  await page.goto('/en/privacy');
  await expect(page.getByRole('heading', { name: 'Privacy Policy' })).toBeVisible();
  await expect(page.getByText('Effective 7 August 2026')).toBeVisible();
  await page.getByRole('link', { name: /data-deletion page/i }).click();
  await expect(page).toHaveURL(/\/en\/data-deletion$/);
  await expect(
    page.getByRole('heading', { name: 'Account and data deletion' }),
  ).toBeVisible();
  await expect(page.getByText(/complete eligible requests within 30 days/i)).toBeVisible();

  await page.getByRole('link', { name: 'Miftah Privacy Policy' }).click();
  await expect(page).toHaveURL(/\/en\/privacy$/);
  await page.goto('/en/terms');
  await expect(page.getByRole('heading', { name: 'Terms of Use' })).toBeVisible();
  await page.getByRole('link', { name: 'Privacy Policy' }).click();
  await expect(page).toHaveURL(/\/en\/privacy$/);

  await anonymous.close();
});
