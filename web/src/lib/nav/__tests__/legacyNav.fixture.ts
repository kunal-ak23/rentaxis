// src/lib/nav/__tests__/legacyNav.fixture.ts
import type { UserRole } from "../../rbac";

const D = "/dashboard";
const F = "/dashboard/finance";
const SHELL = [D, `${D}/help`];
const LEDGER = [`${F}/accounts`, `${F}/journals`, `${F}/general-ledger`, `${F}/tenant-ledger`, `${F}/trial-balance`];
const CUTOVER = [`${F}/vouchers`, `${F}/import-batches`, `${F}/opening-balances`, `${F}/reconciliation`, `${F}/recognition`];
const CHEQUES = [`${F}/cheques`, `${F}/cheques/collection`, `${F}/cheques/return-replace`, `${F}/cheques/post-dated`, `${F}/penalties`];
const PROPERTY_REPORTS = [`${F}/reports/property-pl`, `${F}/reports/property-statement`, `${F}/reports/balance-sheet`];
const FINANCE_REPORTS = [`${F}/reports/company-pl`, `${F}/reports/vat-return`, `${F}/payables/aging`,
    `${F}/payables/opening-items`, `${F}/payables/payment-runs`, `${F}/payables/issued-cheques`, `${F}/bank-reconciliation`];
const SETUP = [`${D}/settings/account-template`, `${D}/settings/fiscal`, `${D}/settings/charge-types`];
const WORKSPACE_PM = [`${D}/properties`, `${D}/renters`, `${D}/leases`, `${D}/listings`, `${D}/tickets`, `${D}/meetings`, `${D}/gatepass`, `${D}/bookings`];

const TENANT_ADMIN = [...SHELL, ...WORKSPACE_PM, `${D}/promotions`, "/superadmin/users",
    ...LEDGER, ...CUTOVER, ...CHEQUES, `${F}/vendors`, `${F}/bank-accounts`,
    ...PROPERTY_REPORTS, ...FINANCE_REPORTS, `${D}/staff`,
    ...SETUP, `${D}/settings/gateway`, `${D}/settings/rent-settings`, `${D}/settings/fines`];

export const LEGACY_NAV: Record<UserRole, string[]> = {
    SUPER_ADMIN: [...TENANT_ADMIN, "/superadmin/tenants"],
    TENANT_ADMIN,
    ACCOUNTANT: [...SHELL, `${D}/leases`, ...LEDGER, ...CUTOVER, ...CHEQUES, `${F}/vendors`,
        ...PROPERTY_REPORTS, ...FINANCE_REPORTS, ...SETUP],
    PROPERTY_MANAGER: [...SHELL, ...WORKSPACE_PM, ...CHEQUES, ...PROPERTY_REPORTS, `${F}/payables/aging`],
    TENANT_USER: [...SHELL, `${D}/my-unit`],
    RENTER: [...SHELL, `${D}/renter-portal`, `${D}/renter-portal/payments`, `${D}/renter-portal/penalties`,
        `${D}/tickets`, "/marketplace/acme", `${D}/meetings`],
    SECURITY_GUARD: [...SHELL],
};
