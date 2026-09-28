// Tutorial 15. Default role for scenes that do not name one: tenantAdmin.
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/finance/payments', 'Rent cheque operations', 'Use the payment workspace to follow pending, collected, deposited, cleared, and bounced cheques.'),
  routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Lease payment schedule', 'Each installment retains its amount, due date, method, cheque metadata, and state.'),
  routeScene('/en/dashboard/finance/payments', 'Operational follow-up', 'Filter by the current cheque state before collecting, depositing, clearing, or marking a failure.'),
];

export default { role: 'tenantAdmin', scenes };
