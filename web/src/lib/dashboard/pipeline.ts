import type { LeaseDetail } from "@/lib/api/leasing";
import type { Page } from "@/lib/api/ledger";

export type StageId = "draft" | "upcoming" | "active" | "expiring" | "notice" | "settlement";
export interface PipelineStage {
    id: StageId;
    count: number;
    /** The count is a lower bound: the bounded page it was read from did not cover every candidate. */
    capped: boolean;
    oldest: { leaseId: string; label: string; date: string } | null;
    href: string;
}
export interface PipelineInput {
    draft: Page<LeaseDetail>;          // status=DRAFT, sort=startDate,asc, size=1
    pending: Page<LeaseDetail>;        // status=PENDING_SIGNATURE, sort=startDate,asc, size=1
    activeByStart: Page<LeaseDetail>;  // status=ACTIVE, sort=startDate,desc, size=50
    activeByEnd: Page<LeaseDetail>;    // status=ACTIVE, sort=endDate,asc, size=100
    notice: Page<LeaseDetail>;         // status=NOTICE_GIVEN, sort=endDate,asc, size=1
    terminated: Page<LeaseDetail>;     // status=TERMINATED, sort=endDate,asc, size=1
    expired: Page<LeaseDetail>;        // status=EXPIRED, sort=endDate,asc, size=1
}

export const EXPIRING_DAYS = 60;

/** `iso` + `n` calendar days, as an ISO date (UTC arithmetic, so no DST drift). */
export const plusDays = (iso: string, n: number): string => {
    const [y, m, d] = iso.split("-").map(Number);
    return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
};

const label = (l: LeaseDetail) => `${l.unitIdentifier ?? "—"} · ${l.renterName ?? "—"}`;

/** The row with the smallest `date`, whatever order the page came in. */
function earliest(rows: LeaseDetail[], date: (l: LeaseDetail) => string | null | undefined) {
    let best: LeaseDetail | undefined;
    for (const l of rows) {
        const v = date(l);
        if (!v) continue;
        const b = best ? date(best) : null;
        if (!b || v < b) best = l;
    }
    return best ? { leaseId: best.id, label: label(best), date: date(best) as string } : null;
}

const isSorted = (rows: LeaseDetail[], key: (l: LeaseDetail) => string, dir: "asc" | "desc") =>
    rows.every((l, i) => i === 0 || (dir === "asc" ? key(rows[i - 1]) <= key(l) : key(rows[i - 1]) >= key(l)));

/**
 * A count read from one bounded, sorted page is exact when the page held every
 * row, or when the page came back sorted and its LAST row is already past the
 * stage (every later row is further past it). A row can fail the stage for
 * another reason (an expiring-by-date contract that has not started yet), so
 * "some row did not qualify" proves nothing (PR #368 R1 P2-3). Otherwise the
 * count is a lower bound — which includes a property manager's list, which the
 * server pages in memory without applying the sort (`LeaseService.getAllLeasesPaged`).
 */
function bounded(page: Page<LeaseDetail>, qualifies: (l: LeaseDetail) => boolean, pastStage: (l: LeaseDetail) => boolean,
    key: (l: LeaseDetail) => string, dir: "asc" | "desc") {
    const rows = page.content ?? [];
    const hits = rows.filter(qualifies);
    const complete = rows.length >= page.totalElements;
    const last = rows[rows.length - 1];
    const exact = complete || (!!last && pastStage(last) && isSorted(rows, key, dir));
    return { hits, capped: !exact };
}

/** Active contracts in force today that end within 60 days, from one page sorted by end date (and whether that count may be short). */
export function expiringFrom(page: Page<LeaseDetail>, today: string): { rows: LeaseDetail[]; capped: boolean } {
    const horizon = plusDays(today, EXPIRING_DAYS);
    const r = bounded(page, l => l.startDate <= today && l.endDate <= horizon, l => l.endDate > horizon, l => l.endDate, "asc");
    return { rows: r.hits, capped: r.capped };
}

/** Posted contracts that have not started yet, from one ACTIVE page sorted by start date, latest first. */
export function upcomingFrom(page: Page<LeaseDetail>, today: string): { rows: LeaseDetail[]; capped: boolean } {
    const r = bounded(page, l => l.startDate > today, l => l.startDate <= today, l => l.startDate, "desc");
    return { rows: r.hits, capped: r.capped };
}

/**
 * Draft (incl. awaiting signature) → Upcoming → Active → Expiring ≤ 60 d → Notice → Settlement due.
 * Status totals come straight from /leases/paged; Upcoming (posted, not yet
 * started) and Expiring are read from one bounded, sorted page each, and a
 * stage whose count may be short is marked `capped` (shown as "N+").
 * "Settlement due" is TERMINATED + EXPIRED: finalising a settlement moves a
 * contract to CLOSED.
 */
export function buildPipeline(i: PipelineInput, today: string): PipelineStage[] {
    const up = upcomingFrom(i.activeByStart, today);
    const exp = expiringFrom(i.activeByEnd, today);
    const settlementOldest = earliest([...i.terminated.content, ...i.expired.content], l => l.endDate);
    return [
        // Draft = drafts and contracts awaiting the renter's signature: both are what an accountant posts (R1 P3-2).
        { id: "draft", count: i.draft.totalElements + i.pending.totalElements, capped: false,
          oldest: earliest([...i.draft.content, ...i.pending.content], l => l.startDate), href: "/dashboard/leases?view=draft" },
        { id: "upcoming", count: up.rows.length, capped: up.capped, oldest: earliest(up.rows, l => l.startDate), href: "/dashboard/leases?view=upcoming" },
        // Every ACTIVE contract, as the list's Active pill counts them; Upcoming and Expiring are parts of it (R1 P3-3).
        { id: "active", count: i.activeByStart.totalElements, capped: false, oldest: null, href: "/dashboard/leases?status=ACTIVE" },
        { id: "expiring", count: exp.rows.length, capped: exp.capped, oldest: earliest(exp.rows, l => l.endDate), href: "/dashboard/leases?view=expiring" },
        { id: "notice", count: i.notice.totalElements, capped: false, oldest: earliest(i.notice.content, l => l.endDate), href: "/dashboard/leases?status=NOTICE_GIVEN" },
        { id: "settlement", count: i.terminated.totalElements + i.expired.totalElements, capped: false, oldest: settlementOldest, href: "/dashboard/leases?view=settlement" },
    ];
}
