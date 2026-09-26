import { describe, expect, it } from "vitest";
import { CONTRACT_VIEWS, contractRead, expiringHorizon, parseContractView, segmentReads, statusQuery, viewQuery } from "../contractListView";

const p = (q: string) => parseContractView(new URLSearchParams(q));
const apply = (changes: Record<string, string | null>) => {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(changes)) if (v) q.set(k, v);
    return q.toString();
};

describe("contract list view", () => {
    it.each([
        ["", "all", null, ""], ["status=DRAFT", "draft", null, "DRAFT"], ["view=draft", "draft", null, ""],
        ["status=PENDING_SIGNATURE", "draft", null, "PENDING_SIGNATURE"], ["status=ACTIVE", "active", null, ""],
        ["status=NOTICE_GIVEN", "notice", null, ""], ["status=EXPIRED", "ended", null, "EXPIRED"], ["view=ended", "ended", null, ""],
        ["view=settlement", "ended", "settlement", ""], ["view=settlement&status=EXPIRED", "ended", "settlement", "EXPIRED"],
        ["view=settlement&status=CLOSED", "ended", "settlement", ""],
        ["view=upcoming", "active", "upcoming", ""], ["view=expiring", "expiring", null, ""], ["view=expiring&status=DRAFT", "expiring", null, ""],
        ["status=BOGUS", "all", null, ""], ["status=draft", "draft", null, "DRAFT"],
    ])("?%s → %s / %s / %s", (q, view, subset, status) => {
        expect(p(q)).toMatchObject({ view, subset, status });
    });

    it("keeps property and search", () => {
        expect(p("propertyId=p1&search=olv")).toMatchObject({ propertyId: "p1", search: "olv" });
    });

    it("round-trips every pill and every Filters status through the URL", () => {
        for (const v of CONTRACT_VIEWS) expect(p(apply(viewQuery(v))).view, v).toBe(v);
        expect(p(apply(statusQuery("RENEWED")))).toMatchObject({ view: "ended", status: "RENEWED" });
        expect(p(apply(statusQuery("")))).toMatchObject({ view: "all", status: "" });
    });

    it("lists exactly the statuses a pill counts (R1 P2-2)", () => {
        expect(contractRead(p("view=ended"))).toEqual({ kind: "multi", statuses: ["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"] });
        expect(contractRead(p("view=settlement"))).toEqual({ kind: "multi", statuses: ["TERMINATED", "EXPIRED"] });
        expect(contractRead(p("view=draft"))).toEqual({ kind: "multi", statuses: ["DRAFT", "PENDING_SIGNATURE"] });
        expect(contractRead(p("status=CLOSED"))).toEqual({ kind: "single", status: "CLOSED" });
        expect(contractRead(p("status=ACTIVE"))).toEqual({ kind: "single", status: "ACTIVE" });
        expect(contractRead(p(""))).toEqual({ kind: "single" });
    });

    it("reads Expiring and Upcoming as one bounded sorted page", () => {
        expect(contractRead(p("view=expiring"))).toEqual({ kind: "bounded", which: "expiring", status: "ACTIVE", sort: "endDate,asc", size: 100 });
        expect(contractRead(p("view=upcoming"))).toEqual({ kind: "bounded", which: "upcoming", status: "ACTIVE", sort: "startDate,desc", size: 100 });
    });

    it("looks 60 days ahead", () => {
        expect(expiringHorizon("2026-09-25")).toBe("2026-11-24");
    });
});

describe("segmentReads — several statuses listed back to back, one page at a time", () => {
    const ST = ["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"] as const;
    /** Simulates the reads against per-status lists and returns the ids a page shows. */
    const pageOf = (totals: number[], pageIndex: number, size: number) =>
        segmentReads([...ST], totals, pageIndex, size).flatMap(r =>
            Array.from({ length: totals[ST.indexOf(r.status as typeof ST[number])] }, (_, i) => `${r.status}-${i}`)
                .slice(r.page * r.size, r.page * r.size + r.size).slice(r.skip, r.skip + r.take));

    it("walks every row exactly once, in order, across page and status boundaries", () => {
        const totals = [5, 40, 0, 3];
        const all = ST.flatMap((s, i) => Array.from({ length: totals[i] }, (_, j) => `${s}-${j}`));
        for (const size of [1, 4, 7, 25, 100]) {
            const seen: string[] = [];
            for (let pg = 0; pg * size < all.length; pg++) seen.push(...pageOf(totals, pg, size));
            expect(seen, `size ${size}`).toEqual(all);
        }
    });

    it("needs at most two server pages per status", () => {
        const reads = segmentReads([...ST], [5, 40, 0, 3], 1, 25);
        expect(reads.map(r => [r.status, r.page, r.skip, r.take])).toEqual([["EXPIRED", 0, 20, 5], ["EXPIRED", 1, 0, 15], ["CLOSED", 0, 0, 3]]);
    });
});
