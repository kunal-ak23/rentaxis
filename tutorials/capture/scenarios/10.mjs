// Tutorial 10. Default role for scenes that do not name one: tenantAdmin.
import { navTimeoutMs, validateOnly } from '../lib/context.mjs';
import { glideTo } from '../lib/cursor.mjs';
import { applyCaptureStyles, waitForApp } from '../lib/page.mjs';
import { escapeRegExp, expectCount, expectText, pace, waitForInputValue } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

// ── tutorial 10: draft a tenancy contract ────────────────────────────────────
// The wizard is a create-only modal on the contracts list, so the whole flow
// runs in one page (see `stepScene`). It saves a real DRAFT at the Charges
// step; a draft posts nothing to the ledger, and the run prints its id so the
// take can be accounted for (and deleted) afterwards. The unit must be VACANT —
// the wizard's unit list offers nothing else — and a draft does not occupy it,
// so the same unit serves every take.
const draftUnitNumber = process.env.TUTORIAL_DRAFT_UNIT || 'A-201';
const draftTenantName = process.env.TUTORIAL_DRAFT_TENANT || 'Rajesh Kumar';
const draftStartDate = process.env.TUTORIAL_DRAFT_START || '2026-10-01';
const draftEndDate = process.env.TUTORIAL_DRAFT_END || '2027-09-30';
const draftFirstChequeNo = '500101';

/** Pick an option in the wizard's searchable unit (0) or tenant (1) select. */
async function pickInWizard(page, index, query) {
  const wizard = page.getByTestId('lease-wizard');
  await wizard.getByRole('combobox').nth(index).click();
  await wizard.getByRole('combobox').nth(index + 1).fill(query);
  await page.getByRole('option', { name: new RegExp(escapeRegExp(query), 'i') }).first().click();
}

