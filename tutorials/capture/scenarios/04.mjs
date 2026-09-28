// Tutorial 04 — Provision organisations and manage feature access. The System
// Admin provisions a fictional organisation, sets its features, switches into
// it, and shows where it is corrected or deactivated. A leftover copy from an
// earlier take is deleted off camera before the first frame, so the list on
// screen only ever holds Oasis Crest and the organisation this take creates.
import { navTimeoutMs, tenantName } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { applyCaptureStyles, goto, waitForApp } from '../lib/page.mjs';
import { expectCount, expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const org = {
  name: 'Palm Vista Real Estate',
  address: 'Office 902, Harbour Point, Al Khan Street, Sharjah, United Arab Emirates',
  trn: '100987654300003',
  phone: '+971 6 000 0000',
};
const enabledFeatures = ['Meetings & Scheduling', 'Gate Passes & Security'];
const featureLabels = ['Listings (Marketplace)', 'Meetings & Scheduling', 'Email Notifications',
  'Contract Renewals & Reminders', 'Gate Passes & Security', 'Mobile finance screens'];

/** Hard-delete any organisation of this name left by an earlier take (API, off camera). */
async function removeLeftoverOrganisation(page) {
  const res = await page.request.get('/api/proxy/admin/tenants');
  if (!res.ok()) throw new Error(`Listing organisations failed: ${res.status()}`);
  for (const tenant of (await res.json()).filter((t) => t.name === org.name)) {
    const del = await page.request.delete(
      `/api/proxy/admin/tenants/${tenant.id}?confirmName=${encodeURIComponent(org.name)}`);
    if (!del.ok()) throw new Error(`Removing the earlier ${org.name} failed: ${del.status()}`);
  }
}

const orgRow = (page) => page.getByRole('row').filter({ hasText: org.name });
const featuresDrawer = (page) => page.locator('div.fixed').filter({ has: page.getByRole('heading', { name: 'Feature Toggles' }) });

/** The drawer's switch for one feature: the button beside its label. */
const featureSwitch = (page, label) => featuresDrawer(page)
  .locator('div.justify-between').filter({ has: page.getByText(label, { exact: true }) }).getByRole('button');
const isOn = async (toggle) => (await toggle.getAttribute('class')).includes('bg-blue-600');

async function expectFeatures(page, onLabels) {
  for (const label of featureLabels) {
    const toggle = featureSwitch(page, label);
    await toggle.waitFor({ state: 'visible', timeout: navTimeoutMs });
    const want = onLabels.includes(label);
    if (await isOn(toggle) !== want) throw new Error(`${label} should be ${want ? 'on' : 'off'}.`);
  }
}

const scenes = [
  // Weights are the seconds of narration each scene covers (Ava DragonHD at
  // "140" speaks ~165 wpm), measured from the rendered subtitles.
  {
    ...roleRouteScene('superadmin', '/en/superadmin/tenants', 'Organisations',
      'Settings › Administration › Organisations. Search first, so you never provision a duplicate.', { weight: 24.2 }),
    run: async (page) => {
      await goto(page, '/en/superadmin/tenants');
      await removeLeftoverOrganisation(page);
      await goto(page, '/en/superadmin/tenants');
      await orgRow(page).or(page.getByRole('row').filter({ hasText: tenantName })).first()
        .waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 11500);
      await page.getByPlaceholder('Search...').fill('Palm Vista');
      await expectCount(orgRow(page), 0, `${org.name} rows`);
      await restPointer(page, 1100, 620);
    },
  },
  stepScene('Provision New Organisation',
    'Name, office address, TRN and phone: they appear on receipts and contracts, so enter them exactly.',
    async (page) => {
      await page.getByPlaceholder('Search...').fill('');
      await page.getByRole('button', { name: /Provision New Organisation/ }).click();
      await page.getByTestId('org-name').waitFor({ state: 'visible' });
      await pace(page, 2000);
      await page.getByTestId('org-name').fill(org.name);
      await page.getByPlaceholder('Office address (shown on receipts)').fill(org.address);
      await page.getByPlaceholder('e.g. 100XXXXXXXXX').fill(org.trn);
      await page.getByPlaceholder('+971 50 123 4567').fill(org.phone);
      await pace(page, 11500);
      await pointAt(page.getByText('Ticket Closure OTP', { exact: true }));
    }, { weight: 22.3 }),
  stepScene('Create Organisation',
    'The new organisation joins the list, Active, with its own ID.',
    async (page) => {
      await page.getByRole('button', { name: 'Create Organisation', exact: true }).click();
      await orgRow(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(orgRow(page), 'Active', 'New organisation status');
      await expectText(orgRow(page), org.trn, 'New organisation TRN');
      await restPointer(page, 1100, 640);
    }, { weight: 6.8 }),
  stepScene('Feature Toggles',
    'Optional features start off. Turn on only what the organisation has signed up for; each switch saves at once.',
    async (page) => {
      await orgRow(page).getByRole('button', { name: 'Feature Toggles' }).click();
      await expectText(featuresDrawer(page), org.name, 'Drawer organisation');
      await expectFeatures(page, []);
      await pace(page, 26000);
      for (const label of enabledFeatures) {
        await featureSwitch(page, label).click();
        await page.waitForFunction(
          (el) => el && !el.disabled && el.className.includes('bg-blue-600'),
          await featureSwitch(page, label).elementHandle(),
          { timeout: navTimeoutMs },
        );
        await pace(page, 1500);
      }
      await pace(page, 3000);
      // Close and reopen: the switches come back from the server.
      await page.keyboard.press('Escape');
      await featuresDrawer(page).waitFor({ state: 'detached' });
      await orgRow(page).getByRole('button', { name: 'Feature Toggles' }).click();
      await expectFeatures(page, enabledFeatures);
      await restPointer(page, 1500, 560);
    }, { weight: 37.7 }),
  // Same page session as the create: the switcher lists the new organisation
  // without a reload (tutorials/bugs/2026-09-28-04.md, fixed in 913f150b).
  stepScene('Switch into the organisation',
    'The switcher now names the new organisation. It has no properties or contracts yet.',
    async (page) => {
      await page.keyboard.press('Escape');
      await featuresDrawer(page).waitFor({ state: 'detached' });
      await page.getByTestId('org-switcher-button').click();
      await page.getByPlaceholder('Search organizations...').fill('Palm');
      await page.getByRole('button', { name: org.name }).click();
      // Switching reloads the shell; wait for it to come back in the new organisation.
      await page.waitForFunction(
        (name) => document.querySelector('[data-testid="org-switcher-button"]')?.innerText.includes(name),
        org.name, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await expectText(page.getByTestId('org-switcher-button'), org.name, 'Active organisation');
      await expectText(page.locator('main'), 'Nothing needs you right now.', 'Empty organisation');
      await expectText(page.locator('main'), '0 properties', 'Empty organisation');
      await restPointer(page, 1100, 620);
    }, { weight: 10.2, verifyTenantContext: false }),
  roleRouteScene('superadmin', '/en/superadmin/tenants', 'Correct or deactivate',
    'Edit corrects legal details. Deactivate signs everyone in the organisation out until it is activated again.', {
    weight: 21.7,
    afterNavigation: async (page) => {
      await orgRow(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await orgRow(page).getByRole('button', { name: 'Edit' }).click();
      await page.getByRole('button', { name: 'Save Changes', exact: true }).waitFor({ state: 'visible' });
      if (await page.getByTestId('org-name').inputValue() !== org.name) throw new Error('Edit did not load the organisation.');
      await pace(page, 5000);
      await page.getByRole('button', { name: 'Cancel', exact: true }).click();
      await orgRow(page).getByRole('button', { name: 'Deactivate' }).click();
      await page.getByText('Deactivate this organisation?', { exact: true }).waitFor({ state: 'visible' });
      await expectText(page.getByRole('dialog').or(page.locator('div.fixed').filter({ hasText: 'Deactivate this organisation?' })).first(),
        `Everyone in ${org.name} is signed out`, 'Deactivate warning');
      await pace(page, 8000);
      await page.getByRole('button', { name: /^cancel$/i }).click();
      await page.getByText('Deactivate this organisation?', { exact: true }).waitFor({ state: 'hidden' });
      await expectText(orgRow(page), 'Active', 'Organisation still active');
      await restPointer(page, 1100, 640);
    },
  }),
];

export default { role: 'superadmin', scenes };
