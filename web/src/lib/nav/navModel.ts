// src/lib/nav/navModel.ts
import { hasPermission, type Permission } from "../rbac";
import { accountingHome, buildAccountingNav } from "./accountingNav";
import { buildCollectionsTabs } from "./collectionsModel";
import { buildSettingsSections } from "./settingsModel";
import type { Label, NavContext } from "./types";

export type RailId = "home" | "leasing" | "collection" | "accounting" | "operations" | "settings" | "more";
export interface PanelItem { id: string; href: string; label: Label; testId: string; exact?: boolean }
export interface PanelGroup { id: string; label: Label | null; items: PanelItem[]; defaultOpen: boolean }
export type StatusCardKind = "booksLocked" | "chequesToDeposit";
export interface RailSection {
    id: RailId; href: string; label: Label; tourId: string; match: string[];
    /** A shorter label for the narrow rail when the section's name is long (defaults to `label`). */
    railLabel: Label;
    groups: PanelGroup[]; savedViews: PanelItem[]; badge: "collection" | null; statusCard: StatusCardKind | null;
}
export interface NavModelContext extends NavContext { booksLive: boolean }

const N = (key: string): Label => ({ ns: "Navigation", key });
const pi = (id: string, href: string, label: Label, testId: string, exact = false): PanelItem =>
    ({ id, href, label, testId, ...(exact ? { exact } : {}) });
const one = (id: string, items: PanelItem[], label: Label | null = null, defaultOpen = true): PanelGroup[] =>
    items.length ? [{ id, label, items, defaultOpen }] : [];
