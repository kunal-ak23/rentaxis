// Tutorial 24 — Facilities and booking approvals (Oasis Crest Properties).
// The Company Admin checks the Residence Tower inventory (Residents Fitness
// Centre bookable + active, parking spot B2-18 available) → the Tenant Ahmed
// Hassan requests a fitness-centre session and spot B2-18 → the admin opens
// each in Operations › Bookings, sees the other requests for the facility and
// approves with a note → the Tenant sees both Approved, with Release Spot.
//
// Snapshot pre24 = pre23 (one seeded, approved fitness booking for 05/10). The
// two requests are created on camera; the proof and capture restore pre24.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh).
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { isoDate, towerId } from '../lib/fixtures.mjs';
import { goto } from '../lib/page.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

const GYM = 'Residents Fitness Centre';
const SPOT = 'B2-18';
const inTwoDays = () => { const d = new Date(); d.setDate(d.getDate() + 2); return isoDate(d); };
/** The facility card (amenity or parking) whose heading is `name`. */
const card = (page, name) => page.locator('main div.rounded-xl, main div.rounded-2xl').filter({ has: page.getByText(name, { exact: true }) }).filter({ has: page.getByRole('button', { name: 'Request', exact: true }) }).last();
const myRequest = (page, text, status) => page.locator('tr').filter({ hasText: text }).filter({ hasText: status });

async function request(page, name, note, date) {
  await card(page, name).getByRole('button', { name: 'Request', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.waitFor({ state: 'visible', timeout: navTimeoutMs });
  if (date) await dialog.locator('input[type=date]').fill(date);
  await dialog.locator('textarea').fill(note);
  return dialog;
}

const scenes = [
  roleRouteScene('tenantAdmin', `/en/dashboard/properties/${towerId}`, 'Bookable inventory',
    'Amenities and parking spots are set up per property; Tenants see the active, bookable ones.', {
    weight: 22.07,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await page.getByRole('button', { name: 'Amenities' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(12.8);
      await page.getByRole('button', { name: 'Amenities' }).click();
      const gym = page.locator('tr').filter({ hasText: GYM });
      await expectText(gym, 'BOOKABLE', 'Fitness centre bookable');
      await at(14.6);
      await pointAt(gym.getByText(GYM).first());
      await at(16.2);
      await pointAt(gym.getByText(/^active$/i));
      await at(18.3);
      await page.getByRole('button', { name: 'Parking' }).click();
      const spot = page.locator('tr').filter({ hasText: SPOT });
      await expectText(spot, 'AVAILABLE', 'B2-18 available');
      await at(19.8);
      await pointAt(spot.getByText(SPOT).first());
      await at(20.9);
      await pointAt(spot.getByText(/^available$/i));
    },
  }),
  roleRouteScene('renter', '/en/dashboard/renter-portal', 'Request a booking',
    'The Tenant requests the facility and the spot; both wait as Pending.', {
    weight: 28.37,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const entry = page.getByText('Facilities & Parking', { exact: true }).first();
      await entry.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(2.6);
      await entry.click();
      await card(page, GYM).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(5.4);
      let dialog = await request(page, GYM, 'Morning session, 7 to 8 am.', inTwoDays());
      await at(10.8);
      await dialog.getByRole('button', { name: 'Submit Request' }).click();
      await dialog.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      const pending = page.getByText('1 pending request').first();
      await pending.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(12.4);
      await pointAt(pending);
      await at(17.4);
      dialog = await request(page, SPOT, 'For my second car, from next week.');
      await at(20.6);
      await dialog.getByRole('button', { name: 'Submit Request' }).click();
      await dialog.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      const gymRow = myRequest(page, GYM, 'Pending');
      const spotRow = myRequest(page, SPOT, 'Pending');
      await spotRow.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await gymRow.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(22.0);
      await spotRow.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await at(22.8);
      await pointAt(gymRow.getByText('Pending'));
      await at(24.2);
      await pointAt(spotRow.getByText('Pending'));
      await at(25.8);
      await pointAt(spotRow.getByRole('button', { name: 'Cancel Request' }));
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/bookings', 'Review and approve',
    'Check the Tenant, the date, the note and any clashing requests, then approve with a note.', {
    weight: 25.57,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const gymRow = page.locator('tr').filter({ hasText: GYM }).filter({ hasText: 'Pending' });
      const spotRow = page.locator('tr').filter({ hasText: SPOT }).filter({ hasText: 'Pending' });
      await gymRow.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await spotRow.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(2.8);
      await pointAt(gymRow.getByText('Pending'));
      await at(4.6);
      await gymRow.click();
      let drawer = page.getByRole('dialog');
      await drawer.getByText('Morning session', { exact: false }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(drawer, 'Morning session, 7 to 8 am.', 'Tenant note in the drawer');
      await expectText(drawer, 'Approved', 'Other approved session for the fitness centre');
      await at(6.4);
      await pointAt(drawer.getByText('Ahmed Hassan').first());
      await at(9.0);
      await pointAt(drawer.getByText('Morning session', { exact: false }));
      await at(12.6);
      await pointAt(drawer.getByText('Other requests for this resource', { exact: false }));
      await at(16.2);
      await drawer.locator('textarea').fill('Approved, see you at the gym.');
      await at(17.4);
      await drawer.getByRole('button', { name: 'Approve' }).click();
      await drawer.getByText('Decided on', { exact: false }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.keyboard.press('Escape');
      await drawer.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await at(19.3);
      await spotRow.click();
      drawer = page.getByRole('dialog');
      await drawer.getByText('second car', { exact: false }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(drawer, 'second car', 'Parking note in the drawer');
      await drawer.locator('textarea').fill('Approved: B2-18 is yours from Monday.');
      await at(21.0);
      await drawer.getByRole('button', { name: 'Approve' }).click();
      const release = drawer.getByRole('button', { name: 'Release' });
      await release.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(22.6);
      await pointAt(release);
    },
  }),
  roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Approved',
    'Both requests are Approved, with the manager\'s notes; a parking spot can be released later.', {
    weight: 11.21,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const gymRow = page.locator('tr').filter({ hasText: 'Approved, see you at the gym.' });
      const spotRow = myRequest(page, SPOT, 'Approved');
      await spotRow.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(gymRow, 'Approved', 'Fitness request approved');
      await expectText(spotRow, 'B2-18 is yours from Monday', 'Parking note');
      await restPointer(page, 1300, 640);
      await at(0.6);
      await spotRow.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await at(2.2);
      await pointAt(gymRow.getByText('Approved', { exact: true }));
      await at(3.6);
      await pointAt(spotRow.getByText('B2-18 is yours from Monday', { exact: false }));
      await at(6.0);
      await pointAt(spotRow.getByRole('button', { name: 'Release Spot' }));
    },
  }),
];

export default { role: 'tenantAdmin', anchored: true, scenes };
