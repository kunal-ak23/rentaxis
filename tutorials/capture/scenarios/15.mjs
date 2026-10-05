// Tutorial 15 — Manage rent cheques end to end (Oasis Crest Properties, the
// Property Manager Maya Khoury). The operational cheque tutorial: Due and
// Overdue; Scan cheques reads a fictional photo of Ahmed's 200104 and attaches it;
// To deposit → bank Ahmed Hassan's 200104 and Fatima Al Zaabi's
// 300204 in one batch (36,750.00); clear Ahmed's deposited July cheque 200103;
// bounce Fatima's 300204 (Payment stopped), the after-bounce panel (cheque-
// return charge proposed) → Replace now with 300295 (15 Oct, 15,500.00);
// Post-dated shows 300295; Ahmed's contract Cheques tab shows 200103 Cleared
// and 200104 Deposited.
//
// The scan reads a fictional cheque photo (fixtures/cheque-200104.png, made by
// fixtures/make-cheque-image.py): record with TUTORIAL_CHEQUE_OCR=on so the
// stack keeps .env.backend's Azure OpenAI settings (it blanks them otherwise).
// Fatima's contract grid is not shown after the replace (cheque-total warning,
// logged under 40).
//
// Writes cheque state: the proof and the capture start from snapshot pre15.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { ahmedLeaseId } from '../lib/fixtures.mjs';

