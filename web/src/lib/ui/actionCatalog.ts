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
    | "collections.post-dated" | "collections.penalties" | "collections.all" | "ledger.general"
    // UI PR 3: the contract page, the lists and Home.
    | "lease.actions" | "lease.drawer.assignment" | "lease.drawer.writeOff"
    | "lease.section.cheques" | "lease.section.penalties" | "lease.section.journals" | "lease.section.recognition"
    | "lease.section.contract" | "lease.section.attachments" | "lease.section.addenda"
    | "lease.section.interactions" | "lease.section.maintenance"
    | "leases.row" | "leases.header" | "properties.header" | "properties.more" | "home";

/** Locations the PR 3 renderer (action-coverage-v3.test.tsx) draws; the rest are drawn by action-coverage.test.tsx. */
export const V3_LOCATION = (loc: ActionLocation) => /^(lease\.|leases\.|properties\.|home$)/.test(loc);

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
    // UI PR 3 — contract header (inventory: 15 header actions). Primary or under More actions
    // depends on the status; the renderer draws a draft, an active and a terminated contract.
    ...(["post", "amend", "renew", "extend", "add-charge", "transfer", "reduce", "ledger", "download-contract", "raise-penalty",
        "give-notice", "terminate", "settle", "delete", "edit", "record-payment"] as const)
        .map(a => ({ id: `lease.${a}`, was: "contract header", location: "lease.actions" as const, probe: `lease-${a}` })),
    { id: "lease.assignmentEntry", was: "Overview › Assignment card", location: "lease.actions", probe: "lease-assignment" },
    { id: "lease.writeOffEntry", was: "Overview › Bad debt card", location: "lease.actions", probe: "lease-write-off" },
    { id: "lease.assignment", was: "Overview › Assignment card", location: "lease.drawer.assignment", probe: "assignment-card" },
    { id: "lease.badDebt", was: "Overview › Bad debt card (write off, approve, recover)", location: "lease.drawer.writeOff", probe: "bad-debt-card" },
    { id: "lease.chequeGrid", was: "Overview › cheque grid (deposit, receive, details, cancel, clear, bounce, replace, receipt; save; bulk upload)", location: "lease.section.cheques", probe: "cheque-grid" },
    { id: "lease.penaltiesTab", was: "Penalties tab (propose)", location: "lease.section.penalties", probe: "probe-penalties" },
    { id: "lease.journalsTab", was: "Journals tab", location: "lease.section.journals", probe: "probe-journals" },
    { id: "lease.recognitionTab", was: "Recognition tab", location: "lease.section.recognition", probe: "probe-recognition" },
    { id: "lease.contractTab", was: "Contract tab (generate preview, view settlement)", location: "lease.section.contract", probe: "lease-section-contract" },
    { id: "lease.documentsTab", was: "Documents tab (attach, download, delete)", location: "lease.section.attachments", probe: "lease-section-attachments" },
    { id: "lease.addenda", was: "Overview › Addenda / Ejari panel", location: "lease.section.addenda", probe: "probe-addenda" },
    { id: "lease.interactions", was: "Interactions tab", location: "lease.section.interactions", probe: "probe-interactions" },
    { id: "lease.maintenance", was: "Maintenance tab", location: "lease.section.maintenance", probe: "lease-section-maintenance" },
    // Contract list rows (inventory: edit, delete, generate, post, pdf, terminate, docs, view) — ROW is any fixture row's id.
    ...(["view", "edit", "delete", "generate", "post", "pdf", "docs"] as const)
        .map(a => ({ id: `leases.row.${a}`, was: "contract list row button", location: "leases.row" as const, probe: `lease-action-${a}-ROW` })),
    { id: "leases.row.terminate", was: "contract list row button", location: "leases.row", probe: "lease-list-terminate-ROW" },
    { id: "leases.card.menu", was: "contract card buttons", location: "leases.row", probe: "lease-card-menu-ROW" },
    { id: "leases.bulkPost", was: "contract list bulk post", location: "leases.header", probe: "bulk-post-select-all" },
    { id: "leases.statusFilter", was: "contract list status select", location: "leases.header", probe: "lease-status-filter" },
    { id: "leases.new", was: "contract list New lease", location: "leases.header", probe: "lease-new" },
    { id: "leases.views", was: "contract list table / cards / board", location: "leases.header", probe: "lease-view-board" },
    { id: "properties.addProperty", was: "properties header", location: "properties.header", probe: "properties-add-property" },
    { id: "properties.addProject", was: "properties header", location: "properties.more", probe: "properties-add-project" },
    { id: "properties.import", was: "properties header", location: "properties.more", probe: "properties-import" },
    { id: "properties.importPortfolio", was: "properties header", location: "properties.more", probe: "properties-import-portfolio" },
    { id: "home.newContract", was: "Home New lease", location: "home", probe: "home-new-contract" },
    { id: "home.overdue", was: "Home overdue widget + KPI link", location: "home", probe: "kpi-overdue" },
    { id: "home.today", was: "Home widgets (overdue, to deposit, recognition behind, follow-ups)", location: "home", probe: "today-list" },
    { id: "home.unitStatus", was: "Home occupancy donut + portfolio snapshot", location: "home", probe: "kpi-unit-status" },
    { id: "home.recentActivity", was: "Home recent activity", location: "home", probe: "recent-activity" },
];
