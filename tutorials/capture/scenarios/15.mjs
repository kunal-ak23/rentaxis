// Tutorial 15. Default role for scenes that do not name one: tenantAdmin.
//
// The old /dashboard/finance/payments route never existed under that name —
// every cheque screen is now a pill on the Cheque / Cash Collection hub
// (/dashboard/collections?tab=…), which 308s the old /finance/cheques* paths
// there (see src/lib/nav/routeMap.ts). Ahmed's contract already carries a
// mixed register — cleared, one deposited, one bounced+replaced, one still
// registered (scripts/seed_demo_tenant.py:14-18) — so the register pills and
// the lease's own cheque grid both have rows to show without any new data.
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/collections?tab=due', 'Due and overdue cheques', 'The Due pill lists every registered cheque coming up for collection or already overdue, across every property.'),
  routeScene('/en/dashboard/collections?tab=deposit', 'Deposit to bank', 'Select the cleared-to-deposit cheques and record them against the bank in one batch.'),
  routeScene('/en/dashboard/collections?tab=all', 'Cheque register', 'The register is every cheque on every contract, filterable by status, mode and property.'),
  routeScene('/en/dashboard/collections?tab=returned', 'Return and replace', 'A bounced cheque is replaced here with new cheques for the same balance, keeping the original bounce on record.'),
  routeScene('/en/dashboard/collections?tab=post-dated', 'Post-dated cheques', 'The PDC schedule groups upcoming post-dated cheques by month with a running total.'),
  routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Bulk cheque images', 'On the contract’s own Payments tab, upload the scanned cheque images for every registered PDC row in one pass.', async (page) => {
    await page.getByTestId('lease-tab-payments').click();
  }),
];

export default { role: 'tenantAdmin', scenes };
