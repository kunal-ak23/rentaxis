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
 * A count read from one bounded page is exact when the page held every row, or
 * when it came back sorted on the stage's date and some row already fell outside
 * the stage (so every later row does too). Otherwise it is a lower bound —
 * which includes a property manager's list, which the server pages in memory
 * without applying the sort (`LeaseService.getAllLeasesPaged`).
 */
function bounded(page: Page<LeaseDetail>, qualifies: (l: LeaseDetail) => boolean, key: (l: LeaseDetail) => string, dir: "asc" | "desc") {
    const rows = page.content ?? [];
    const hits = rows.filter(qualifies);
    const complete = rows.length >= page.totalElements;
    const exact = complete || (hits.length < rows.length && isSorted(rows, key, dir));
    return { hits, capped: !exact };
}

/** Active contracts in force today that end within 60 days, from one page sorted by end date (and whether that count may be short). */
export function expiringFrom(page: Page<LeaseDetail>, today: string): { rows: LeaseDetail[]; capped: boolean } {
    const horizon = plusDays(today, EXPIRING_DAYS);
    const r = bounded(page, l => l.startDate <= today && l.endDate <= horizon, l => l.endDate, "asc");
    return { rows: r.hits, capped: r.capped };
}

/**
 * Draft → Upcoming → Active → Expiring ≤ 60 d → Notice → Settlement due.
 * Status totals come straight from /leases/paged; Upcoming (posted, not yet
 * started) and Expiring are read from one bounded, sorted page each, and a
 * stage whose count may be short is marked `capped` (shown as "N+").
 * "Settlement due" is TERMINATED + EXPIRED: finalising a settlement moves a
 * contract to CLOSED.
 */
export function buildPipeline(i: PipelineInput, today: string): PipelineStage[] {
    const up = bounded(i.activeByStart, l => l.startDate > today, l => l.startDate, "desc");
    const e = expiringFrom(i.activeByEnd, today);
    const exp = { hits: e.rows, capped: e.capped };
    const settlementOldest = earliest([...i.terminated.content, ...i.expired.content], l => l.endDate);
    return [
        { id: "draft", count: i.draft.totalElements, capped: false, oldest: earliest(i.draft.content, l => l.startDate), href: "/dashboard/leases?status=DRAFT" },
        { id: "upcoming", count: up.hits.length, capped: up.capped, oldest: earliest(up.hits, l => l.startDate), href: "/dashboard/leases?status=ACTIVE" },
        { id: "active", count: Math.max(0, i.activeByStart.totalElements - up.hits.length), capped: false, oldest: null, href: "/dashboard/leases?status=ACTIVE" },
        { id: "expiring", count: exp.hits.length, capped: exp.capped, oldest: earliest(exp.hits, l => l.endDate), href: "/dashboard/leases?view=expiring" },
        { id: "notice", count: i.notice.totalElements, capped: false, oldest: earliest(i.notice.content, l => l.endDate), href: "/dashboard/leases?status=NOTICE_GIVEN" },
        { id: "settlement", count: i.terminated.totalElements + i.expired.totalElements, capped: false, oldest: settlementOldest, href: "/dashboard/leases?view=ended" },
    ];
}
