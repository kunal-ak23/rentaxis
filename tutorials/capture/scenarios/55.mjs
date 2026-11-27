// Tutorial 55 — Tenant rating (premium, PR #467).
// Oasis Crest, snapshot `pre55` (tutorials/seed/seed_rating_55.py: RENTER_RATING on, ratings stored):
// Fatima Al Zaabi C 60 (70, five on-time cheques +5, the April bounce −15), Ahmed Hassan B 75, Rajesh
// Kumar B 81 (eleven on-time cheques). The Company Admin opens Tenant ratings, filters by grade, opens
// Fatima's details, then adds +5 to Rajesh ("Referred a new tenant"): A 86, with "by Noura Al Suwaidi".
// Renewal season (December 2026) shows the three badges, Rajesh's now A 86.
//
// The adjustment is stored and cannot be removed without a second, visible action: proof and capture
// start from pre55.
import { navTimeoutMs, seed } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { narrationDurationSeconds } from '../lib/narration.mjs';
import { goto } from '../lib/page.mjs';
import { expectText, pace, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const fatima = seed.renterIds?.fatima;
const rajesh = seed.renterIds?.rajesh;
if (!fatima || !rajesh) throw new Error('Seed manifest has no renter ids: run recording-stack.sh seed.');
const REASON = 'Referred a new tenant';

// ── narration-paced cues: `at(n)` waits until n words into the scene's narration ─
const WORDS = [74, 19, 75, 44, 48];
const secondsPerWord = (narrationDurationSeconds() + 2) / WORDS.reduce((a, b) => a + b, 0);
const wordClock = (page) => {
  const at = sceneClock(page);
  return (words) => at(words * secondsPerWord);
};

/** Waits for `locator` to show `text` (async-loaded), then proves it. */
async function untilText(locator, text, what) {
  await locator.filter({ hasText: text }).first().waitFor({ state: 'visible', timeout: navTimeoutMs }).catch(() => {});
  await expectText(locator, text, what);
}
const panel = (page) => page.getByTestId('rating-panel');
const eventRow = (page, text) => panel(page).locator('li').filter({ hasText: text });

const scenes = [
  roleRouteScene('tenantAdmin', '/en/dashboard/renter-ratings', 'Tenant rating',
    'A score from 0 to 100 and a grade, from the last two years of history.', {
    weight: WORDS[0],
    afterNavigation: async (page) => {
      const at = wordClock(page);
      const table = page.getByTestId('rating-table');
      await table.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await untilText(page.getByTestId(`rating-row-${fatima}`), '60', 'Fatima stored score');
      await untilText(page.getByTestId(`rating-row-${rajesh}`), '81', 'Rajesh stored score');
      await restPointer(page, 900, 600);
      await at(30);
      await pointAt(page.getByTestId('sidebar-renter-ratings'));
      await at(42);
      await pointAt(page.getByTestId('rating-grade-A'));
      await at(49);
      await pointAt(page.getByTestId('rating-grade-B'));
      await at(55);
      await pointAt(page.getByTestId('rating-grade-C'));
      await at(61);
      await pointAt(page.getByTestId('rating-grade-D'));
      await at(68);
      await pointAt(page.getByTestId('rating-grade-NEW'));
    },
  }),
  stepScene('Filter, search, export',
    'By grade or property, by name, by score.',
    async (page) => {
      const at = wordClock(page);
      await page.getByTestId('rating-grade-C').click();
      await page.getByTestId(`rating-row-${rajesh}`).waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await expectText(page.getByTestId(`rating-row-${fatima}`), 'Fatima Al Zaabi', 'Grade C filter');
      await at(5);
      await page.getByTestId('rating-grade-ALL').click();
      await page.getByTestId(`rating-row-${rajesh}`).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pointAt(page.getByTestId('rating-property-filter'));
      await at(9);
      await pointAt(page.getByTestId('rating-search'));
      await at(12);
      await pointAt(page.getByTestId('rating-sort-score'));
      await at(16);
      await pointAt(page.getByTestId('rating-export'));
    }, { weight: WORDS[1] }),
  stepScene('Why a C',
    'Starts at 70: on-time cheques +1 each, a bounced cheque −15. Fatima: 60.',
    async (page) => {
      const at = wordClock(page);
      await page.getByTestId(`rating-badge-${fatima}`).click();
      await panel(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await untilText(panel(page), 'Every tenant starts at 70', 'Baseline');
      await expectText(eventRow(page, 'Bounced cheque'), '−15.0', 'Bounce deduction');
      if (await eventRow(page, 'On-time cheque').count() !== 5) throw new Error('Fatima should have five on-time cheques');
      await restPointer(page, 1100, 400);
      await at(7);
      await pointAt(panel(page).getByText('Every tenant starts at 70'));
      await at(14);
      await pointAt(eventRow(page, 'On-time cheque').first());
      await at(28);
      await pointAt(eventRow(page, 'On-time cheque').nth(2).getByText('+1'));
      await at(37);
      await pointAt(eventRow(page, 'Bounced cheque'));
      await at(45);
      await pointAt(panel(page).getByText('Fair', { exact: true }));
      await at(66);
      await pointAt(eventRow(page, 'Bounced cheque').getByRole('link', { name: 'Contract' }));
    }, { weight: WORDS[2] }),
  stepScene('A manual adjustment',
    'Company Admin only, −30 to +30, reason required. Rajesh: 81 → 86, an A.',
    async (page) => {
      const at = wordClock(page);
      await panel(page).getByRole('button', { name: 'Close' }).first().click();
      await panel(page).waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await page.getByTestId(`rating-badge-${rajesh}`).click();
      await untilText(panel(page), 'Good', 'Rajesh B');
      const form = page.getByTestId('rating-adjust-form');
      await form.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await pace(page, 700);
      await at(8);
      await pointAt(form);
      await at(24);
      await form.getByLabel('Points (-30 to 30)').fill('5');
      await form.getByLabel('Reason').fill(REASON);
      await at(30);
      await form.getByRole('button', { name: 'Add adjustment' }).click();
      await untilText(panel(page), 'Excellent', 'Rajesh A after the adjustment');
      await expectText(panel(page).locator('span').filter({ hasText: /^A$/ }).first().locator('..'), '86', 'Score 86');
      const adj = eventRow(page, REASON);
      await expectText(adj, 'Noura Al Suwaidi', 'Who added it');
      await adj.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await pace(page, 600);
      await at(34);
      await pointAt(panel(page).getByText('Excellent', { exact: true }));
      await at(40);
      await pointAt(adj.getByTestId('rating-adjustment-by'));
    }, { weight: WORDS[3] }),
  stepScene('Where it shows',
    'Renewal season, the contract and the tenant profile. Tenants never see it.',
    async (page) => {
      const at = wordClock(page);
      await goto(page, '/en/dashboard/leases/renewals');
      const rows = page.locator('[data-testid^="renewal-row-"]');
      const badge = rows.getByTestId(`rating-badge-${rajesh}`);
      await untilText(badge, '86', 'Renewal queue shows the new score');
      await restPointer(page, 900, 300);
      await at(4);
      await pointAt(badge);
      await at(10);
      await pointAt(rows.getByTestId(`rating-badge-${fatima}`));
      await at(22);
      await pointAt(rows.filter({ has: page.getByTestId(`rating-badge-${rajesh}`) }).getByText('No renewal yet'));
      await at(34);
      await pointAt(badge);
    }, { weight: WORDS[4] }),
];

export default { role: 'tenantAdmin', anchored: true, scenes };
