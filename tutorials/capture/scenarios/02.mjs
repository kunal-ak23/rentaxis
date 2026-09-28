// Tutorial 02 — Dashboard, search, notifications and help. The Company Admin
// works through the four tools in one page (see `stepScene`). Nothing is
// written: notifications are filtered, never marked read, so every take starts
// from the same unread set.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { applyCaptureStyles, waitForApp } from '../lib/page.mjs';
import { expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const searchDialog = (page) => page.getByRole('dialog', { name: /search/i });

const scenes = [
  // Weights are the seconds of narration each scene covers (Ava DragonHD at
  // "140" speaks ~165 wpm), measured from the rendered subtitles.
  roleRouteScene('tenantAdmin', '/en/dashboard', 'Home · Today',
    'The Contract pipeline counts contracts by stage; Needs you now lists the work waiting for you.', {
    weight: 39.3,
    afterNavigation: async (page) => {
      const pipeline = page.getByText('Contract pipeline', { exact: true });
      const needs = page.getByText('Needs you now', { exact: true });
      await pipeline.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await needs.waitFor({ state: 'visible' });
      for (const label of ['Cheques to deposit', 'Overdue payments', 'Open tickets']) {
        await page.getByText(label, { exact: true }).first().waitFor({ state: 'visible' });
      }
      await page.getByText('Unit Status', { exact: true }).last().waitFor({ state: 'visible' });
      await pace(page, 7500);
      await pointAt(pipeline);
      await pace(page, 7500);
      await pointAt(needs);
      await pace(page, 11000);
      await pointAt(page.getByText('Overdue', { exact: true }).last());
    },
  }),
  stepScene('Global search',
    'Select the search box, or press Command K on a Mac or Control K on Windows, and type part of a name, unit or cheque number.',
    async (page) => {
      await page.getByRole('button', { name: /Search contracts, tenants, cheques/ }).click();
      const dialog = searchDialog(page);
      await dialog.waitFor({ state: 'visible' });
      await pace(page, 8500);
      await dialog.getByRole('textbox').pressSequentially('Ahmed', { delay: 120 });
      const contract = dialog.getByRole('button').filter({ hasText: 'A-101 · Ahmed Hassan' }).first();
      await contract.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(dialog, 'Cheque 200100', 'Cheque results');
      await restPointer(page, 1300, 700);
    }, { weight: 21.8 }),
  stepScene('Open the tenancy contract',
    'The result opens the tenancy contract itself, not a page of search results.',
    async (page) => {
      await searchDialog(page).getByRole('button').filter({ hasText: 'A-101 · Ahmed Hassan' }).first().click();
      await page.waitForURL(new RegExp(`/en/dashboard/leases/${ahmedLeaseId}$`), { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await expectText(page.getByTestId('lease-status'), 'Active', 'Contract status');
      await expectText(page.locator('main'), 'Unit A-101', 'Contract unit');
      await restPointer(page, 1200, 820);
    }, { weight: 6.7 }),
  stepScene('Notifications',
    'The badge counts unread notifications; the bell previews the latest ones.',
    async (page) => {
      await page.getByTestId('header-notifications').click();
      await page.getByText('View All Notifications', { exact: true }).waitFor({ state: 'visible' });
      await page.getByText('New booking request', { exact: true }).first().waitFor({ state: 'visible' });
    }, { weight: 9.2 }),
  stepScene('Notification history',
    'Unread shows only what has not been acknowledged. Review each item before Mark All as Read.',
    async (page) => {
      await page.getByText('View All Notifications', { exact: true }).click();
      await page.waitForURL(/\/en\/dashboard\/notifications/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await pace(page, 2500);
      await page.getByRole('button', { name: 'Unread', exact: true }).click();
      await page.getByText('New booking request', { exact: true }).first().waitFor({ state: 'visible' });
      await page.getByRole('button', { name: /Mark All as Read/ }).waitFor({ state: 'visible' });
      await restPointer(page, 1100, 760);
    }, { weight: 16.2 }),
  stepScene('Help Center',
    'Articles are grouped by topic and tagged with the roles they apply to. Search for roles.',
    async (page) => {
      await page.getByTestId('header-help').click();
      await page.waitForURL(/\/en\/dashboard\/help$/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await pace(page, 7500);
      await page.getByPlaceholder('Search help articles...').pressSequentially('roles', { delay: 120 });
      await page.getByText('Roles & Permissions', { exact: true }).first().waitFor({ state: 'visible' });
    }, { weight: 9.8 }),
  stepScene('Roles & Permissions',
    'What each role, from System Admin to Tenant, can see and do.',
    async (page) => {
      await page.getByText('Roles & Permissions', { exact: true }).first().click();
      await page.waitForURL(/\/help\/getting-started--roles-and-permissions$/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      const article = page.locator('main');
      for (const role of ['System Admin', 'Company Admin', 'Property Manager', 'Security Guard', 'Company User', 'Tenant']) {
        await expectText(article, role, 'Role list');
      }
      await restPointer(page, 1600, 640);
    }, { weight: 19.8 }),
];

export default { role: 'tenantAdmin', scenes };
