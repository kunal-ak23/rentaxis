// Tutorial 25. Default role for scenes that do not name one: tenantAdmin.
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/gatepass', 'Gate-pass operations', 'Review the Gate Pass Report filters, scan count, empty state, and audit columns before investigating an entry or exit.'),
  routeScene('/en/dashboard/gatepass', 'Scope by property', 'Use the property selector to narrow the gate audit to one building and verify the resulting scan count.', async (page) => {
    await page.locator('#gatepass-property').selectOption({ label: 'RentAxis Academy Residence Tower' });
    await page.waitForTimeout(450);
  }),
  routeScene('/en/dashboard/gatepass', 'Entry and exit audit', 'Adjust the date range when investigating a period, then export the filtered report when records are available.', async (page) => {
    await page.locator('#gatepass-from').fill('2026-01-01');
    await page.locator('#gatepass-to').fill('2026-12-31');
    await page.waitForTimeout(450);
  }),
];

export default { role: 'tenantAdmin', scenes };
