// Tutorial 13 — Renew, extend, terminate and settle a contract. As Company
// Admin: renew Ahmed Hassan's A-101 contract for next year at +5 % (a new
// draft linked to it), extend Sara Mansour's M-1501 contract by one month
// (Rent 9,500 and cheque 500407), then take Rajesh Kumar's notice for A-103
// (given 31 July, moving out 31 August), terminate at 31 August (cheques up to
// the move-out kept, later ones returned), and settle: a 1,500 cleaning
// deduction, Save draft, and Finalize.
//
// Extending and terminating post journals the app cannot undo, so the proof
// and the capture each start from the database snapshot `pre13` (restored by
// record-local.sh). The termination date is 31 August, the last month-end the
// demo books have recognised, so the settlement has no unrecognised periods.
// Tenant email and phone on the overview are hidden (lib/page.mjs).
import { navTimeoutMs, seed } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { ahmedLeaseId, saraLeaseId } from '../lib/fixtures.mjs';
import { expectCount, expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const rajeshLeaseId = seed.leases?.rajesh;
const moveOut = '2026-08-31';

async function moreAction(page, item) {
  await page.getByTestId('lease-more-actions').click();
  await page.getByRole('menuitem', { name: item, exact: true }).click();
}

const scenes = [
  // Weights are the seconds of narration each scene covers (measured from the
  // synthesized cues); paces put each action on its cue.
  roleRouteScene('tenantAdmin', '/en/dashboard/leases', 'Tenancy Contracts',
    'Renew for a new term, extend for a short stay, or end the contract and settle.', {
    weight: 16,
    afterNavigation: async (page) => {
      await page.getByRole('heading', { name: 'Tenancy Contracts', exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      const row = page.getByRole('row').filter({ hasText: 'A-101' });
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 7000);
      await pointAt(row.getByText('Ahmed Hassan', { exact: true }));
      await pace(page, 1500);
      await row.getByText('A-101', { exact: true }).click();
      await page.waitForURL(new RegExp(`/dashboard/leases/${ahmedLeaseId}`), { timeout: navTimeoutMs });
      await expectText(page.getByTestId('lease-status'), 'Active', 'A-101 status');
      await restPointer(page, 1100, 560);
      await pointAt(page.getByText('31/12/2026', { exact: true }).first());
    },
  }),
  stepScene('Renew',
    'Next year, charge lines copied, rent up 5 %, deposit carried forward.',
    async (page) => {
      await page.getByTestId('lease-renew').click();
      await page.getByTestId('renew-start-date').waitFor({ state: 'visible' });
      if (await page.getByTestId('renew-start-date').inputValue() !== '2027-01-01') throw new Error('Renewal should start on 1 January 2027.');
      if (await page.getByTestId('renew-end-date').inputValue() !== '2027-12-31') throw new Error('Renewal should end on 31 December 2027.');
      await pace(page, 600);
      await pointAt(page.getByTestId('renew-end-date'));
      await pace(page, 2500);
      await pointAt(page.getByTestId('renew-copy-lines'));
      await pace(page, 1800);
      await page.getByTestId('renew-mode-PERCENT').check();
      await page.getByTestId('renew-percent').fill('5');
      await expectText(page.getByTestId('renew-rent-preview'), 'Rent 85,000.00 → 89,250.00 (+5.00%)', 'Renewal rent');
      await pointAt(page.getByTestId('renew-rent-preview'));
      await pace(page, 2500);
      if (!(await page.getByTestId('renew-carry-deposit').isChecked())) throw new Error('Carry deposit forward should be ticked.');
      await pointAt(page.getByTestId('renew-carry-deposit'));
      await pace(page, 2800);
    }, { weight: 16.7 }),
  stepScene('The renewal draft',
    'A new draft for next year, linked to the contract it renews.',
    async (page) => {
      await page.getByRole('button', { name: /^renew$/i }).last().click();
      await page.waitForURL((url) => /\/dashboard\/leases\/[0-9a-f-]{36}$/.test(url.pathname) && !url.pathname.endsWith(ahmedLeaseId),
        { timeout: navTimeoutMs });
      await expectText(page.getByTestId('lease-status'), 'Draft', 'Renewal status');
      await expectText(page.locator('main'), '89,250.00', 'Renewal value');
      await page.getByTestId('lease-renewed-from').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1100, 560);
      await pointAt(page.getByTestId('lease-renewed-from'));
      await pace(page, 2500);
      await pointAt(page.getByTestId('lease-post'));
    }, { weight: 8 }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Extend Contract',
    'One more month on the same contract: a Rent line and the cheque that pays it.', {
    weight: 23.6,
    afterNavigation: async (page) => {
      await expectText(page.getByTestId('lease-status'), 'Active', 'M-1501 status');
      await pace(page, 3500);
      await moreAction(page, 'Extend Contract');
      await page.getByTestId('extend-new-end-date').waitFor({ state: 'visible' });
      await pace(page, 1500);
      await page.getByTestId('extend-new-end-date').fill('2027-01-31');
      await pace(page, 2500);
      await page.getByTestId('lease-line-type-0').selectOption({ label: 'Rent' });
      await page.getByTestId('lease-line-amount-0').fill('9500');
      await pace(page, 1200);
      await page.getByLabel('Cheque No 1').fill('500407');
      await page.getByLabel('Date 1', { exact: true }).fill('2027-01-01');
      await page.getByLabel('Payee Bank 1').fill('Emirates NBD');
      await page.getByLabel('Amount 1', { exact: true }).fill('9500');
      await expectText(page.getByTestId('extend-match'), 'Cheques match the contract value of 9,500.00', 'Extension cheques');
      await pace(page, 600);
      await page.getByTestId('extend-lease-confirm').click();
      await page.getByTestId('extend-new-end-date').waitFor({ state: 'detached', timeout: navTimeoutMs });
      await page.locator('main').getByText('31/01/2027', { exact: true }).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1100, 560);
      await pointAt(page.locator('main').getByText('31/01/2027', { exact: true }).first());
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${rajeshLeaseId}`, 'Give notice',
    'Notice given by the tenant on 31 July, moving out on 31 August. No journal is written.', {
    weight: 18.9,
    afterNavigation: async (page) => {
      await expectText(page.getByTestId('lease-status'), 'Active', 'A-103 status');
      await pace(page, 3200);
      await moreAction(page, 'Give notice');
      await page.locator('#notice-date').waitFor({ state: 'visible' });
      await pace(page, 800);
      await page.locator('#notice-date').fill('2026-07-31');
      await pace(page, 900);
      await page.locator('#notice-move-out').fill(moveOut);
      await page.locator('#notice-notes').fill('Relocating to Abu Dhabi for work.');
      await pace(page, 1200);
      await page.getByTestId('lease-give-notice-confirm').click();
      await page.getByTestId('lease-give-notice-confirm').waitFor({ state: 'detached', timeout: navTimeoutMs });
      await page.getByTestId('lease-status').filter({ hasText: 'Notice Given' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('lease-notice-summary'), 'move-out 31/08/2026', 'Notice summary');
      await restPointer(page, 1100, 560);
      await pointAt(page.getByTestId('lease-notice-summary'));
    },
  }),
  stepScene('Terminate',
    'Rent earned to the move-out, unearned rent reversed; cheques after it go back to the tenant.',
    async (page) => {
      await moreAction(page, 'Terminate');
      await page.waitForURL(/\/terminate$/, { timeout: navTimeoutMs });
      const date = page.getByTestId('terminate-date');
      await date.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 800);
      await date.fill(moveOut);
      await page.getByTestId('terminate-submit').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('terminate-earned').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 1500);
      await pointAt(page.getByTestId('terminate-earned'));
      await pace(page, 3000);
      await pointAt(page.getByTestId('terminate-unearned'));
      await pace(page, 2500);
      // Rows up to the move-out are kept for collection, later ones returned.
      const decision = (chequeNo, label) => page.getByRole('row').filter({ hasText: chequeNo }).getByRole('button', { name: label, exact: true });
      await pointAt(decision('400308', 'Keep'));
      await pace(page, 2800);
      await pointAt(decision('400309', 'Return'));
      await pace(page, 1500);
      await page.getByTestId('terminate-notes').fill('Tenant moved out on 31 August after notice.');
      await page.getByTestId('terminate-submit').click();
      await page.getByText('Terminate on 31/08/2026?', { exact: true }).waitFor({ state: 'visible' });
      await pace(page, 700);
      await page.getByTestId('terminate-confirm').click();
      await page.waitForURL(/\/settlement$/, { timeout: navTimeoutMs });
      await page.getByTestId('settlement-add-deduction').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.getByTestId('settlement-unrecognised'), 0, 'unrecognised-period warnings');
    }, { weight: 22.9 }),
  stepScene('Settlement',
    'Deposit held, the balance, a cleaning deduction, and the refund. Save draft, then Finalize.',
    async (page) => {
      await restPointer(page, 1100, 560);
      await expectText(page.getByTestId('settlement-deposits-held'), '12,000.00', 'Deposit held');
      await pointAt(page.getByTestId('settlement-deposits-held'));
      await pace(page, 3000);
      await expectText(page.getByTestId('settlement-receivable-balance'), '-1,112.33', 'Receivable balance');
      await pointAt(page.getByTestId('settlement-receivable-balance'));
      await pace(page, 3000);
      await page.getByTestId('settlement-add-deduction').click();
      await page.getByTestId('settlement-category-0').selectOption({ label: 'Cleaning' });
      await page.getByTestId('settlement-description-0').fill('Deep cleaning after move-out');
      await page.getByTestId('settlement-amount-0').fill('1500');
      await expectText(page.getByTestId('settlement-net-refund'), '11,612.33', 'Refund to tenant');
      await pointAt(page.getByTestId('settlement-net-refund'));
      await pace(page, 1200);
      await page.getByTestId('settlement-save-draft').click();
      await page.getByTestId('settlement-status').filter({ hasText: 'Draft' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 2000);
      await page.getByTestId('settlement-acknowledge').check();
      await pace(page, 800);
      await page.getByTestId('settlement-finalize').click();
      await page.getByText('Finalize this settlement?', { exact: true }).waitFor({ state: 'visible' });
      await pace(page, 700);
      await page.getByTestId('settlement-finalize-confirm').click();
      await page.getByTestId('settlement-status').filter({ hasText: 'Finalized' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('settlement-journal').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('settlement-status').scrollIntoViewIfNeeded();
      await restPointer(page, 1100, 400);
      await pointAt(page.getByTestId('settlement-journal'));
    }, { weight: 27.4 }),
];

export default { role: 'tenantAdmin', scenes };
