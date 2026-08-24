/**
 * 01b — Shared navigation, localization, profile, password, and logout.
 *
 * Uses the disposable PROPERTY_MANAGER created by 01a, changes its password,
 * proves the old authenticated session can log out and the new credential can
 * log back in, then removes the assignment and deletes the user via TENANT_ADMIN.
 */
import { test, expect, type APIResponse } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

async function assertOk(response: APIResponse, operation: string): Promise<void> {
  if (!response.ok()) {
    const body = await response.text().catch(() => '');
    throw new Error(`${operation} failed (${response.status()}): ${body.slice(0, 400)}`);
  }
}

test('property manager switches language, updates profile/password, logs out, and signs in again', async ({
  browser,
}) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.managedUser?.id, '01a-feature-and-user-admin must run first').toBeTruthy();

  const browserCtx = await browser.newContext({ baseURL: ctx.baseURL });
  const page = await browserCtx.newPage();
  await page.goto('/en/auth/login');
  await page.locator('#login-email').fill(ctx.managedUser.email);
  await page.locator('#login-password').fill(ctx.managedUser.password);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/en\/dashboard/, { timeout: 15_000 });

  await page.getByRole('link', { name: 'AR', exact: true }).click();
  await page.waitForURL(/\/ar\/dashboard/);
  expect(await page.locator('html').getAttribute('dir')).toBe('rtl');
  await page.getByRole('link', { name: 'EN', exact: true }).click();
  await page.waitForURL(/\/en\/dashboard/);
  expect(await page.locator('html').getAttribute('dir')).toBe('ltr');

  await page.goto('/en/dashboard/profile');
  await expect(page.getByRole('heading', { name: /my profile/i })).toBeVisible();

  const profileName = `TEST-Managed PM Profile ${ctx.runSuffix}`;
  await page.locator('input[type="text"]').fill(profileName);
  await page.locator('input[type="tel"]').fill('+971500000010');
  await page.getByRole('button', { name: /save changes/i }).click();
  await expect(page.getByRole('button', { name: /^saved$/i })).toBeVisible();

  await page.getByRole('button', { name: /change password/i }).click();
  const passwordInputs = page.locator('input[type="password"]');
  await passwordInputs.nth(0).fill(ctx.managedUser.password);
  await passwordInputs.nth(1).fill(ctx.managedUser.newPassword);
  await passwordInputs.nth(2).fill(ctx.managedUser.newPassword);
  await page.getByRole('button', { name: /update password/i }).click();
  await expect(page.getByText(/password updated successfully/i)).toBeVisible();

  // Open the profile popover by its updated-session identity button. The
  // NextAuth session retains the pre-edit name, so match the stable role text.
  await page.locator('header button').filter({ hasText: /property manager/i }).click();
  await page.getByRole('button', { name: /logout/i }).click();
  await page.waitForURL(/\/auth\/login/, { timeout: 15_000 });

  await page.locator('#login-email').fill(ctx.managedUser.email);
  await page.locator('#login-password').fill(ctx.managedUser.newPassword);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/en\/dashboard/, { timeout: 15_000 });
  await browserCtx.close();

  const tenantAdminCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(tenantAdminCtx, ctx.tenant.id);
  const removeAssignment = await tenantAdminCtx.request.delete(
    `/api/proxy/admin/users/${ctx.managedUser.id}/properties/${ctx.property.id}`,
    { failOnStatusCode: false },
  );
  await assertOk(removeAssignment, 'managed user property removal');

  const deleteManaged = await tenantAdminCtx.request.delete(
    `/api/proxy/admin/users/${ctx.managedUser.id}`,
    { failOnStatusCode: false },
  );
  await assertOk(deleteManaged, 'managed user deletion');

  const usersAfterDelete = await tenantAdminCtx.request.get('/api/proxy/admin/users', {
    failOnStatusCode: false,
  });
  await assertOk(usersAfterDelete, 'tenant user listing after delete');
  expect(
    ((await usersAfterDelete.json()) as Array<{ id: string }>).some(
      (user) => user.id === ctx.managedUser.id,
    ),
  ).toBe(false);
  await tenantAdminCtx.request.dispose();
});
