// Tutorial 02. Default role for scenes that do not name one: superadmin.
import { navTimeoutMs, tenantName } from '../lib/context.mjs';
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { openGlobalSearch } from '../lib/flows.mjs';
import { waitForApp } from '../lib/page.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

const scenes = [
  roleRouteScene('superadmin', '/en/dashboard', 'Operational dashboard', 'Read tenant-scoped KPIs as signals, then open the underlying workflow or report for detail.', {
    weight: 75,
  }),
  roleRouteScene('superadmin', '/en/dashboard', 'Global search', 'Press Command K on macOS or Control K on Windows, then search for a known renter, lease, cheque, payment, or organisation.', {
    weight: 82,
    afterNavigation: async (page) => openGlobalSearch(page, 'Ahmed Hassan'),
  }),
  roleRouteScene('superadmin', '/en/dashboard', 'Open the prepared lease', 'Choose the tenant-scoped result and confirm RentAxis opens the correct lease rather than a generic results page.', {
    weight: 44,
    afterNavigation: async (page) => {
      await openGlobalSearch(page, 'Ahmed Hassan');
      await page.getByRole('dialog', { name: /search/i }).getByRole('button').filter({ hasText: 'Ahmed Hassan' }).first().click();
      await page.waitForURL(new RegExp(`/en/dashboard/leases/${ahmedLeaseId}`), { timeout: navTimeoutMs });
      await waitForApp(page);
    },
  }),
  roleRouteScene('superadmin', '/en/dashboard', 'Notification preview', 'Use the unread indicator to review recent event details and follow a related record when one is available.', {
    weight: 72,
    afterNavigation: async (page) => {
      await page.locator('header button').filter({ has: page.locator('svg.lucide-bell') }).click();
      await page.getByText('Notifications', { exact: true }).waitFor({ state: 'visible' });
    },
  }),
  roleRouteScene('superadmin', '/en/dashboard/notifications', 'Notification history', 'Acknowledging an item removes it from the unread view while preserving it in full history.', {
    weight: 52,
  }),
  roleRouteScene('superadmin', '/en/dashboard/help', 'Search the Help Center', 'Find role-aware workflow and safety guidance without leaving the application.', {
    weight: 67,
    afterNavigation: async (page) => {
      await page.getByPlaceholder('Search help articles...').fill('Security Guard');
      await page.getByText('Roles & Permissions', { exact: true }).waitFor({ state: 'visible' });
    },
  }),
  roleRouteScene('superadmin', '/en/dashboard/help/getting-started--roles-and-permissions', 'Role guidance', 'Compare each role’s responsibilities with its available navigation and enabled features.', {
    weight: 58,
  }),
  roleRouteScene('superadmin', '/en/dashboard', 'Use the four-tool pattern', 'Dashboard totals identify work, search reaches records, notifications surface events, and Help explains the approved process.', {
    weight: 38,
    afterNavigation: async (page) => openGlobalSearch(page, tenantName),
  }),
];

export default { role: 'superadmin', scenes };
