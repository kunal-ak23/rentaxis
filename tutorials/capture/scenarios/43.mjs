// Tutorial 43 — Renewals, rent-free periods and unit transfers in the books (Palm
// Ridge Properties, the Company Admin Karim Saleh; renewing needs that role).
// Omar Haddad's R-101 is renewed on camera (+5% to 75,600, deposit carried, a
// 525 Renewal Fee) into a draft; Priya Raman's R-204 is the posted result (rent
// revised 64,000 → 67,200, the Renewal Fee line, JV-26/2 moving the 3,200
// deposit); Sahara's BC-302 rent-free February (concession 7,364.38, rent line
// 88,635.62, VAT 4,431.78); Mohammed Al Rashid's R-202 → R-203 transfer preview
// (earned 46,435.07, paid ahead 13,264.93, deposit 3,900, gap 0), not confirmed.
// The renewal writes a draft, so the proof and the capture restore snapshot pre43.
//
// Weights are seconds of narration per scene (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { palmLease } from '../lib/fixtures.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

const omar = palmLease('omar');
const priya = palmLease('priya-renewal');
const sahara = palmLease('sahara');
const mohammed = palmLease('mohammed');

const scenes = [
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${omar}`, 'A renewal',
    'Omar Haddad, R-101: plus 5 percent, deposit carried forward, a Renewal Fee.', {
    weight: 39.1,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await expectText(page.getByTestId('lease-status'), 'Active', 'Contract status');
      await restPointer(page, 1200, 620);
      await at(9.2);
      await pointAt(page.getByText('Omar Haddad', { exact: true }).first());
      await at(13.2);
      await pointAt(page.getByText('30/09/2026', { exact: true }).first());
      await at(15.2);
      await page.getByTestId('lease-renew').click();
      const start = page.getByTestId('renew-start-date');
      await start.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(16.6);
      await pointAt(start);
      await at(19.2);
      await pointAt(page.getByTestId('renew-end-date'));
      await at(21.6);
      await page.getByTestId('renew-mode-PERCENT').click();
      await at(22.8);
      await page.getByTestId('renew-percent').fill('5');
      const preview = page.getByTestId('renew-rent-preview');
      await expectText(preview, '75,600.00', 'Rent preview');
      await at(24.4);
      await pointAt(preview);
      await at(27.0);
      await pointAt(page.getByTestId('renew-carry-deposit'));
      await at(28.6);
      await page.getByTestId('renew-add-charge').click();
      await page.getByTestId('renew-extra-type-0').selectOption({ label: 'Renewal Fee' });
      await page.getByTestId('renew-extra-amount-0').fill('525');
      await pointAt(page.getByTestId('renew-extra-amount-0'));
      await at(31.0);
      await pointAt(page.getByTestId('renew-lease-confirm'));
      await page.getByTestId('renew-lease-confirm').click();
      await page.waitForURL((url) => !url.pathname.endsWith(omar), { timeout: navTimeoutMs });
      await expectText(page.getByTestId('lease-status'), 'Draft', 'Renewal status');
      await expectText(page.getByTestId('lease-renewal-revised'), '75,600.00', 'Renewal header');
      await restPointer(page, 1200, 620);
      await at(33.0);
      await pointAt(page.getByTestId('lease-status'));
      await at(36.4);
      await pointAt(page.getByTestId('lease-post'));
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${priya}`, 'The finished renewal',
    'Priya Raman, R-204: the revised rent, the Renewal Fee and the deposit JV.', {
    weight: 30.2,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const revised = page.getByTestId('lease-renewal-revised');
      await expectText(revised, '67,200.00', 'Rent revised');
      await expectText(page.getByTestId('lease-line-row-1'), 'Renewal Fee', 'Renewal Fee line');
      await restPointer(page, 1200, 620);
      await at(3.0);
      await pointAt(revised);
      await at(8.4);
      await pointAt(page.getByTestId('lease-renewed-from'));
      await at(11.5);
      await pointAt(page.getByTestId('lease-line-row-1'));
      await at(13.4);
      await pointAt(page.getByTestId('lease-line-row-1').getByText('Income when charged', { exact: true }));
      await at(17.0);
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('cheque-grid').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('lease-section-toggle-journals').click();
      const jv = page.getByTestId('lease-journals-tab').locator('table').first()
        .locator('tr').filter({ hasText: 'JV-26/2' }).first();
      await jv.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(jv, 'Security deposit carried forward', 'Deposit JV');
      await expectText(jv, '3,200.00', 'Deposit JV amount');
      await at(18.6);
      await pointAt(jv);
      await at(26.4);
      await pointAt(jv.getByText('3,200.00', { exact: true }));
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${sahara}`, 'A rent-free period',
    'Sahara Design Studio, BC-302: February free; the concession comes off the rent line.', {
    weight: 22.1,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const free = page.getByTestId('rent-free-row');
      await expectText(free, '7,364.38', 'Concession');
      await expectText(page.getByTestId('lease-line-net-1'), '88,635.62', 'Rent after concession');
      await expectText(page.getByTestId('lease-line-row-1'), '4,431.78', 'VAT');
      await restPointer(page, 1200, 620);
      await at(2.0);
      await pointAt(page.getByText('Sahara Design Studio LLC', { exact: true }).first());
      await at(5.4);
      await pointAt(free);
      await at(9.4);
      await pointAt(page.getByTestId('lease-line-net-1'));
      await at(13.0);
      await pointAt(page.getByTestId('lease-line-row-1').getByText('4,431.78', { exact: true }));
      await at(17.2);
      await pointAt(page.getByTestId('rent-free-card').getByText(/Income is spread evenly/).first());
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${mohammed}`, 'A unit transfer',
    'Mohammed Al Rashid, R-202 to R-203: the preview, before anything is posted.', {
    weight: 34.9,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await expectText(page.getByTestId('lease-status'), 'Active', 'Contract status');
      await restPointer(page, 1200, 620);
      await at(2.4);
      await pointAt(page.getByText('Mohammed Al Rashid', { exact: true }).first());
      await at(4.4);
      await page.getByTestId('lease-more-actions').click();
      await pointAt(page.getByTestId('lease-transfer'));
      await page.getByTestId('lease-transfer').click();
      const moveDate = page.getByTestId('transfer-move-date');
      await moveDate.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(6.4);
      await moveDate.fill('2026-09-30');
      await pointAt(moveDate);
      await at(8.0);
      await page.getByTestId('transfer-unit').click();
      await page.getByRole('option', { name: /R-203/ }).first().click();
      const summary = page.getByTestId('transfer-summary');
      await summary.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('transfer-balance'), '13,264.93', 'Paid ahead');
      await expectText(summary, '46,435.07', 'Earned on the current unit');
      await expectText(summary, '3,900.00', 'Deposit carried');
      await expectText(page.getByTestId('transfer-gap'), '0.00', 'Gap');
      await at(10.4);
      await pointAt(page.getByTestId('transfer-cheques'));
      const first = page.getByTestId('transfer-cheques').locator('li').first();
      await at(13.6);
      await pointAt(first.locator('[data-testid^="transfer-carry-"]'));
      await at(14.8);
      await pointAt(first.locator('[data-testid^="transfer-keep-"]'));
      await at(15.8);
      await pointAt(first.locator('[data-testid^="transfer-return-"]'));
      await at(17.0);
      await pointAt(summary.getByText('46,435.07', { exact: true }));
      await at(21.0);
      await pointAt(page.getByTestId('transfer-balance'));
      await at(25.4);
      await pointAt(summary.getByText('3,900.00', { exact: true }));
      await at(28.6);
      await pointAt(page.getByTestId('transfer-gap'));
      await at(31.6);
      await pointAt(page.getByTestId('transfer-confirm'));
    },
  }),
];

export default {
  role: 'tenantAdmin',
  anchored: true,
  warmup: [{ role: 'tenantAdmin', path: `/en/dashboard/leases/${priya}?tab=payments` }],
  scenes,
};
