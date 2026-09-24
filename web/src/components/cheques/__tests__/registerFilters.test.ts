import { describe, expect, it } from "vitest";
import { EMPTY_REGISTER_FILTERS, filtersFromQuery, queryWithFilters } from "../registerFilters";

describe("register filters <-> URL (#85)", () => {
    it("reads every filter from the query", () => {
        expect(filtersFromQuery(new URLSearchParams(
            "status=BOUNCED&mode=PDC&propertyId=p1&from=2026-01-01&to=2026-03-31&search=700102",
        ))).toEqual({ status: "BOUNCED", mode: "PDC", propertyId: "p1", from: "2026-01-01", to: "2026-03-31", search: "700102" });
    });

    it("drops what the register cannot ask for: DRAFT, unknown modes, non-ISO dates", () => {
        expect(filtersFromQuery(new URLSearchParams("status=DRAFT&mode=CRYPTO&from=01/01/2026"))).toEqual(EMPTY_REGISTER_FILTERS);
    });

    it("accepts a lower-case status from a hand-typed link", () => {
        expect(filtersFromQuery(new URLSearchParams("status=bounced")).status).toBe("BOUNCED");
    });

    it("is empty with no query", () => {
        expect(filtersFromQuery(null)).toEqual(EMPTY_REGISTER_FILTERS);
    });

    it("writes the filters back, dropping cleared ones and keeping non-filter params", () => {
        const q = queryWithFilters("?leaseId=l1&status=BOUNCED&mode=PDC",
            { ...EMPTY_REGISTER_FILTERS, status: "DEPOSITED", from: "2026-06-01" });
        const params = new URLSearchParams(q);
        expect(params.get("leaseId")).toBe("l1");
        expect(params.get("status")).toBe("DEPOSITED");
        expect(params.get("from")).toBe("2026-06-01");
        expect(params.has("mode")).toBe(false);
    });

    it("gives an empty string when nothing is left", () => {
        expect(queryWithFilters("?status=BOUNCED", EMPTY_REGISTER_FILTERS)).toBe("");
    });
});
