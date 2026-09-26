import type { LeaseStatus } from "../api/leasing";
import { EXPIRING_DAYS, plusDays } from "../dashboard/pipeline";

/** The contract list's status pills (spec §1a): All · Draft · Active · Expiring · Notice · Ended. */
export type ContractView = "all" | "draft" | "active" | "expiring" | "notice" | "ended";
export const CONTRACT_VIEWS: ContractView[] = ["all", "draft", "active", "expiring", "notice", "ended"];
export const ENDED: LeaseStatus[] = ["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"];
const ALL_STATUSES: LeaseStatus[] = ["DRAFT", "PENDING_SIGNATURE", "ACTIVE", "NOTICE_GIVEN", ...ENDED];
/** The status a pill lists (Ended lists one ended status at a time; TERMINATED first). */
export const VIEW_STATUS: Record<ContractView, LeaseStatus | ""> = {
    all: "", draft: "DRAFT", active: "ACTIVE", expiring: "", notice: "NOTICE_GIVEN", ended: "TERMINATED",
};

export interface ContractListState { view: ContractView; status: LeaseStatus | ""; propertyId: string; search: string }

/**
 * The list's state lives in the URL (`?status=`, `?view=`, `?propertyId=`,
 * `?search=`) so a view can be bookmarked and Home's pipeline can link to it.
 * A status the pills don't name (PENDING_SIGNATURE) is the All pill with a
 * status filter; an ended status is the Ended pill.
 */
export function parseContractView(sp: URLSearchParams): ContractListState {
    const raw = (sp.get("status") ?? "").toUpperCase();
    const status = (ALL_STATUSES as string[]).includes(raw) ? (raw as LeaseStatus) : "";
    const v = sp.get("view");
    const view: ContractView =
        v === "expiring" ? "expiring"
        : v === "ended" || ENDED.includes(status as LeaseStatus) ? "ended"
        : status === "DRAFT" ? "draft" : status === "ACTIVE" ? "active" : status === "NOTICE_GIVEN" ? "notice" : "all";
    return {
        view,
        status: view === "expiring" ? "" : view === "ended" && !status ? "TERMINATED" : status,
        propertyId: sp.get("propertyId") ?? "",
        search: sp.get("search") ?? "",
    };
}

/** The query-string changes that select `view` (other params are kept). */
export function viewQuery(view: ContractView): Record<string, string | null> {
    if (view === "expiring") return { view: "expiring", status: null };
    if (view === "ended") return { view: "ended", status: null };
    return { view: null, status: VIEW_STATUS[view] || null };
}

export function expiringHorizon(today: string): string {
    return plusDays(today, EXPIRING_DAYS);
}

/** What GET /leases/paged is asked for: Expiring is one bounded page sorted by end date, the rest a normal paged read. */
export function contractViewQuery(view: ContractView, status: LeaseStatus | ""): { status?: LeaseStatus; sort?: string; size?: number; bounded: boolean } {
    if (view === "expiring") return { status: "ACTIVE", sort: "endDate,asc", size: 100, bounded: true };
    return { ...(status ? { status } : {}), bounded: false };
}
