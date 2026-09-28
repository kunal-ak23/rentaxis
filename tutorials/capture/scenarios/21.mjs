// Tutorial 21. Default role for scenes that do not name one: renter.
import { publicRouteScene } from '../lib/scenes.mjs';

const scenes = [
  publicRouteScene('/en/dashboard/renter-portal/payments', 'My payments', 'Residents review installment due dates, methods, status, and payment history from one schedule.'),
  publicRouteScene('/en/dashboard/renter-portal/payments', 'Receipts and history', 'Open completed items for receipts and retain failed or pending states for follow-up.'),
  publicRouteScene('/en/dashboard/renter-portal', 'Renter dashboard', 'Online payment appears only when the organisation and property have an active supported gateway.'),
];

export default { role: 'renter', scenes };
