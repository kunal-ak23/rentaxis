// Tutorial 19. Default role for scenes that do not name one: tenantAdmin.
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/finance/vendors', 'Vendor directory', 'Keep supplier identity and contact records separate from financial transactions.'),
  routeScene('/en/dashboard/finance/transactions', 'Vendor-linked expenses', 'Split a single supplier invoice across properties or units when allocation is required.'),
  routeScene('/en/dashboard/finance/bank-accounts', 'Bank accounts', 'Maintain approved property bank accounts and identify the default account clearly.'),
];

export default { role: 'tenantAdmin', scenes };
