// Tutorial 44 — Supplier bills, payment runs and issued cheques (Palm Ridge
// Properties, the Accountant Rania Khoury). BlueWave Cleaning's September bill
// (BW-2609-09, 2,400 + 120 VAT) is posted on camera as PISR-26/13 (Dr Cleaning
// Services 2,400, Dr Input VAT 120 / Cr BlueWave 2,520); Payables aging shows it
// Current with a zero tie-out; a transfer run on 30 September pays it (one BPV,
// Dr BlueWave / Cr Emirates Islamic – Residences); Issued cheques shows 004501
// presented on 12 September (BPC-26/1) and 004521 outstanding, equal to the PDC
// Payable balance.
//
// Posting cannot be undone: the proof and the capture each start from snapshot
// pre44 (the Palm Ridge base on the current schema).
//
// Weights are seconds of narration per scene (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const INVOICE = 'BW-2609-09';
const journalLine = (page, account, amount) => page.locator('main table').first().locator('tbody tr')
  .filter({ hasText: account }).filter({ hasText: amount }).first();
const voucherRow = (page, text) => page.getByTestId('vouchers-table').locator('tr').filter({ hasText: text }).first();

const scenes = [
  roleRouteScene('accountant', '/en/dashboard/finance/vouchers', 'Receipt / Payment Vouchers',
    'Every supplier bill and every payment of the year, each with its journal.', {
    weight: 13.44,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('vouchers-table').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(voucherRow(page, 'PISR-25/1'), 'Purchase Invoice', 'First bill');
      await restPointer(page, 1200, 640);
      await at(8.6);
      await pointAt(voucherRow(page, 'PISR-25/2'));
      await at(10.8);
      await pointAt(voucherRow(page, 'BPV-25/1'));
    },
  }),
  stepScene('A supplier bill',
    'BlueWave Cleaning, September: 2,400 plus 5% VAT, due on their 30-day terms.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('new-purchase-invoice').click();
      await page.getByTestId('voucher-form').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('doc-date').fill('2026-09-30');
      await page.getByTestId('vendor').selectOption({ label: 'BlueWave Cleaning LLC' });
      await page.getByTestId('property').selectOption({ label: 'Palm Ridge Residences' });
      await at(5.6);
      await page.getByTestId('invoice-number').fill(INVOICE);
      await page.getByTestId('supplier-invoice-date').fill('2026-09-25');
      await page.getByTestId('narration').fill('Common-area cleaning September 2026');
      await at(8.4);
      await pointAt(page.getByTestId('due-date'));
      await expectText(page.getByTestId('vendor-terms'), '30 days', 'Vendor terms');
      const account = page.getByTestId('voucher-line-0').getByLabel('Account');
      await account.click();
      await account.fill('Cleaning');
      await page.getByTestId('voucher-line-0').locator('li button').filter({ hasText: 'D-01-102' }).click();
      await page.getByTestId('line-description-0').fill('Common-area cleaning, September');
      await page.getByTestId('line-amount-0').fill('2400');
      await page.getByTestId('line-vat-rate-0').selectOption({ label: '5%' });
      await expectText(page.getByTestId('vat-total'), '120.00', 'VAT total');
      await expectText(page.getByTestId('gross-total'), '2,520.00', 'Invoice total');
      await pointAt(page.getByTestId('gross-total'));
      await at(17.8);
      await page.getByTestId('post-voucher').click();
      await page.getByTestId('confirm-post').click();
      await page.getByTestId('vouchers-table').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('filter-from').fill('2026-09-01');
      await voucherRow(page, 'PISR-26/13').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await voucherRow(page, 'PISR-26/13').getByRole('link', { name: 'View journal' }).click();
      await journalLine(page, 'BlueWave', '2,520.00').waitFor({ state: 'visible', timeout: navTimeoutMs });
    }, { weight: 21.75 }),
  stepScene('The purchase invoice journal',
    'PISR: Dr Cleaning Services 2,400 and Input VAT 120, Cr BlueWave 2,520.',
    async (page) => {
      const at = sceneClock(page);
      await expectText(journalLine(page, 'Input VAT', '120.00'), '120.00', 'Input VAT line');
      await restPointer(page, 1200, 700);
      await at(1.4);
      await pointAt(journalLine(page, 'Cleaning Services', '2,400.00'));
      await at(3.4);
      await pointAt(journalLine(page, 'Input VAT', '120.00'));
      await at(5.8);
      await pointAt(journalLine(page, 'BlueWave', '2,520.00'));
    }, { weight: 13.45 }),
  stepScene('Payables aging',
    'Current, because it is not due yet; open items tie to the vendor ledger (Δ 0.00).',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-payables-aging').or(page.getByRole('link', { name: 'Payables aging', exact: true })).first().click();
      const row = page.getByTestId('aging-table').locator('tr').filter({ hasText: 'BlueWave Cleaning LLC' }).first();
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row, '2,520.00', 'BlueWave open');
      await restPointer(page, 1200, 640);
      await at(2.4);
      await pointAt(row.locator('td').nth(2));
      await at(7.6);
      await pointAt(row.locator('[data-testid^="aging-delta-"]'));
    }, { weight: 15.02 }),
  stepScene('A payment run',
    'Due by 31 October: tick the bill, pay by transfer from the Residences account.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByRole('link', { name: 'Payment runs', exact: true }).first().click();
      await page.getByTestId('new-run').click();
      await page.getByTestId('run-due-before').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(4.0);
      await page.getByTestId('run-due-before').fill('2026-10-31');
      await page.getByTestId('run-filter').click();
      await page.getByTestId(`run-pick-${INVOICE}`).check();
      await at(8.4);
      await page.getByTestId('run-payment-date').fill('2026-09-30');
      await page.getByTestId('run-method').selectOption({ label: 'Transfer' });
      await page.getByTestId('run-account').selectOption({ label: '100005 Emirates Islamic - Palm Ridge Residences' });
      await page.getByTestId('run-narration').fill('Supplier payments September 2026');
      await expectText(page.getByTestId('run-selected-total'), '2,520.00', 'Selected total');
    }, { weight: 13.84 }),
  stepScene('Preview, then post',
    'The voucher before anything is written: BlueWave debited, the bank credited.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('run-save-preview').click();
      await page.getByTestId('run-preview').waitFor({ state: 'visible', timeout: navTimeoutMs });
      const journal = page.getByTestId('run-journal-BlueWave Cleaning LLC');
      await expectText(journal, '2,520.00', 'Run journal');
      await restPointer(page, 1200, 640);
      await at(2.0);
      await pointAt(journal);
      await at(6.4);
      await pointAt(page.getByTestId('problem-NO_IBAN'));
      await at(13.0);
      await page.getByTestId('run-post').click();
      await page.getByRole('button', { name: 'Post run', exact: true }).last().click();
      await page.getByTestId('run-items').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('run-items'), 'BPV-26/12', 'Payment voucher');
      await pointAt(page.getByTestId('run-items').getByText('BPV-26/12'));
    }, { weight: 19.38 }),
  stepScene('Issued cheques',
    'Presented: Dr PDC Payable, Cr bank. Outstanding = the PDC Payable balance.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByRole('link', { name: 'Issued cheques', exact: true }).first().click();
      await page.getByTestId('cheque-register').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('cheque-status').selectOption({ index: 0 });
      await page.getByTestId('cheque-filter').click();
      const presented = page.getByTestId('cheque-row-004501');
      await presented.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(presented, 'BPC-26/1', 'Presented cheque');
      await expectText(page.getByTestId('cheque-difference'), '0.00', 'Tie-out');
      await restPointer(page, 1200, 640);
      await at(11.5);
      await pointAt(presented);
      await at(15.0);
      await pointAt(presented.getByText('BPC-26/1', { exact: false }));
      await at(25.7);
      await pointAt(page.getByTestId('cheque-row-004521'));
      await at(29.4);
      await pointAt(page.getByTestId('cheque-outstanding'));
      await at(31.0);
      await pointAt(page.getByTestId('cheque-difference'));
    }, { weight: 32.82 }),
];

export default {
  role: 'accountant',
  anchored: true,
  warmup: [{ role: 'accountant', path: '/en/dashboard/finance/payables/issued-cheques' }],
  scenes,
};
