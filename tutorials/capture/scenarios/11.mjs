// Tutorial 11. Default role for scenes that do not name one: tenantAdmin.
import { saraLeaseId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Contract-ready lease', 'Confirm the renter, unit, dates, rent, deposit, and payment plan before reviewing the contract.'),
  routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Contract workspace', 'Use the authenticated Preview Contract control or its new-tab fallback to inspect the current agreement without changing the lease state.', async (page) => {
    await page.getByTestId('lease-tab-documents').click();
  }),
  routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Signature state', 'Pending Signature keeps the agreement awaiting renter action; review the current document before any acceptance or rejection.', async (page) => {
    await page.getByTestId('lease-tab-documents').click();
  }),
];

export default { role: 'tenantAdmin', scenes };