const scenes = [
  // Weights follow the narration: each is roughly the seconds its part of the
  // script takes to speak, so the wizard never runs ahead of the voice.
  roleRouteScene('tenantAdmin', '/en/dashboard/leases', 'Tenancy Contracts',
    'Leasing › Tenancy Contracts lists every contract, and the status pills count them by stage.', {
    weight: 34,
    afterNavigation: async (page) => {
      await page.getByTestId('contract-pills').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('lease-new').waitFor({ state: 'visible' });
      await expectText(page.locator('h1').first(), 'Tenancy Contracts', 'Page heading');
    },
  }),
  stepScene('Step 1 · Parties',
    'Draft Tenancy Contract opens the wizard. Only vacant units are offered; then choose the tenant.',
    async (page) => {
      await page.getByTestId('lease-new').click();
      const wizard = page.getByTestId('lease-wizard');
      await expectText(wizard.locator('h2').first(), 'New Contract — Parties', 'Wizard step');
      await pace(page, 3000);
      await pickInWizard(page, 0, draftUnitNumber);
      await page.getByTestId('wizard-unit-property').waitFor({ state: 'visible' });
      await pace(page, 4000);
      await pickInWizard(page, 1, draftTenantName);
      await expectText(wizard, draftTenantName, 'Chosen tenant');
    }, { weight: 29 }),
  stepScene('Step 2 · Terms',
    'Enter the start and end dates. Four cheques, paid by cheque. Ejari # and Payment Reference # are optional.',
    async (page) => {
      await page.getByTestId('wizard-next').click();
      await expectText(page.getByTestId('lease-wizard').locator('h2').first(), 'New Contract — Terms', 'Wizard step');
      await pace(page, 3000);
      await page.getByTestId('wizard-start-date').fill(draftStartDate);
      await page.getByTestId('wizard-end-date').fill(draftEndDate);
      if (await page.getByTestId('wizard-end-date').inputValue() !== draftEndDate) {
        throw new Error('The end date did not take.');
      }
    }, { weight: 43 }),
  stepScene('Step 3 · Charges',
    'One line per charge: Rent 96,000 and a Security Deposit of 5,000 make a contract value of 101,000.00.',
    async (page) => {
      await page.getByTestId('wizard-next').click();
      await page.getByTestId('lease-line-type-0').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('lease-line-type-0').selectOption({ label: 'Rent' });
      await page.getByTestId('lease-line-amount-0').fill('96000');
      await pace(page, 2000);
      await page.getByTestId('lease-lines-add').click();
      await page.getByTestId('lease-line-type-1').selectOption({ label: 'Security Deposit' });
      await page.getByTestId('lease-line-amount-1').fill('5000');
      await expectText(page.getByTestId('lease-lines-contract-value'), '101,000.00', 'Contract value');
    }, { weight: 24 }),
  stepScene('Save the draft, then generate cheques',
    'Save draft stores the contract as a draft. Generate Cheques builds four post-dated cheques that match the contract value.',
    async (page) => {
      await page.getByTestId('wizard-next').click();
      await page.getByTestId('cheque-grid').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('lease-wizard').locator('h2').first(), 'New Contract — Cheques', 'Wizard step');
      // Hold on the empty grid while the narration says so.
      await pace(page, 8000);
      await page.getByTestId('cheque-grid-generate').click();
      await page.getByTestId('cheque-generate-confirm').click();
      await page.getByTestId('cheque-row-3').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.locator('[data-testid^="cheque-row-"]'), 4, 'cheque rows');
      await expectText(page.getByTestId('cheque-grid-match'), 'Cheques match the contract value of 101,000.00', 'Cheque total check');
    }, { weight: 41 }),
  stepScene('Number the cheques',
    'Generate Cheque Numbers fills every row, counting up from the first cheque number.',
    async (page) => {
      await pace(page, 3000);
      await page.getByTestId('cheque-grid-numbers').click();
      await page.getByTestId('cheque-numbers-form').locator('input').first().fill(draftFirstChequeNo);
      await page.getByTestId('cheque-numbers-confirm').click();
      await waitForInputValue(page, '[data-testid="cheque-row-0"]', draftFirstChequeNo);
      await waitForInputValue(page, '[data-testid="cheque-row-3"]', '500104');
    }, { weight: 20 }),
  stepScene('Step 5 · Review',
    'Check unit, tenant, dates, contract value, VAT and the cheque total. The review reports Ready to post.',
    async (page) => {
      await page.getByTestId('wizard-next').click();
      const review = page.getByTestId('wizard-review');
      await review.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('wizard-dry-run-ok').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(review, `${draftUnitNumber} • `, 'Review unit');
      await expectText(review, draftTenantName, 'Review tenant');
      await expectText(review, 'Cheques Total 101,000.00', 'Review cheque total');
      // Leave the pointer on the summary, not resting where Next was — on this
      // step that spot is Post Contract, which the tutorial does not press.
      if (!validateOnly) await glideTo(review.locator('dl').first());
    }, { weight: 16 }),
  stepScene('Open the draft contract',
    'Status Draft, four cheques, and each charge line with the account it credits.',
    async (page) => {
      await page.getByRole('button', { name: 'Open the contract', exact: true }).click();
      await page.waitForURL(/\/en\/dashboard\/leases\/[0-9a-f-]{36}$/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await expectText(page.getByTestId('lease-status'), 'Draft', 'Contract status');
      await expectText(page.getByTestId('lease-lines-contract-value'), '101,000.00', 'Contract value');
      console.log(`draft_contract_id=${page.url().split('/').pop()}`);
    }, { weight: 28 }),
  stepScene('Cheques tab',
    'The same four numbered cheques. The draft stays editable until it is posted.',
    async (page) => {
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('cheque-row-3').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.locator('[data-testid^="cheque-row-"]'), 4, 'cheque rows');
      await expectText(page.getByTestId('cheque-grid-match'), '101,000.00', 'Cheque total check');
      await page.waitForFunction(() => document.body.innerText.includes('500104')
        || [...document.querySelectorAll('[data-testid^="cheque-row-"] input')].some((input) => input.value === '500104'),
      null, { timeout: navTimeoutMs });
    }, { weight: 18 }),
];

export default { role: 'tenantAdmin', scenes };
