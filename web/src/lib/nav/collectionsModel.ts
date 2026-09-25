// src/lib/nav/collectionsModel.ts
import { hasPermission, type Permission, type UserRole } from "../rbac";
import type { Label } from "./types";

export type CollectionsTabId = "deposit" | "due" | "overdue" | "returned" | "post-dated" | "penalties" | "all";
export interface CollectionsTab { id: CollectionsTabId; href: string; label: Label; testId: string; permission: Permission }

const tab = (id: CollectionsTabId, href: string, key: string, permission: Permission): CollectionsTab =>
    ({ id, href, label: { ns: "Collections", key }, testId: `collections-tab-${id}`, permission });

/** A hub pill's URL: every Collection view is a tab of `/dashboard/collections`. */
export const collectionsHref = (id: CollectionsTabId) => `/dashboard/collections?tab=${id}`;

/**
 * The Cheque / Cash Collection hub's pills (spec §2), in the client's order.
 * Gates are the old pages' own: the cheque register's canManageCheques and
 * the penalty queue's canProposePenalties. Due and Overdue read the same
 * cheque data the register shows (GET /cheques/due), under the same gate.
 */
const TABS: CollectionsTab[] = [
    tab("deposit", collectionsHref("deposit"), "tabDeposit", "canManageCheques"),
    tab("due", collectionsHref("due"), "tabDue", "canManageCheques"),
    tab("overdue", collectionsHref("overdue"), "tabOverdue", "canManageCheques"),
    tab("returned", collectionsHref("returned"), "tabReturned", "canManageCheques"),
    tab("post-dated", collectionsHref("post-dated"), "tabPostDated", "canManageCheques"),
    tab("penalties", collectionsHref("penalties"), "tabPenalties", "canProposePenalties"),
    tab("all", collectionsHref("all"), "tabAll", "canManageCheques"),
];

export function buildCollectionsTabs(role: UserRole | undefined): CollectionsTab[] {
    return TABS.filter(t => hasPermission(role, t.permission));
}
