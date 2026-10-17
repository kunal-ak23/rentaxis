// src/lib/leases/renewalSeason.ts
import type { LeaseDetail, LeaseStatus, SuccessorState } from "../api/leasing";
import { idParam } from "../urlIds";

/**
 * The renewal season queue (scale spec #16): the contracts ending in one month, worked in
 * stages. Each stage is a set of statuses and a successor state the server filters by
 * (`GET /leases/paged?endDateFrom=&endDateTo=&excludeStatus=&successor=`), so its count is
 * the server's total, not a page's worth of guesswork.
 *
 * - **todo**: ACTIVE or EXPIRED (a holdover), no renewal drafted yet — what bulk renew is for;
 * - **drafted**: a renewal (or transfer) has been drafted, not yet posted;
 * - **notRenewing**: the renter (or landlord) gave notice — "mark not renewing";
 * - **renewed**: the renewal is on the books;
 * - **all**: every contract ending in the month that has not been terminated or closed.
 */
export type QueueStage = "todo" | "drafted" | "notRenewing" | "renewed" | "all";
export const QUEUE_STAGES: QueueStage[] = ["todo", "drafted", "notRenewing", "renewed", "all"];

const EVERY: LeaseStatus[] = ["DRAFT", "PENDING_SIGNATURE", "ACTIVE", "NOTICE_GIVEN", "RENEWED", "TERMINATED", "EXPIRED", "CLOSED"];

const STAGE: Record<QueueStage, { statuses: LeaseStatus[]; successor: SuccessorState }> = {
    todo: { statuses: ["ACTIVE", "EXPIRED"], successor: "NONE" },
    drafted: { statuses: ["ACTIVE", "NOTICE_GIVEN", "EXPIRED"], successor: "DRAFTED" },
    notRenewing: { statuses: ["NOTICE_GIVEN"], successor: "NONE" },
    renewed: { statuses: ["RENEWED"], successor: "ANY" },
    all: { statuses: ["ACTIVE", "NOTICE_GIVEN", "EXPIRED", "RENEWED"], successor: "ANY" },
};

/** The paged-read filters for a stage: the statuses it does not hold are excluded. */
export function stageQuery(stage: QueueStage): { excludeStatus: LeaseStatus[]; successor: SuccessorState } {
    const s = STAGE[stage];
    return { excludeStatus: EVERY.filter(x => !s.statuses.includes(x)), successor: s.successor };
}

/** `YYYY-MM` → its first and last day (ISO). */
export function monthRange(ym: string): { from: string; to: string } {
    const [y, m] = ym.split("-").map(Number);
    const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
    const mm = String(m).padStart(2, "0");
    return { from: `${y}-${mm}-01`, to: `${y}-${mm}-${String(last).padStart(2, "0")}` };
}

/** `YYYY-MM` shifted by `n` months. */
export function addMonths(ym: string, n: number): string {
    const [y, m] = ym.split("-").map(Number);
    const d = new Date(Date.UTC(y, m - 1 + n, 1));
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

/** The months the picker offers: three back (holdovers still to regularise) and twelve ahead. */
export function monthOptions(today: string): string[] {
    const here = today.slice(0, 7);
    return Array.from({ length: 16 }, (_, i) => addMonths(here, i - 3));
}

/** Home's "Needs you now" row and the queue's default: next month, when renewals are prepared. */
export function defaultMonth(today: string): string {
    return addMonths(today.slice(0, 7), 1);
}

export interface QueueState {
    tab: "renewals" | "terminate";
    month: string;
    stage: QueueStage;
    propertyId: string;
    buildingId: string;
    floor: string;
    search: string;
    /** Bulk terminate: the end date the contracts share. */
    date: string;
}

const YM = /^\d{4}-(0[1-9]|1[0-2])$/;
const YMD = /^\d{4}-\d{2}-\d{2}$/;

/** The queue's state lives in the URL, so a stage can be bookmarked and Home links into it. */
export function parseQueueState(sp: URLSearchParams, today: string): QueueState {
    const month = sp.get("month") ?? "";
    const stage = sp.get("stage") as QueueStage | null;
    const date = sp.get("date") ?? "";
    return {
        tab: sp.get("tab") === "terminate" ? "terminate" : "renewals",
        month: YM.test(month) ? month : defaultMonth(today),
        stage: stage && QUEUE_STAGES.includes(stage) ? stage : "todo",
        propertyId: idParam(sp.get("propertyId")),
        buildingId: idParam(sp.get("buildingId")),
        floor: (sp.get("floor") ?? "").trim().slice(0, 20),
        search: sp.get("search") ?? "",
        date: YMD.test(date) ? date : today,
    };
}

/** Whether bulk renew can take this row: a running or held-over contract with nothing drafted from it. */
export function isRenewable(l: Pick<LeaseDetail, "status" | "renewedToLeaseId" | "transferredToLeaseId">): boolean {
    return (l.status === "ACTIVE" || l.status === "EXPIRED" || l.status === "NOTICE_GIVEN")
        && !l.renewedToLeaseId && !l.transferredToLeaseId;
}

/**
 * Whether bulk renew may select this row (review P3-3): renewable, and not a contract whose
 * renter gave notice — those are renewed one at a time from the row, never swept up by
 * "Select all" on the All stage.
 */
export function isBulkRenewable(l: Pick<LeaseDetail, "status" | "renewedToLeaseId" | "transferredToLeaseId">): boolean {
    return isRenewable(l) && l.status !== "NOTICE_GIVEN";
}

/** Whether bulk terminate can take this row (`LeaseTerminationService.TERMINABLE`). */
export function isTerminable(l: Pick<LeaseDetail, "status">): boolean {
    return l.status === "ACTIVE" || l.status === "NOTICE_GIVEN";
}

/** `items` in runs of at most `size`, in order. */
export function chunks<T>(items: readonly T[], size: number): T[][] {
    const out: T[][] = [];
    for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
    return out;
}

/** How many contracts each batch call carries: small enough for steady progress and short requests. */
export const BATCH_CHUNK = 25;

/**
 * What the rule does to one rent, for the dialog's example line — the server's rounding:
 * whole AED, half up, on percent and on an added amount; no change leaves the rent as it is.
 */
export function ruleExample(rent: number, mode: "NONE" | "PERCENT" | "ADD", value: number | null): number | null {
    if (mode === "NONE") return rent;
    if (value == null || !Number.isFinite(value)) return null;
    const raw = mode === "PERCENT" ? rent * (1 + value / 100) : rent + value;
    // Half up on the cents first, as BigDecimal does on the exact value (avoids 0.5 drifting under binary floats).
    const cents = Math.round(raw * 100);
    const whole = Math.floor((cents + 50) / 100);
    return whole > 0 ? whole : null;
}
