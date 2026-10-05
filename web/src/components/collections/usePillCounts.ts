"use client";

import { useEffect, useState } from "react";
import { COUNTS_STALE_EVENT } from "@/lib/countsStale";
import { chequeApi, penaltyApi } from "@/lib/api/leasing";
import { hasPermission, type UserRole } from "@/lib/rbac";
import type { PillCounts } from "./CollectionPills";

/**
 * Pill counts from endpoints that already exist (no new endpoint): To deposit
 * = GET /cheques/to-deposit totalElements; Due, Overdue, Returned =
 * GET /cheques/summary dueCount, overdueCount, bouncedCount; Penalties =
 * GET /penalties?status=PROPOSED totalElements. Post-dated (per month) and the
 * register (everything) have none. A failed call leaves its pill without a number.
 *
 * Read again whenever a deposit, bounce, replace or penalty action succeeds
 * (the API layer announces {@link COUNTS_STALE_EVENT}) — tutorial 15: the pills
 * kept their page-load numbers until a full reload.
 */
export function usePillCounts(role: UserRole | undefined, propertyId: string): PillCounts {
    // Counts are kept per (role, property): a reply for another filter never mixes in.
    const key = `${role ?? ""}|${propertyId}`;
    const [state, setState] = useState<{ key: string; counts: PillCounts }>({ key, counts: {} });
    const [tick, setTick] = useState(0);
    useEffect(() => {
        const onStale = () => setTick(n => n + 1);
        window.addEventListener(COUNTS_STALE_EVENT, onStale);
        return () => window.removeEventListener(COUNTS_STALE_EVENT, onStale);
    }, []);
    useEffect(() => {
        let alive = true;
        const put = (c: PillCounts) => {
            if (alive) setState(prev => ({ key, counts: { ...(prev.key === key ? prev.counts : {}), ...c } }));
        };
        const pid = propertyId || undefined;
        if (hasPermission(role, "canManageCheques")) {
            chequeApi.toDeposit({ propertyId: pid, page: 0, size: 1 }).then(p => put({ deposit: p.totalElements })).catch(() => {});
            chequeApi.summary(pid).then(s => put({ due: s.dueCount, overdue: s.overdueCount, returned: s.bouncedCount })).catch(() => {});
        }
        if (hasPermission(role, "canProposePenalties")) {
            penaltyApi.list({ status: "PROPOSED", propertyId: pid, page: 0, size: 1 }).then(p => put({ penalties: p.totalElements })).catch(() => {});
        }
        return () => { alive = false; };
    }, [role, propertyId, key, tick]);
    return state.key === key ? state.counts : {};
}
