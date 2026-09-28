// Tutorial 03. Default role for scenes that do not name one: superadmin.
import { navTimeoutMs, tenantName } from '../lib/context.mjs';
import { towerName } from '../lib/fixtures.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

const scenes = [
  roleRouteScene('superadmin', '/en/dashboard', 'Super Admin organisation context', 'Open the organisation switcher and verify the customer context before administering any tenant record.', {
    weight: 72,
    afterNavigation: async (page) => {
      await page.getByRole('button', { name: 'Switch organization' }).click();
      await page.getByPlaceholder('Search organizations...').fill(tenantName);
      await page.getByRole('button', { name: tenantName }).waitFor({ state: 'visible' });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard', 'Tenant Admin', 'Tenant administrators manage the enabled portfolio, leasing, finance, users, and settings for their own organisation.', {
    weight: 53,
  }),
  roleRouteScene('propertyManager', '/en/dashboard/properties', 'Property Manager', 'Property managers work only with their assigned properties; direct navigation must not bypass that boundary.', {
    weight: 58,
    verifyTenantContext: false,
    afterNavigation: async (page) => {
      await page.getByText(towerName, { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/help/getting-started--roles-and-permissions', 'Tenant User', 'Use this more limited staff role for a defined operational surface without tenant-wide administration.', {
    weight: 34,
  }),
  roleRouteScene('renter', '/en/dashboard/renter-portal', 'Renter', 'Residents see their tenancy, payments, maintenance, meetings, services, and marketplace features—not internal administration.', {
    weight: 48,
    verifyTenantContext: false,
  }),
  // Security Guards authenticate with phone OTP through the guard/mobile
  // surface, not the web email/password form. Show the documented role
  // boundary here instead of attempting an invalid web login.
  roleRouteScene('tenantAdmin', '/en/dashboard/help/getting-started--roles-and-permissions', 'Security Guard', 'Guards use assigned-property visitor queues, scans, admissions, and exits without lease, finance, or tenant settings access.', {
    weight: 52,
  }),
  roleRouteScene('superadmin', '/en/dashboard', 'Return to the controlled context', 'Finish by checking the tenant, role, property assignment, and feature access before every sensitive action.', {
    weight: 49,
    afterNavigation: async (page) => {
      await page.getByRole('button', { name: 'Switch organization' }).click();
      await page.getByPlaceholder('Search organizations...').fill(tenantName);
      await page.getByRole('button', { name: tenantName }).waitFor({ state: 'visible' });
    },
  }),
];

export default { role: 'superadmin', scenes };
