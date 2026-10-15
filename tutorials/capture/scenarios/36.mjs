// Tutorial 36 — Month-end recognition (Palm Ridge Properties, the Accountant
// Rania Khoury). Omar Haddad's R-101 recognition schedule (72,000 ÷ 365 days:
// October 6,115.07, February 5,523.29, September 5,917.79 absorbing the
// rounding, Σ 72,000), then September is closed on camera: Month-end
// recognition to 30/09/2026 previews 9 periods, 52,359.10 (R-202's rent and
// parking fee), the run posts nine CILs, and the General Ledger for September
// shows Advance Rent – Residences debited 35,112.32 and Rental Income credited
// the same. Back on R-101, September is Posted.
//
// Recognition cannot be undone here: the proof and the capture each start from
// snapshot pre36 (the Palm Ridge base on the current schema, September planned).
//
// Weights are seconds of narration per scene (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { palmLease } from '../lib/fixtures.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const omar = palmLease('omar');
const schedule = (page) => page.getByTestId('recognition-schedule');
const period = (page, text) => schedule(page).locator('tr').filter({ hasText: text }).first();

async function openSchedule(page) {
  await page.getByTestId('lease-tab-payments').click();
  // The section remembers being open: toggle it only when it is closed.
  await page.waitForTimeout(400);
  if (!(await schedule(page).isVisible())) await page.getByTestId('lease-section-toggle-recognition').click();
  await schedule(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
  await period(page, '01/09/2026').scrollIntoViewIfNeeded();
}

const scenes = [
  roleRouteScene('accountant', `/en/dashboard/leases/${omar}`, 'Earned by the day',
    "Omar Haddad, R-101: 72,000 for 365 days; each month is the day rate × its days.", {
    weight: 42.68,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await at(8.0);
      await openSchedule(page);
      await expectText(period(page, '01/10/2025'), '6,115.07', 'October');
      await expectText(period(page, '01/02/2026'), '5,523.29', 'February');
      await expectText(period(page, '01/09/2026'), '5,917.79', 'September');
      await expectText(period(page, '01/09/2026'), 'Planned', 'September status');
      await expectText(schedule(page), '72,000.00', 'Schedule total');
      await restPointer(page, 1300, 500);
      await at(24.1);
      await pointAt(period(page, '01/10/2025'));
      await at(26.6);
      await pointAt(period(page, '01/02/2026'));
      await at(29.0);
      await pointAt(period(page, '01/09/2026'));
      await at(34.6);
      await pointAt(schedule(page).getByText('72,000.00', { exact: true }));
      await at(37.4);
      await pointAt(period(page, '01/08/2026').getByText('CIL-26/66'));
      await at(41.2);
      await pointAt(period(page, '01/09/2026').getByText('Planned'));
    },
  }),
  stepScene('Preview the close',
    'To 30/09/2026: nine periods, 52,359.10, grouped by building. Nothing written yet.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('rail-accounting').click();
      await page.getByRole('link', { name: 'Month-end recognition', exact: true }).first().click();
      await page.getByTestId('recognition-to-date').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(1.9);
      await page.getByTestId('recognition-to-date').fill('2026-09-30');
      await at(5.3);
      await page.getByTestId('recognition-preview').click();
      await expectText(page.getByTestId('recognition-would-post'), '9', 'Would post');
      await expectText(page.getByTestId('recognition-result-amount'), '52,359.10', 'Preview amount');
      await restPointer(page, 1300, 500);
      await at(8.0);
      await pointAt(page.getByTestId('recognition-result'));
      await at(9.9);
      await pointAt(page.getByTestId('recognition-pending-total'));
      const r202 = page.locator('[data-testid^="recognition-pending-row-"]').filter({ hasText: 'R-202' });
      await at(14.6);
      await pointAt(r202.first());
      await at(16.9);
      await pointAt(r202.filter({ hasText: '98.63' }));
      await at(21.9);
      await pointAt(page.getByTestId('recognition-result-amount'));
    }, { weight: 24.76 }),
  stepScene('Run it',
    'Nine CIL journals, each dated 30/09/2026.',
    async (page) => {
      await page.getByTestId('recognition-run').click();
      await page.getByRole('dialog').getByRole('button', { name: 'Run recognition' }).click();
      await page.getByText('Recognition run', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('recognition-result'), '52,359.10', 'Posted amount');
      await pointAt(page.getByTestId('recognition-result'));
    }, { weight: 12.38 }),
  stepScene('In the General Ledger',
    'Dr Advance Rent, Cr Rental Income: 35,112.32 for the Residences in September.',
    async (page) => {
      const at = sceneClock(page);
      // The two accounts by id (looked up off camera), September, straight from the URL.
      const accounts = await (await page.request.get('/api/proxy/v1/finance/accounts')).json();
      const idOf = (name) => accounts.find((a) => a.name === name)?.id;
      const ids = [idOf('Advance Rent - Palm Ridge Residences'), idOf('Rental Income Palm Ridge Residences')];
      if (ids.some((x) => !x)) throw new Error('General Ledger accounts not found');
      await page.goto(`/en/dashboard/finance/general-ledger?accountIds=${ids.join(',')}&from=2026-09-01&to=2026-09-30`);
      const subTotals = page.locator('main tr').filter({ hasText: 'Sub Total' });
      await subTotals.first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(subTotals.nth(0), '35,112.32', 'Advance Rent debits');
      await expectText(subTotals.nth(1), '35,112.32', 'Rental Income credits');
      await restPointer(page, 1300, 500);
      await at(6.5);
      await pointAt(page.locator('main tr').filter({ hasText: 'CIL-26/74' }).first());
      await at(10.4);
      await pointAt(subTotals.nth(0));
      await at(13.3);
      await pointAt(subTotals.nth(1));
    }, { weight: 16.22 }),
  stepScene('September is posted',
    'The schedule row now carries its CIL. The nightly run does the same on its own.',
    async (page) => {
      const at = sceneClock(page);
      // Deep link: the Cheques tab with Revenue recognition open and scrolled to.
      await page.goto(`/en/dashboard/leases/${omar}?tab=payments&section=recognition`);
      await schedule(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
      // Let the section's own smooth scroll finish, then bring September to the middle.
      await page.waitForTimeout(1200);
      await period(page, '01/09/2026').evaluate((el) => el.scrollIntoView({ block: 'center' }));
      await expectText(period(page, '01/09/2026'), 'Posted', 'September posted');
      await at(2.4);
      await pointAt(period(page, '01/09/2026'));
    }, { weight: 13.23 }),
];

export default {
  role: 'accountant',
  anchored: true,
  warmup: [{ role: 'accountant', path: '/en/dashboard/finance/recognition' }],
  scenes,
};
