// src/lib/nav/collectionsModel.ts
import { hasPermission, type Permission, type UserRole } from "../rbac";
import type { Label } from "./types";

export type CollectionsTabId = "deposit" | "due" | "overdue" | "returned" | "post-dated" | "penalties" | "all";
export interface CollectionsTab { id: CollectionsTabId; href: string; label: Label; testId: string; permission: Permission }

const tab = (id: CollectionsTabId, href: string, key: string, permission: Permission): CollectionsTab =>
    ({ id, href, label: { ns: "Collections", key }, testId: `collections-tab-${id}`, permission });

/**
 * The Cheque / Cash Collection views. PR 1 points at today's pages; PR 2
 * (Task 18) points every tab at the hub (`/dashboard/collections?tab=…`) and
 * adds Due and Overdue. Gates are the pages' own: the cheque register's
 * canManageCheques and the penalty queue's canProposePenalties.
 */
const TABS: CollectionsTab[] = [
    tab("deposit", "/dashboard/finance/cheques/collection", "tabDeposit", "canManageCheques"),
    tab("returned", "/dashboard/finance/cheques/return-replace", "tabReturned", "canManageCheques"),
    tab("post-dated", "/dashboard/finance/cheques/post-dated", "tabPostDated", "canManageCheques"),
    tab("penalties", "/dashboard/finance/penalties", "tabPenalties", "canProposePenalties"),
    tab("all", "/dashboard/finance/cheques", "tabAll", "canManageCheques"),
];

export function buildCollectionsTabs(role: UserRole | undefined): CollectionsTab[] {
    return TABS.filter(t => hasPermission(role, t.permission));
}
