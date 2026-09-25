// src/components/nav/useNavCounts.ts
"use client";
import { useEffect, useRef, useState } from "react";
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
/** Counts older than this are fetched again on the next navigation or window focus. */
export const NAV_COUNTS_MAX_AGE_MS = 30_000;

export function useNavCounts(role: UserRole | undefined, pathname = ""): NavCounts {
    const [counts, setCounts] = useState<NavCounts>({ collectionBadge: null, chequesToDeposit: null, booksLockedThrough: null, booksLive: true });
    const fetched = useRef<{ role: UserRole | undefined; at: number } | null>(null);
    // Re-read after a navigation or on focus once the last read is stale, so a
    // deposit made a minute ago is reflected without a hard reload (PR #363 R1),
    // while quick page-to-page clicks do not refetch every time.
    const [focusTick, setFocusTick] = useState(0);
    useEffect(() => {
        const onFocus = () => setFocusTick(n => n + 1);
        window.addEventListener("focus", onFocus);
        return () => window.removeEventListener("focus", onFocus);
    }, []);
    // In-flight reads must survive a navigation (the next effect run may skip
    // fetching), so they are dropped only once the shell unmounts or the role
    // changes under them.
    const mounted = useRef(true);
    useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
    useEffect(() => {
        const last = fetched.current;
        if (last && last.role === role && Date.now() - last.at < NAV_COUNTS_MAX_AGE_MS) return;
        fetched.current = { role, at: Date.now() };
        const forRole = role;
        const alive = () => mounted.current && fetched.current?.role === forRole;
        if (hasPermission(role, "canManageCheques")) {
            Promise.all([chequeApi.toDeposit({ page: 0, size: 1 }), chequeApi.summary()])
                .then(([toDeposit, summary]) => {
                    if (!alive()) return;
                    setCounts(c => ({ ...c, chequesToDeposit: toDeposit.totalElements, collectionBadge: toDeposit.totalElements + summary.overdueCount }));
                })
                .catch(() => {});
        }
        if (hasPermission(role, "canManageAccountSetup")) {
            ledgerApi.fiscal.get()
                .then(fs => { if (alive()) setCounts(c => ({ ...c, booksLockedThrough: fs.booksLockedThrough, booksLive: isBooksLive(fs) })); })
                .catch(() => {});
        }
    }, [role, pathname, focusTick]);
    return counts;
}
