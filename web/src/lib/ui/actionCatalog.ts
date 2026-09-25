// src/lib/ui/actionCatalog.ts
/**
 * Every action a user could take on 2026-09-25 (inventory: sidebar, lease
 * header/cards/tabs, cheque grid, list rows, settings), mapped to where it
 * lives now. `probe` is the data-testid the coverage test must find inside
 * that location. Adding a location means adding its renderer to
 * src/lib/ui/__tests__/action-coverage.test.tsx.
 */
export type ActionLocation =
    | "settings.organisation" | "settings.users" | "settings.rent" | "settings.payments"
    | "operations.staff" | "header"
    | "collections.deposit" | "collections.due" | "collections.overdue" | "collections.returned"
    | "collections.post-dated" | "collections.penalties" | "collections.all" | "ledger.general";

export interface CatalogEntry { id: string; was: string; location: ActionLocation; probe: string }

export const ACTION_CATALOG: CatalogEntry[] = [
    { id: "users.manage", was: "/superadmin/users (sidebar Users)", location: "settings.users", probe: "probe-users" },
    { id: "staff.manage", was: "/dashboard/staff (sidebar Staff)", location: "settings.users", probe: "probe-staff" },
    { id: "staff.page", was: "/dashboard/staff (sidebar Staff)", location: "operations.staff", probe: "probe-staff" },
    { id: "fines.org", was: "/dashboard/settings/fines", location: "settings.rent", probe: "probe-fines" },
    { id: "rent.perProperty", was: "/dashboard/settings/rent-settings", location: "settings.rent", probe: "probe-rent" },
    { id: "gateway.config", was: "/dashboard/settings/gateway", location: "settings.payments", probe: "probe-gateway" },
    { id: "online.switch", was: "rent-settings online-payment toggle", location: "settings.payments", probe: "probe-online-switch" },
    { id: "org.info", was: "(new, read-only)", location: "settings.organisation", probe: "probe-organisation" },
    { id: "help", was: "sidebar Help & Guides", location: "header", probe: "header-help" },
    { id: "notifications", was: "header bell", location: "header", probe: "header-notifications" },
    // UI PR 2: the cheque and penalty pages are the Cheque / Cash Collection hub's pills.
    { id: "cheques.depositBatch", was: "/finance/cheques/collection", location: "collections.deposit", probe: "panel-deposit" },
    { id: "cheques.due", was: "(new view of GET /cheques/due)", location: "collections.due", probe: "panel-due" },
    { id: "cheques.overdue", was: "Home overdue widget", location: "collections.overdue", probe: "panel-overdue" },
    { id: "cheques.replace", was: "/finance/cheques/return-replace", location: "collections.returned", probe: "panel-returned" },
    { id: "cheques.postDated", was: "/finance/cheques/post-dated", location: "collections.post-dated", probe: "panel-post-dated" },
    { id: "penalties.queue", was: "/finance/penalties", location: "collections.penalties", probe: "panel-penalties" },
    { id: "cheques.register", was: "/finance/cheques (deposit, receive, details, cancel, clear, bounce, replace, receipt, cash receipt, clear batch)", location: "collections.all", probe: "panel-all" },
    { id: "ledger.tower", was: "(PACT column)", location: "ledger.general", probe: "ledger-col-tower" },
];
