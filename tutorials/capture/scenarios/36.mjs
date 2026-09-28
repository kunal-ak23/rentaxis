// Tutorial 36. Default role for scenes that do not name one: tenantAdmin.
import { advanceRentAccountId, ahmedLeaseId, contractYearStart, lastMonthEnd } from '../lib/fixtures.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

const scenes = [
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${ahmedLeaseId}`, 'Read the recognition schedule',
    'The term is divided by its real day count to get a daily rate, then multiplied by the real number of days in each month.', {
    weight: 75,
    afterNavigation: async (page) => {
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('lease-section-toggle-recognition').click();
      await page.getByTestId('recognition-schedule').waitFor({ state: 'visible', timeout: 20_000 });
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${ahmedLeaseId}`, 'A short first period and a rounding row',
    'A contract that starts mid month opens short and closes short, and the last row absorbs the rounding so the schedule adds up exactly.', {
    weight: 50,
    afterNavigation: async (page) => {
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('lease-section-toggle-recognition').click();
      await page.getByTestId('recognition-schedule').waitFor({ state: 'visible', timeout: 20_000 });
      await page.getByTestId('recognition-schedule').locator('tbody tr').last().scrollIntoViewIfNeeded();
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/recognition', 'Preview the close',
    'Entering a cut-off date lists every planned period that ends on or before it, with a total. Read the total before posting it.', {
    weight: 70,
    afterNavigation: async (page) => {
      await page.getByTestId('recognition-to-date').fill(lastMonthEnd());
      await page.getByTestId('recognition-future-warning').waitFor({ state: 'detached' }).catch(() => {});
      await page.getByTestId('recognition-preview').click();
      await page.getByTestId('recognition-result-title').waitFor({ state: 'visible', timeout: 40_000 });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/recognition', 'Run it',
    'Each period becomes one journal dated the last day of that period, debiting advance rent and crediting rental income.', {
    weight: 55,
    afterNavigation: async (page) => {
      await page.getByTestId('recognition-to-date').fill(lastMonthEnd());
      await page.getByTestId('recognition-preview').click();
      await page.getByTestId('recognition-result-title').waitFor({ state: 'visible', timeout: 40_000 });
      await page.getByTestId('recognition-run').click();
      await page.getByTestId('recognition-run-confirm').click();
      await page.getByTestId('recognition-result-title').waitFor({ state: 'visible', timeout: 60_000 });
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/finance/general-ledger?accountId=${advanceRentAccountId}`, 'Watch advance rent fall',
    'The advance rent balance drops by exactly what was recognised, and rental income rises by the same amount.', {
    weight: 45,
    afterNavigation: async (page) => {
      // These contracts are dated 1 January and the filter opens on the
      // current month, so the report is empty until the From box moves back.
      await page.locator('#ledger-from').fill(contractYearStart());
      await page.getByRole('button', { name: 'Apply' }).click();
      await page.getByRole('row').filter({ hasText: /CIL-/ }).first()
        .waitFor({ state: 'visible', timeout: 20_000 });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/journals', 'It also runs nightly',
    'A backdated contract catches up on its own. Running it by hand is for closing a period on purpose.', {
    weight: 25,
    afterNavigation: async (page) => {
      // Doc type alone, not a date window as well: each filter change is its
      // own fetch, and a take was lost to the last of three still being in
      // flight when the scene's hold began — the page held on an empty list
      // with Apply greyed out. The CIL rows are the point, not the range.
      await page.locator('#jv-doc-type').selectOption('CIL');
      await page.getByRole('button', { name: 'Apply' }).click();
      await page.getByRole('row').filter({ hasText: /CIL-/ }).first()
        .waitFor({ state: 'visible', timeout: 30_000 });
      await page.waitForLoadState('networkidle', { timeout: 30_000 }).catch(() => {});
    },
  }),
];

export default { role: 'tenantAdmin', scenes };
