import { describe, expect, it } from "vitest";
import { chequeLabel } from "../chequeLabel";

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
