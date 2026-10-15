// Tutorial 18 — Journal vouchers: create, read and reverse (Palm Ridge
// Properties, the Accountant Rania Khoury). The Journal Voucher list and its
// document types; JV-26/1 (cash from R-103's returned cheque banked: Dr
// Emirates Islamic - Residences 14,000 / Cr Cash Account 14,000); a new JV on
// camera dated 30/09/2026 for the Residences (Dr Cleaning Services 600 / Cr
// Repairs & Maintenance 600, Post disabled while unbalanced) posts JV-26/3; it
// is reversed with a reason (JV-26/4, the mirror, linked both ways); a TCO has
// no Reverse button and links to its source contract.
//
// Posting cannot be undone: proof and capture each start from snapshot pre18.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const row = (page, text) => page.locator('main tr').filter({ hasText: text }).first();
const journalLines = (page) => page.locator('main table').first().locator('tbody tr');
const journalLine = (page, account, amount) => journalLines(page).filter({ hasText: account }).filter({ hasText: amount }).first();
const formRows = (page) => page.locator('main form table tbody tr');

async function openJournal(page, link, docNo) {
  await link.click();
  await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/, { timeout: navTimeoutMs });
  await page.getByText(`Journal Voucher ${docNo}`, { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
  await journalLines(page).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
}

async function filterDocType(page, type) {
  await page.locator('#jv-doc-type').selectOption(type);
  await page.getByRole('button', { name: 'Apply', exact: true }).click();
  await page.locator('main tbody tr').filter({ hasText: `${type}-` }).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
}

async function pickAccount(page, i, query, code) {
  const box = formRows(page).nth(i).getByRole('textbox', { name: 'Account' });
  await box.click();
  await box.pressSequentially(query, { delay: 40 });
  await page.locator('li > button', { hasText: code }).first().click();
}

const scenes = [
  roleRouteScene('accountant', '/en/dashboard/finance/journals', 'Every entry in one list',
    'Manual and automatic journals together, each with its document type: TCO, PDR, CIL, JV…', {
    weight: 25.54,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await row(page, 'CIL-26/72').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 560);
      await at(11.6);
      await pointAt(page.locator('main table').first().locator('thead'));
      await at(16.4);
      await pointAt(page.locator('#jv-doc-type'));
      await at(21.4);
      await pointAt(row(page, 'CIL-26/72'));
    },
  }),
  stepScene('A manual journal',
    'JV-26/1: Dr Emirates Islamic - Residences 14,000, Cr Cash Account 14,000. Cash box to bank.',
    async (page) => {
      const at = sceneClock(page);
      await filterDocType(page, 'JV');
      const jv1 = row(page, 'JV-26/1');
      await expectText(jv1, '14,000.00', 'JV-26/1 total');
      await at(1.4);
      await pointAt(jv1);
      await at(4.0);
      await openJournal(page, jv1.getByText('JV-26/1', { exact: true }), 'JV-26/1');
      await expectText(journalLine(page, 'Emirates Islamic - Palm Ridge Residences', '14,000.00'), '14,000.00', 'Bank debit');
      await expectText(journalLine(page, 'Cash Account', '14,000.00'), '14,000.00', 'Cash credit');
      await restPointer(page, 1300, 640);
      await at(4.8);
      await pointAt(journalLine(page, 'Emirates Islamic - Palm Ridge Residences', '14,000.00'));
      await at(7.6);
      await pointAt(journalLine(page, 'Cash Account', '14,000.00'));
      await at(10.4);
      await pointAt(row(page, 'Total'));
    }, { weight: 17.03 }),
  stepScene('Write the correction',
    'Dr Cleaning Services 600, Cr Repairs & Maintenance 600, dated 30/09/2026. Post stays off until it balances.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-journals').click();
      const add = page.getByRole('link', { name: 'New Journal Voucher' });
      await add.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(5.0);
      await add.click();
      await page.locator('#jv-date').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(7.0);
      await page.locator('#jv-date').fill('2026-09-30');
      await at(9.6);
      await page.locator('#jv-property').selectOption({ label: 'Palm Ridge Residences' });
      await at(11.6);
      await page.locator('#jv-narration').click();
      await page.locator('#jv-narration').pressSequentially('Deep cleaning of R-203 moved from repairs to cleaning', { delay: 15 });
      await at(16.2);
      await pickAccount(page, 0, 'Cleaning', 'D-01-102');
      await formRows(page).nth(0).getByRole('textbox', { name: 'Debit' }).pressSequentially('600', { delay: 60 });
      const unbalanced = page.getByText('Debits and credits must be equal', { exact: true });
      await unbalanced.waitFor({ state: 'visible', timeout: navTimeoutMs });
      const post = page.getByRole('button', { name: 'Post', exact: true });
      if (await post.isEnabled()) throw new Error('Post is enabled on an unbalanced journal');
      await at(18.4);
      await pointAt(unbalanced);
      await at(21.4);
      await pointAt(post);
      await at(23.5);
      await pickAccount(page, 1, 'Repairs', 'D-01-101');
      await formRows(page).nth(1).getByRole('textbox', { name: 'Credit' }).pressSequentially('600', { delay: 60 });
      await at(25.6);
      if (!(await post.isEnabled())) throw new Error('Post is still disabled on a balanced journal');
      await pointAt(post);
      await at(26.6);
      await post.click();
      await page.getByText('Journal JV-26/3 posted', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(journalLine(page, 'Cleaning Services', '600.00'), '600.00', 'Debit line');
      await pointAt(page.getByText('Journal JV-26/3 posted', { exact: true }));
    }, { weight: 30.0 }),
  stepScene('Reverse, never edit',
    'JV-26/4 mirrors JV-26/3, dated 30/09/2026, with the reason. The two are linked and net to zero.',
    async (page) => {
      const at = sceneClock(page);
      await at(3.4);
      await pointAt(page.getByTestId('reverse-journal'));
      await at(6.2);
      await page.getByTestId('reverse-journal').click();
      const dialog = page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.locator('#jv-reverse-date').fill('2026-09-30');
      await page.locator('#jv-reverse-reason').click();
      await page.locator('#jv-reverse-reason').pressSequentially('Already reclassified on the bill', { delay: 15 });
      await dialog.getByRole('button', { name: 'Reverse' }).click();
      await page.getByText('Journal Voucher JV-26/4', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(journalLine(page, 'Cleaning Services', '600.00'), '600.00', 'Mirror credit');
      await restPointer(page, 1300, 640);
      await at(11.0);
      await pointAt(journalLine(page, 'Cleaning Services', '600.00'));
      await at(12.6);
      await pointAt(journalLine(page, 'Repairs & Maintenance', '600.00'));
      await at(14.0);
      const back = page.getByRole('link', { name: 'Reversal of JV-26/3' });
      await pointAt(back);
      await at(15.6);
      await back.click();
      await page.getByText('Journal Voucher JV-26/3', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByRole('link', { name: 'Reversed by JV-26/4' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      if (await page.getByTestId('reverse-journal').isVisible()) throw new Error('A reversed journal still offers Reverse');
      await at(17.6);
      await pointAt(page.locator('main').getByText('Reversed', { exact: true }).first());
      await at(19.2);
      await pointAt(page.getByRole('link', { name: 'Reversed by JV-26/4' }));
    }, { weight: 21.08 }),
  stepScene('Journals the system writes',
    'A contract’s TCO has no Reverse here; it is corrected from the contract itself.',
    async (page) => {
      const at = sceneClock(page);
      await page.goto('/en/dashboard/finance/journals');
      await page.locator('#jv-doc-type').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await filterDocType(page, 'TCO');
      await openJournal(page, row(page, 'TCO-25/3').getByText('TCO-25/3', { exact: true }), 'TCO-25/3');
      if (await page.getByTestId('reverse-journal').isVisible()) throw new Error('A TCO offers Reverse');
      const source = page.getByRole('link', { name: 'Source contract' });
      await source.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(3.2);
      await pointAt(page.getByText('Journal Voucher TCO-25/3', { exact: true }));
      await at(6.4);
      await pointAt(source);
    }, { weight: 11.76 }),
];

export default {
  role: 'accountant',
  anchored: true,
  warmup: [{ role: 'accountant', path: '/en/dashboard/finance/journals/new' }],
  scenes,
};
