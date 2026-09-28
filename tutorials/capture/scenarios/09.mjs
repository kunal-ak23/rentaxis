// Tutorial 09 — Manage tenants and portal access. The Company Admin searches
// for the planned tenant, adds "Mariam Al Nuaimi" with an Arabic name,
// contact details and Arabic as preferred language, with a portal account:
// Miftah emails her an invite to set her own password (no password is shown).
// The tenant page shows the pending invite. Portal access is then withdrawn in
// Settings > Users & staff by deleting the portal user, and the tenant profile
// stays. Tenant email and phone are hidden in every frame (lib/page.mjs). The
// app cannot delete a tenant profile, so one an earlier take left is removed
// before the first frame: its portal user through the app, the profile from
// the local tutorial database (lib/local-db.mjs refuses anything with history).
import { navTimeoutMs, tenantId } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { fieldByLabel as field, modalForm } from '../lib/forms.mjs';
import { purgeTenantProfile } from '../lib/local-db.mjs';
import { goto } from '../lib/page.mjs';
import { expectCount, expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const tenant = {
  nameEn: 'Mariam Al Nuaimi',
  nameAr: 'مريم النعيمي',
  email: 'mariam.alnuaimi@oasiscrest.example',
  phone: '+971 50 000 0621',
};
const tenantsPage = '/en/dashboard/renters';
const usersPage = '/en/dashboard/settings?section=users';
let tenantPath = null;

const listSearch = (page) => page.locator('main').getByPlaceholder('Search...');
const tenantRow = (page) => page.getByRole('row').filter({ hasText: tenant.nameEn });

/** Remove what an earlier take left: the portal user through the app, then the profile. */
async function removeLeftovers(page) {
  const users = await page.request.get('/api/proxy/admin/users');
  if (!users.ok()) throw new Error(`Listing users failed: ${users.status()}`);
  for (const user of (await users.json()).filter((u) => (u.email || '').toLowerCase() === tenant.email)) {
    const del = await page.request.delete(`/api/proxy/admin/users/${user.id}`);
    if (!del.ok()) throw new Error(`Removing the earlier portal user failed: ${del.status()}`);
  }
  purgeTenantProfile(tenantId, tenant.email);
}

const scenes = [
  // Weights are the seconds of narration each scene covers, and paces put
  // each action on its cue (measured from the synthesized subtitles).
  {
    ...roleRouteScene('tenantAdmin', tenantsPage, 'Tenants',
      'A tenant profile holds the name, contact details and language. Search first, to avoid duplicates.', { weight: 18.1 }),
    run: async (page) => {
      // Off camera, before the first page load (the API needs no page).
      await removeLeftovers(page);
      await goto(page, tenantsPage);
      await page.getByRole('heading', { name: 'Tenants', exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 12000);
      await listSearch(page).fill('Mariam');
      await page.waitForTimeout(1200);
      await expectCount(tenantRow(page), 0, `${tenant.nameEn} rows`);
      await restPointer(page, 1700, 620);
    },
  },
  stepScene('Add Tenant',
    'English and Arabic names, email, phone and preferred language. Create Portal Account sends an invite.',
    async (page) => {
      await listSearch(page).fill('');
      await page.getByRole('button', { name: 'Add Tenant', exact: true }).click();
      const form = modalForm(page, 'Add Tenant');
      await form.waitFor({ state: 'visible' });
      await field(form, 'Name (English)').fill(tenant.nameEn);
      await field(form, 'Name (Arabic)').fill(tenant.nameAr);
      await pace(page, 1500);
      await field(form, 'Email').fill(tenant.email);
      await field(form, 'Phone').fill(tenant.phone);
      await pace(page, 7000);
      await field(form, 'Preferred Language').selectOption({ label: 'Arabic' });
      await pace(page, 2500);
      const portal = form.getByRole('checkbox');
      if (!(await portal.isChecked())) throw new Error('Create Portal Account should start ticked.');
      await pointAt(form.getByText('Create Portal Account', { exact: true }));
      await pace(page, 4500);
    }, { weight: 20.9 }),
  stepScene('Invite sent',
    'Miftah emails the tenant a link to set their own password. No password is shown or shared.',
    async (page) => {
      await modalForm(page, 'Add Tenant').getByRole('button', { name: 'Create', exact: true }).click();
      const notice = page.getByRole('dialog').filter({ hasText: 'Invite sent' });
      await notice.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1700, 620);
      await pace(page, 8500);
      await notice.getByRole('button', { name: 'Done', exact: true }).click();
      await notice.waitFor({ state: 'hidden' });
      await tenantRow(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(tenantRow(page), 'Resend invite', 'Pending invite action');
      await expectText(tenantRow(page), 'Arabic', 'Tenant language');
      await pointAt(tenantRow(page).getByText('Resend invite'));
    }, { weight: 19.2 }),
  stepScene('The tenant page',
    'Invite pending until the tenant sets a password. Contracts, cheques and tickets collect here.',
    async (page) => {
      await pace(page, 1500);
      await tenantRow(page).getByRole('link', { name: 'View', exact: true }).click();
      await page.waitForURL(/\/dashboard\/renters\/[0-9a-f-]{36}$/, { timeout: navTimeoutMs });
      tenantPath = new URL(page.url()).pathname;
      await page.getByRole('heading', { name: tenant.nameEn }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.locator('main'), 'Invite pending', 'Invite status');
      await expectText(page.locator('main'), tenant.nameAr, 'Arabic name');
      await expectText(page.locator('main'), 'This tenant has no contracts yet.', 'Contracts');
      await restPointer(page, 1700, 760);
      await pointAt(page.getByText('Invite pending', { exact: true }));
    }, { weight: 11.7 }),
  roleRouteScene('tenantAdmin', usersPage, 'Withdraw portal access',
    'The portal account is a user with the Tenant role. Deleting it removes the sign-in only.', {
    weight: 18.4,
    afterNavigation: async (page) => {
      await page.getByText('Staff Management', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 9000);
      await page.getByPlaceholder('Search users...').fill('Mariam');
      const row = page.getByRole('row').filter({ hasText: tenant.nameEn });
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row, 'Tenant', 'Portal user role');
      await pace(page, 4000);
      await row.getByRole('button', { name: 'Delete', exact: true }).click();
      await page.getByText('Delete User', { exact: true }).first().waitFor({ state: 'visible' });
      await pace(page, 1500);
      await page.getByRole('button', { name: 'Delete User', exact: true }).click();
      await page.getByRole('button', { name: 'Delete User', exact: true }).waitFor({ state: 'detached', timeout: navTimeoutMs });
      await row.waitFor({ state: 'detached', timeout: navTimeoutMs });
      await restPointer(page, 1700, 560);
    },
  }),
  stepScene('The profile stays',
    'The tenant keeps their profile and history, without a portal sign-in.',
    async (page) => {
      await pace(page, 1000);
      await goto(page, tenantPath);
      await page.getByRole('heading', { name: tenant.nameEn }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.getByText('Invite pending', { exact: true }), 0, 'invite badges');
      await expectCount(page.getByRole('button', { name: 'Resend invite' }), 0, 'Resend invite buttons');
      await restPointer(page, 1700, 760);
    }, { weight: 13.0 }),
];

export default { role: 'tenantAdmin', scenes };
