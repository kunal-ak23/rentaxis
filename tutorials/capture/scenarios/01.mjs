// Tutorial 01 — Sign in and navigate Miftah. One page for the whole tour: the
// anonymous context signs in as the Company Admin, so sign-in, navigation,
// language, profile and logout are one continuous session (see `stepScene`).
import { navTimeoutMs, seed, tenantName } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { applyCaptureStyles, waitForApp } from '../lib/page.mjs';
import { expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const railIds = ['rail-home', 'rail-leasing', 'rail-collection', 'rail-accounting', 'rail-operations', 'rail-settings', 'rail-more'];

async function expectDir(page, dir) {
  const actual = await page.evaluate(() => document.documentElement.dir || 'ltr');
  if (actual !== dir) throw new Error(`Expected a ${dir} layout, found ${actual}.`);
}

const scenes = [
  // Weights follow the narration: roughly the seconds each part takes to speak.
  roleRouteScene('anonymous', '/en/auth/login', 'Sign in',
    'Enter the email address and password your administrator gave you, then select Sign In.', {
    weight: 30,
    verifyTenantContext: false,
    afterNavigation: async (page) => {
      await page.locator('#login-email').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 7000);
      await page.locator('#login-email').fill(seed.adminLogin.email);
      await page.locator('#login-password').fill(seed.adminLogin.password);
      await pace(page, 1500);
      await page.getByRole('button', { name: /sign in/i }).click();
      await page.waitForURL(/\/en\/dashboard$/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await restPointer(page, 1100, 560);
      await expectText(page.getByTestId('profile-menu'), 'Company Admin', 'Signed-in role');
    },
  }),
  stepScene('Your home page',
    'Your role shows under your name; the organisation shows above the side panel. The rail holds the main areas.',
    async (page) => {
      await expectText(page.getByTestId('org-switcher-button'), tenantName, 'Organisation');
      for (const id of railIds) await page.getByTestId(id).waitFor({ state: 'visible' });
      await pace(page, 5000);
      await pointAt(page.getByTestId('profile-menu'));
      await pace(page, 5000);
      await pointAt(page.getByTestId('org-switcher-button'));
      await pace(page, 5000);
      await pointAt(page.getByTestId('rail-home'));
      await restPointer(page, 31, 380);
    }, { weight: 36 }),
  stepScene('Leasing',
    'Each rail area opens its own pages in the side panel: Tenancy Contracts, Tenants, Properties & Units and Enquiry.',
    async (page) => {
      await page.getByTestId('rail-leasing').click();
      await page.waitForURL(/\/en\/dashboard\/leases/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      for (const label of ['Tenancy Contracts', 'Tenants', 'Properties & Units', 'Enquiry']) {
        await page.getByRole('link', { name: label, exact: true }).first().waitFor({ state: 'visible' });
      }
      await restPointer(page, 190, 460);
    }, { weight: 14 }),
  stepScene('Arabic, right to left',
    'AR switches the labels to Arabic and mirrors the layout. EN switches back.',
    async (page) => {
      await page.getByRole('link', { name: 'AR', exact: true }).click();
      await page.waitForURL(/\/ar\/dashboard\/leases/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await expectDir(page, 'rtl');
      // The header mirrors under the pointer: move it off the toolbar.
      await restPointer(page, 1100, 620);
      await pace(page, 7000);
      await page.getByRole('link', { name: 'EN', exact: true }).click();
      await page.waitForURL(/\/en\/dashboard\/leases/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await expectDir(page, 'ltr');
      await restPointer(page, 1100, 620);
    }, { weight: 18 }),
  stepScene('Your account menu',
    'The menu under your name shows your email address, Update Profile and Logout.',
    async (page) => {
      await page.getByTestId('profile-menu').click();
      await page.getByText('Update Profile', { exact: true }).waitFor({ state: 'visible' });
      await page.getByRole('button', { name: 'Logout', exact: true }).waitFor({ state: 'visible' });
      await pointAt(page.getByText('Update Profile', { exact: true }));
    }, { weight: 9 }),
  stepScene('My Profile',
    'Correct your full name or phone number and select Save Changes. Only an administrator can change your email.',
    async (page) => {
      await page.getByText('Update Profile', { exact: true }).click();
      await page.waitForURL(/\/en\/dashboard\/profile/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await expectText(page.locator('main h1').first(), 'My Profile', 'Page heading');
      await expectText(page.locator('main'), 'Company Admin', 'Profile role');
      await page.getByRole('button', { name: 'Save Changes', exact: true }).waitFor({ state: 'visible' });
      await restPointer(page, 1300, 420);
      await pace(page, 16000);
      await pointAt(page.getByText('Change Password', { exact: true }));
    }, { weight: 32 }),
  stepScene('Log out',
    'Open the menu under your name and select Logout. The sign-in page returns.',
    async (page) => {
      await page.getByTestId('profile-menu').click();
      await page.getByRole('button', { name: 'Logout', exact: true }).click();
      await page.waitForURL(/\/auth\/login/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await page.locator('#login-email').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 700);
    }, { weight: 16, verifyTenantContext: false }),
];

export default { role: 'anonymous', scenes };
