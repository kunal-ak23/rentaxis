/**
 * Break-it R3 portal3 F1: the renter's contracts a ticket may be raised on — the
 * same rule the backend enforces (MaintenanceTicketService.renterCurrentLease):
 * live (ACTIVE, or ACTIVE with notice given) and today inside the term. An
 * ended lease still marked ACTIVE is not offered, so the form never builds a
 * request the server refuses.
 */
export type RenterLeaseLike = {
    status?: string | null;
    startDate?: string | null;
    endDate?: string | null;
};

const LIVE = new Set(["ACTIVE", "NOTICE_GIVEN"]);

/** `today` is the business day, `YYYY-MM-DD` (businessTodayIso()). */
export function currentRenterLeases<T extends RenterLeaseLike>(leases: T[], today: string): T[] {
    return leases.filter((l) =>
        LIVE.has(l.status ?? "")
        && (!l.startDate || l.startDate.slice(0, 10) <= today)
        && (!l.endDate || l.endDate.slice(0, 10) >= today));
}
