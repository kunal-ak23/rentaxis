// Tutorial 42 — Termination, settlement and deposit refunds in the books (Palm
// Ridge Properties, the Accountant Rania Khoury). Grace Okafor's R-203 contract,
// terminated by the seed on 31 May 2026: the returned July cheque and the
// reversal of its PDR (17,500), the TCR (Dr Advance Rent / Cr Rent Receivable
// 26,082.19), the settlement statement (earned 43,917.81, received 56,000,
// receivable -8,582.19, deposit 3,500, cleaning 750, refund 11,332.19 paid), the
// STL journal and the BPV that paid the refund. Read-only.
//
// Weights are seconds of narration per scene (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { palmLease } from '../lib/fixtures.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const grace = palmLease('grace');
const lines = (page) => page.locator('main table').first().locator('tbody tr');
const line = (page, account, amount) => lines(page).filter({ hasText: account }).filter({ hasText: amount }).first();

const scenes = [
  roleRouteScene('accountant', `/en/dashboard/leases/${grace}`, 'A contract that ended early',
    'Grace Okafor, R-203: terminated on 31 May 2026, settled, and the deposit refunded.', {
    weight: 30.7,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await expectText(page.getByTestId('lease-status'), 'Closed', 'Contract status');
      const summary = page.getByTestId('lease-settlement-summary');
      await expectText(summary, '11,332.19', 'Settlement summary');
      await restPointer(page, 1200, 620);
      await at(9.0);
      await pointAt(page.getByText('Grace Okafor', { exact: true }).first());
      await at(12.2);
      await pointAt(page.getByTestId('lease-line-row-1'));
      await at(20.4);
      await pointAt(page.getByTestId('lease-status'));
      await at(22.4);
      await pointAt(summary.getByText('AED 3,500.00', { exact: true }));
      await at(25.0);
      await pointAt(summary.getByText('AED 750.00', { exact: true }));
      await at(27.4);
      await pointAt(summary.getByText('AED 11,332.19', { exact: true }));
    },
  }),
  stepScene('Termination in the ledger',
    'The returned cheque\'s PDR is reversed; TCR takes back the rent never earned.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('cheque-grid').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('cheque-row-4'), 'Returned', 'July cheque');
      await at(2.2);
      await pointAt(page.getByTestId('cheque-row-3'));
      await at(4.4);
      await pointAt(page.getByTestId('cheque-row-4'));
      await at(9.6);
      await page.getByTestId('lease-section-toggle-journals').click();
      const ledger = page.getByTestId('lease-journals-tab').locator('table').nth(1);
      const reversal = ledger.locator('tr').filter({ hasText: 'PDR-26/20' }).first();
      const tcr = ledger.locator('tr').filter({ hasText: 'TCR-26/2' }).first();
      await reversal.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(reversal, '17,500.00', 'PDR reversal');
      await expectText(tcr, '26,082.19', 'TCR');
      await expectText(tcr, '8,582.19 Cr', 'Balance after TCR');
      await at(11.4);
      await pointAt(ledger.getByText('Name :: Rent Receivable - Palm Ridge Residences', { exact: false }).first());
      await at(15.4);
      await pointAt(reversal);
      await at(20.2);
      await pointAt(tcr);
      await at(32.4);
      await pointAt(tcr.getByText('8,582.19 Cr', { exact: true }));
    }, { weight: 39.1 }),
  stepScene('The settlement',
    'Computed from the ledger: earned, received, the deposit held, deductions, the refund.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('lease-settle').click();
      await page.getByTestId('settlement-earned-rent').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('settlement-earned-rent'), '43,917.81', 'Earned rent');
      await expectText(page.getByTestId('settlement-net-refund'), '11,332.19', 'Refund');
      await expectText(page.getByTestId('settlement-refund-outstanding'), '0.00', 'Outstanding');
      await restPointer(page, 1300, 600);
      await at(2.4);
      await pointAt(page.getByTestId('settlement-earned-rent'));
      await at(6.4);
      await pointAt(page.getByTestId('settlement-received'));
      await at(9.0);
      await pointAt(page.getByTestId('settlement-receivable-balance'));
      await at(12.4);
      await pointAt(page.getByTestId('settlement-deposits-held'));
      await at(14.9);
      await pointAt(page.getByTestId('settlement-line-0'));
      await at(18.6);
      await pointAt(page.getByTestId('settlement-net-refund'));
      await at(20.6);
      await pointAt(page.getByTestId('settlement-refund-paid'));
      await at(22.6);
      await pointAt(page.getByTestId('settlement-refund-outstanding'));
    }, { weight: 25.2 }),
  stepScene('The settlement journal',
    'STL releases the deposit, clears the prepaid balance and owes the refund.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('rail-accounting').click();
      const type = page.locator('main select').first();
      await type.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await type.selectOption('STL');
      await page.getByRole('button', { name: 'Apply', exact: true }).click();
      await page.locator('main table').getByRole('link', { name: 'STL-26/2', exact: true }).click();
      await page.getByText('Journal Voucher STL-26/2', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1200, 700);
      await at(2.5);
      await pointAt(line(page, 'Security Deposit', '3,500.00'));
      await at(4.6);
      await pointAt(line(page, 'Rent Receivable', '8,582.19'));
      await at(7.9);
      await pointAt(line(page, 'Maintenance Charges', '750.00'));
      await at(10.4);
      await pointAt(line(page, 'Refunds payable', '11,332.19'));
    }, { weight: 17.8 }),
  stepScene('The refund is paid',
    'BPV: Refunds payable debited, the bank credited.',
    async (page) => {
      const at = sceneClock(page);
      await at(1.0);
      await page.getByTestId('sidebar-journals').click();
      const type = page.locator('main select').first();
      await type.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(4.2);
      await type.selectOption('BPV');
      await page.getByRole('button', { name: 'Apply', exact: true }).click();
      const row = page.locator('main table tbody tr').filter({ hasText: 'Security deposit refund' }).first();
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(5.6);
      await pointAt(row);
      await at(7.0);
      await row.getByRole('link').first().click();
      await line(page, 'Refunds payable', '11,332.19').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1200, 700);
      await at(8.4);
      await pointAt(line(page, 'Refunds payable', '11,332.19'));
      await at(10.4);
      await pointAt(line(page, 'Emirates Islamic - Palm Ridge Residences', '11,332.19'));
    }, { weight: 18.8 }),
];

export default {
  role: 'accountant',
  anchored: true,
  warmup: [{ role: 'accountant', path: `/en/dashboard/leases/${grace}?tab=payments` }],
  scenes,
};