const chequePhoto = new URL('../fixtures/cheque-200104.png', import.meta.url).pathname;
import { expectCount, expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const regRows = (page) => page.locator('[data-testid^="cheque-row-"]:not([data-testid*="-action-"])');
const regRow = (page, no) => regRows(page).filter({ hasText: no }).first();
const mainRow = (page, text) => page.locator('main tr').filter({ hasText: text }).first();

async function searchRegister(page, no) {
  await page.getByTestId('cheque-search').fill('');
  await page.getByTestId('cheque-search').pressSequentially(no, { delay: 45 });
  await page.getByTestId('cheque-filter-apply').click();
  // The register refetches after Apply: wait until only the searched cheque is listed.
  const deadline = Date.now() + navTimeoutMs;
  while (Date.now() < deadline) {
    if ((await regRows(page).count()) === 1 && (await regRow(page, no).isVisible())) break;
    await page.waitForTimeout(150);
  }
  await expectCount(regRows(page), 1, `register rows for ${no}`);
}

const scenes = [
  roleRouteScene('propertyManager', '/en/dashboard/collections?tab=due', 'Due and overdue',
    'Due lists every cheque whose date has arrived and has not cleared; Overdue narrows it to those past their grace period.', {
    weight: 23.0,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const due = mainRow(page, '200104');
      await due.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(8.6);
      await pointAt(page.getByTestId('collections-pill-due'));
      await at(10.4);
      await pointAt(due);
      await at(14.8);
      await page.getByTestId('collections-pill-overdue').click();
      const may = mainRow(page, '300290');
      await may.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(may, 'Fatima Al Zaabi', 'Overdue May cheque tenant');
      await expectText(may, '10,000.00', 'Overdue May cheque amount');
      await at(19.2);
      await pointAt(may);
    },
  }),
  stepScene('Scan the cheque',
    'Scan cheques reads the photo and matches it to the contract\'s cheque; approve to attach it.',
    async (page) => {
      const at = sceneClock(page);
      await at(3.9);
      await page.getByRole('button', { name: 'Scan cheques' }).click();
      const search = page.getByTestId('scan-cheques-lease-search');
      await search.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(5.6);
      await search.pressSequentially('Ahmed', { delay: 70 });
      const option = page.getByTestId(`scan-cheques-lease-option-${ahmedLeaseId}`);
      await option.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(7.0);
      await option.click();
      await at(7.9);
      await page.getByRole('button', { name: /continue/i }).click();
      const input = page.getByTestId('bulk-cheque-upload-photos-input');
      await input.waitFor({ state: 'attached', timeout: navTimeoutMs });
      await at(9.4);
      await input.setInputFiles(chequePhoto);
      const flow = page.getByTestId('bulk-cheque-upload');
      const extract = flow.getByRole('button', { name: /extract/i });
      await at(11.2);
      await pointAt(extract);
      await extract.click();
      const approve = flow.getByRole('button', { name: /Approve all \(1\/1\)/ });
      await approve.waitFor({ state: 'visible', timeout: 90000 });
      const inputs = flow.locator('tbody input');
      if ((await inputs.nth(0).inputValue()) !== '200104') throw new Error(`Scanned cheque number: ${await inputs.nth(0).inputValue()}`);
      if (!/GULF CRESCENT/i.test(await inputs.nth(1).inputValue())) throw new Error('Scanned bank not read');
      await expectText(flow, '21,250.00', 'Scanned amount');
      const target = flow.locator('tbody select').first();
      if (!/#5/.test(await target.locator('option:checked').innerText())) throw new Error('Scan not matched to cheque 5');
      await at(14.6);
      await pointAt(inputs.nth(0));
      await at(16.4);
      await pointAt(inputs.nth(1));
      await at(17.6);
      await pointAt(inputs.nth(2));
      await at(19.0);
      await pointAt(flow.getByText('21,250.00').first());
      await at(21.8);
      await pointAt(target);
      await at(25.6);
      await pointAt(approve);
      await approve.click();
      await page.getByText('Cheque scans attached.').waitFor({ state: 'visible', timeout: navTimeoutMs });
    }, { weight: 29.0 }),
  stepScene('Bank a batch',
    'Tick the cheques going to the bank today; the selected total should match the deposit slip.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('collections-pill-deposit').click();
      const a = page.getByRole('row').filter({ hasText: '200104' });
      const f = page.getByRole('row').filter({ hasText: '300204' });
      await a.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(4.0);
      await pointAt(a.getByText('200104', { exact: true }));
      await a.locator('[data-testid^="collection-select-"]').check();
      await at(7.6);
      await pointAt(f.getByText('300204', { exact: true }));
      await f.locator('[data-testid^="collection-select-"]').check();
      await expectText(page.getByTestId('collection-selected-total'), '36,750.00', 'Selected total');
      await at(11.4);
      await pointAt(page.getByTestId('collection-selected-total'));
      await at(14.6);
      await page.getByTestId('collection-deposit-selected').click();
      await page.getByTestId('deposit-batch-total').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pointAt(page.getByTestId('deposit-batch-date'));
      await at(17.4);
      await page.getByTestId('deposit-batch-confirm').click();
      await a.waitFor({ state: 'detached', timeout: navTimeoutMs });
      await f.waitFor({ state: 'detached', timeout: navTimeoutMs });
    }, { weight: 20.28 }),
  stepScene('Clear when the bank confirms',
    'Find the cheque in the register, check the account it clears into, and confirm.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('collections-pill-all').click();
      await page.getByTestId('cheque-search').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(4.4);
      await searchRegister(page, '200103');
      const row = regRow(page, '200103');
      await expectText(row, 'DEPOSITED', 'Ahmed\'s July cheque before clearing');
      await expectText(row, '21,250.00', 'Ahmed\'s July cheque amount');
      await at(6.4);
      await pointAt(row.locator('[data-testid^="cheque-status-"]'));
      await at(9.0);
      await row.locator('[data-testid^="cheque-row-action-clear-"]').click();
      const into = page.getByTestId('cheque-cleared-into');
      await into.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(into, 'Emirates Islamic - Oasis Crest Residence Tower', 'Cleared into');
      await at(10.6);
      await pointAt(into);
      await at(12.4);
      await page.getByTestId('cheque-clear-confirm').click();
      await row.locator('[data-testid^="cheque-status-"]').filter({ hasText: /cleared/i }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row, 'Receipt', 'Receipt number after clearing');
      await at(13.6);
      await pointAt(row.locator('[data-testid^="cheque-status-"]'));
    }, { weight: 16.46 }),
  stepScene('A cheque comes back',
    'Bounce it with the reason the bank gave; Miftah proposes the cheque-return charge and asks what next.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.8);
      await searchRegister(page, '300204');
      const row = regRow(page, '300204');
      await expectText(row, 'DEPOSITED', 'Fatima\'s cheque before the return');
      await pointAt(row.getByText('300204', { exact: true }));
      await at(4.0);
      await row.locator('[data-testid^="cheque-row-action-bounce-"]').click();
      const reason = page.getByTestId('bounce-failure-reason');
      await reason.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(5.6);
      await pointAt(reason);
      await reason.selectOption({ label: 'Payment stopped' });
      await at(8.4);
      await page.getByTestId('cheque-bounce-confirm').click();
      const flow = page.getByTestId('bounce-flow');
      await flow.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('bounce-flow-auto-penalty'), '1,000.00', 'Cheque-return charge');
      await at(10.4);
      await pointAt(flow.getByText('300204', { exact: false }).first());
      await at(12.0);
      await pointAt(page.getByTestId('bounce-flow-auto-penalty'));
    }, { weight: 16.07 }),
  stepScene('Replace it',
    'Record the new cheque the tenant hands over; when it covers the whole amount, nothing is left owing.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('bounce-flow-replace').click();
      const no = page.getByTestId('replace-row-0-number');
      await no.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(1.6);
      await pointAt(page.getByTestId('replace-row-0-amount'));
      await at(5.6);
      await no.pressSequentially('300295', { delay: 60 });
      await at(8.6);
      await page.getByTestId('replace-row-0-date').fill('2026-10-15');
      await at(10.4);
      await page.getByTestId('replace-row-0-bank').pressSequentially('Emirates NBD', { delay: 40 });
      await expectText(page.getByTestId('replace-residual'), '0.00', 'Residual');
      await at(11.4);
      await pointAt(page.getByTestId('replace-residual'));
      await at(15.6);
      await page.getByTestId('replace-confirm').click();
      await page.getByTestId('bounce-flow-replaced').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('bounce-flow-done').click();
      await page.getByTestId('bounce-flow').waitFor({ state: 'detached', timeout: navTimeoutMs });
    }, { weight: 15.25 }),
  stepScene('Post-dated cheques',
    'Cheques maturing in the next two weeks, with a running total.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('collections-pill-post-dated').click();
      const row = mainRow(page, '300295');
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row, 'Fatima Al Zaabi', 'New cheque tenant');
      await expectText(row, '15,500.00', 'New cheque amount');
      await restPointer(page, 1300, 700);
      await at(1.6);
      await pointAt(page.getByTestId('post-dated-presets'));
      await at(5.2);
      await pointAt(page.getByTestId('post-dated-running-total'));
      await at(6.8);
      await pointAt(row);
    }, { weight: 9.24 }),
  stepScene('On the contract',
    'Each tenancy contract carries the same cheques; nothing is ever deleted.',
    async (page) => {
      const at = sceneClock(page);
      await page.goto(`/en/dashboard/leases/${ahmedLeaseId}`);
      const tab = page.getByTestId('lease-tab-payments');
      await tab.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(1.2);
      await tab.click();
      const grid = page.locator('main tr');
      const cleared = grid.filter({ hasText: '200103' }).first();
      const deposited = grid.filter({ hasText: '200104' }).first();
      await cleared.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(cleared, 'Cleared', '200103 on the contract');
      await expectText(deposited, 'Deposited', '200104 on the contract');
      await expectText(deposited, 'GULF CRESCENT BANK', 'Bank read from the scan');
      await restPointer(page, 1300, 820);
      await at(4.6);
      await pointAt(cleared);
      await at(6.8);
      await pointAt(deposited);
      await at(8.6);
      await pointAt(deposited.getByText('GULF CRESCENT BANK'));
      await at(11.4);
      await pointAt(grid.filter({ hasText: '200100' }).first());
    }, { weight: 17.27 }),
];

export default { role: 'propertyManager', anchored: true, scenes };
