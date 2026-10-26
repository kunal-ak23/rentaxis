import { defaultLedgerRange } from "@/components/finance/LedgerFilters";
import type { TenantLedgerQuery } from "@/lib/api/reports";

/**
 * The Tenant Ledger page's URL (PACT report spec §2, "Screen"):
 *
 *   view            receivable | all
 *   from, to        ISO days; the last 12 months when absent
 *   propertyId      one property
 *   accountIds      comma-separated account ids
 *   renterId        one tenant
 *   leaseId         one contract
 *   onlyWithBalance 1 when ticked
 *   hideReversed    1 when ticked
 *   page            0-based tenant-group page, omitted when 0
 */

export type TenantLedgerView = "receivable" | "all";

/**
 * The view a URL opens on: the one it names, else "All accounts" when it
 * carries a contract — the lease page's "Ledger" link (`?renterId=&leaseId=`)
 * and LeaseJournalsTab readers expect that one tenant's statement — else PACT's
 * receivable view.
 */
export function viewFrom(sp: URLSearchParams): TenantLedgerView {
    const view = sp.get("view");
    if (view === "all" || view === "receivable") return view;
    return sp.get("leaseId") ? "all" : "receivable";
}

/** A whole, non-negative page number, else the first page. */
function pageFrom(raw: string | null): number {
    if (!raw || !/^\d+$/.test(raw)) return 0;
    const n = Number(raw);
    return Number.isSafeInteger(n) ? n : 0;
}

/** The receivable view's filter and page from the query string. */
export function readQuery(sp: URLSearchParams): { q: TenantLedgerQuery; page: number } {
    const range = defaultLedgerRange();
    const accountIds = (sp.get("accountIds") ?? "").split(",").map(s => s.trim()).filter(Boolean);
    return {
        q: {
            from: sp.get("from") || range.from,
            to: sp.get("to") || range.to,
            propertyId: sp.get("propertyId") || undefined,
            accountIds: accountIds.length ? accountIds : undefined,
            renterId: sp.get("renterId") || undefined,
            leaseId: sp.get("leaseId") || undefined,
            onlyWithBalance: sp.get("onlyWithBalance") === "1",
            hideReversed: sp.get("hideReversed") === "1",
        },
        page: pageFrom(sp.get("page")),
    };
}

/** The filter keys for `useReportQuery`: an empty pick or an unticked switch removes its key. */
export function toUrl(q: TenantLedgerQuery): Record<string, string | undefined> {
    return {
        from: q.from || undefined,
        to: q.to || undefined,
        propertyId: q.propertyId || undefined,
        accountIds: q.accountIds?.length ? q.accountIds.join(",") : undefined,
        renterId: q.renterId || undefined,
        leaseId: q.leaseId || undefined,
        onlyWithBalance: q.onlyWithBalance ? "1" : undefined,
        hideReversed: q.hideReversed ? "1" : undefined,
    };
}

/** Nothing loads until a tenant, a property, a contract or an account narrows the ledger. */
export function hasScope(q: TenantLedgerQuery): boolean {
    return Boolean(q.renterId || q.propertyId || q.leaseId || (q.accountIds?.length ?? 0) > 0);
}
