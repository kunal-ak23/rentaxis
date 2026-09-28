// Tutorial 14. Default role for scenes that do not name one: tenantAdmin.
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/settings/fines', 'Fine configuration', 'Configure supported cheque-failure reasons and penalty amounts before processing failures.'),
  routeScene('/en/dashboard/finance/payments', 'Cheque failure workflow', 'Choose the exact failure reason so the correct auditable penalty is generated.'),
  routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Lease penalties', 'Review assessed penalties, payment state, receipts, and related installment history.', async (page) => {
    await page.getByTestId('lease-tab-payments').click();
  }),
];

export default { role: 'tenantAdmin', scenes };
