// Tutorial 16. Default role for scenes that do not name one: tenantAdmin.
//
// Settings > Payment gateway and Settings > Rent settings are gone as
// separate routes; both 308 into the one Settings page (routeMap.ts). The
// gateway picker AND the per-property online-payment switch both now live
// in Settings > Payments (?section=payments) — the switch moved out of Rent
// settings under PR #363 — while Settings > Rent (?section=rent) keeps only
// the fine and grace-period fields (RentSettings embedded
// hideOnlinePaymentToggle). GatewaySettings has no deactivate action today —
// only Test Connection and Save Configuration — so the tour ends at
// verifying the Tenant sees Pay Now once a property is switched on, not at
// winding the configuration back down (see tutorials/bugs).
import { routeScene, publicRouteScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/settings?section=payments', 'Payment gateway configuration', 'Select the approved provider and test mode before entering credentials.'),
  routeScene('/en/dashboard/settings?section=payments', 'Credential safety', 'Saved secrets remain masked; use Test Connection without revealing credentials on camera.', async (page) => {
    await page.getByText(/Test Connection/i).first().waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
  }),
  routeScene('/en/dashboard/settings?section=payments', 'Switch on a property', 'The online-payment switch is per property: pick one, then flip it on once the gateway is configured.', async (page) => {
    const propertySelect = page.getByTestId('online-payment-switch').locator('select');
    await propertySelect.locator('option').nth(1).waitFor({ state: 'attached', timeout: 15000 });
    await propertySelect.selectOption({ index: 1 });
  }),
  routeScene('/en/dashboard/settings?section=rent', 'Rent settings', 'Grace days, the late rate, and the due day of month live in their own Rent and fines section.'),
  { ...publicRouteScene('/en/dashboard/renter-portal/payments', 'Tenant sees Pay Now', 'Once a property is switched on, an eligible cheque on that property shows Pay Now for the Tenant.'), role: 'renter' },
];

export default { role: 'tenantAdmin', scenes };
