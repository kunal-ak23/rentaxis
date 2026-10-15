// Tutorial 45 — Bank reconciliation (Palm Ridge Properties, the Accountant
// Rania Khoury). Import the seeded August 2026 statement for Emirates Islamic –
// Palm Ridge Residences (tutorials/work/seed/palm-ridge-statement-2026-08.csv):
// columns recognised, mapping saved, opening 585,587.81 / closing 622,895.31,
// six lines; Auto-match finds the three cheque clearings (330105, 770108,
// 220104) by cheque number → Confirm all HIGH; book the bank-only lines from
// the line: the 105 maintenance charge with VAT (100 + 5, the bank's TRN is on
// file), the 412.50 profit credit as interest, the 2,500 unidentified inward
// transfer to Unidentified bank receipts; start the first reconciliation for
// August (difference 0.00) and Finalize (locked through 31/08/2026).
//
// Snapshot pre45 = base1005 plus the bank's TRN (100246813500003) on the
// Residences account, set from Ledger accounts off camera. Writes BNK journals
// and a finalized reconciliation: the proof and the capture restore pre45.
// Account numbers (IBANs) are blurred. Weights are the seconds of narration
// each scene covers (tutorials/work/acct/tts.sh); `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';
import { seed } from '../lib/context.mjs';

const statementCsv = new URL('../../work/seed/palm-ridge-statement-2026-08.csv', import.meta.url).pathname;
const residencesBank = seed.bankAccounts?.residences;
const accountRow = (page) => page.locator('main tr').filter({ hasText: 'Palm Ridge Residences' }).first();
const line = (page, description) => page.getByTestId(`sl-${description}`);

/** Blur IBAN / account numbers wherever they are on screen. */
async function blurAccountNumbers(page) {
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('main *')) {
      if (el.children.length === 0 && /\bAE\d{2}\s?\d{6,}/.test(el.textContent || '')) el.style.filter = 'blur(6px)';
    }
  });
}

async function recordFromLine(page, description, tab) {
  await line(page, description).locator('[data-testid^="row-menu-"]').click();
  await page.getByText('Record from this line…').click();
  await page.getByTestId(`tab-${tab}`).click();
  await page.getByTestId('posting-preview').waitFor({ state: 'visible', timeout: navTimeoutMs });
}

async function bookIt(page) {
  await page.getByTestId('action-submit').click();
  await page.getByRole('dialog').waitFor({ state: 'detached', timeout: navTimeoutMs });
  await page.getByTestId('ws-notice').filter({ hasText: /Posted BNK-/ }).waitFor({ state: 'visible', timeout: navTimeoutMs });
}

