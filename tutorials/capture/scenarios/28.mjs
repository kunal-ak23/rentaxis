// Tutorial 28. Default role for scenes that do not name one: tenantAdmin.
import { routeScene } from '../lib/scenes.mjs';
import { promotionBusinessName } from '../lib/fixtures.mjs';

const scenes = [
  routeScene('/en/dashboard/promotions', 'Promotions and offers', 'Manage participating businesses and targeted resident ads from one workspace.', async (page) => {
    await page.getByRole('tab', { name: 'Businesses', exact: true }).click();
    await page.waitForTimeout(350);
  }),
  routeScene('/en/dashboard/promotions', 'Coupon configuration', 'Set bilingual copy, placement, dates, property targeting, CTA, code, and terms.', async (page) => {
    await page.getByRole('tab', { name: 'Ads', exact: true }).click();
    await page.getByRole('button', { name: 'Edit', exact: true }).click();
  }),
  routeScene('/en/dashboard/promotions', 'Engagement analytics', 'Impressions, clicks, and tap-through rate show whether a live promotion is being used.', async (page) => {
    await page.locator('select[aria-label="Business"]').selectOption({ label: promotionBusinessName });
    await page.waitForTimeout(350);
  }),
];

export default { role: 'tenantAdmin', scenes };
