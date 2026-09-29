// Tutorial 14 — Penalties, fines and recharges (Palm Ridge Properties). The
// Company Admin Karim Saleh shows the organisation's cheque-failure fines and
// proposal rules; the Accountant Rania Khoury reads the Proposed queue
// (Mohammed Al Rashid's late payment 250, Hana Yoshida's maintenance recharge
// 800 from ticket TKT-26/1), approves both, reads their PEN journals (Dr Rent
// Receivable / Cr Rent Penalty 250; Dr Rent Receivable / Cr Maintenance Charges
// 800) and the Approved tab with Reverse. Palm Ridge's tenants have no portal
// accounts, so the old Tenant-view scene is dropped.
//
// Approving cannot be undone: the proof and the capture each start from snapshot
// pre14 (the Palm Ridge base).
//
// Weights are seconds of narration per scene (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectCount, expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const field = (page, label) => page.locator('main label, main p, main span, main div')
  .filter({ hasText: new RegExp(`^${label}$`, 'i') }).first()
  .locator('xpath=following::input[1]');
const confirmButton = (page) => page.getByTestId('lease-dialog-cancel').locator('xpath=following-sibling::button[1]');
const lines = (page) => page.locator('main table').first().locator('tbody tr');
const line = (page, account, amount) => lines(page).filter({ hasText: account }).filter({ hasText: amount }).first();

async function approveFirst(page, who) {
  const row = page.getByTestId('penalty-row-0');
  await expectText(row, who, 'First proposed row');
  await page.getByTestId('penalty-approve-0').click();
  await page.getByTestId('penalty-approve-breakdown').waitFor({ state: 'visible', timeout: navTimeoutMs });
  await pointAt(page.getByTestId('penalty-decision-date'));
  await confirmButton(page).click();
  await page.getByTestId('penalty-approve-breakdown').waitFor({ state: 'detached', timeout: navTimeoutMs });
  await page.getByTestId('penalty-queue').getByText(who, { exact: true }).waitFor({ state: 'detached', timeout: navTimeoutMs });
}

async function openPen(page, docNo) {
  await page.getByTestId('sidebar-journals').click();
  const type = page.locator('main select').first();
  await type.waitFor({ state: 'visible', timeout: navTimeoutMs });
  await type.selectOption('PEN');
  await page.getByRole('button', { name: 'Apply', exact: true }).click();
  const link = page.locator('main table').getByRole('link', { name: docNo, exact: true });
  await link.waitFor({ state: 'visible', timeout: navTimeoutMs });
  await link.click();
  await page.getByText(`Journal Voucher ${docNo}`, { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
}

const scenes = [
  roleRouteScene('tenantAdmin', '/en/dashboard/settings?section=rent', 'Cheque-failure fines',
    'Set once for the organisation. Rules propose; nothing posts by itself.', {
    weight: 34.1,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const bounce = field(page, 'Bounce fine \\(AED\\)');
      await bounce.waitFor({ state: 'visible', timeout: navTimeoutMs });
      if ((await bounce.inputValue()) !== '500') throw new Error(`Bounce fine ${await bounce.inputValue()}`);
      await restPointer(page, 1300, 640);
      await at(12.9);
      await pointAt(bounce);
      await at(17.4);
      await pointAt(field(page, 'Signature mismatch fine \\(AED\\)'));
      await at(19.8);
      await pointAt(field(page, 'Account closed fine \\(AED\\)'));
      await at(22.2);
      await pointAt(field(page, 'Bounces before a penalty is proposed'));
      await at(24.4);
      await pointAt(page.getByText('Auto-propose on a returned cheque past the threshold', { exact: true }));
      await at(27.2);
      await pointAt(page.getByText('Auto-propose when rent clears after its grace period', { exact: true }));
    },
  }),
  roleRouteScene('accountant', '/en/dashboard/collections?tab=penalties', 'The penalties queue',
    'Proposed items wait for finance: approve, waive or reduce.', {
    weight: 28.3,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('penalty-row-1').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.locator('[data-testid^="penalty-row-"]'), 2, 'proposed penalties');
      await expectText(page.getByTestId('penalty-row-0'), '250.00', 'Late payment');
      await expectText(page.getByTestId('penalty-row-1'), '800.00', 'Recharge');
      await restPointer(page, 1300, 640);
      await at(4.7);
      await pointAt(page.getByTestId('penalty-tab-PROPOSED'));
      await at(7.1);
      await pointAt(page.getByTestId('penalty-description-0'));
      await at(11.0);
      await pointAt(page.getByTestId('penalty-row-0').getByText('250.00', { exact: true }));
      await at(14.6);
      await pointAt(page.getByTestId('penalty-row-1').getByText('800.00', { exact: true }));
      await at(18.1);
      await pointAt(page.getByTestId('penalty-description-1'));
      await at(21.7);
      await pointAt(page.getByTestId('penalty-waive-0'));
      await at(25.2);
      await pointAt(page.getByTestId('penalty-reduce-0'));
    },
  }),
  stepScene('Approve',
    'Approval is the only step that posts.',
    async (page) => {
      await approveFirst(page, 'Mohammed Al Rashid');
      await approveFirst(page, 'Hana Yoshida');
    }, { weight: 7.0 }),
  stepScene('The penalty journals',
    'PEN: Rent Receivable debited; Rent Penalty or Maintenance Charges credited.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('rail-accounting').click();
      await openPen(page, 'PEN-26/2');
      await expectText(line(page, 'Rent Penalty', '250.00'), '250.00', 'Penalty credit');
      await restPointer(page, 1200, 700);
      await at(3.6);
      await pointAt(line(page, 'Rent Receivable', '250.00'));
      await at(5.6);
      await pointAt(line(page, 'Rent Penalty', '250.00'));
      await at(11.3);
      await openPen(page, 'PEN-26/3');
      await expectText(line(page, 'Maintenance Charges', '800.00'), '800.00', 'Recharge credit');
      await at(13.0);
      await pointAt(line(page, 'Rent Receivable', '800.00'));
      await at(15.0);
      await pointAt(line(page, 'Maintenance Charges', '800.00'));
    }, { weight: 20.9 }),
  stepScene('Approved, and Reverse',
    'Reversing posts the mirror journal; nothing is deleted.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('rail-collection').click();
      await page.getByTestId('nav-panel').getByText('Penalties', { exact: true }).click();
      await page.getByTestId('penalty-tab-APPROVED').click();
      const rows = page.locator('[data-testid^="penalty-row-"]');
      await page.getByTestId('penalty-queue').getByText('Layla Nasser', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(rows, 3, 'approved penalties');
      await restPointer(page, 1300, 640);
      await at(1.5);
      await pointAt(page.getByTestId('penalty-tab-APPROVED'));
      await at(4.4);
      await pointAt(rows.filter({ hasText: 'Layla Nasser' }).getByText('500.00', { exact: true }));
      await at(7.6);
      await pointAt(page.getByTestId('penalty-reverse-1'));
      await at(10.0);
      await pointAt(page.getByTestId('penalty-reverse-2'));
    }, { weight: 19.0 }),
];

export default {
  role: 'accountant',
  anchored: true,
  warmup: [
    { role: 'tenantAdmin', path: '/en/dashboard/settings?section=rent' },
    { role: 'accountant', path: '/en/dashboard/collections?tab=penalties' },
    { role: 'accountant', path: '/en/dashboard/finance/journals' },
  ],
  scenes,
};
