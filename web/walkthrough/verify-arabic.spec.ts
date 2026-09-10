import { test, expect, type Page } from '@playwright/test';
import * as path from 'node:path';

import ar from '../messages/ar.json';

/**
 * Verifies the shell localization (#156) and the properties page (#157)
 * against DEPLOYED production, not a local render.
 *
 * Signs in as the disposable walkthrough tenant admin and switches to /ar.
 */
const BASE = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';

async function signIn(page: Page) {
  await page.goto(`${BASE}/ar/auth/login`);
  await page.locator('#login-email').fill(process.env.WT_ADMIN_EMAIL!);
  await page.locator('#login-password').fill(process.env.WT_ADMIN_PASSWORD!);
  await page.getByRole('button', { name: /sign in|log in|تسجيل/i }).click();
  await page.waitForURL(/\/dashboard/, { timeout: 45_000 });
}

test('Arabic dashboard shell and properties page are localized on production', async ({ page }) => {
  test.setTimeout(180_000);
  await signIn(page);

  // Suppress the first-login onboarding tour so it does not cover the sidebar.
  await page.evaluate(() => {
    try {
      window.localStorage.setItem('rentaxis_tours_completed', JSON.stringify(['admin-onboarding']));
    } catch { /* ignore */ }
  });
  await page.goto(`${BASE}/ar/dashboard`);
  await page.waitForLoadState('networkidle');

  const shell = page.locator('body');
  await expect(shell).toContainText(ar.Navigation.tickets);
  await expect(shell).toContainText(ar.Navigation.accountMappings);
  await expect(shell).toContainText(ar.TenantSwitcher.organization);
  // Section headers, not just the items under them.
  await expect(shell).toContainText(ar.Navigation.sectionWorkspace);
  await expect(shell).toContainText(ar.Navigation.sectionOperations);
  await expect(shell).toContainText(ar.Navigation.helpAndGuides);

  // The literals that used to be hardcoded must be gone from the Arabic page.
  const shellText = (await shell.innerText()).toLowerCase();
  for (const literal of ['tickets', 'account mappings', 'organization', 'workspace', 'operations', 'help & guides']) {
    expect(shellText, `"${literal}" should not appear on the Arabic dashboard`).not.toContain(literal);
  }
  await page.screenshot({ path: path.join(__dirname, 'takes', 'ar-dashboard.png'), fullPage: false });

  await page.goto(`${BASE}/ar/dashboard/properties`);
  await page.waitForLoadState('networkidle');
  const props = page.locator('body');
  await expect(props).toContainText(ar.MasterData.unitsCount);
  await expect(props).toContainText(ar.MasterData.occupancy);

  const propsText = (await props.innerText()).toLowerCase();
  for (const literal of ['occupancy', 'vacant', 'actions', 'search...']) {
    expect(propsText, `"${literal}" should not appear on the Arabic properties page`).not.toContain(literal);
  }
  await page.screenshot({ path: path.join(__dirname, 'takes', 'ar-properties.png'), fullPage: false });

  console.log('  Arabic shell + properties verified on production');
});
