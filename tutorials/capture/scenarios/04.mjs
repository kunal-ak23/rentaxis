// Tutorial 04. Default role for scenes that do not name one: superadmin.
import { navTimeoutMs, tenantName } from '../lib/context.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/superadmin/tenants', 'Organisation administration', 'Search, provision, and review isolated customer organisations from one controlled list.', async (page) => {
    await page.getByPlaceholder('Search...').fill(tenantName);
  }),
  routeScene('/en/superadmin/tenants', 'Tutorial Demo tenant', 'The recording tenant uses synthetic legal and contact data and remains separate from customer records.', async (page) => {
    await page.getByPlaceholder('Search...').fill(tenantName);
  }),
  routeScene('/en/superadmin/tenants', 'Feature access', 'Listings, Meetings, Email Notifications, Lease Renewals, and Gate Pass are enabled per organisation.', async (page) => {
    await page.getByPlaceholder('Search...').fill(tenantName);
    const row = page.getByRole('row').filter({ hasText: tenantName });
    await row.getByRole('button', { name: 'Feature Toggles' }).click();
    await page.getByText('Listings (Marketplace)', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
  }),
  routeScene('/en/dashboard', 'Verify the tenant context', 'After switching, confirm the organisation name before creating or editing records.'),
];

export default { role: 'superadmin', scenes };
