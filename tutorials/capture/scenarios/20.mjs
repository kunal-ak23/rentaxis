// Tutorial 20 — Financial reports: profit and loss, balance sheet, owner
// statement and aging (Palm Ridge Properties, the Accountant Rania Khoury).
// Profit & Loss for 01/09/2025–31/08/2026 (rental income 567,333.63, bad debts
// recovered 5,000 as other income, bad debts written off 11,326.03, NOI
// 494,787.54, ties to the ledger); the Property Profit Report for August 2026
// against July, per building; the Balance Sheet at 31/08/2026 (PDC receivable
// 165,985.00, advance rent 214,145.83, security deposits 42,200.00, A = L + E
// in every column); the Owner Statement for Palm Ridge Residences, August 2026;
// Payables aging at 31/08/2026 (BlueWave 2,520.00, Δ 0.00) and the aging strip
// on Cheque / Cash Collection.
//
// Read-only: nothing is posted, so no snapshot is needed.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const row = (page, text) => page.locator('main tr').filter({ hasText: text }).first();
const apply = (page) => page.getByRole('button', { name: 'Apply', exact: true }).first().click();
const centre = (locator) => locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));

const scenes = [
  roleRouteScene('accountant', '/en/dashboard/finance/reports/company-pl', 'Profit & Loss',
    'Sep 2025 – Aug 2026: income as earned, bad debts written off and recovered, NOI 494,787.54.', {
    weight: 42.12,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const dates = page.locator('main input[type=date]');
      await dates.first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 560);
      await at(8.6);
      await dates.nth(0).fill('2025-09-01');
      await at(11.4);
      await dates.nth(1).fill('2026-08-31');
      await apply(page);
      const rental = row(page, 'Rental income');
      await expectText(rental, '567,333.63', 'Rental income');
      await expectText(row(page, 'Bad debts recovered'), '5,000.00', 'Recovery');
      await expectText(row(page, 'Bad debts written off'), '11,326.03', 'Write-off');
      await expectText(row(page, 'NOI'), '494,787.54', 'NOI');
      await expectText(page.getByTestId('check-badge'), 'Ties to the ledger', 'Ledger tie');
      await restPointer(page, 1300, 560);
      await at(15.9);
      await pointAt(rental);
      await at(21.0);
      await pointAt(row(page, 'Admin fee'));
      await at(23.6);
      await pointAt(row(page, 'Cheque return penalty'));
      await at(26.3);
      await pointAt(row(page, 'Bad debts recovered'));
      await at(30.6);
      await pointAt(row(page, 'Bad debts written off'));
      await at(34.2);
      await pointAt(row(page, 'NOI'));
      await at(38.6);
      await pointAt(page.getByTestId('check-badge'));
    },
  }),
  stepScene('Property Profit Report',
    'August 2026 against July, building by building: income, expenses and NOI.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-property-pl').click();
      const anchor = page.locator('#pnl-anchor');
      await anchor.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(5.4);
      await anchor.fill('2026-08');
      await page.locator('#pnl-compare').selectOption({ label: 'Previous period' });
      await apply(page);
      await page.getByText('Compared with 01/07/2026 – 31/07/2026', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row(page, 'Rental income'), '36,010.96', 'Residences rental income');
      const head = page.locator('main thead').first();
      await restPointer(page, 1300, 640);
      await at(9.8);
      await pointAt(head.getByText('Palm Ridge Business Centre'));
      await at(11.4);
      await pointAt(head.getByText('Palm Ridge Residences'));
      await at(13.9);
      await pointAt(row(page, 'NOI'));
      await at(15.6);
      await pointAt(page.getByText('Compared with 01/07/2026 – 31/07/2026', { exact: true }));
    }, { weight: 18.23 }),
  stepScene('Balance Sheet',
    'At 31/08/2026: the paper in the drawer, rent billed not earned, deposits held; A = L + E.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-balance-sheet').click();
      const asAt = page.locator('main input[type=date]').first();
      await asAt.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(1.8);
      await asAt.fill('2026-08-31');
      await apply(page);
      const pdc = row(page, 'PDC receivable');
      await expectText(pdc, '165,985.00', 'PDC receivable');
      await expectText(row(page, 'Advance rent'), '214,145.83', 'Advance rent');
      await expectText(row(page, 'Security deposit'), '42,200.00', 'Security deposits');
      await expectText(page.getByTestId('bs-check-badge'), 'Balanced', 'A = L + E');
      await restPointer(page, 1300, 640);
      await at(4.3);
      await pointAt(pdc);
      await at(8.6);
      await pointAt(row(page, 'Advance rent'));
      await at(12.9);
      await pointAt(row(page, 'Security deposit'));
      await at(17.1);
      await pointAt(row(page, 'Retained Earnings'));
      await at(20.0);
      await pointAt(row(page, 'Current year result'));
      await at(23.2);
      await pointAt(row(page, 'Check: assets'));
      await at(24.6);
      await pointAt(page.getByTestId('bs-check-badge'));
    }, { weight: 26.48 }),
  stepScene('Owner Statement',
    'One building, one month: NOI, instalments, collected, outstanding, deposits, expenses — PDF in English or Arabic.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-property-statement').click();
      const anchor = page.locator('#pnl-anchor');
      await anchor.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await anchor.fill('2026-08');
      await page.locator('#pnl-property').selectOption({ label: 'Palm Ridge Residences' });
      await apply(page);
      const noi = page.getByText('1. Income, expenses and NOI', { exact: true });
      await noi.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(4.4);
      await pointAt(noi);
      await at(6.8);
      await pointAt(page.getByText('2. Instalments due', { exact: true }));
      await at(9.4);
      await pointAt(page.getByText('3. Collected', { exact: true }));
      await at(11.0);
      await pointAt(page.getByText('4. Outstanding', { exact: true }));
      await at(12.8);
      await pointAt(page.getByText('5. Deposits held', { exact: true }));
      await at(14.2);
      await pointAt(page.getByText('6. Expenses incurred', { exact: true }));
      await at(15.4);
      await pointAt(page.getByRole('link', { name: 'PDF (Arabic)' }).first());
    }, { weight: 16.94 }),
  stepScene('Aging',
    'Payables aging at 31/08/2026, tied to each supplier ledger; the tenant aging strip on the register.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-payables-aging').click();
      const asOf = page.getByTestId('aging-as-of');
      await asOf.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await asOf.fill('2026-08-31');
      await page.getByTestId('aging-apply').click();
      const bw = page.locator('[data-testid^="aging-row-"]').filter({ hasText: 'BlueWave Cleaning LLC' }).first();
      await expectText(bw, '2,520.00', 'BlueWave open');
      await restPointer(page, 1300, 640);
      await at(1.4);
      await pointAt(bw);
      await at(8.6);
      await pointAt(page.locator('[data-testid^="aging-delta-"]').first());
      await at(11.4);
      await page.goto('/en/dashboard/collections?tab=all');
      const strip = page.getByTestId('cheque-aging-strip');
      await strip.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(13.0);
      await pointAt(strip);
      await at(16.2);
      await pointAt(page.getByTestId('cheque-aging-1-30'));
    }, { weight: 20.84 }),
];

export default {
  role: 'accountant',
  anchored: true,
  warmup: [{ role: 'accountant', path: '/en/dashboard/finance/reports/company-pl' }],
  scenes,
};
