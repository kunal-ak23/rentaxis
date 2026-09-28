// Tutorial 13. Default role for scenes that do not name one: tenantAdmin.
import { seed } from '../lib/context.mjs';
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { roleRouteScene, routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/leases', 'Lease lifecycle queue', 'Use status and date filters to identify renewal, extension, termination, and settlement work.'),
  routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Renew or extend', 'Review current terms and renter intent before changing the lease end date or renewal state.'),
  routeScene(`/en/dashboard/leases/${seed.leases?.rajesh}`, 'Terminate and settle', 'Open the settlement preview to review outstanding balances, deductions, deposit application, and the final settlement before closing.', async (page) => {
    await page.getByTestId('lease-more-actions').click();
    await page.getByTestId('lease-terminate').click();
    await page.waitForURL(/\/settlement/, { waitUntil: 'domcontentloaded', timeout: 15000 });
    await page.getByRole('heading', { name: 'Settlement', exact: true }).waitFor({ state: 'visible', timeout: 15000 });
    await page.evaluate(() => { document.body.style.zoom = '85%'; });
  }),
  roleRouteScene('renter', '/en/dashboard/renter-portal/renewals', 'Renter renewal response', 'The resident renewal view records intent while the property team retains approval and closure control.', { verifyTenantContext: false }),
];

export default { role: 'tenantAdmin', scenes };
