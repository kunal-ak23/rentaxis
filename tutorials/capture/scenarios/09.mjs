// Tutorial 09. Default role for scenes that do not name one: tenantAdmin.
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/renters', 'Renter directory', 'Search prepared renter profiles, then preview the create-renter form and portal-account option without saving a new record.', async (page) => {
    await page.getByRole('button', { name: 'Add Renter', exact: true }).click();
  }),
  routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Linked tenancy', 'Renter, unit, payment schedule, and active lease status remain connected.'),
  routeScene('/en/dashboard/renters', 'Portal access', 'Use the renter directory as the starting point for authorised portal access, while keeping credentials out of recordings.'),
];

export default { role: 'tenantAdmin', scenes };
