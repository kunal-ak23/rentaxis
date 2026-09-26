import { describe, expect, it } from "vitest";
import type { LeaseDetail } from "@/lib/api/leasing";
import type { Page } from "@/lib/api/ledger";
import { buildPipeline, plusDays, type PipelineInput } from "../pipeline";

const L = (id: string, startDate: string, endDate: string) => ({ id, startDate, endDate, unitIdentifier: `U-${id}`, renterName: `T ${id}` }) as LeaseDetail;
const page = (content: LeaseDetail[], totalElements = content.length, size = 100): Page<LeaseDetail> => ({ content, totalElements, totalPages: 1, number: 0, size });
const TODAY = "2026-09-25";
const input = (over: Partial<PipelineInput> = {}): PipelineInput => ({
    draft: page([L("d1", "2026-10-01", "2027-09-30")], 4),
    pending: page([L("s1", "2026-09-01", "2027-08-31")], 2),
    activeByStart: page([L("f1", "2026-11-01", "2027-10-31"), L("a1", "2026-01-01", "2026-12-31")], 20, 50),
    activeByEnd: page([L("e1", "2025-11-01", "2026-10-10"), L("e2", "2025-12-01", "2026-11-20"), L("a2", "2026-01-01", "2027-06-30")], 20),
    notice: page([L("n1", "2025-01-01", "2026-10-31")], 2),
    terminated: page([L("t1", "2025-01-01", "2026-08-31")], 3),
    expired: page([], 1),
    ...over,
});

describe("buildPipeline", () => {
    it("counts each stage from the existing paged endpoint", () => {
        const byId = Object.fromEntries(buildPipeline(input(), TODAY).map(s => [s.id, s.count]));
        // Draft counts the contracts awaiting signature too; Active is every ACTIVE contract (upcoming ones included).
        expect(byId).toEqual({ draft: 6, upcoming: 1, active: 20, expiring: 2, notice: 2, settlement: 4 });
        expect(buildPipeline(input(), TODAY).every(s => !s.capped)).toBe(true);
    });

    it("names the oldest item of each stage", () => {
        const s = Object.fromEntries(buildPipeline(input(), TODAY).map(x => [x.id, x.oldest]));
        expect(s.draft).toEqual({ leaseId: "s1", label: "U-s1 · T s1", date: "2026-09-01" });
        expect(s.expiring?.leaseId).toBe("e1");
        expect(s.settlement?.leaseId).toBe("t1");
        expect(s.active).toBeNull();
    });

    it("marks a bounded count as capped when every fetched row qualified", () => {
        const all = Array.from({ length: 100 }, (_, i) => L(`x${i}`, "2025-01-01", "2026-10-01"));
        const exp = buildPipeline(input({ activeByEnd: page(all, 300) }), TODAY).find(s => s.id === "expiring")!;
        expect(exp).toMatchObject({ count: 100, capped: true });
    });

    it("does not trust the sort of a page that came back unsorted (a property manager's in-memory page)", () => {
        const unsorted = page([L("a2", "2026-01-01", "2027-06-30"), L("e1", "2025-11-01", "2026-10-10")], 40);
        const exp = buildPipeline(input({ activeByEnd: unsorted }), TODAY).find(s => s.id === "expiring")!;
        expect(exp).toMatchObject({ count: 1, capped: true });
        expect(exp.oldest?.leaseId).toBe("e1");
    });

    it("does not call a count exact because a row failed for another reason (R1 P2-3)", () => {
        // Sorted by end date, all 100 end within 60 days; one has not started yet, so it is not a hit —
        // but the last row is still inside the horizon, so page 2 may hold more.
        const rows = Array.from({ length: 100 }, (_, i) => L(`x${i}`, i === 50 ? "2026-10-01" : "2025-01-01", plusDays("2026-09-26", Math.floor(i / 2))));
        const exp = buildPipeline(input({ activeByEnd: page(rows, 300) }), TODAY).find(s => s.id === "expiring")!;
        expect(exp).toMatchObject({ count: 99, capped: true });
        // Once the last row is past the horizon the count is exact.
        const past = [...rows.slice(0, 99), L("late", "2025-01-01", "2027-01-01")];
        expect(buildPipeline(input({ activeByEnd: page(past, 300) }), TODAY).find(s => s.id === "expiring")).toMatchObject({ capped: false });
    });

    it("marks Upcoming capped unless the last row of its page has already started", () => {
        const future = Array.from({ length: 50 }, (_, i) => L(`f${i}`, plusDays("2026-12-01", -i), "2027-12-31"));
        expect(buildPipeline(input({ activeByStart: page(future, 80, 50) }), TODAY).find(s => s.id === "upcoming")).toMatchObject({ count: 50, capped: true });
    });

    it("links each stage to the contract list", () => {
        expect(buildPipeline(input(), TODAY).map(s => s.href)).toEqual([
            "/dashboard/leases?view=draft", "/dashboard/leases?view=upcoming", "/dashboard/leases?status=ACTIVE",
            "/dashboard/leases?view=expiring", "/dashboard/leases?status=NOTICE_GIVEN", "/dashboard/leases?view=settlement",
        ]);
    });

    it("adds days across month and year ends", () => {
        expect(plusDays("2026-09-25", 60)).toBe("2026-11-24");
        expect(plusDays("2026-12-15", 20)).toBe("2027-01-04");
    });
});
