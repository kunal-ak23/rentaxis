import { describe, expect, it } from "vitest";
import { stillDueOf, writtenOffTotal } from "../writtenOff";

describe("writtenOff (#102)", () => {
    it("counts only write-offs in force", () => {
        expect(writtenOffTotal([
            { status: "WRITTEN_OFF", amount: 1000.1 },
            { status: "WRITTEN_OFF", amount: 0.2 },
            { status: "REVERSED", amount: 500 },
            { status: "PROPOSED", amount: 40 },
            { status: "REJECTED", amount: 7 },
        ])).toBe(1000.3);
        expect(writtenOffTotal([])).toBe(0);
    });

    it("still due never goes negative", () => {
        expect(stillDueOf(10000, 4000)).toBe(6000);
        expect(stillDueOf(100, 400)).toBe(0);
        expect(stillDueOf(null, 0)).toBe(0);
    });
});
