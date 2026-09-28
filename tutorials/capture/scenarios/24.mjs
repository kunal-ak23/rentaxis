// Tutorial 24. Default role for scenes that do not name one: tenantAdmin.
import { navTimeoutMs } from '../lib/context.mjs';
import { parkingSpotNumber, towerId } from '../lib/fixtures.mjs';
import { openPreparedBookingApproval, openPreparedParkingRequest } from '../lib/flows.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

const scenes = [
  roleRouteScene('tenantAdmin', `/en/dashboard/properties/${towerId}`, 'Confirm bookable inventory', 'Before a resident requests anything, verify that the facility is active, bookable, available, and scoped to the correct property.', {
    weight: 43,
    afterNavigation: async (page) => page.getByRole('button', { name: /^parking$/i }).click(),
  }),
  roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Prepare a parking request', 'The resident checks the property, availability, unit, preferred date, and note before submitting the request.', {
    weight: 55,
    verifyTenantContext: false,
    afterNavigation: openPreparedParkingRequest,
  }),
  roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Submit once', 'After submission, the new request appears as Pending in My Requests and the parking spot remains unallocated until approval.', {
    weight: 42,
    verifyTenantContext: false,
    afterNavigation: async (page) => {
      const dialog = await openPreparedParkingRequest(page);
      await dialog.getByRole('button', { name: 'Submit Request', exact: true }).click();
      await dialog.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await page.getByRole('row').filter({ hasText: parkingSpotNumber }).filter({ hasText: 'Pending' }).waitFor({ state: 'visible' });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/bookings', 'Review the request', 'The manager opens the Pending request and verifies the renter, unit, resource, preferred date, conflicts, and note.', {
    weight: 52,
    afterNavigation: openPreparedBookingApproval,
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/bookings', 'Approve with an auditable note', 'Approve only after the checks are complete. The request changes to Approved and records the manager’s decision note.', {
    weight: 48,
    afterNavigation: async (page) => {
      const drawer = await openPreparedBookingApproval(page);
      await drawer.getByRole('button', { name: 'Approve', exact: true }).click();
      await drawer.getByText('Approved', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
    },
  }),
  roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Confirm the allocation', 'Back in the renter account, the same request is Approved and the parking card is held for this resident.', {
    weight: 38,
    verifyTenantContext: false,
    afterNavigation: async (page) => {
      await page.getByRole('row').filter({ hasText: parkingSpotNumber }).filter({ has: page.getByText('Approved', { exact: true }) }).waitFor({ state: 'visible' });
    },
  }),
  roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Release the parking spot', 'When the allocation is no longer needed, release it deliberately. History remains visible while availability is restored.', {
    weight: 47,
    verifyTenantContext: false,
    afterNavigation: async (page) => {
      const row = page.getByRole('row').filter({ hasText: parkingSpotNumber }).filter({ has: page.getByText('Approved', { exact: true }) });
      await row.getByRole('button', { name: 'Release Spot', exact: true }).click();
      await page.getByText('Give up this parking spot? It becomes available to others.', { exact: true }).waitFor({ state: 'visible' });
      await page.getByRole('button', { name: 'Release Spot', exact: true }).last().click();
      await page.getByRole('row').filter({ hasText: parkingSpotNumber }).filter({ has: page.getByText('Released', { exact: true }) }).last().waitFor({ state: 'visible', timeout: navTimeoutMs });
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/properties/${towerId}`, 'Verify restored availability', 'Return to the property inventory and confirm that the spot is Available, active, and ready for another request.', {
    weight: 39,
    afterNavigation: async (page) => page.getByRole('button', { name: /^parking$/i }).click(),
  }),
];

export default { role: 'tenantAdmin', scenes };
