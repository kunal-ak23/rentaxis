import { describe, expect, it } from "vitest";
import { CONTRACT_VIEWS, contractViewQuery, expiringHorizon, parseContractView, viewQuery } from "../contractListView";

const p = (q: string) => parseContractView(new URLSearchParams(q));
describe("contract list view", () => {
    it.each([
        ["", "all", ""], ["status=DRAFT", "draft", "DRAFT"], ["status=ACTIVE", "active", "ACTIVE"],
        ["status=NOTICE_GIVEN", "notice", "NOTICE_GIVEN"], ["status=EXPIRED", "ended", "EXPIRED"],
        ["view=ended", "ended", "TERMINATED"], ["view=expiring", "expiring", ""], ["view=expiring&status=DRAFT", "expiring", ""],
        ["status=PENDING_SIGNATURE", "all", "PENDING_SIGNATURE"], ["status=BOGUS", "all", ""], ["status=draft", "draft", "DRAFT"],
    ])("?%s → %s / %s", (q, view, status) => {
        expect(p(q)).toMatchObject({ view, status });
    });

    it("keeps property and search", () => {
        expect(p("propertyId=p1&search=olv")).toMatchObject({ propertyId: "p1", search: "olv" });
    });

    it("round-trips every pill through the URL", () => {
        for (const v of CONTRACT_VIEWS) {
            const q = new URLSearchParams();
            for (const [k, val] of Object.entries(viewQuery(v))) if (val) q.set(k, val);
            expect(p(q.toString()).view, v).toBe(v);
        }
    });

    it("reads Expiring as one bounded page sorted by end date", () => {
        expect(contractViewQuery("expiring", "")).toEqual({ status: "ACTIVE", sort: "endDate,asc", size: 100, bounded: true });
        expect(contractViewQuery("ended", "CLOSED")).toEqual({ status: "CLOSED", bounded: false });
        expect(contractViewQuery("all", "PENDING_SIGNATURE")).toEqual({ status: "PENDING_SIGNATURE", bounded: false });
        expect(contractViewQuery("all", "")).toEqual({ bounded: false });
    });

    it("looks 60 days ahead", () => {
        expect(expiringHorizon("2026-09-25")).toBe("2026-11-24");
    });
});
