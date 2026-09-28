// Tutorial 16. Default role for scenes that do not name one: tenantAdmin.
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/settings/gateway', 'Payment gateway configuration', 'Select the approved provider and test mode before entering credentials.'),
  routeScene('/en/dashboard/settings/gateway', 'Credential safety', 'Saved secrets remain masked; use the connection test without revealing credentials in recordings.'),
  routeScene('/en/dashboard/settings/rent-settings', 'Online rent settings', 'Property-level settings control readiness, payment instructions, and permitted collection behavior.', async (page) => {
    const propertySelect = page.locator('select').first();
    await propertySelect.locator('option').nth(1).waitFor({ state: 'attached', timeout: 15000 });
    await propertySelect.selectOption({ index: 1 });
    await page.getByText('Loading settings...', { exact: true }).waitFor({ state: 'detached', timeout: 15000 });
    await page.getByText(/Online payment|Rent due|Grace period/i).first().waitFor({ state: 'visible', timeout: 15000 });
  }),
];

export default { role: 'tenantAdmin', scenes };
