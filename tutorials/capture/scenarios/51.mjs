// Tutorial 51 — Monthly rent: posted nightly, and checked by hand. As Company Admin on Oasis
// Crest, after tutorials 47–50: the nightly recognition run posts each ended month on its
// own; Month-end recognition is where an operator sees what is still waiting and posts it.
// Fatima Al Zaabi's A-102 shows July and August Posted and September (ended) still Planned;
// the page lists ten ended months, 87,369.90 (Fatima's September and nine months of Sara
// Mansour's M-1501, posted 29 September for a term from 1 January); Preview, Run; September
// is Posted and its CIL debits Advance Rent and credits Rental Income 5,095.89.
//
// The recording stack keeps the nightly job off (recording-stack.sh), which is what leaves
// those months waiting. Recognition cannot be undone here: the proof and the capture each
// start from snapshot `pre51` (the books as tutorial 50's take leaves them).
import { navTimeoutMs, seed } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { applyCaptureStyles, goto, waitForApp } from '../lib/page.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const fatimaSchedule = `/en/dashboard/leases/${seed.leases?.fatima}?tab=payments&section=recognition`;

const centre = (locator) => locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
const schedule = (page) => page.getByTestId('recognition-schedule');
/** A schedule row by its period start; cells are period, days, amount, status, journal. */
const period = (page, start) => schedule(page).locator('tr').filter({ hasText: start }).first();
const cell = (row, i) => row.locator('td').nth(i);
const pendingRows = (page, unit) => page.locator('[data-testid^="recognition-pending-row-"]').filter({ hasText: unit });
/** A line of the open journal, by account code: cells are line, account, debit, credit. */
const line = (page, code) => page.locator('main table tbody tr').filter({ hasText: code }).first();

/** The page proposes the last day of the previous month (30/09/2026 on 29/10/2026), not today. */
async function expect30September(toDate) {
  const value = await toDate.inputValue();
  if (value !== '2026-09-30') throw new Error(`To date: expected 2026-09-30, found ${value}.`);
}

async function openSchedule(page) {
  await schedule(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
  // Let the section's own scroll finish, then bring September to the middle.
  await page.waitForTimeout(1200);
  await centre(period(page, '01/09/2026'));
}

const scenes = [
  roleRouteScene('tenantAdmin', fatimaSchedule, 'Posted on its own',
    'Each ended month moves from Advance Rent to Rental Income overnight. September is still Planned.', {
    weight: 20.3,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await openSchedule(page);
      await expectText(period(page, '01/07/2026'), 'Posted', 'July');
      await expectText(period(page, '01/08/2026'), 'Posted', 'August');
      await expectText(period(page, '01/09/2026'), 'Planned', 'September');
      await expectText(period(page, '01/09/2026'), '5,095.89', 'September amount');
      await restPointer(page, 1300, 500);
      await at(10.6);
      await pointAt(cell(period(page, '01/07/2026'), 4));
      await at(12.6);
      await pointAt(cell(period(page, '01/08/2026'), 4));
      await at(17.4);
      await pointAt(cell(period(page, '01/09/2026'), 3));
    },
  }),
  stepScene('What is waiting',
    'Accounting, Month-end recognition: ten ended months, 87,369.90, listed by building.',
    async (page) => {
      const at = sceneClock(page);
      await at(1.4);
      await page.getByTestId('rail-accounting').click();
      await at(3.2);
      await page.getByRole('link', { name: 'Month-end recognition', exact: true }).first().click();
      const warning = page.getByTestId('recognition-behind-warning');
      await warning.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await applyCaptureStyles(page);
      await expectText(warning, '10 ended periods', 'Behind count');
      await expectText(warning, '87,369.90', 'Behind amount');
      await expectText(warning, '31/01/2026', 'Oldest period');
      const a102 = pendingRows(page, 'A-102');
      const m1501 = pendingRows(page, 'M-1501');
      if ((await a102.count()) !== 1 || (await m1501.count()) !== 9) {
        throw new Error(`Pending rows: expected A-102 ×1 and M-1501 ×9, found ${await a102.count()} and ${await m1501.count()}.`);
      }
      await at(4.6);
      await pointAt(warning);
      await at(14.0);
      await centre(a102.first());
      await pointAt(cell(a102.first(), 2));
      await at(16.0);
      await centre(m1501.first());
      await pointAt(cell(m1501.first(), 0));
      await at(19.6);
      await pointAt(cell(m1501.first(), 2));
    }, { weight: 23.45 }),
  stepScene('Preview, then run',
    'To 30/09/2026: ten periods, 87,369.90. Preview writes nothing; Run posts them. Safe to repeat.',
    async (page) => {
      const at = sceneClock(page);
      const toDate = page.getByTestId('recognition-to-date');
      await centre(toDate);
      await expect30September(toDate);
      await at(1.0);
      await pointAt(toDate);
      await at(9.0);
      await page.getByTestId('recognition-preview').click();
      await expectText(page.getByTestId('recognition-would-post'), '10', 'Would post');
      await expectText(page.getByTestId('recognition-result-amount'), '87,369.90', 'Preview amount');
      await at(10.4);
      await pointAt(page.getByTestId('recognition-would-post'));
      await at(11.4);
      await pointAt(page.getByTestId('recognition-result-amount'));
      await at(13.0);
      await page.getByTestId('recognition-run').click();
      await at(14.4);
      await page.getByTestId('recognition-run-confirm').click();
      await page.getByTestId('recognition-posted').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('recognition-posted'), '10', 'Posted');
      await expectText(page.getByTestId('recognition-result-amount'), '87,369.90', 'Posted amount');
      await page.getByTestId('recognition-no-pending').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(15.6);
      await pointAt(page.getByTestId('recognition-posted'));
      await at(19.0);
      await centre(page.getByTestId('recognition-no-pending'));
      await pointAt(page.getByTestId('recognition-no-pending'));
    }, { weight: 24.6 }),
  stepScene('September is posted',
    'September now carries its CIL: Advance Rent debited, Rental Income credited 5,095.89.',
    async (page) => {
      const at = sceneClock(page);
      await goto(page, fatimaSchedule);
      await openSchedule(page);
      const september = period(page, '01/09/2026');
      await expectText(september, 'Posted', 'September posted');
      const journal = september.getByRole('link').first();
      await at(2.6);
      await pointAt(cell(september, 3));
      await at(4.2);
      await journal.click();
      await page.waitForURL(/\/finance\/journals\/[0-9a-f-]{36}$/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await expectText(line(page, '100002'), '5,095.89', 'Advance Rent debit');
      await expectText(line(page, '100014'), '5,095.89', 'Rental Income credit');
      await at(6.0);
      await pointAt(cell(line(page, '100002'), 2));
      await at(9.4);
      await pointAt(cell(line(page, '100014'), 3));
    }, { weight: 11.9 }),
];

export default {
  role: 'tenantAdmin',
  anchored: true,
  warmup: [{ role: 'tenantAdmin', path: '/en/dashboard/finance/recognition' }],
  scenes,
};
