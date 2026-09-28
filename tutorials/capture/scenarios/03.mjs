// Tutorial 03 — Roles, permissions, and organisation switching. Each role is
// its own signed-in session (one clip per role); the Property Manager's two
// scenes share a page. Security Guards sign in on the mobile app by phone, so
// the guard and the Company User (no seeded account) are shown from Help.
import { navTimeoutMs, seed, tenantName } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { applyCaptureStyles, goto } from '../lib/page.mjs';
import { expectCount, expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const towerName = 'Oasis Crest Residence Tower';
const marinaName = 'Oasis Crest Marina Heights';
const marinaId = seed.properties?.marina;

async function openSwitcher(page) {
  await page.getByTestId('org-switcher-button').click();
  await page.getByPlaceholder('Search organizations...').waitFor({ state: 'visible' });
  await page.getByText('Global View', { exact: true }).waitFor({ state: 'visible' });
  await page.getByRole('button', { name: tenantName }).waitFor({ state: 'visible' });
}

const scenes = [
  // Weights are the seconds of narration each scene covers (Ava DragonHD at
  // "140" speaks ~165 wpm), measured from the rendered subtitles.
  roleRouteScene('superadmin', '/en/dashboard', 'System Admin',
    'The switcher reads Administering. Global View, or one organisation at a time — check the name before you change anything.', {
    weight: 39.4,
    afterNavigation: async (page) => {
      await expectText(page.getByTestId('profile-menu'), 'System Admin', 'Signed-in role');
      await expectText(page.getByTestId('org-switcher-button'), 'Administering', 'Switcher state');
      await pace(page, 11500);
      await pointAt(page.getByTestId('profile-menu'));
      await pace(page, 3000);
      await pointAt(page.getByTestId('org-switcher-button'));
      await pace(page, 3500);
      await openSwitcher(page);
      await restPointer(page, 760, 420);
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard', 'Company Admin',
    'Runs one organisation. Settings has no Administration section: organisations and platform users stay with the System Admin.', {
    weight: 17.3,
    afterNavigation: async (page) => {
      await expectText(page.getByTestId('profile-menu'), 'Company Admin', 'Signed-in role');
      await pace(page, 4000);
      await page.getByTestId('rail-settings').click();
      await page.waitForURL(/\/en\/dashboard\/settings/, { timeout: navTimeoutMs });
      await page.getByTestId('settings-nav-users').first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await applyCaptureStyles(page);
      await expectCount(page.getByRole('link', { name: 'Organisations', exact: true }), 0, 'Organisations links');
      await restPointer(page, 190, 520);
    },
  }),
  roleRouteScene('propertyManager', '/en/dashboard/properties', 'Property Manager',
    'No Settings in the rail, and Properties lists only the property assigned to this manager.', {
    weight: 8.8,
    afterNavigation: async (page) => {
      await expectText(page.getByTestId('profile-menu'), 'Property Manager', 'Signed-in role');
      await expectCount(page.getByTestId('rail-settings'), 0, 'Settings rail items');
      await page.getByText(towerName, { exact: true }).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.getByText(marinaName, { exact: true }), 0, `${marinaName} rows`);
      await pointAt(page.getByText(towerName, { exact: true }).first());
    },
  }),
  stepScene('A direct link is not a way round',
    'Opening an unassigned property directly: not found, or you may not have access.',
    async (page) => {
      if (!marinaId) throw new Error('The seed manifest has no marina property id.');
      await pace(page, 1000);
      await goto(page, `/en/dashboard/properties/${marinaId}`);
      await expectText(page.locator('main'), 'Property not found', 'Unassigned property');
      await expectText(page.locator('main'), 'you may not have access to it', 'Unassigned property');
      await restPointer(page, 1200, 700);
    }, { role: 'propertyManager', weight: 11.5 }),
  roleRouteScene('renter', '/en/dashboard/renter-portal', 'Tenant',
    'The Tenant Portal: contracts, payments, penalties, tickets, listings and meetings — no internal administration.', {
    weight: 10.3,
    afterNavigation: async (page) => {
      await expectText(page.getByTestId('profile-menu'), 'Tenant', 'Signed-in role');
      for (const label of ['My Tenancy Contracts', 'My Payments', 'My Penalties', 'My Tickets', 'Listings', 'Meetings']) {
        await page.getByRole('link', { name: label, exact: true }).first().waitFor({ state: 'visible' });
      }
      await expectCount(page.getByTestId('rail-settings'), 0, 'Settings rail items');
      // The first card a tenant reads. It must not say "nothing to pay" and
      // "overdue" at once — tutorials/bugs/2026-09-28-03.md (fixed in 1109d00a:
      // Ahmed's cheques are cleared, at the bank or post-dated, so the card
      // reads "All payments up to date").
      const main = (await page.locator('main').innerText()).replace(/\s+/g, ' ');
      const next = main.match(/next payment AED ([\d,.]+)/i);
      if (!next && !/all payments up to date/i.test(main)) {
        throw new Error('The Tenant Portal home shows neither a Next payment card nor "All payments up to date".');
      }
      if (next && Number(next[1].replace(/,/g, '')) === 0 && /\d+ days overdue/i.test(main)) {
        throw new Error('Tenant Portal home shows "Next payment AED 0" as overdue (tutorials/bugs/2026-09-28-03.md).');
      }
      if (!next && /\d+ days overdue/i.test(main)) {
        throw new Error('Tenant Portal home says "All payments up to date" and "overdue" at once.');
      }
      await restPointer(page, 1700, 620);
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/help/getting-started--roles-and-permissions', 'Company User and Security Guard',
    'Help › Roles & Permissions describes every role, including the Company User and the Security Guard.', {
    weight: 14.1,
    afterNavigation: async (page) => {
      const article = page.locator('main');
      await expectText(article, 'Company User Limited access', 'Company User row');
      await expectText(article, 'Security Guard Manages gate access at assigned properties', 'Security Guard row');
      await pace(page, 2500);
      await pointAt(page.getByText('Company User', { exact: true }).last());
      await pace(page, 3700);
      await pointAt(page.getByText('Security Guard', { exact: true }).last());
    },
  }),
  roleRouteScene('superadmin', '/en/dashboard', 'Back to the controlled context',
    'Check the organisation, the role and the property assignment before every sensitive action.', {
    weight: 24.2,
    afterNavigation: async (page) => {
      await openSwitcher(page);
      await expectText(page.getByTestId('org-switcher-button'), tenantName, 'Active organisation');
      await restPointer(page, 760, 420);
    },
  }),
];

export default { role: 'superadmin', scenes };
