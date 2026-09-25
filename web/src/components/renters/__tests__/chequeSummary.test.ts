import { describe, expect, it } from "vitest";
import { chequeSummary } from "../chequeSummary";

describe("chequeSummary", () => {
    it("counts a cheque carried on a unit transfer once — the TRANSFERRED row is history (PR #359 R1)", () => {
        const s = chequeSummary([
            { amount: 15000, status: "TRANSFERRED" },
            { amount: 15000, status: "REGISTERED" },
            { amount: 15000, status: "CLEARED" },
        ]);
        expect(s.count).toBe(2);
        expect(s.total).toBe(30000);
        expect(s.outstanding).toBe(15000);
    });
});
