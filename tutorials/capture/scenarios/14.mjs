// Tutorial 14. Default role for scenes that do not name one: tenantAdmin.
//
// Fine settings and the cheque register moved under the admin UI
// simplification (spec 2026-09-25): Settings > Rent & fines is now
// /dashboard/settings?section=rent, and every cheque/penalty screen is a
// pill on the Cheque / Cash Collection hub at /dashboard/collections. A
// single bounce does not cross the org's auto-propose threshold
// (FineSettingsInitializer), so the penalty on Ahmed's lease is raised by
// hand from the lease page, then approved from the Penalties pill — see
// scripts/seed_demo_tenant.py:982-992.
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { routeScene, publicRouteScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/settings?section=rent', 'Rent & fines settings',
    'Configure the approved fine amounts for a bounced cheque, a signature mismatch and a closed account, the grace days and the per-day late rate.'),
  routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Raise a penalty', 'Open the lease, choose Raise penalty, and select the cheque-failure reason.', async (page) => {
    await page.getByTestId('lease-tab-payments').click();
    await page.getByTestId('lease-raise-penalty').click();
  }),
  routeScene('/en/dashboard/collections?tab=penalties', 'Approve the penalty', 'A raised penalty is proposed, not charged — Finance approves it here before anything reaches the ledger.'),
  { ...publicRouteScene('/en/dashboard/renter-portal/penalties', 'Tenant sees the charge', 'Once approved, the penalty appears on the Tenant’s own Penalties page with its reason, amount and date.'), role: 'renter' },
  routeScene('/en/dashboard/collections?tab=all', 'Collect or waive', 'Collect the fine as an ordinary cash receipt against the lease, or waive it with an authorisation note — either way the original penalty stays on record.'),
];

export default { role: 'tenantAdmin', scenes };
