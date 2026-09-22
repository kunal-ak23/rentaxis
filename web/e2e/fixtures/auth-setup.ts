import { test as setup } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const CONTEXT_PATH = path.join(__dirname, '..', '.test-context.json');
const AUTH_DIR = path.join(__dirname, '..', '.auth');

type RoleKey = 'superAdmin' | 'tenantAdmin' | 'propertyManager' | 'tenantUser' | 'renter';

const ROLE_FILE_MAP: Record<RoleKey, string> = {
  superAdmin: 'super-admin.json',
  tenantAdmin: 'tenant-admin.json',
  propertyManager: 'property-manager.json',
  tenantUser: 'tenant-user.json',
  renter: 'renter.json',
};

function getTestContext() {
  const raw = fs.readFileSync(CONTEXT_PATH, 'utf-8');
  return JSON.parse(raw);
}

for (const [roleKey, fileName] of Object.entries(ROLE_FILE_MAP)) {
  setup(`authenticate as ${roleKey}`, async ({ page }) => {
    const ctx = getTestContext();
    const creds = ctx.users[roleKey as RoleKey];
    if (!creds) {
      console.log(`No credentials for ${roleKey}, skipping`);
      return;
    }

    // Navigate to login page
    await page.goto('/en/auth/login');
    await page.waitForLoadState('networkidle');

    // Fill in credentials
    await page.locator('input[type="email"]').fill(creds.email);
    await page.locator('input[type="password"]').fill(creds.password);

    // Submit form
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();

    // Wait for redirect away from login page
    await page.waitForURL(/\/dashboard/, { timeout: 15_000 });

    // A super-admin works inside a selected organisation (the proxy forwards
    // the `active_tenant_id` cookie as X-Tenant-Id); without one, every
    // tenant-scoped API call is refused. Select the test tenant, as a person
    // would with the org switcher.
    if (roleKey === 'superAdmin' && ctx.testTenantId) {
      const origin = new URL(page.url());
      await page.context().addCookies([{
        name: 'active_tenant_id',
        value: String(ctx.testTenantId),
        domain: origin.hostname,
        path: '/',
      }]);
    }

    // Save storage state
    if (!fs.existsSync(AUTH_DIR)) {
      fs.mkdirSync(AUTH_DIR, { recursive: true });
    }
    await page.context().storageState({ path: path.join(AUTH_DIR, fileName) });
    console.log(`Saved auth state for ${roleKey} → ${fileName}`);
  });
}