const pathOf = (href: string) => href.split(/[?#]/)[0];

function section(id: RailId, key: string, tourId: string, match: string[], groups: PanelGroup[],
    extra: Partial<Pick<RailSection, "savedViews" | "badge" | "statusCard" | "href" | "railLabel">> = {}): RailSection | null {
    const items = groups.flatMap(g => g.items);
    if (items.length === 0) return null;
    return {
        id, label: N(key), railLabel: extra.railLabel ?? N(key), tourId, match, groups,
        href: extra.href ?? items[0].href,
        savedViews: extra.savedViews ?? [], badge: extra.badge ?? null, statusCard: extra.statusCard ?? null,
    };
}

/**
 * The two-level shell (spec §1a): an icon rail of sections, each with a
 * panel of pages. Every item keeps the gate its sidebar link had before
 * (MvpSidebar.tsx at 2026-09-25); the model only regroups. A section with no
 * visible item is dropped, so no role sees an empty rail icon.
 */
export function buildNav(ctx: NavModelContext): RailSection[] {
    const { role, isEnabled, tenantSlug, booksLive } = ctx;
    const can = (p: Permission) => hasPermission(role, p);
    const ops = can("canViewProperties");

    if (can("canViewRenterPortal")) {
        const items = [
            pi("today", "/dashboard", N("home"), "sidebar-home", true),
            pi("my-leases", "/dashboard/renter-portal", N("myLeases"), "sidebar-my-leases"),
            pi("my-payments", "/dashboard/renter-portal/payments", { ns: "OnlinePayments", key: "myPayments" }, "sidebar-my-payments"),
            pi("my-penalties", "/dashboard/renter-portal/penalties", N("myPenalties"), "sidebar-my-penalties"),
            pi("my-tickets", "/dashboard/tickets", N("myTickets"), "sidebar-my-tickets"),
            ...(isEnabled("LISTINGS") && tenantSlug ? [pi("listings", `/marketplace/${tenantSlug}`, N("listings"), "sidebar-listings")] : []),
            ...(isEnabled("MEETINGS") ? [pi("meetings", "/dashboard/meetings", N("meetings"), "sidebar-meetings")] : []),
        ];
        return [section("home", "home", "sidebar-home", ["/dashboard"], one("main", items))!];
    }

    const rail: (RailSection | null)[] = [];

    rail.push(section("home", "home", "sidebar-home", ["/dashboard"], one("main", [
        pi("today", "/dashboard", N("today"), "sidebar-home", true),
        ...(ops ? [pi("unit-status", "/dashboard#unit-status", N("unitStatus"), "sidebar-unit-status", true)] : []),
    ])));

    rail.push(section("leasing", "leasing", "sidebar-leases", ["/dashboard/leases", "/dashboard/renters", "/dashboard/properties", "/dashboard/listings"], one("main", [
        ...(can("canViewLeases") ? [pi("contracts", "/dashboard/leases", N("tenancyContracts"), "sidebar-leases")] : []),
        ...(ops ? [pi("tenants", "/dashboard/renters", { ns: "MasterData", key: "renters" }, "sidebar-renters")] : []),
        ...(ops ? [pi("properties", "/dashboard/properties", N("propertiesAndUnits"), "sidebar-properties")] : []),
        ...(ops && isEnabled("LISTINGS") ? [pi("enquiry", "/dashboard/listings", N("enquiry"), "sidebar-listings")] : []),
    ])));

    const tabs = buildCollectionsTabs(role);
    rail.push(section("collection", "collections", "sidebar-collections", COLLECTION_MATCH,
        one("main", tabs.map(t => pi(t.id, t.href, t.label, t.testId))),
        { railLabel: N("collectionShort"),
          savedViews: tabs.some(t => t.id === "overdue")
              ? [pi("saved-overdue", "/dashboard/collections?tab=overdue", { ns: "Collections", key: "tabOverdue" }, "saved-overdue")] : [],
          badge: tabs.some(t => t.permission === "canManageCheques") ? "collection" : null,
          statusCard: tabs.some(t => t.permission === "canManageCheques") ? "chequesToDeposit" : null }));

    const acc = buildAccountingNav(role, { booksLive });
    rail.push(section("accounting", "accounting", "sidebar-finance", ["/dashboard/finance"],
        acc.map(g => ({ id: g.id, label: g.label, defaultOpen: g.defaultOpen || g.id !== "setup", items: g.items.map(i => pi(i.id, i.href, i.label, i.testId)) })),
        { href: accountingHome(role) ?? undefined, statusCard: can("canManageAccountSetup") ? "booksLocked" : null }));

    rail.push(section("operations", "operations", "sidebar-operations", ["/dashboard/tickets", "/dashboard/bookings", "/dashboard/staff"], one("main", [
        ...(ops ? [pi("tickets", "/dashboard/tickets", N("tickets"), "sidebar-tickets")] : []),
        ...(ops && can("canManageFacilities") ? [pi("bookings", "/dashboard/bookings", { ns: "Bookings", key: "navLabel" }, "sidebar-bookings")] : []),
        ...(can("canAccessFinanceOps") ? [pi("staff", "/dashboard/staff", N("staff"), "sidebar-staff")] : []),
    ])));

    const sections = buildSettingsSections(role);
    rail.push(section("settings", "settings", "sidebar-settings", ["/dashboard/settings", "/superadmin"], [
        ...one("sections", sections.map(s => pi(s.id, s.href, s.label, s.testId))),
        ...one("admin", can("canManageTenants") ? [
            pi("tenants", "/superadmin/tenants", N("tenants"), "sidebar-tenants"),
            pi("users", "/superadmin/users", N("users"), "sidebar-users"),
        ] : [], N("sectionAdmin")),
    ]));

    rail.push(section("more", "more", "sidebar-more", ["/dashboard/meetings", "/dashboard/gatepass", "/dashboard/promotions"], one("main", [
        ...(ops && isEnabled("MEETINGS") ? [pi("meetings", "/dashboard/meetings", N("meetings"), "sidebar-meetings")] : []),
        // Gate pass stays role-gated, NOT wrapped in isEnabled("GATEPASS") — the
        // same call the old sidebar made (MvpSidebar at 2026-09-25): the flag
        // defaults off and tenants use the page without it, so gating it here
        // would take it away from them (ruling 2026-09-25, UI PR 1).
        ...(ops && can("canViewGatePassReport") ? [pi("gatepass", "/dashboard/gatepass", { ns: "GatePass", key: "navLabel" }, "sidebar-gatepass")] : []),
        ...(ops && can("canManagePromotions") ? [pi("promotions", "/dashboard/promotions", { ns: "Promotions", key: "navLabel" }, "sidebar-promotions")] : []),
    ])));

    return rail.filter((s): s is RailSection => s !== null);
}

const COLLECTION_MATCH = ["/dashboard/collections"];

export function flattenNav(rail: RailSection[]): string[] {
    return [...new Set(rail.flatMap(s => [...s.groups.flatMap(g => g.items.map(i => i.href)), ...s.savedViews.map(v => v.href)]))];
}

/**
 * The section and item to light for a pathname: exact items match only
 * themselves; everything else matches whole-segment prefixes and the longest
 * match wins, so /dashboard/finance/journals/new lights Journals.
 * Items that share a path and differ by query (Settings' sections) are told
 * apart by `search`: the one whose query the URL carries wins, else the first.
 */
export function activeNav(pathname: string, rail: RailSection[], search = ""): { section: RailId | null; item: string | null } {
    const path = pathname.replace(/^\/(en|ar)(?=\/|$)/, "") || "/";
    const params = new URLSearchParams(search);
    const queryMatches = (href: string) => {
        const q = href.split("#")[0].split("?")[1];
        if (!q) return false;
        return [...new URLSearchParams(q)].every(([k, v]) => params.get(k) === v);
    };
    let best: { section: RailId; item: string | null; len: number; q: boolean } | null = null;
    const offer = (sectionId: RailId, item: string | null, prefix: string, exact: boolean, q: boolean) => {
        const hit = exact ? path === prefix : path === prefix || path.startsWith(`${prefix}/`);
        if (!hit) return;
        const b = best as { item: string | null; len: number; q: boolean } | null;
        if (!b || prefix.length > b.len
            || (prefix.length === b.len && item && !b.item)
            || (prefix.length === b.len && item && b.item && q && !b.q)) {
            best = { section: sectionId, item, len: prefix.length, q };
        }
    };
    for (const s of rail) {
        for (const m of s.match) offer(s.id, null, m, m === "/dashboard", false);
        for (const g of s.groups) for (const i of g.items) {
            if (i.href.includes("#")) continue;
            offer(s.id, i.id, pathOf(i.href), !!i.exact, queryMatches(i.href));
        }
    }
    const b = best as { section: RailId; item: string | null } | null;
    return b ? { section: b.section, item: b.item } : { section: null, item: null };
}