const scenes = [
  roleRouteScene('accountant', '/en/dashboard/finance/bank-reconciliation', 'Bank reconciliation',
    'Each bank account with its ledger account; the bank\'s TRN is on file for Palm Ridge Residences.', {
    weight: 18.35,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const row = accountRow(page);
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await blurAccountNumbers(page);
      await expectText(row, '100246813500003', 'Bank TRN on file');
      await restPointer(page, 1300, 640);
      await at(6.4);
      await pointAt(page.getByRole('link', { name: 'Bank reconciliation' }).first());
      await at(9.8);
      await pointAt(row.getByText('Emirates Islamic - Palm Ridge Residences'));
      await at(14.4);
      await pointAt(row.getByText(/Bank TRN/));
    },
  }),
  stepScene('Import the statement',
    'Columns recognised and the mapping saved; the opening and closing balances come from the file.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.4);
      await accountRow(page).getByRole('button', { name: 'Import statement' }).click();
      await page.getByTestId('import-file').waitFor({ state: 'attached', timeout: navTimeoutMs });
      await at(2.6);
      await page.getByTestId('import-file').setInputFiles(statementCsv);
      const wizard = page.getByTestId('mapping-wizard');
      await wizard.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(4.4);
      await pointAt(page.getByTestId('map-txnDate'));
      await at(6.4);
      await pointAt(page.getByTestId('map-chequeNo'));
      await at(9.8);
      await page.getByTestId('map-save').click();
      const result = page.getByTestId('import-result');
      await result.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(result, '6 new', 'New lines');
      await expectText(result, '585,587.81', 'Opening balance');
      await expectText(result, '622,895.31', 'Closing balance');
      await at(14.4);
      await pointAt(result.getByText(/Opening balance/));
      await at(18.6);
      await pointAt(result.getByText(/Closing balance/));
      await at(20.6);
      await pointAt(page.getByTestId('import-preview'));
      await at(22.6);
      await page.getByTestId('import-commit').click();
      await page.getByTestId('import-done').waitFor({ state: 'visible', timeout: navTimeoutMs });
    }, { weight: 23.36 }),
  stepScene('Auto-match',
    'The three cheque clearings find their cheques by cheque number; confirm the high-confidence matches.',
    async (page) => {
      const at = sceneClock(page);
      await page.goto(`/en/dashboard/finance/bank-reconciliation/${residencesBank}`);
      await line(page, 'PROFIT CREDIT').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 700);
      await at(1.6);
      await pointAt(page.getByTestId('pane-statement'));
      await at(4.0);
      await pointAt(page.getByTestId('pane-books'));
      await at(5.9);
      await page.getByTestId('auto-match').click();
      const suggestions = page.getByTestId('suggestions');
      await suggestions.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(suggestions, '14,000.00', 'Suggestion 330105');
      await expectText(suggestions, '5,500.00', 'Suggestion 770108');
      await expectText(suggestions, '15,000.00', 'Suggestion 220104');
      await at(7.2);
      await pointAt(suggestions);
      await at(12.2);
      await page.getByTestId('confirm-high').click();
      await suggestions.waitFor({ state: 'detached', timeout: navTimeoutMs });
    }, { weight: 13.35 }),
  stepScene('Book a bank charge',
    'Record from the line: 105 with VAT is 100 to the charge and 5 to Input VAT.',
    async (page) => {
      const at = sceneClock(page);
      await at(1.0);
      await pointAt(line(page, 'INWARD TT REF UNKNOWN'));
      await at(4.6);
      await pointAt(line(page, 'ACCOUNT MAINTENANCE CHARGE + VAT'));
      await at(8.4);
      await recordFromLine(page, 'ACCOUNT MAINTENANCE CHARGE + VAT', 'charge');
      await at(11.8);
      await page.getByTestId('vat-included').check();
      const preview = page.getByTestId('posting-preview');
      await preview.filter({ hasText: 'Input VAT' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(preview, 'Input VAT', 'VAT line');
      await expectText(preview, '100.00', 'Net charge');
      await expectText(preview, '5.00', 'VAT');
      await at(15.4);
      await pointAt(preview);
      await at(25.4);
      await bookIt(page);
    }, { weight: 26.28 }),
  stepScene('Interest and an unidentified receipt',
    'The profit credit is interest; the unknown transfer waits in Unidentified bank receipts.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.3);
      await recordFromLine(page, 'PROFIT CREDIT', 'interest');
      await page.getByTestId('posting-preview').filter({ hasText: 'Bank interest' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(3.4);
      await pointAt(page.getByTestId('posting-preview'));
      await at(5.6);
      await bookIt(page);
      await at(6.6);
      await recordFromLine(page, 'INWARD TT REF UNKNOWN', 'suspense');
      await page.getByTestId('posting-preview').filter({ hasText: 'Unidentified bank receipts' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(10.6);
      await pointAt(page.getByTestId('posting-preview'));
      await at(19.4);
      await bookIt(page);
    }, { weight: 22.52 }),
  stepScene('Reconcile and finalize',
    'Statement and books agree at 622,895.31, difference zero; finalizing locks the account through 31 August.',
    async (page) => {
      const at = sceneClock(page);
      const from = page.getByTestId('rec-from');
      await from.scrollIntoViewIfNeeded();
      await at(1.4);
      await pointAt(from);
      await from.fill('2026-08-01');
      await at(3.6);
      await page.getByTestId('rec-to').fill('2026-08-31');
      await at(6.4);
      await page.getByTestId('rec-create').click();
      const figures = page.getByTestId('rec-figures');
      await figures.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(figures, '622,895.31', 'Statement and book balance');
      await expectText(figures.getByText('Difference').locator('..'), '0.00', 'Difference');
      await at(7.8);
      await pointAt(figures);
      await at(12.2);
      await pointAt(figures.getByText('Difference'));
      await at(14.8);
      await pointAt(page.getByTestId('rec-checklist'));
      await at(16.6);
      await page.getByTestId('rec-finalize').click();
      await page.getByTestId('rec-finalize-confirm').click();
      const locked = page.getByTestId('rec-locked-through');
      await locked.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(locked, '31/08/2026', 'Locked through');
      await at(18.4);
      await pointAt(locked);
      await at(25.4);
      await pointAt(page.locator('[data-testid^="rec-pdf-en-"]').first());
      await at(27.4);
      await pointAt(page.locator('[data-testid^="rec-pdf-ar-"]').first());
    }, { weight: 32.04 }),
];

export default { role: 'accountant', anchored: true, scenes };
