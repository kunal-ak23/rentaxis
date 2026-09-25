// src/components/nav/useNavCounts.ts
"use client";
import { useEffect, useState } from "react";
import { chequeApi } from "@/lib/api/leasing";
import { ledgerApi } from "@/lib/api/ledger";
import { isBooksLive } from "@/lib/nav/accountingNav";
import { hasPermission, type UserRole } from "@/lib/rbac";

export type NavCounts = { collectionBadge: number | null; chequesToDeposit: number | null; booksLockedThrough: string | null; booksLive: boolean };

/**
 * Rail badge and panel status cards, from endpoints that already exist:
 * GET /cheques/to-deposit (its page's totalElements) and GET /cheques/summary
 * (overdueCount) for Collection; GET /finance/fiscal-settings for the books.
 * Each call is made only for a role its controller admits, and a failure
 * leaves the count null (no badge) rather than a wrong number.
 */
export function useNavCounts(role: UserRole | undefined): NavCounts {
    const [counts, setCounts] = useState<NavCounts>({ collectionBadge: null, chequesToDeposit: null, booksLockedThrough: null, booksLive: true });
    useEffect(() => {
        let alive = true;
        if (hasPermission(role, "canManageCheques")) {
            Promise.all([chequeApi.toDeposit({ page: 0, size: 1 }), chequeApi.summary()])
                .then(([toDeposit, summary]) => {
                    if (!alive) return;
                    setCounts(c => ({ ...c, chequesToDeposit: toDeposit.totalElements, collectionBadge: toDeposit.totalElements + summary.overdueCount }));
                })
                .catch(() => {});
        }
        if (hasPermission(role, "canManageAccountSetup")) {
            ledgerApi.fiscal.get()
                .then(fs => { if (alive) setCounts(c => ({ ...c, booksLockedThrough: fs.booksLockedThrough, booksLive: isBooksLive(fs) })); })
                .catch(() => {});
        }
        return () => { alive = false; };
    }, [role]);
    return counts;
}
