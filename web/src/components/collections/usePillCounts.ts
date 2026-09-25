"use client";

import { useEffect, useState } from "react";
import { chequeApi, penaltyApi } from "@/lib/api/leasing";
import { hasPermission, type UserRole } from "@/lib/rbac";
import type { PillCounts } from "./CollectionPills";

/**
 * Pill counts from endpoints that already exist (no new endpoint): To deposit
 * = GET /cheques/to-deposit totalElements; Due, Overdue, Returned =
 * GET /cheques/summary dueCount, overdueCount, bouncedCount; Penalties =
 * GET /penalties?status=PROPOSED totalElements. Post-dated (per month) and the
 * register (everything) have none. A failed call leaves its pill without a number.
 */
export function usePillCounts(role: UserRole | undefined, propertyId: string): PillCounts {
    const [counts, setCounts] = useState<PillCounts>({});
    useEffect(() => {
        let alive = true;
        const put = (c: PillCounts) => { if (alive) setCounts(prev => ({ ...prev, ...c })); };
        setCounts({});
        const pid = propertyId || undefined;
        if (hasPermission(role, "canManageCheques")) {
            chequeApi.toDeposit({ propertyId: pid, page: 0, size: 1 }).then(p => put({ deposit: p.totalElements })).catch(() => {});
            chequeApi.summary(pid).then(s => put({ due: s.dueCount, overdue: s.overdueCount, returned: s.bouncedCount })).catch(() => {});
        }
        if (hasPermission(role, "canProposePenalties")) {
            penaltyApi.list({ status: "PROPOSED", propertyId: pid, page: 0, size: 1 }).then(p => put({ penalties: p.totalElements })).catch(() => {});
        }
        return () => { alive = false; };
    }, [role, propertyId]);
    return counts;
}
