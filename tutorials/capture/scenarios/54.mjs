// Tutorial 54 — Parking and amenity fees in the books (PR #468).
// Oasis Crest, snapshot `pre54` (tutorials/seed/seed_bookings_54.py: Fitness Centre AED 30 per day, bay
// B2-18 free). Company Admin sets B2-18 to Per month AED 500 (income account left empty: the property's
// Additional Parking account), books it for Ahmed Hassan (A-101) from today for one month with New booking
// (quote 500, no VAT on this organisation's booking fees), and the journal voucher posts Dr Rent Receivable
// / Cr Additional Parking 500. Ahmed's contract gets a 500 collection row. Release the same day: today counts
// as used, a second voucher credits 483.33 and the row is re-registered at 16.67. Fatima Al Zaabi requests
// the Fitness Centre for one day and sees the AED 30 quote before she submits (not approved on camera).
//
// The booking and the release post journals, which cannot be undone: proof and capture start from pre54.
import { navTimeoutMs, seed } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { narrationDurationSeconds } from '../lib/narration.mjs';
import { goto } from '../lib/page.mjs';
import { expectText, pace, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const towerId = seed.properties?.tower;
const ahmedLease = seed.leases?.ahmed;
if (!towerId || !ahmedLease || !seed.parkingSpotId || !seed.amenityId) {
  throw new Error('Seed manifest incomplete: run recording-stack.sh seed, seed_bookings_54.py, then snapshot pre54.');
}
const SPOT = 'B2-18';
const dubaiToday = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Dubai' }).format(new Date());
const plusDays = (iso, n) => { const d = new Date(`${iso}T00:00:00Z`); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
/** One whole month from `iso`: the day before the same date next month. */
const oneMonth = (iso) => { const d = new Date(`${iso}T00:00:00Z`); d.setUTCMonth(d.getUTCMonth() + 1); d.setUTCDate(d.getUTCDate() - 1); return d.toISOString().slice(0, 10); };
const TODAY = dubaiToday();
const END = oneMonth(TODAY);
const dmy = (iso) => iso.split('-').reverse().join('/');

// ── narration-paced cues: `at(n)` waits until n words into the scene's narration ─
const WORDS = [64, 59, 39, 21, 71, 57];
// Hold weights: the renter clip starts late (new context and sign-in); scene 5 hands it 10 words.
const WEIGHTS = [64, 59, 39, 21, 61, 67];
const secondsPerWord = (narrationDurationSeconds() + 2) / WORDS.reduce((a, b) => a + b, 0);
const wordClock = (page) => {
  const at = sceneClock(page);
  return (words) => at(words * secondsPerWord);
};
/** Waits for `locator` to show `text` (async-loaded fields), then proves it. */
async function untilText(locator, text, what) {
  await locator.filter({ hasText: text }).first().waitFor({ state: 'visible', timeout: navTimeoutMs })
    .catch(() => {});
  await expectText(locator, text, what);
}
const centre = (locator) => locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
const journalRow = (page, number) => page.locator('main tr').filter({ has: page.getByRole('link', { name: number, exact: true }) });

/** The booking row on /dashboard/bookings, whatever the status filter. */
async function bookingRow(page) {
  const row = page.locator('main tr, main [role="row"], main li').filter({ hasText: SPOT }).filter({ hasText: 'Ahmed' }).first();
  if (!(await row.isVisible().catch(() => false))) {
    const filter = page.getByTestId('bookings-status-filter');
    if (await filter.count()) await filter.selectOption({ label: 'Approved' }).catch(() => filter.selectOption(''));
  }
  await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
  return row;
}

const scenes = [
  roleRouteScene('tenantAdmin', `/en/dashboard/properties/${towerId}`, 'Fees by the day or the month',
    'Bay B2-18: Per month, AED 500. No income account chosen: Additional Parking.', {
    weight: WEIGHTS[0],
    afterNavigation: async (page) => {
      const at = wordClock(page);
      await restPointer(page, 900, 300);
      await at(24);
      await page.getByRole('button', { name: 'Parking', exact: true }).click();
      const row = page.locator('main tr').filter({ hasText: SPOT }).first();
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(30);
      await row.getByRole('button', { name: 'Edit Spot' }).click();
      const type = page.getByTestId('spot-fee-type');
      await type.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(36);
      await type.selectOption('PER_MONTH');
      await pace(page, 600);
      const amount = page.getByTestId('spot-fee-amount');
      await amount.fill('500');
      await at(48);
      await pointAt(page.getByTestId('spot-income-account'));
      await at(62);
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      await type.waitFor({ state: 'hidden', timeout: navTimeoutMs });
    },
  }),
  stepScene('Book for a tenant',
    'New booking: the fee is quoted before you book, then approved at once.',
    async (page) => {
      const at = wordClock(page);
      await goto(page, '/en/dashboard/bookings');
      await at(9);
      await page.getByTestId('bookings-new').click();
      const dialog = page.getByTestId('onbehalf-dialog');
      await dialog.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('onbehalf-property').selectOption(towerId);
      await pace(page, 500);
      await page.getByTestId('onbehalf-unit').selectOption({ label: 'A-101' }).catch(() => page.getByTestId('onbehalf-unit').selectOption(seed.units.a101));
      await untilText(page.getByTestId('onbehalf-renter'), 'Ahmed Hassan', 'Tenant from the live contract');
      await at(22);
      await pointAt(page.getByTestId('onbehalf-renter'));
      await at(30);
      await page.getByTestId('onbehalf-type').selectOption('PARKING_SPOT');
      await page.getByTestId('onbehalf-resource').selectOption(seed.parkingSpotId);
      await page.getByTestId('onbehalf-start').fill(TODAY);
      await page.getByTestId('onbehalf-end').fill(END);
      const quote = page.getByTestId('onbehalf-quote');
      await untilText(quote, '500', 'Quote for one month');
      await at(42);
      await pointAt(quote);
      await at(55);
      await page.getByTestId('onbehalf-submit').click();
      await dialog.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await untilText(await bookingRow(page), 'Approved', 'Booked and approved');
    }, { weight: WEIGHTS[1] }),
  stepScene('A journal voucher',
    'Dr Rent Receivable (Ahmed) 500, Cr Additional Parking 500.',
    async (page) => {
      const at = wordClock(page);
      await goto(page, '/en/dashboard/finance/journals');
      const jv = journalRow(page, 'JV-26/1');
      await jv.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(jv, `Parking ${SPOT}`, 'Voucher narration');
      await pointAt(jv);
      await at(12);
      await jv.getByRole('link', { name: 'JV-26/1', exact: true }).click();
      await page.waitForURL(/\/finance\/journals\/[0-9a-f-]{36}/, { timeout: navTimeoutMs });
      const lines = page.locator('main table').first().locator('tbody tr');
      const debit = lines.filter({ hasText: 'Rent Receivable' }).first();
      const credit = lines.filter({ hasText: 'Additional Parking' }).first();
      await expectText(debit, '500.00', 'Debit line');
      await expectText(credit, '500.00', 'Credit line');
      await restPointer(page, 1100, 650);
      await at(17);
      await pointAt(debit);
      await at(25);
      await pointAt(credit);
      await at(33);
      await pointAt(page.getByText(`Booking fee - Parking ${SPOT}`).first());
    }, { weight: WEIGHTS[2] }),
  stepScene("On Ahmed's contract",
    'A row collects the 500, like any other payment.',
    async (page) => {
      const at = wordClock(page);
      await goto(page, `/en/dashboard/leases/${ahmedLease}`);
      await page.getByTestId('lease-tab-payments').click();
      const fee = page.locator('[data-testid^="cheque-row-"]').filter({ hasText: `Parking ${SPOT}` }).first();
      await fee.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(fee, '500.00', 'Collection row');
      await centre(fee);
      await pace(page, 800);
      await at(4);
      await pointAt(fee);
    }, { weight: WEIGHTS[3] }),
  stepScene('Released early',
    'Today counts as used; 483.33 is credited and the row drops to 16.67.',
    async (page) => {
      const at = wordClock(page);
      await goto(page, '/en/dashboard/bookings');
      const row = await bookingRow(page);
      await at(10);
      await row.click();
      const release = page.getByRole('button', { name: 'Release', exact: true });
      await release.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pointAt(release);
      await at(15);
      page.once('dialog', (d) => d.accept());
      const [res] = await Promise.all([
        page.waitForResponse((r) => r.url().includes('/release') && r.request().method() === 'POST', { timeout: navTimeoutMs }),
        release.click(),
      ]);
      if (!res.ok()) throw new Error(`Release refused: HTTP ${res.status()}`);
      await pace(page, 1200);
      await at(27);
      await goto(page, '/en/dashboard/finance/journals');
      const credit = journalRow(page, 'JV-26/2');
      await credit.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(credit, '483.33', 'Credit voucher');
      await pointAt(credit);
      await at(43);
      await goto(page, `/en/dashboard/leases/${ahmedLease}`);
      await page.getByTestId('lease-tab-payments').click();
      const fee = page.locator('[data-testid^="cheque-row-"]').filter({ hasText: `Parking ${SPOT}` }).filter({ hasText: '16.67' }).first();
      await fee.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await centre(fee);
      await pace(page, 800);
      await pointAt(fee);
    }, { weight: WEIGHTS[4] }),
  {
    role: 'renter:Fatima Al Zaabi',
    title: 'The tenant sees the fee first',
    body: 'Fitness Centre, AED 30 a day: shown before she submits.',
    weight: WEIGHTS[5],
    verifyTenantContext: false,
    run: async (page) => {
      await goto(page, '/en/dashboard/renter-portal/facilities');
      const at = wordClock(page);
      const fee = page.getByTestId(`amenity-fee-${seed.amenityId}`);
      await untilText(fee, 'AED 30', 'Per-day fee label');
      await restPointer(page, 900, 300);
      await at(15);
      await pointAt(fee);
      await at(22);
      const card = page.locator('div').filter({ has: fee }).filter({ has: page.getByRole('button', { name: 'Request', exact: true }) }).last();
      await card.getByRole('button', { name: 'Request', exact: true }).click();
      const start = page.getByTestId('request-start-date');
      await start.waitFor({ state: 'visible', timeout: navTimeoutMs });
      const from = plusDays(TODAY, 3);
      await start.fill(from);
      await untilText(page.getByTestId('request-quote-total'), '30.00', 'One day at 30');
      await at(28);
      await pointAt(page.getByTestId('request-quote'));
      await at(34);
      await pointAt(page.getByRole('button', { name: 'Submit Request', exact: true }));
    },
  },
];

export default { role: 'tenantAdmin', anchored: true, scenes };
