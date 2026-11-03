// Tutorial 47 — A tenancy contract from draft to cleared cheques. As Company Admin:
// draft Rajesh Kumar's contract for the vacant unit A-202 (start 1 November, rent
// 42,000 in four cheques, a 4,000 security deposit and a 1,000 admin fee), watch each
// charge's Ledger fill itself in, generate the cheques — the deposit and the fee on
// cheques of their own, dated the contract date (client request 2026-10-28) — number
// them, post the contract from the wizard, then bank the deposit and fee cheques (due
// today) and clear the deposit cheque.
//
// Posting, banking and clearing cannot be undone in the app, so the proof and the
// capture each start from the database snapshot `pre47` (restored by record-local.sh).
import { navTimeoutMs, validateOnly } from '../lib/context.mjs';
import { glideTo, pointAt, restPointer } from '../lib/cursor.mjs';
import { applyCaptureStyles, waitForApp } from '../lib/page.mjs';
import { escapeRegExp, expectCount, expectText, pace, waitForInputValue } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const unitNumber = 'A-202';
const tenantName = 'Rajesh Kumar';
const startDate = '2026-11-01';
const endDate = '2027-10-31';
const firstChequeNo = '600101';
const depositChequeNo = '600105';
const feeChequeNo = '600106';

/**
 * Pick an option in the wizard's searchable unit (0) or tenant (1) picker. Both are
 * button comboboxes (the Property/Building filters before them are plain selects);
 * opening one focuses its search box.
 */
async function pickInWizard(page, index, query) {
  const wizard = page.getByTestId('lease-wizard');
  await wizard.locator('button[role="combobox"]').nth(index).click();
  await pace(page, 400);
  await page.keyboard.type(query, { delay: validateOnly ? 0 : 25 });
  await page.getByRole('option', { name: new RegExp(escapeRegExp(query), 'i') }).first().click();
}

/** A line's Ledger box holds an account name (filled from the property's accounts). */
async function ledgerFilled(page, row) {
  await page.waitForFunction((i) => {
    const input = document.querySelector(`[data-testid="lease-line-row-${i}"] input[placeholder="Ledger"]`);
    return !!input && input.value.trim().length > 0;
  }, row, { timeout: navTimeoutMs });
  return page.getByTestId(`lease-line-row-${row}`).getByPlaceholder('Ledger');
}

// Rows only: a register row's cells carry cheque-row-amount-…, -face-…, -owed-… ids too.
const registerRows = (page) => page.locator('tr[data-testid^="cheque-row-"]');
const registerRow = (page, no) => registerRows(page).filter({ hasText: no }).first();

/** Wait until the register shows exactly `n` rows (applying a search re-fetches the list). */
async function waitForRegisterRows(page, n) {
  await page.waitForFunction((want) => document.querySelectorAll('tr[data-testid^="cheque-row-"]').length === want,
    n, { timeout: navTimeoutMs });
}

