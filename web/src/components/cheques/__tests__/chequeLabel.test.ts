import { describe, expect, it } from "vitest";
import { chequeLabel, chequeTitle } from "../chequeLabel";

describe("chequeLabel", () => {
    it("shows the cheque number when a PDC row has one", () => {
        expect(chequeLabel({ chequeNumber: "100041", seqNo: 3, mode: "PDC" })).toBe("100041");
    });

    it("falls back to #seqNo for a numberless PDC row", () => {
        expect(chequeLabel({ chequeNumber: null, seqNo: 3, mode: "PDC" })).toBe("#3");
    });

    it("shows a dash for a numberless CASH row rather than #seqNo", () => {
        expect(chequeLabel({ chequeNumber: null, seqNo: 6, mode: "CASH" })).toBe("—");
    });
});

describe("chequeTitle", () => {
    it("names the cheque between the action and the amount", () => {
        expect(chequeTitle("Bounce", { chequeNumber: "100041", seqNo: 3, mode: "PDC" }, "13,700.00"))
            .toBe("Bounce — 100041 · 13,700.00");
        expect(chequeTitle("Replace", { chequeNumber: null, seqNo: 3, mode: "PDC" }, "13,700.00"))
            .toBe("Replace — #3 · 13,700.00");
    });

    it("leaves the label out for a numberless CASH row instead of a double dash", () => {
        expect(chequeTitle("Receive", { chequeNumber: null, seqNo: 6, mode: "CASH" }, "1,000.00"))
            .toBe("Receive · 1,000.00");
    });
});
