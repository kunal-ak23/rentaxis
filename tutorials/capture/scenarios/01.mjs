// Tutorial 01. Default role for scenes that do not name one: anonymous.
import { navTimeoutMs, seed } from '../lib/context.mjs';
import { signInWithCredentials } from '../lib/flows.mjs';
import { waitForApp } from '../lib/page.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

const scenes = [
  roleRouteScene('anonymous', '/en/auth/login', 'Sign in securely', 'Use only the account supplied by your administrator. On shared devices, do not save the password.', {
    weight: 48,
    verifyTenantContext: false,
  }),
  roleRouteScene('anonymous', '/en/auth/login', 'Authenticate', 'Enter the prepared account email and password, then select Sign In to open the role-appropriate landing page.', {
    weight: 42,
    verifyTenantContext: false,
    afterNavigation: async (page) => signInWithCredentials(page, seed.adminLogin),
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard', 'Role-aware navigation', 'Confirm the current organisation and review the menu RentAxis has made available for this account.', {
    weight: 57,
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard', 'Switch to Arabic', 'Use AR in the header. Labels change and the interface moves to a right-to-left layout.', {
    weight: 48,
    afterNavigation: async (page) => {
      await page.getByRole('link', { name: 'AR', exact: true }).click();
      await page.waitForURL(/\/ar\/dashboard/);
      await waitForApp(page);
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard', 'Open the account menu', 'Switch back to English, then use the profile menu for account settings and secure sign-out.', {
    weight: 38,
    afterNavigation: async (page) => {
      await page.locator('header button').filter({ hasText: 'Tenant Admin' }).last().click();
      await page.getByText('Update Profile', { exact: true }).waitFor({ state: 'visible' });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/profile', 'Review your profile', 'Check your name, email, role, and phone. Save only intentional changes and never expose passwords in recordings.', {
    weight: 62,
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard', 'Log out safely', 'On a shared device, finish every session by opening the account menu and selecting Logout.', {
    weight: 41,
    verifyTenantContext: false,
    afterNavigation: async (page) => {
      await page.locator('header button').filter({ hasText: 'Tenant Admin' }).last().click();
      await page.getByRole('button', { name: 'Logout', exact: true }).click();
      await page.waitForURL(/\/auth\/login/, { timeout: navTimeoutMs });
      await waitForApp(page);
    },
  }),
  roleRouteScene('anonymous', '/en/auth/login', 'Verify access', 'Sign in again when needed. The same secure navigation pattern applies throughout RentAxis.', {
    weight: 37,
    verifyTenantContext: false,
    afterNavigation: async (page) => signInWithCredentials(page, seed.adminLogin),
  }),
];

export default { role: 'anonymous', scenes };
