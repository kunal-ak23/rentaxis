// Tutorial 40 — Returned cheques: replace, settle in cash, write off and recover
// (Palm Ridge Properties, the Accountant Rania Khoury), told on the seeded year:
// Layla Nasser's 220102 (15,000, returned February) Replaced by 220190 and
// 220191 (7,500 each, cleared) plus the 500 penalty, and the same story in her
// Tenant Ledger (CBR-26/1 reopens Rent Receivable, PEN-26/1, PDR-26/16/17 close
// it); Arjun Mehta's 330103 settled by a CASH replacement row (14,000); Daniel
// Brooks' terminated contract and its Write off drawer (BDW-26/1, 11,326.03,
// recovery BDR-26/1, 5,000); Cheque / Cash Collection's Due and Returned /
// replace no longer list Daniel's 440102 (#397).
//
// Read-only: the proof and the capture start from snapshot pre40 (the Palm
// Ridge base on the current schema) so the register is the seeded one.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { palmLease, palmTenant } from '../lib/fixtures.mjs';
import { expectCount, expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const regRows = (page) => page.locator('[data-testid^="cheque-row-"]:not([data-testid*="-action-"])');
const regRow = (page, text) => regRows(page).filter({ hasText: text }).first();
const row = (page, text) => page.locator('main tr').filter({ hasText: text }).first();

async function searchRegister(page, query) {
  await page.getByTestId('cheque-search').fill('');
  await page.getByTestId('cheque-search').pressSequentially(query, { delay: 45 });
  await page.getByTestId('cheque-filter-apply').click();
}

const scenes = [
  roleRouteScene('accountant', '/en/dashboard/collections?tab=all', 'Replaced',
    "Layla Nasser's 220102 came back in February; two cheques of 7,500 replaced it and both cleared.", {
    weight: 28.35,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('cheque-search').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 560);
      await at(11.0);
      await searchRegister(page, 'Layla');
      const returned = regRow(page, '220102');
      await expectText(returned, 'REPLACED', 'Returned cheque status');
      await expectText(regRow(page, '220190'), 'CLEARED', 'Replacement 1');
      await expectText(regRow(page, '220191'), 'CLEARED', 'Replacement 2');
      await restPointer(page, 1300, 560);
      await at(16.4);
      await pointAt(returned);
      await at(19.4);
      await pointAt(returned.getByText('REPLACED'));
      await at(21.6);
      await pointAt(regRow(page, '220190'));
      await at(23.2);
      await pointAt(regRow(page, '220191'));
      await at(25.4);
      await pointAt(regRows(page).filter({ hasText: '500.00' }).filter({ hasText: 'Cash' }).first());
    },
  }),
  stepScene('In the Tenant Ledger',
    'CBR-26/1 reopens Rent Receivable 15,000; the penalty comes and goes; PDR-26/16 and 26/17 close it.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.6);
      await page.goto(`/en/dashboard/finance/tenant-ledger?renterId=${palmTenant('layla')}&from=2025-09-01&to=2026-08-31`);
      const cbr = row(page, 'CBR-26/1');
      await cbr.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(cbr, '15,000.00 Dr', 'Balance after the return');
      await expectText(row(page, 'PDR-26/17'), '0.00', 'Balance after the replacements');
      await restPointer(page, 1300, 560);
      await at(4.0);
      await pointAt(cbr);
      await at(12.4);
      await pointAt(row(page, 'PEN-26/1'));
      await at(15.2);
      await pointAt(row(page, 'PDR-26/16'));
      await at(17.0);
      await pointAt(row(page, 'PDR-26/17'));
      await at(22.6);
      await pointAt(row(page, 'Sub Total'));
    }, { weight: 28.36 }),
  stepScene('Settled in cash',
    "Arjun Mehta's 330103: a CASH replacement row, received on the spot, 14,000.",
    async (page) => {
      const at = sceneClock(page);
      await page.goto('/en/dashboard/collections?tab=all');
      await page.getByTestId('cheque-search').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await searchRegister(page, 'Arjun');
      const returned = regRow(page, '330103');
      await expectText(returned, 'REPLACED', 'Returned cheque status');
      const cash = regRows(page).filter({ hasText: '14,000.00' }).filter({ hasText: 'Cash' }).first();
      await expectText(cash, 'CLEARED', 'Cash replacement');
      await restPointer(page, 1300, 560);
      await at(2.4);
      await pointAt(returned);
      await at(6.0);
      await pointAt(cash);
      await at(9.4);
      await pointAt(cash.getByText('CLEARED'));
    }, { weight: 18.65 }),
  stepScene('Written off',
    "Daniel Brooks: terminated and settled; 11,326.03 written off (BDW-26/1), approved by the Company Admin.",
    async (page) => {
      const at = sceneClock(page);
      await page.goto(`/en/dashboard/leases/${palmLease('daniel')}`);
      await expectText(page.getByTestId('lease-status'), 'Terminated', 'Contract status');
      await restPointer(page, 1300, 560);
      await at(2.0);
      await pointAt(page.getByTestId('lease-status'));
      await at(10.0);
      await page.getByRole('button', { name: 'More actions' }).click();
      await at(11.0);
      await page.getByRole('menuitem', { name: 'Write off' }).click();
      const drawer = page.getByTestId('lease-write-off-drawer');
      const list = drawer.getByTestId('bd-list');
      await list.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(list, '11,326.03', 'Written off');
      await expectText(drawer.getByTestId('bd-journal'), 'BDW-26/1', 'Write-off journal');
      await restPointer(page, 1300, 560);
      await at(13.6);
      await pointAt(list.getByText('11,326.03').first());
      await at(18.4);
      await pointAt(drawer.getByTestId('bd-journal'));
    }, { weight: 26.86 }),
  stepScene('Recovered',
    '5,000 recovered in July (BDR-26/1): Dr bank, Cr Bad debts recovered. The write-off stays.',
    async (page) => {
      const at = sceneClock(page);
      const rec = page.getByTestId('lease-write-off-drawer').getByTestId('bd-recoveries');
      await expectText(rec, 'BDR-26/1', 'Recovery journal');
      await expectText(rec, '5,000.00', 'Recovered');
      await at(1.0);
      await pointAt(rec);
    }, { weight: 11.57 }),
  stepScene('Off the due lists',
    "Daniel's 440102 is neither due nor waiting for a replacement.",
    async (page) => {
      await page.goto('/en/dashboard/collections?tab=due');
      await page.getByTestId('collections-count-returned').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.locator('main tr').filter({ hasText: '880102' }).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('collections-count-returned'), '0', 'Returned / replace count');
      await expectCount(page.locator('main tr').filter({ hasText: '440102' }), 0, "Daniel's cheque in Due");
      await pointAt(page.getByTestId('collections-pill-returned'));
    }, { weight: 5.97 }),
];

export default {
  role: 'accountant',
  anchored: true,
  warmup: [{ role: 'accountant', path: '/en/dashboard/collections?tab=all' }],
  scenes,
};
