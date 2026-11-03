// Tutorial 48 — End a contract early: terminate, settle and pay the refund. As Company
// Admin: Ahmed Hassan leaves A-101 on 31 August, four months before his term ends. His
// July cheque, already banked, is cleared first; then the contract is terminated at
// 31 August (the October cheque goes back to him), the settlement takes a cleaning
// deduction and is finalized, and the refund is paid with a payment voucher from the
// building's bank account — which Oasis Crest, with no bank account set up in Bank
// Reconciliation, can now choose (client report 2026-10-29).
//
// Clearing, terminating, finalizing and paying cannot be undone in the app, so the
// proof and the capture each start from the database snapshot `pre48`. 31 August is the
// last month-end the demo books have recognised, so the settlement has no unrecognised
// periods.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { applyCaptureStyles, waitForApp } from '../lib/page.mjs';
import { expectCount, expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const moveOut = '2026-08-31';
const julyChequeNo = '200103';
const octoberChequeNo = '200104';
const bankAccount = '100003 Emirates Islamic - Oasis Crest Residence Tower';
const transferReference = 'TRF-A101-REFUND';

const registerRows = (page) => page.locator('tr[data-testid^="cheque-row-"]');
const registerRow = (page, no) => registerRows(page).filter({ hasText: no }).first();

/** Wait until the register shows exactly `n` rows (applying a search re-fetches the list). */
async function waitForRegisterRows(page, n) {
  await page.waitForFunction((want) => document.querySelectorAll('tr[data-testid^="cheque-row-"]').length === want,
    n, { timeout: navTimeoutMs });
}

/** Wait for a figure to show `text` (figures recompute after an input changes). */
async function waitForFigure(page, testId, text) {
  await page.getByTestId(testId).filter({ hasText: text }).waitFor({ state: 'visible', timeout: navTimeoutMs });
}

async function moreAction(page, item) {
  await page.getByTestId('lease-more-actions').click();
  await page.getByRole('menuitem', { name: item, exact: true }).click();
}

const scenes = [
  roleRouteScene('tenantAdmin', '/en/dashboard/collections?tab=all', 'The banked July cheque',
    'The bank has confirmed Ahmed\'s July cheque, so it is cleared first.', {
    weight: 26.8,
    afterNavigation: async (page) => {
      await page.getByTestId('cheque-search').fill(julyChequeNo);
      await page.getByTestId('cheque-filter-apply').click();
      const row = registerRow(page, julyChequeNo);
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await waitForRegisterRows(page, 1);
      await expectCount(registerRows(page), 1, `register rows for ${julyChequeNo}`);
      await expectText(row, 'DEPOSITED', 'July cheque before clearing');
      await pace(page, 4500);
      await pointAt(row.locator('[data-testid^="cheque-status-"]'));
      await pace(page, 2000);
      await row.locator('[data-testid^="cheque-row-action-clear-"]').click();
      await page.getByTestId('cheque-cleared-into').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 1000);
      await page.getByTestId('cheque-clear-confirm').click();
      await row.locator('[data-testid^="cheque-status-"]').filter({ hasText: /cleared/i }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pointAt(row.locator('[data-testid^="cheque-status-"]'));
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${ahmedLeaseId}`, 'Terminate on the move-out date',
    'Rent is earned to 31 August, the rest reversed; the October cheque goes back to him.', {
    weight: 21.6,
    afterNavigation: async (page) => {
      await expectText(page.getByTestId('lease-status'), 'Active', 'A-101 status');
      await pace(page, 1500);
      await moreAction(page, 'Terminate');
      await page.waitForURL(/\/terminate$/, { timeout: navTimeoutMs });
      const date = page.getByTestId('terminate-date');
      await date.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 800);
      await date.fill(moveOut);
      await page.getByTestId('terminate-earned').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await waitForFigure(page, 'terminate-earned', '56,589.04');
      await waitForFigure(page, 'terminate-unearned', '28,410.96');
      await pace(page, 1000);
      await pointAt(page.getByTestId('terminate-earned'));
      await pace(page, 2200);
      await pointAt(page.getByTestId('terminate-unearned'));
      await pace(page, 2200);
      const ret = page.getByRole('row').filter({ hasText: octoberChequeNo }).getByRole('button', { name: 'Return', exact: true });
      await ret.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await ret.click();
      await pointAt(ret);
      await pace(page, 1800);
      await page.getByTestId('terminate-notes').fill('Relocating; moved out on 31 August.');
      await page.getByTestId('terminate-submit').click();
      await page.getByText('Terminate on 31/08/2026?', { exact: true }).waitFor({ state: 'visible' });
      await pace(page, 600);
      await page.getByTestId('terminate-confirm').click();
      await page.waitForURL(/\/settlement$/, { timeout: navTimeoutMs });
      await page.getByTestId('settlement-add-deduction').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.getByTestId('settlement-unrecognised'), 0, 'unrecognised-period warnings');
    },
  }),
  stepScene('Settle the deposit',
    'Deposit held, the balance, a cleaning deduction, and the refund. Save draft, then Finalize.',
    async (page) => {
      await restPointer(page, 1100, 560);
      await waitForFigure(page, 'settlement-deposits-held', '22,000.00');
      await waitForFigure(page, 'settlement-receivable-balance', '-7,160.96');
      await pointAt(page.getByTestId('settlement-deposits-held'));
      await pace(page, 2800);
      await pointAt(page.getByTestId('settlement-receivable-balance'));
      await pace(page, 2800);
      await page.getByTestId('settlement-add-deduction').click();
      await page.getByTestId('settlement-category-0').selectOption({ label: 'Cleaning' });
      await page.getByTestId('settlement-description-0').fill('Deep cleaning after move-out');
      await page.getByTestId('settlement-amount-0').fill('750');
      await pace(page, 600);
      await waitForFigure(page, 'settlement-net-refund', '28,410.96');
      await pointAt(page.getByTestId('settlement-net-refund'));
      await pace(page, 2000);
      await page.getByTestId('settlement-save-draft').click();
      await page.getByTestId('settlement-status').filter({ hasText: 'Draft' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.getByTestId('settlement-acknowledge'), 0, 'outstanding-instrument acknowledgements');
      await pace(page, 1000);
      await page.getByTestId('settlement-finalize').click();
      await page.getByText('Finalize this settlement?', { exact: true }).waitFor({ state: 'visible' });
      await pace(page, 700);
      await page.getByTestId('settlement-finalize-confirm').click();
      await page.getByTestId('settlement-status').filter({ hasText: 'Finalized' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('settlement-pay-refund').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('settlement-pay-refund').scrollIntoViewIfNeeded();
      await pointAt(page.getByTestId('settlement-refund-outstanding'));
    }, { weight: 22.4 }),
  stepScene('Pay the refund',
    'A payment voucher, prefilled. Pay from the building\'s bank account by transfer, then Post.',
    async (page) => {
      await page.getByTestId('settlement-pay-refund').click();
      await page.waitForURL(/\/finance\/vouchers\/payment\?settlementId=/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      const payFrom = page.getByTestId('refund-payment-account');
      await payFrom.locator('option', { hasText: bankAccount }).waitFor({ state: 'attached', timeout: navTimeoutMs });
      await pace(page, 2500);
      await pointAt(payFrom);
      await pace(page, 1000);
      await payFrom.selectOption({ label: bankAccount });
      await pace(page, 1000);
      await page.locator('#voucher-payment-reference').fill(transferReference);
      await expectCount(page.getByTestId('voucher-blocker'), 0, 'voucher blockers');
      await pace(page, 1000);
      await page.getByTestId('post-voucher').click();
      await page.getByTestId('confirm-post').click();
      await page.waitForURL(/\/finance\/vouchers(\?|$)/, { timeout: navTimeoutMs });
    }, { weight: 16.8 }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${ahmedLeaseId}/settlement`, 'Refund paid',
    'Paid in full; nothing outstanding.', {
    weight: 7.0,
    afterNavigation: async (page) => {
      await page.getByTestId('settlement-refund-status').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await waitForFigure(page, 'settlement-refund-paid', '28,410.96');
      await expectText(page.getByTestId('settlement-refund-outstanding'), '0.00', 'Refund outstanding');
      // The refund block is the page's last card, under the caption at full scroll: blank
      // room below it lets it scroll up to the middle of the frame.
      await page.getByTestId('settlement-refund-status').evaluate((el) => {
        const room = document.createElement('div');
        room.style.height = '320px';
        el.after(room);
        el.scrollIntoView({ block: 'center' });
      });
      await restPointer(page, 1100, 560);
      await pointAt(page.getByTestId('settlement-refund-outstanding'));
    },
  }),
];

export default { role: 'tenantAdmin', scenes };
