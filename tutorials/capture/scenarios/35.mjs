// Tutorial 35 — Register, bank and clear cheques (Palm Ridge Properties, the
// Accountant Rania Khoury). The Cheque register filtered to Registered; To
// deposit: Mohammed Al Rashid's matured cheque 880102 (19,500) banked in a
// Deposit Batch (no journal); Hana Yoshida's deposited cheque 770109 (5,500)
// cleared into Emirates Islamic – Palm Ridge Residences, and its CRT read in
// Journal Voucher; the summary tiles and the aging strip. Returned cheques are
// tutorial 40.
//
// Banking and clearing cannot be undone, so the proof and the capture each start
// from snapshot pre35 (the Palm Ridge base).
//
// Weights are seconds of narration per scene (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectCount, expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const rows = (page) => page.locator('[data-testid^="cheque-row-"]:not([data-testid*="-action-"])');
const rowFor = (page, no) => rows(page).filter({ hasText: no }).first();

async function searchRegister(page, text) {
  await page.getByTestId('cheque-search').fill(text);
  await page.getByTestId('cheque-filter-apply').click();
  await rowFor(page, text).waitFor({ state: 'visible', timeout: navTimeoutMs });
  await expectCount(rows(page), 1, `register rows for ${text}`);
}

const scenes = [
  roleRouteScene('accountant', '/en/dashboard/collections?tab=all', 'The cheque register',
    'Every cheque from every posted contract. Registered: recorded, its PDR written, the paper still in the office.', {
    weight: 23.55,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('cheque-summary-tiles').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(10.4);
      await pointAt(rows(page).first());
      await at(13.9);
      await page.getByTestId('cheque-status-filter').selectOption('REGISTERED');
      await page.getByTestId('cheque-filter-apply').click();
      await page.waitForFunction(() => {
        const s = [...document.querySelectorAll('[data-testid^="cheque-status-"]')].filter((e) => !e.matches('select'));
        return s.length > 0 && s.every((e) => /registered/i.test(e.textContent));
      }, null, { timeout: navTimeoutMs });
      await at(16.2);
      await pointAt(page.locator('[data-testid^="cheque-status-"]:not(select)').first());
      await at(20.0);
      await pointAt(page.getByTestId('cheque-summary-registered'));
    },
  }),
  stepScene('Bank a batch',
    'To deposit lists registered cheques whose date has arrived. Banking writes no journal.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('collections-tab-deposit').click();
      const row = page.getByRole('row').filter({ hasText: '880102' });
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row, '19,500.00', 'Cheque amount');
      await at(4.9);
      await pointAt(row.getByText('880102', { exact: true }));
      await at(9.0);
      await row.locator('[data-testid^="collection-select-"]').check();
      await page.getByTestId('collection-deposit-selected').click();
      await page.getByTestId('deposit-batch-total').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(11.3);
      await pointAt(page.getByTestId('deposit-batch-date'));
      await at(12.4);
      await page.getByTestId('deposit-batch-confirm').click();
      await row.waitFor({ state: 'detached', timeout: navTimeoutMs });
      await page.getByTestId('collections-tab-all').click();
      await searchRegister(page, '880102');
      await expectText(rowFor(page, '880102'), 'DEPOSITED', 'Banked cheque');
      await at(13.8);
      await pointAt(rowFor(page, '880102').locator('[data-testid^="cheque-status-"]'));
    }, { weight: 20.94 }),
  stepScene('Clear when the bank confirms',
    'Clearing moves the money into the property\'s bank account.',
    async (page) => {
      const at = sceneClock(page);
      await searchRegister(page, '770109');
      const row = rowFor(page, '770109');
      await expectText(row, 'DEPOSITED', 'Hana\'s cheque before clearing');
      await expectText(row, '5,500.00', 'Hana\'s cheque amount');
      await at(1.2);
      await pointAt(row.getByText('770109', { exact: true }));
      await at(4.3);
      await pointAt(row.locator('[data-testid^="cheque-status-"]'));
      await at(6.9);
      await row.locator('[data-testid^="cheque-row-action-clear-"]').click();
      const into = page.getByTestId('cheque-cleared-into');
      await into.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(into, 'Emirates Islamic - Palm Ridge Residences', 'Cleared into');
      await at(9.6);
      await pointAt(into);
      await at(15.4);
      await page.getByTestId('cheque-clear-confirm').click();
      await row.locator('[data-testid^="cheque-status-"]').filter({ hasText: /cleared/i }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(16.0);
      await pointAt(row.locator('[data-testid^="cheque-status-"]'));
    }, { weight: 19.44 }),
  stepScene('The receipt journal',
    'CRT: debit the bank, credit PDC Receivable.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('rail-accounting').click();
      await page.getByTestId('sidebar-journals').click();
      const type = page.locator('main select').first();
      await type.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(1.0);
      await type.selectOption('CRT');
      await page.getByRole('button', { name: 'Apply', exact: true }).click();
      const first = page.locator('main table tbody tr').first();
      await expectText(first, '5,500.00', 'Newest CRT');
      await at(3.0);
      await first.getByRole('link').first().click();
      const lines = page.locator('main table').first().locator('tbody tr');
      await lines.first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(lines.filter({ hasText: 'Emirates Islamic - Palm Ridge Residences' }).first(), '5,500.00', 'Bank debit');
      await expectText(lines.filter({ hasText: 'PDC Receivable' }).first(), '5,500.00', 'PDC credit');
      await restPointer(page, 1200, 700);
      await at(5.2);
      await pointAt(lines.filter({ hasText: 'Emirates Islamic - Palm Ridge Residences' }).first());
      await at(7.6);
      await pointAt(lines.filter({ hasText: 'PDC Receivable' }).first());
    }, { weight: 14.95 }),
  stepScene('Totals and aging',
    'The tiles total the register; the aging strip sorts what is due by days overdue.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('rail-collection').click();
      await page.getByTestId('nav-panel').getByText('Cheque register', { exact: true }).click();
      await page.getByTestId('cheque-summary-tiles').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(1.5);
      await pointAt(page.getByTestId('cheque-summary-tiles'));
      await at(3.6);
      await pointAt(page.getByTestId('cheque-summary-registered'));
      await at(5.0);
      await pointAt(page.getByTestId('cheque-summary-deposited'));
      await at(6.4);
      await pointAt(page.getByTestId('cheque-summary-clearedThisMonth'));
      await at(8.5);
      await pointAt(page.getByTestId('cheque-aging-strip'));
      await at(10.4);
      await pointAt(page.getByTestId('cheque-aging-1-30'));
      await at(13.5);
      await pointAt(page.getByTestId('cheque-summary-overdue'));
    }, { weight: 18.45 }),
];

export default { role: 'accountant', scenes };
