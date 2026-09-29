// Tutorial 39 — Start your books: opening balances and cut-over (Palm Ridge
// Properties). The Company Admin Karim Saleh shows the books start date on Year
// End Closing; the Accountant Rania Khoury walks Import Batches (none: Palm
// Ridge's contracts were entered one by one), the posted Opening Balances grid
// (derived rows greyed, four manual figures, 430,000 = 430,000), the OB-25/1
// journal, the AP opening item CFS-2508-114 and Cut-over reconciliation.
// Read-only: the opening balances are dated inside the locked period, so they
// are shown posted rather than posted on camera. No snapshot needed.
//
// Weights are seconds of narration per scene (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectCount, expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const obRow = (page, code) => page.getByTestId('ob-grid').locator('tbody tr').filter({ hasText: code }).first();

const scenes = [
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/fiscal', 'When the books start',
    'The Company Admin sets the books start date. The period before it is locked.', {
    weight: 26.0,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const start = page.getByTestId('fiscal-books-start');
      await start.waitFor({ state: 'visible', timeout: navTimeoutMs });
      if ((await start.inputValue()) !== '2025-09-01') throw new Error(`Books start: ${await start.inputValue()}`);
      await restPointer(page, 1200, 600);
      await at(11.0);
      await pointAt(start);
      await at(14.5);
      await pointAt(page.locator('main select').first());
      await at(17.8);
      await pointAt(page.getByTestId('fiscal-books-start-rule'));
    },
  }),
  roleRouteScene('accountant', '/en/dashboard/finance/import-batches', 'Import Batches',
    'Running contracts come across from the cut-over workbook, one batch per import.', {
    weight: 26.0,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('batches-empty').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1200, 600);
      await at(4.5);
      await pointAt(page.getByTestId('download-template'));
      await at(7.5);
      await pointAt(page.getByText('Upload cut-over workbook', { exact: true }).first());
      await at(11.5);
      await pointAt(page.getByText(/Reversing a batch reverses every journal it wrote/).first());
      await at(20.5);
      await pointAt(page.getByTestId('batches-empty'));
    },
  }),
  stepScene('Opening Balances',
    'Derived accounts come from the contracts. Everything else is entered as at 31/08/2025.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-opening-balances').click();
      await page.getByTestId('ob-grid').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('ob-posted-banner'), 'OB-25/1', 'Posted banner');
      await expectText(obRow(page, '100001'), 'cannot be typed here', 'Derived row');
      await expectText(obRow(page, '100005'), '250,000.00', 'Residences bank');
      await expectText(obRow(page, '100026'), '180,000.00', 'Business Centre bank');
      await expectText(obRow(page, '100043'), '12,600.00', 'Coastline');
      await expectText(obRow(page, 'F-01'), '417,400.00', 'Capital');
      await expectText(page.locator('main'), 'Difference: 0.00', 'Difference');
      await restPointer(page, 1200, 600);
      await at(2.0);
      await pointAt(obRow(page, '100001').locator('td').last());
      await at(6.0);
      await pointAt(obRow(page, '100004').locator('td').last());
      await at(10.0);
      await pointAt(obRow(page, '100006').locator('td').last());
      await at(14.0);
      await pointAt(page.getByText('Balances as at 31/08/2025', { exact: false }).first());
      await at(17.8);
      await pointAt(obRow(page, '100005'));
      await at(20.0);
      await pointAt(obRow(page, '100026'));
      await at(22.3);
      await pointAt(obRow(page, '100043'));
      await at(24.5);
      await pointAt(obRow(page, 'F-01'));
      await at(26.6);
      await pointAt(page.getByText('Difference: 0.00', { exact: true }));
      await at(31.0);
      await page.getByTestId('ob-posted-banner').scrollIntoViewIfNeeded();
      await pointAt(page.getByTestId('ob-posted-banner'));
    }, { weight: 35.45 }),
  stepScene('The opening balance journal',
    'OB-25/1, dated 31/08/2025: the banks debited, the supplier and capital credited.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.6);
      await page.getByTestId('sidebar-journals').click();
      const type = page.locator('main select').first();
      await type.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await type.selectOption('OB');
      await page.getByRole('button', { name: 'Apply', exact: true }).click();
      const link = page.locator('main table').getByRole('link', { name: 'OB-25/1', exact: true });
      await link.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.locator('main table tbody tr'), 1, 'OB journals');
      await at(4.0);
      await link.click();
      await page.getByText('Journal Voucher OB-25/1', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      const lines = page.locator('main table').first().locator('tbody tr');
      await expectCount(lines, 4, 'OB journal lines');
      await restPointer(page, 1200, 700);
      await at(7.9);
      await pointAt(lines.filter({ hasText: 'Emirates Islamic - Palm Ridge Residences' }));
      await at(9.6);
      await pointAt(lines.filter({ hasText: 'Emirates Islamic - Palm Ridge Business Centre' }));
      await at(11.6);
      await pointAt(lines.filter({ hasText: 'Coastline' }));
      await at(13.2);
      await pointAt(lines.filter({ hasText: 'Capital' }));
      await at(14.8);
      await pointAt(page.locator('main table').first().locator('tr').filter({ hasText: 'Total' }).filter({ hasText: '430,000.00' }).first());
    }, { weight: 16.15 }),
  stepScene('AP opening items',
    'The supplier invoice open at cut-over, so it can be paid and aged like any other.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-payables-opening').click();
      const item = page.getByTestId('opening-item-CFS-2508-114');
      await item.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(item, '12,600.00', 'Opening item');
      const check = page.getByTestId('ob-check');
      await restPointer(page, 1200, 600);
      await at(4.8);
      await pointAt(item.getByText('CFS-2508-114', { exact: true }));
      await at(9.0);
      await pointAt(item);
      await at(13.8);
      const checkRow = page.locator('main tr').filter({ hasText: 'Coastline Facility Services LLC' }).first();
      await expectText(checkRow, '12,600.00 12,600.00 0.00', 'Opening check');
      await pointAt(checkRow);
      if (await check.isVisible().catch(() => false)) await pointAt(checkRow);
    }, { weight: 18.1 }),
  stepScene('Cut-over reconciliation',
    'Our balance, leaving out the opening journal, against the old trial balance.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-reconciliation').click();
      await page.getByTestId('rec-table').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('rec-total-difference'), '0.00', 'Total difference');
      await expectCount(page.locator('[data-testid^="rec-row-"]'), 4, 'reconciliation rows');
      await restPointer(page, 1200, 600);
      await at(2.2);
      await pointAt(page.getByTestId('rec-derived-100005'));
      await at(5.5);
      await pointAt(page.getByTestId('rec-pact-100005'));
      await at(8.8);
      await pointAt(page.getByTestId('rec-out-of-balance'));
      await at(11.0);
      await pointAt(page.getByTestId('rec-row-F-01'));
      await at(15.8);
      await pointAt(page.getByTestId('rec-total-difference'));
    }, { weight: 21.3 }),
];

export default { role: 'accountant', scenes };
