// Tutorial 41 — VAT per instalment and the VAT return (Palm Ridge Properties,
// the Accountant Rania Khoury). Noor Pharmacy's draft BC-G01 contract (150,000
// + 7,500 VAT, 15,000 deposit) is posted on camera and its TCO read (the VAT
// pair Dr Rent Receivable 7,500 / Cr Output VAT – not yet due); the cheque grid's
// VAT column (1,875 per rent cheque); Meridian Logistics' VAT schedule (four tax
// points, VTP 1,500 each, a tax invoice each); the tax-point panel on Month-end
// recognition (nothing left to declare); the VAT return for the quarter from
// July 2026 (not fileable before 30 September) and the filed April quarter.
//
// Posting cannot be undone: the proof and the capture each start from snapshot
// pre41 (the Palm Ridge base).
//
// Weights are seconds of narration per scene (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { palmLease } from '../lib/fixtures.mjs';
import { expectCount, expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const noor = palmLease('noor');
const meridian = palmLease('meridian');
const lines = (page) => page.locator('main table').first().locator('tbody tr');
const line = (page, account, amount) => lines(page).filter({ hasText: account }).filter({ hasText: amount }).first();

const scenes = [
  roleRouteScene('accountant', `/en/dashboard/leases/${noor}`, 'A commercial contract with VAT',
    'Noor Pharmacy, BC-G01: rent 150,000 plus 5% VAT, and a deposit that carries none.', {
    weight: 17.4,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await expectText(page.getByTestId('lease-status'), 'Draft', 'Contract status');
      await expectText(page.getByTestId('lease-lines-total-vat'), '7,500.00', 'VAT total');
      await expectText(page.getByTestId('lease-lines-contract-value'), '172,500.00', 'Contract value');
      await restPointer(page, 1200, 620);
      await at(9.6);
      await pointAt(page.getByTestId('lease-line-row-1'));
      await at(12.8);
      await pointAt(page.getByTestId('lease-lines-total-vat'));
      await at(15.2);
      await pointAt(page.getByTestId('lease-line-row-0'));
    },
  }),
  stepScene('Post it',
    'Review before posting, then post.',
    async (page) => {
      await page.getByTestId('lease-post').click();
      await page.getByTestId('post-dry-run-ok').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pointAt(page.getByTestId('post-dry-run-ok'));
      await page.getByTestId('post-lease-confirm').click();
      await page.getByTestId('lease-posting-journal').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('lease-status'), 'Active', 'Posted contract');
    }, { weight: 3.5 }),
  stepScene('The contract journal',
    'VAT waits in Output VAT – not yet due: a contract is not a tax invoice.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('lease-posting-journal').click();
      await page.waitForURL(/\/dashboard\/finance\/journals\//, { timeout: navTimeoutMs });
      await line(page, 'Output VAT', '7,500.00').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(lines(page), 6, 'TCO lines');
      await restPointer(page, 1200, 700);
      await at(2.0);
      await pointAt(line(page, 'Rent Receivable', '15,000.00'));
      await at(3.6);
      await pointAt(line(page, 'Rent Receivable', '150,000.00'));
      await at(5.6);
      await pointAt(line(page, 'Security Deposit', '15,000.00'));
      await at(7.0);
      await pointAt(line(page, 'Advance Rent', '150,000.00'));
      await at(9.4);
      await pointAt(line(page, 'Rent Receivable', '7,500.00'));
      await at(12.6);
      await pointAt(line(page, 'Output VAT', '7,500.00'));
    }, { weight: 23.25 }),
  stepScene('VAT on every instalment',
    'Each rent cheque carries its own VAT; the deposit carries none.',
    async (page) => {
      const at = sceneClock(page);
      await page.goBack();
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('cheque-grid').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('cheque-vat-1'), '1,875.00', 'Rent cheque VAT');
      await expectText(page.getByTestId('cheque-vat-0'), '0.00', 'Deposit VAT');
      await at(3.2);
      await pointAt(page.getByTestId('cheque-row-1'));
      await at(5.6);
      await pointAt(page.getByTestId('cheque-vat-1'));
      await at(6.6);
      await pointAt(page.getByTestId('cheque-vat-4'));
      await at(7.6);
      await pointAt(page.getByTestId('cheque-vat-0'));
      await at(9.8);
      await pointAt(page.getByTestId('cheque-grid-vat-total'));
    }, { weight: 12.0 }),
  roleRouteScene('accountant', `/en/dashboard/leases/${meridian}?tab=payments`, 'Tax points',
    'Each instalment\'s VAT falls due on its tax point, with its own tax invoice.', {
    weight: 28.3,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('lease-section-toggle-vat').click();
      const vat = page.getByTestId('lease-section-vat');
      const rows = vat.locator('table').first().locator('tbody tr');
      await rows.first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(rows.filter({ hasText: 'Declared' }), 4, 'declared tax points');
      await vat.scrollIntoViewIfNeeded();
      await restPointer(page, 1300, 640);
      await at(5.6);
      await pointAt(rows.nth(0));
      await at(7.2);
      await pointAt(rows.nth(1));
      await at(8.4);
      await pointAt(rows.nth(3));
      await at(10.0);
      await pointAt(vat.getByRole('link', { name: 'TI-26/5', exact: true }).first());
      await at(14.2);
      await pointAt(rows.nth(3).locator('td').first());
      await at(20.8);
      await vat.getByRole('link', { name: 'VTP-26/5', exact: true }).first().click();
      await line(page, 'Output VAT on Sales', '1,500.00').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1200, 700);
      await at(22.4);
      await pointAt(line(page, 'not yet due', '1,500.00'));
      await at(25.0);
      await pointAt(line(page, 'Output VAT on Sales', '1,500.00'));
    },
  }),
  stepScene('Declare tax points',
    'The nightly run declares them; you can also run it before closing a period.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-recognition').click();
      const panel = page.getByTestId('vat-run-panel');
      await panel.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await panel.scrollIntoViewIfNeeded();
      await restPointer(page, 1300, 640);
      await at(2.6);
      await pointAt(panel);
      await at(7.0);
      await page.getByTestId('vat-run-preview').click();
      await expectText(page.getByTestId('vat-run-result'), ': 0, VAT 0.00', 'Nothing left to declare');
      await at(8.4);
      await pointAt(page.getByTestId('vat-run-result'));
    }, { weight: 18.3 }),
  stepScene('The VAT return',
    'The quarter in the FTA layout. Box 1b: standard-rated rent in Dubai. Box 9: input VAT.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-vat-return').click();
      const quarter = page.getByTestId('vat-quarter');
      await quarter.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(2.4);
      await quarter.selectOption({ label: '01/07/2026' });
      await page.getByTestId('vat-status').filter({ hasText: 'Not filed' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('vat-check'), 'Output VAT ties to the ledger', 'Output check');
      await restPointer(page, 1300, 640);
      await at(5.2);
      await pointAt(page.getByTestId('vat-box-1b'));
      await at(9.0);
      await pointAt(page.getByTestId('vat-box-9'));
      await at(12.9);
      await pointAt(page.getByTestId('vat-check'));
      await at(17.0);
      await page.getByTestId('vat-drill-1b').click();
      await page.getByText('Meridian Logistics FZ-LLC', { exact: false }).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(18.4);
      await pointAt(page.getByText('Meridian Logistics FZ-LLC', { exact: false }).first());
      await at(20.6);
      await page.keyboard.press('Escape');
      await pointAt(page.getByTestId('vat-status'));
      await at(25.6);
      await quarter.selectOption({ label: '01/04/2026' });
      await page.getByTestId('vat-status').filter({ hasText: 'FTA-DEMO-2026Q2' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(26.8);
      await pointAt(page.getByTestId('vat-status'));
    }, { weight: 33.3 }),
];

export default { role: 'accountant', anchored: true, scenes };
