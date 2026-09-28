// Tutorial 12. Default role for scenes that do not name one: tenantAdmin.
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Active lease overview', 'Review status, parties, unit, dates, rent, and deposit before administering an active tenancy.'),
  routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Payment schedule', 'Use the schedule as the authoritative installment and cheque timeline.', async (page) => {
    await page.getByRole('button', { name: 'Payment schedule', exact: true }).click();
  }),
  routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Documents', 'Keep approved tenancy attachments with the lease audit trail.', async (page) => {
    await page.getByTestId('lease-tab-documents').click();
  }),
  routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Interactions', 'Record relevant renter communication without storing passwords or unrelated personal notes.', async (page) => {
    await page.getByTestId('lease-tab-activity').click();
    await page.getByText(/Loading/).waitFor({ state: 'detached', timeout: 10000 });
    await page.getByRole('button', { name: /Log interaction/i }).click();
  }),
];

export default { role: 'tenantAdmin', scenes };
