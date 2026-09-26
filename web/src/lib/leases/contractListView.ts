import type { LeaseStatus } from "../api/leasing";
import { EXPIRING_DAYS, plusDays } from "../dashboard/pipeline";

/** The contract list's status pills (spec §1a): All · Draft · Active · Expiring · Notice · Ended. */
export type ContractView = "all" | "draft" | "active" | "expiring" | "notice" | "ended";
export const CONTRACT_VIEWS: ContractView[] = ["all", "draft", "active", "expiring", "notice", "ended"];
/** Narrower views Home's pipeline opens, shown as a chip on their pill: Settlement due (Ended), Upcoming (Active). */
export type ContractSubset = "settlement" | "upcoming";
export const ENDED: LeaseStatus[] = ["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"];
/** What an accountant posts: drafts and contracts awaiting the renter's signature (PR #368 R1 P3-2). */
export const TO_POST: LeaseStatus[] = ["DRAFT", "PENDING_SIGNATURE"];
/** "Settlement due": a finalised settlement moves a contract to CLOSED; a renewed one carries on in its successor. */
export const SETTLEMENT_DUE: LeaseStatus[] = ["TERMINATED", "EXPIRED"];
const ALL_STATUSES: LeaseStatus[] = [...TO_POST, "ACTIVE", "NOTICE_GIVEN", ...ENDED];

export interface ContractListState {
    view: ContractView;
    subset: ContractSubset | null;
    /** One status the Filters select narrowed to ("" = the view's own set). */
    status: LeaseStatus | "";
    propertyId: string;
    search: string;
}

/**
 * The list's state lives in the URL (`?view=`, `?status=`, `?propertyId=`,
 * `?search=`) so a view can be bookmarked and Home's pipeline links to it. A
 * pill lists exactly the statuses its count adds up (R1 P2-2): Draft is DRAFT +
 * PENDING_SIGNATURE, Ended the four ended statuses, Settlement due TERMINATED +
 * EXPIRED. `?status=` alone picks the pill that holds that status, narrowed to it.
 */
export function parseContractView(sp: URLSearchParams): ContractListState {
    const raw = (sp.get("status") ?? "").toUpperCase();
    const status = (ALL_STATUSES as string[]).includes(raw) ? (raw as LeaseStatus) : "";
    const base = { propertyId: sp.get("propertyId") ?? "", search: sp.get("search") ?? "" };
    const v = sp.get("view");
    if (v === "expiring") return { ...base, view: "expiring", subset: null, status: "" };
    if (v === "upcoming") return { ...base, view: "active", subset: "upcoming", status: "" };
    if (v === "settlement") return { ...base, view: "ended", subset: "settlement", status: SETTLEMENT_DUE.includes(status as LeaseStatus) ? status : "" };
    if (v === "ended" || ENDED.includes(status as LeaseStatus)) return { ...base, view: "ended", subset: null, status: ENDED.includes(status as LeaseStatus) ? status : "" };
    if (v === "draft" || TO_POST.includes(status as LeaseStatus)) return { ...base, view: "draft", subset: null, status: TO_POST.includes(status as LeaseStatus) ? status : "" };
    if (status === "ACTIVE") return { ...base, view: "active", subset: null, status: "" };
    if (status === "NOTICE_GIVEN") return { ...base, view: "notice", subset: null, status: "" };
    return { ...base, view: "all", subset: null, status };
}

/** The query-string changes that select a pill (property and search are kept). */
export function viewQuery(view: ContractView): Record<string, string | null> {
    if (view === "expiring" || view === "ended" || view === "draft") return { view, status: null };
    return { view: null, status: view === "active" ? "ACTIVE" : view === "notice" ? "NOTICE_GIVEN" : null };
}

/** The query-string changes for the Filters select: the pill that holds the status, narrowed to it. */
export function statusQuery(status: LeaseStatus | ""): Record<string, string | null> {
    return { status: status || null, view: null };
}

export function expiringHorizon(today: string): string {
    return plusDays(today, EXPIRING_DAYS);
}

export type ContractRead =
    | { kind: "single"; status?: LeaseStatus }
    | { kind: "multi"; statuses: LeaseStatus[] }
    | { kind: "bounded"; which: "expiring" | "upcoming"; status: LeaseStatus; sort: string; size: number };

/** How GET /leases/paged is asked for a view: one status, several statuses read back to back, or one bounded sorted page. */
export function contractRead(s: Pick<ContractListState, "view" | "subset" | "status">): ContractRead {
    if (s.view === "expiring") return { kind: "bounded", which: "expiring", status: "ACTIVE", sort: "endDate,asc", size: 100 };
    if (s.subset === "upcoming") return { kind: "bounded", which: "upcoming", status: "ACTIVE", sort: "startDate,desc", size: 100 };
    if (s.status) return { kind: "single", status: s.status };
    if (s.view === "draft") return { kind: "multi", statuses: TO_POST };
    if (s.view === "ended") return { kind: "multi", statuses: s.subset === "settlement" ? SETTLEMENT_DUE : ENDED };
    if (s.view === "active") return { kind: "single", status: "ACTIVE" };
    if (s.view === "notice") return { kind: "single", status: "NOTICE_GIVEN" };
    return { kind: "single" };
}

/** The statuses a view's rows can have, for the Filters select's "narrow to" check. */
export function viewStatuses(s: Pick<ContractListState, "view" | "subset">): LeaseStatus[] {
    if (s.view === "draft") return TO_POST;
    if (s.view === "ended") return s.subset === "settlement" ? SETTLEMENT_DUE : ENDED;
    return [];
}

export interface SegmentRead { status: LeaseStatus; page: number; size: number; skip: number; take: number }

/**
 * Page `pageIndex` (size `size`) of several statuses listed back to back, in
 * the order given, when the endpoint filters one status at a time. `totals`
 * are each status's totalElements (same search and property). Each segment
 * needs at most two server pages of `size`; `skip`/`take` cut the rows out.
 */
export function segmentReads(statuses: LeaseStatus[], totals: number[], pageIndex: number, size: number): SegmentRead[] {
    const out: SegmentRead[] = [];
    const from = pageIndex * size, to = from + size;
    let start = 0;
    statuses.forEach((status, i) => {
        const end = start + (totals[i] ?? 0);
        const lo = Math.max(from, start) - start, hi = Math.min(to, end) - start;
        if (lo < hi) {
            const first = Math.floor(lo / size), last = Math.floor((hi - 1) / size);
            for (let p = first; p <= last; p++) {
                const pageLo = p * size;
                const skip = Math.max(lo, pageLo) - pageLo;
                const take = Math.min(hi, pageLo + size) - pageLo - skip;
                out.push({ status, page: p, size, skip, take });
            }
        }
        start = end;
    });
    return out;
}
