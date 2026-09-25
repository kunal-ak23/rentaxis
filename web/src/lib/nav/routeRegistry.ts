// src/lib/nav/routeRegistry.ts
import { hasPermission, type Permission, type UserRole } from "../rbac";
import { buildSettingsSections } from "./settingsModel";

export type RouteAllow = "authenticated" | Permission | ((role: UserRole) => boolean);
export interface RouteEntry { path: string; allow: RouteAllow; flag?: "LISTINGS" | "MEETINGS" | "GATEPASS" }

const r = (path: string, allow: RouteAllow, flag?: RouteEntry["flag"]): RouteEntry => ({ path, allow, ...(flag ? { flag } : {}) });
const D = "/dashboard";
const F = "/dashboard/finance";

/**
 * Every live dashboard/superadmin page and who should be able to reach it —
 * the Playwright sweep's route list and the filesystem guard's source of
 * truth. A moved page is NOT listed here; it is a `from` in routeMap.ts.
 */
export const DASHBOARD_ROUTES: RouteEntry[] = [
    r(D, "authenticated"), r(`${D}/help`, "authenticated"), r(`${D}/help/[slug]`, "authenticated"),
    r(`${D}/notifications`, "authenticated"), r(`${D}/profile`, "authenticated"),
    r(`${D}/properties`, "canViewProperties"), r(`${D}/properties/[id]`, "canViewProperties"), r(`${D}/properties/[id]/units`, "canViewProperties"),
    r(`${D}/renters`, "canViewProperties"), r(`${D}/renters/[id]`, "canViewProperties"),
    r(`${D}/leases`, "canViewLeases"), r(`${D}/leases/[id]`, "canViewLeases"),
    r(`${D}/leases/[id]/terminate`, "canPreviewTermination"), r(`${D}/leases/[id]/settlement`, "canViewSettlement"),
    r(`${D}/listings`, "canViewProperties", "LISTINGS"), r(`${D}/listings/[id]`, "canViewProperties", "LISTINGS"),
    r(`${D}/tickets`, "canViewProperties"), r(`${D}/tickets/[id]`, "canViewProperties"), r(`${D}/tickets/reports`, "canViewProperties"),
    r(`${D}/meetings`, "canManageMeetings", "MEETINGS"), r(`${D}/meetings/[id]`, "canManageMeetings", "MEETINGS"),
    // Role-gated only, like its nav link: the GATEPASS flag is not enforced (ruling 2026-09-25).
    r(`${D}/gatepass`, "canViewGatePassReport"),
    r(`${D}/bookings`, "canManageFacilities"), r(`${D}/promotions`, "canManagePromotions"),
    r(`${D}/staff`, "canAccessFinanceOps"),
    r(`${D}/settings`, role => buildSettingsSections(role).length > 0),
    r(`${F}/accounts`, "canAccessFinance"), r(`${F}/journals`, "canAccessFinance"), r(`${F}/journals/new`, "canPostJournals"),
    r(`${F}/journals/[id]`, "canAccessFinance"), r(`${F}/general-ledger`, "canAccessFinance"),
    r(`${F}/tenant-ledger`, "canAccessFinance"), r(`${F}/trial-balance`, "canAccessFinance"),
    r(`${F}/vouchers`, "canManageVouchers"), r(`${F}/vouchers/payment`, "canManageVouchers"),
    r(`${F}/vouchers/credit-note`, "canManageVouchers"), r(`${F}/vouchers/purchase-invoice`, "canManageVouchers"),
    r(`${F}/import-batches`, "canManageImportBatches"),
    r(`${F}/opening-balances`, "canManageOpeningBalances"), r(`${F}/reconciliation`, "canManageOpeningBalances"),
    r(`${F}/recognition`, "canRunRecognition"),
    r(`${F}/cheques`, "canManageCheques"), r(`${F}/cheques/collection`, "canManageCheques"),
    r(`${F}/cheques/return-replace`, "canManageCheques"), r(`${F}/cheques/post-dated`, "canManageCheques"),
    r(`${F}/penalties`, "canProposePenalties"),
    r(`${F}/vendors`, "canManageVendors"), r(`${F}/vendors/[id]`, "canManageVendors"),
    r(`${F}/bank-accounts`, "canAccessFinanceOps"),
    r(`${F}/bank-reconciliation`, "canReconcileBank"), r(`${F}/bank-reconciliation/[id]`, "canReconcileBank"),
    r(`${F}/payables/aging`, "canViewPayablesAging"), r(`${F}/payables/opening-items`, "canManagePayables"),
    r(`${F}/payables/payment-runs`, "canManagePayables"), r(`${F}/payables/payment-runs/new`, "canManagePayables"),
    r(`${F}/payables/payment-runs/[id]`, "canManagePayables"), r(`${F}/payables/issued-cheques`, "canManagePayables"),
    r(`${F}/reports/property-pl`, "canViewPropertyReports"), r(`${F}/reports/property-statement`, "canViewPropertyReports"),
    r(`${F}/reports/balance-sheet`, "canViewPropertyReports"), r(`${F}/reports/company-pl`, "canViewCompanyReports"),
    r(`${F}/reports/vat-return`, "canViewVatReturn"),
    r(`${F}/account-template`, "canManageAccountSetup"), r(`${F}/fiscal`, "canManageAccountSetup"), r(`${F}/charge-types`, "canManageAccountSetup"),
    r(`${D}/renter-portal`, "canViewRenterPortal"), r(`${D}/renter-portal/payments`, "canViewRenterPortal"),
    r(`${D}/renter-portal/penalties`, "canViewRenterPortal"), r(`${D}/renter-portal/facilities`, "canViewRenterPortal"),
    r(`${D}/renter-portal/renewals`, "canViewRenterPortal"), r(`${D}/renter-portal/renewal-intent`, "canViewRenterPortal"),
    r("/superadmin/tenants", "canManageTenants"), r("/superadmin/users", "canManageUsers"),
];

export function routeAllows(entry: RouteEntry, role: UserRole): boolean {
    if (entry.allow === "authenticated") return true;
    if (typeof entry.allow === "function") return entry.allow(role);
    return hasPermission(role, entry.allow);
}
