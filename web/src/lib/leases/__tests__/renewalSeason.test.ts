import { describe, expect, it } from "vitest";
import {
    addMonths, chunks, defaultMonth, isRenewable, isTerminable, monthOptions, monthRange, parseQueueState, ruleExample, stageQuery,
} from "../renewalSeason";

describe("renewal season stages", () => {
    it("asks the server for each stage by excluding every status the stage does not hold", () => {
        expect(stageQuery("todo")).toEqual({
            excludeStatus: ["DRAFT", "PENDING_SIGNATURE", "NOTICE_GIVEN", "RENEWED", "TERMINATED", "CLOSED"], successor: "NONE",
        });
        expect(stageQuery("drafted").successor).toBe("DRAFTED");
        expect(stageQuery("notRenewing").excludeStatus).not.toContain("NOTICE_GIVEN");
        expect(stageQuery("renewed").excludeStatus).not.toContain("RENEWED");
        // "All" is the month's total: never the terminated, closed or the successors' own drafts.
        expect(stageQuery("all")).toEqual({ excludeStatus: ["DRAFT", "PENDING_SIGNATURE", "TERMINATED", "CLOSED"], successor: "ANY" });
    });

    it("reads a contract as renewable only when nothing has been drafted from it", () => {
        expect(isRenewable({ status: "ACTIVE", renewedToLeaseId: null, transferredToLeaseId: null })).toBe(true);
        expect(isRenewable({ status: "EXPIRED", renewedToLeaseId: null, transferredToLeaseId: null })).toBe(true);
        expect(isRenewable({ status: "ACTIVE", renewedToLeaseId: "x", transferredToLeaseId: null })).toBe(false);
        expect(isRenewable({ status: "ACTIVE", renewedToLeaseId: null, transferredToLeaseId: "t" })).toBe(false);
        expect(isRenewable({ status: "RENEWED", renewedToLeaseId: null, transferredToLeaseId: null })).toBe(false);
        expect(isTerminable({ status: "NOTICE_GIVEN" })).toBe(true);
        expect(isTerminable({ status: "EXPIRED" })).toBe(false);
    });
});

describe("months", () => {
    it("gives a month's first and last day, leap years included", () => {
        expect(monthRange("2027-10")).toEqual({ from: "2027-10-01", to: "2027-10-31" });
        expect(monthRange("2028-02")).toEqual({ from: "2028-02-01", to: "2028-02-29" });
        expect(addMonths("2026-12", 1)).toBe("2027-01");
        expect(addMonths("2027-01", -3)).toBe("2026-10");
        expect(defaultMonth("2026-10-17")).toBe("2026-11");
        expect(monthOptions("2026-10-17")[0]).toBe("2026-07");
        expect(monthOptions("2026-10-17")).toHaveLength(16);
    });

    it("keeps its state in the URL and ignores what does not parse", () => {
        const s = parseQueueState(new URLSearchParams("month=2027-13&stage=bogus&propertyId=nope&floor=07&tab=terminate&date=2027-10-31"), "2026-10-17");
        expect(s).toMatchObject({ tab: "terminate", month: "2026-11", stage: "todo", propertyId: "", floor: "07", date: "2027-10-31" });
        expect(parseQueueState(new URLSearchParams("month=2027-10&stage=drafted"), "2026-10-17")).toMatchObject({ tab: "renewals", month: "2027-10", stage: "drafted", date: "2026-10-17" });
    });
});

describe("the bulk renew rule's example", () => {
    it("rounds to whole dirhams half up, as the server does", () => {
        expect(ruleExample(80000, "PERCENT", 3.33333)).toBe(82667);
        expect(ruleExample(80000, "ADD", 1234.5)).toBe(81235);
        expect(ruleExample(80000, "ADD", 1234.49)).toBe(81234);
        expect(ruleExample(85000.5, "NONE", null)).toBe(85000.5);
        expect(ruleExample(80000, "ADD", -80000)).toBeNull();
        expect(ruleExample(80000, "PERCENT", null)).toBeNull();
    });

    it("cuts a selection into chunks in order", () => {
        expect(chunks([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]]);
        expect(chunks([], 25)).toEqual([]);
    });
});
