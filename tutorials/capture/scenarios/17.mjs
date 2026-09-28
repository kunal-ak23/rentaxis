// Tutorial 17. Default role for scenes that do not name one: tenantAdmin.
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/finance/accounts', 'Chart of Accounts', 'Review the seeded hierarchy before adding or editing a ledger account.'),
  routeScene('/en/dashboard/settings/account-mappings', 'Account mappings', 'Map rent, deposits, penalties, expenses, and other events to the intended ledger codes.'),
  routeScene('/en/dashboard/finance/transactions', 'Downstream ledger effect', 'Verify that operational events post through the configured accounts with traceable descriptions.'),
];

export default { role: 'tenantAdmin', scenes };