const scenes = [
  // Weights are the seconds of narration each scene covers, measured from the synthesized cues.
  roleRouteScene('tenantAdmin', '/en/dashboard/leases', 'Tenancy Contracts',
    'One contract, from the first draft to its cheques clearing at the bank.', {
    weight: 11.1,
    afterNavigation: async (page) => {
      await page.getByTestId('contract-pills').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.locator('h1').first(), 'Tenancy Contracts', 'Page heading');
      await pace(page, 4500);
      await pointAt(page.getByTestId('lease-new'));
      await pace(page, 1500);
      await page.getByTestId('lease-new').click();
      await expectText(page.getByTestId('lease-wizard').locator('h2').first(), 'New Contract — Parties', 'Wizard step');
    },
  }),
  stepScene('Step 1 · Parties', 'The vacant unit A-202 and the tenant, Rajesh Kumar.',
    async (page) => {
      await pickInWizard(page, 0, unitNumber);
      await page.getByTestId('wizard-unit-property').waitFor({ state: 'visible' });
      await pace(page, 300);
      await pickInWizard(page, 1, tenantName);
      await expectText(page.getByTestId('lease-wizard'), tenantName, 'Chosen tenant');
    }, { weight: 5.5 }),
  stepScene('Step 2 · Terms', 'Start 1 November; the end date fills in. Rent 42,000 for the term, four cheques.',
    async (page) => {
      await page.getByTestId('wizard-next').click();
      await expectText(page.getByTestId('lease-wizard').locator('h2').first(), 'New Contract — Terms', 'Wizard step');
      await pace(page, 800);
      await page.getByTestId('wizard-start-date').fill(startDate);
      await page.waitForFunction((want) => document.querySelector('[data-testid="wizard-end-date"]')?.value === want,
        endDate, { timeout: navTimeoutMs });
      await pointAt(page.getByTestId('wizard-end-date'));
      await pace(page, 2500);
      await page.getByTestId('wizard-rent').fill('42000');
      await pace(page, 2000);
      await page.getByTestId('wizard-payment-terms').fill('4');
      if (await page.getByTestId('wizard-payment-terms').inputValue() !== '4') {
        throw new Error('The number of cheques did not take.');
      }
      await expectCount(page.getByTestId('below-expected-rent'), 0, 'below-expected-rent warnings');
    }, { weight: 12.8 }),
  stepScene('Step 3 · Charges', 'Each charge\'s Ledger fills itself in the moment the charge is chosen.',
    async (page) => {
      await page.getByTestId('wizard-next').click();
      await page.getByTestId('lease-line-type-0').waitFor({ state: 'visible', timeout: navTimeoutMs });
      const rentLedger = await ledgerFilled(page, 0);
      await pointAt(rentLedger);
      await pace(page, 3000);
      await page.getByTestId('lease-lines-add').click();
      await page.getByTestId('lease-line-type-1').selectOption({ label: 'Security Deposit' });
      await ledgerFilled(page, 1);
      await page.getByTestId('lease-line-amount-1').fill('4000');
      await pace(page, 800);
      await page.getByTestId('lease-lines-add').click();
      await page.getByTestId('lease-line-type-2').selectOption({ label: 'Admin Fee' });
      const feeLedger = await ledgerFilled(page, 2);
      await page.getByTestId('lease-line-amount-2').fill('1000');
      await pointAt(feeLedger);
      await pace(page, 1500);
      await expectText(page.getByTestId('lease-lines-contract-value'), '47,000.00', 'Contract value');
      await pointAt(page.getByTestId('lease-lines-contract-value'));
    }, { weight: 15.3 }),
  stepScene('Save the draft, then generate cheques',
    'The deposit and the admin fee each get a cheque of their own, dated today. Tick two and Merge to combine them.',
    async (page) => {
      await page.getByTestId('wizard-next').click();
      await page.getByTestId('cheque-grid').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('lease-wizard').locator('h2').first(), 'New Contract — Cheques', 'Wizard step');
      await pace(page, 800);
      await page.getByTestId('cheque-grid-generate').click();
      const separate = page.getByTestId('cheque-generate-separate-one-time');
      if (!(await separate.isChecked())) throw new Error('"Separate cheque for one-time charges" is not ticked by default.');
      await pointAt(separate);
      await pace(page, 2500);
      await page.getByTestId('cheque-generate-confirm').click();
      await page.getByTestId('cheque-row-5').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.locator('[data-testid^="cheque-row-"]'), 6, 'cheque rows');
      await expectText(page.getByTestId('cheque-grid-match'), 'Cheques match the contract value of 47,000.00', 'Cheque total check');
      await waitForInputValue(page, '[data-testid="cheque-row-4"]', '4000');
      await waitForInputValue(page, '[data-testid="cheque-row-5"]', '1000');
      await pointAt(page.getByTestId('cheque-row-4'));
      await pace(page, 3000);
      await pointAt(page.getByTestId('cheque-row-5'));
      await pace(page, 1800);
      await pointAt(page.getByTestId('cheque-grid-merge'));
    }, { weight: 18.2 }),
  stepScene('Number the cheques', 'Every row, counting up from 600101.',
    async (page) => {
      await page.getByTestId('cheque-grid-numbers').click();
      await page.getByTestId('cheque-numbers-form').locator('input').first().fill(firstChequeNo);
      await page.getByTestId('cheque-numbers-confirm').click();
      await waitForInputValue(page, '[data-testid="cheque-row-0"]', firstChequeNo);
      await waitForInputValue(page, '[data-testid="cheque-row-5"]', feeChequeNo);
    }, { weight: 3.8 }),
  stepScene('Review and post', 'Ready to post. Post Contract makes it Active.',
    async (page) => {
      await page.getByTestId('wizard-next').click();
      const review = page.getByTestId('wizard-review');
      await review.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('wizard-dry-run-ok').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(review, 'Cheques Total 47,000.00', 'Review cheque total');
      await pointAt(page.getByTestId('wizard-dry-run-ok'));
      await pace(page, 1500);
      await page.getByTestId('wizard-post').click();
      await page.waitForURL(/\/en\/dashboard\/leases\/[0-9a-f-]{36}\?posted=/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await page.getByTestId('lease-status').filter({ hasText: 'Active' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      console.log(`posted_contract_id=${page.url().split('/').pop().split('?')[0]}`);
    }, { weight: 7.1 }),
  stepScene('Its cheques', 'All six registered: rent cheques wait for their dates; deposit and fee are due today.',
    async (page) => {
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('cheque-row-5').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.locator('[data-testid^="cheque-status-"]').filter({ hasText: /registered/i }), 6, 'registered cheques');
      await restPointer(page, 1100, 560);
      await pointAt(page.getByTestId('cheque-row-0'));
      await pace(page, 2500);
      await pointAt(page.getByTestId('cheque-row-4'));
      await pace(page, 1500);
      await pointAt(page.getByTestId('cheque-row-5'));
    }, { weight: 8.9 }),
  roleRouteScene('tenantAdmin', '/en/dashboard/collections?tab=deposit', 'Bank the cheques that are due',
    'To deposit lists cheques whose date has arrived. Deposit Batch banks the deposit and fee cheques.', {
    weight: 12.5,
    afterNavigation: async (page) => {
      const deposit = page.getByRole('row').filter({ hasText: depositChequeNo });
      const fee = page.getByRole('row').filter({ hasText: feeChequeNo });
      await deposit.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await fee.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 1200);
      await deposit.scrollIntoViewIfNeeded();
      await pointAt(deposit.getByText(depositChequeNo, { exact: true }));
      await pace(page, 1200);
      await deposit.locator('[data-testid^="collection-select-"]').check();
      await fee.locator('[data-testid^="collection-select-"]').check();
      await expectText(page.getByTestId('collection-selected-total'), '5,000.00', 'Selected total');
      await pointAt(page.getByTestId('collection-selected-total'));
      await pace(page, 800);
      await page.getByTestId('collection-deposit-selected').click();
      await page.getByTestId('deposit-batch-total').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 600);
      await page.getByTestId('deposit-batch-confirm').click();
      await deposit.waitFor({ state: 'detached', timeout: navTimeoutMs });
      await fee.waitFor({ state: 'detached', timeout: navTimeoutMs });
    },
  }),
  stepScene('Clear when the bank confirms', 'The dialog names the bank account. Cleared, with its receipt number.',
    async (page) => {
      await page.getByTestId('collections-tab-all').click();
      await page.getByTestId('cheque-search').fill(depositChequeNo);
      await page.getByTestId('cheque-filter-apply').click();
      const row = registerRow(page, depositChequeNo);
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await waitForRegisterRows(page, 1);
      await expectCount(registerRows(page), 1, `register rows for ${depositChequeNo}`);
      await expectText(row, 'DEPOSITED', 'Deposit cheque before clearing');
      await expectText(row, '4,000.00', 'Deposit cheque amount');
      await pace(page, 800);
      await row.locator('[data-testid^="cheque-row-action-clear-"]').click();
      const into = page.getByTestId('cheque-cleared-into');
      await into.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pointAt(into);
      await pace(page, 2500);
      await page.getByTestId('cheque-clear-confirm').click();
      await row.locator('[data-testid^="cheque-status-"]').filter({ hasText: /cleared/i }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row, 'RR-', 'Receipt number');
      if (!validateOnly) await glideTo(row.locator('[data-testid^="cheque-status-"]'));
    }, { weight: 14.2 }),
];

export default { role: 'tenantAdmin', scenes };
