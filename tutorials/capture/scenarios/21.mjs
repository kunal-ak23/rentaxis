// Tutorial 21. Default role for scenes that do not name one: renter.
import { publicRouteScene } from '../lib/scenes.mjs';

const scenes = [
  publicRouteScene('/en/dashboard/renter-portal/payments', 'My payments', 'Each due row shows its amount, due date, and cheque number, with an overdue badge when it has slipped past its date.'),
  publicRouteScene('/en/dashboard/renter-portal/payments', 'Receipts and history', 'Payment History keeps every past cheque with its state and a receipt link for anything already collected.', async (page) => {
    await page.locator('[data-testid^="receipt-link-"]').first().waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
  }),
  publicRouteScene('/en/dashboard/renter-portal', 'Tenant dashboard', 'Online payment appears only when the organisation and this property both have an active, tested payment gateway.'),
];

export default { role: 'renter', scenes };
