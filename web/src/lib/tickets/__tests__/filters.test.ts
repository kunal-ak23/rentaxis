import { describe, expect, it } from "vitest";
import { isTicketPriorityParam, isTicketStatusParam, TICKET_PRIORITIES, TICKET_STATUSES } from "../filters";
import { currentRenterLeases } from "../renterLeases";

// Break-it R3 data3 F6: an unknown ?status= / ?priority= from a stale bookmark is no filter.
describe("ticket URL filters", () => {
    it("accepts every status and priority the backend knows, and ALL", () => {
        for (const s of [...TICKET_STATUSES, "ALL"]) expect(isTicketStatusParam(s)).toBe(true);
        for (const p of [...TICKET_PRIORITIES, "ALL"]) expect(isTicketPriorityParam(p)).toBe(true);
    });

    it("refuses anything else", () => {
        for (const v of ["NOPE", "open", " OPEN", "OPEN ", "", "URGENT"]) expect(isTicketStatusParam(v)).toBe(false);
        for (const v of ["HIGHEST", "low", "OPEN", ""]) expect(isTicketPriorityParam(v)).toBe(false);
    });
});

// Break-it R3 portal3 F1: the renter's create form offers only contracts the backend accepts.
describe("currentRenterLeases", () => {
    const lease = (over: Record<string, unknown>) => ({
        id: "l", status: "ACTIVE", startDate: "2026-01-01", endDate: "2026-12-31",
        propertyId: "p", propertyName: "P", unitId: "u", unitIdentifier: "101", ...over,
    });

    it("keeps live leases whose term covers today", () => {
        const out = currentRenterLeases([lease({ id: "a" }), lease({ id: "n", status: "NOTICE_GIVEN" })], "2026-09-28");
        expect(out.map((l) => l.id)).toEqual(["a", "n"]);
    });

    it("drops ended, terminated, not-yet-started and draft leases", () => {
        const out = currentRenterLeases([
            lease({ id: "ended", endDate: "2026-08-19" }),
            lease({ id: "term", status: "TERMINATED" }),
            lease({ id: "future", startDate: "2026-10-01" }),
            lease({ id: "draft", status: "DRAFT" }),
        ], "2026-09-28");
        expect(out).toEqual([]);
    });

    it("counts the first and last day of the term as current", () => {
        expect(currentRenterLeases([lease({ startDate: "2026-09-28" })], "2026-09-28")).toHaveLength(1);
        expect(currentRenterLeases([lease({ endDate: "2026-09-28" })], "2026-09-28")).toHaveLength(1);
    });
});
