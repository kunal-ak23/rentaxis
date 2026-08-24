/**
 * 01c — Global search, help guidance, and SUPER_ADMIN dashboard follow-ups.
 *
 * Production execution requires #100, #101, and #102. This is a read-only UI
 * journey over the disposable tenant created by 01-provision.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');
const SUPERADMIN_STATE = path.join(__dirname, '..', '.auth', 'superadmin.json');

test('search opens the lease, help documents guards, and super admin follow-ups load', async ({
  browser,
}) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.id, '01-provision must run first').toBeTruthy();

  const tenantAdminBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const tenantAdminPage = await tenantAdminBrowser.newPage();
  await tenantAdminPage.goto('/en/auth/login');
  await tenantAdminPage.locator('#login-email').fill(ctx.adminEmail);
  await tenantAdminPage.locator('#login-password').fill(ctx.adminPassword);
  await tenantAdminPage.getByRole('button', { name: /sign in|log in/i }).click();
  await tenantAdminPage.waitForURL(/\/en\/dashboard/, { timeout: 15_000 });

  await tenantAdminPage.keyboard.press('Control+K');
  const searchDialog = tenantAdminPage.getByRole('dialog', { name: /global search/i });
  await expect(searchDialog).toBeVisible();
  await searchDialog.getByRole('textbox', { name: /search rentaxis/i }).fill(`TEST-${ctx.runSuffix}`);
  const leaseResult = searchDialog
    .getByRole('button')
    .filter({ hasText: `TEST-${ctx.runSuffix}` })
    .first();
  await expect(leaseResult).toBeVisible({ timeout: 15_000 });
  await leaseResult.click();
  await tenantAdminPage.waitForURL(new RegExp(`/en/dashboard/leases/${ctx.lease.id}`));

  await tenantAdminPage.goto('/en/dashboard/help');
  await expect(tenantAdminPage.getByRole('heading', { name: /help center/i })).toBeVisible();
  await tenantAdminPage.getByPlaceholder(/search help articles/i).fill('roles');
  const rolesArticle = tenantAdminPage.getByRole('link', { name: /roles and permissions/i });
  await expect(rolesArticle).toBeVisible();
  await rolesArticle.click();
  await expect(tenantAdminPage.getByRole('heading', { name: /roles and permissions/i })).toBeVisible();
  await expect(tenantAdminPage.getByText('Security Guard', { exact: true }).first()).toBeVisible();
  await tenantAdminBrowser.close();

  const superAdminBrowser = await browser.newContext({
    baseURL: ctx.baseURL,
    storageState: SUPERADMIN_STATE,
  });
  const target = new URL(ctx.baseURL);
  await superAdminBrowser.addCookies([
    {
      name: 'active_tenant_id',
      value: ctx.tenant.id,
      domain: target.hostname,
      path: '/',
      httpOnly: false,
      secure: target.protocol === 'https:',
      sameSite: 'Lax',
    },
  ]);
  const superAdminPage = await superAdminBrowser.newPage();
  await superAdminPage.goto('/en/dashboard');
  await expect(superAdminPage.getByRole('heading', { name: /follow-ups due/i })).toBeVisible();
  await expect(superAdminPage.getByText("Couldn't load follow-ups", { exact: true })).toHaveCount(0);
  await superAdminBrowser.close();
});
