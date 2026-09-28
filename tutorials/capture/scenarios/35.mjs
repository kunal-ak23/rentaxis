// Tutorial 35. Default role for scenes that do not name one: tenantAdmin.
import { chequeIdAt } from '../lib/fixtures.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

/** Filled by tutorial 35's collection scene and read by the clearing scene. */
let bankedChequeIds = [];

const scenes = [
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Open the cheque register',
    'Every cheque from every posted contract, with its status, its maturity date, the property and the renter.', {
    weight: 65,
    afterNavigation: async (page) => {
      await page.getByTestId('cheque-summary-tiles').waitFor({ state: 'visible', timeout: 20_000 });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Filter to what matures now',
    'Registered means the cheque is recorded and its journal is written, but the paper has not left the office.', {
    weight: 50,
    afterNavigation: async (page) => {
      await page.getByTestId('cheque-status-filter').selectOption('REGISTERED');
      await page.getByTestId('cheque-filter-apply').click();
      await page.locator('[data-testid^="cheque-row-"]').first().waitFor({ state: 'visible', timeout: 20_000 });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques/collection', 'Bank a batch',
    'Selecting rows and entering a deposit date moves them to deposited. Nothing is posted, because nothing has changed about what you are owed.', {
    weight: 70,
    afterNavigation: async (page) => {
      // `GET /cheques/to-deposit` is REGISTERED **and matured**
      // (`ChequeRepository#findToDeposit`: `chequeDate <= today`), so which of
      // the seeded rows are bankable depends on the day the take is recorded.
      // The first two rows the page offers are taken instead of named ids.
      const boxes = page.locator('[data-testid^="collection-select-"]:not([data-testid="collection-select-all"])');
      await boxes.first().waitFor({ state: 'visible', timeout: 20_000 });
      bankedChequeIds = (await boxes.evaluateAll((els) => els.slice(0, 2)
        .map((el) => el.getAttribute('data-testid').replace('collection-select-', ''))));
      for (const id of bankedChequeIds) await page.getByTestId(`collection-select-${id}`).check();
      await page.getByTestId('collection-deposit-selected').click();
      await page.getByTestId('deposit-batch-total').waitFor({ state: 'visible', timeout: 15_000 });
      await page.getByTestId('deposit-batch-confirm').click();
      await page.getByTestId(`collection-select-${bankedChequeIds[0]}`).waitFor({ state: 'detached', timeout: 20_000 });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Clear a cheque',
    'When the bank confirms, clearing debits your bank account and credits post-dated cheques receivable. That is when the money becomes yours.', {
    weight: 55,
    afterNavigation: async (page) => {
      const id = bankedChequeIds[0];
      await page.getByTestId(`cheque-row-action-clear-${id}`).click();
      await page.getByTestId('cheque-clear-confirm').click();
      // A cleared PDC may still be returned late, so its bounce action
      // appearing in place of clear is the proof the clearing landed.
      await page.getByTestId(`cheque-row-action-bounce-${id}`).waitFor({ state: 'visible', timeout: 30_000 });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Return a cheque the bank sent back',
    'Bouncing a cleared cheque reverses the bank side: rent receivable is debited again and the bank credited. The amount is owed once more.', {
    weight: 70,
    afterNavigation: async (page) => {
      const id = chequeIdAt('fatima', 2);
      // Narrowed to one renter's unit first. Every transition on this page
      // refreshes the list, the summary tiles and the aging strip together,
      // and doing that over the whole register is the slowest thing either
      // of these scenes does — which matters because the clip is recording
      // throughout, so a slow action is a blank frame.
      await page.getByTestId('cheque-search').fill('A-102');
      await page.getByTestId('cheque-filter-apply').click();
      await page.getByTestId(`cheque-row-action-bounce-${id}`).click();
      await page.getByTestId('bounce-failure-reason').waitFor({ state: 'visible', timeout: 20_000 });
      await page.getByTestId('cheque-bounce-confirm').click();
      await page.getByTestId(`cheque-row-action-replace-${id}`).waitFor({ state: 'visible', timeout: 30_000 });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques/return-replace', 'Replace it with new paper',
    'A returned cheque is usually settled with more than one replacement. Each one registers its own journal, and any shortfall stays on the renter.', {
    weight: 55,
    afterNavigation: async (page) => {
      const id = chequeIdAt('fatima', 2);
      await page.getByTestId(`cheque-row-action-replace-${id}`).click();
      await page.getByTestId('replace-row-0').waitFor({ state: 'visible', timeout: 15_000 });
      // Row 0 opens seeded with the whole returned amount, so splitting it
      // across two instruments means halving row 0 before adding row 1.
      const full = Number(await page.getByTestId('replace-row-0-amount').inputValue());
      const first = Math.round(full * 0.6);
      await page.getByTestId('replace-row-0-amount').fill(String(first));
      await page.getByTestId('replace-row-0-number').fill(`RPL-${Date.now().toString().slice(-6)}-A`);
      await page.getByTestId('replace-add-row').click();
      await page.getByTestId('replace-row-1').waitFor({ state: 'visible' });
      await page.getByTestId('replace-row-1-amount').fill(String(full - first));
      await page.getByTestId('replace-row-1-number').fill(`RPL-${Date.now().toString().slice(-6)}-B`);
      await page.getByTestId('replace-residual').waitFor({ state: 'visible' });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/penalties', 'Penalties are never automatic',
    'A returned cheque proposes a penalty. Finance approves, waives or reverses it from this queue.', {
    weight: 30,
  }),
];

export default { role: 'tenantAdmin', scenes };
