import { describe, expect, it } from "vitest";
import type { Cheque } from "@/lib/api/leasing";
import { applyChequeCsv, chequeRowProblems, csvDate } from "../chequeNumbering";

const c = (id: string, chequeDate: string, mode = "PDC", over: Partial<Cheque> = {}) =>
    ({ id, chequeDate, mode, amount: 20000, status: "DRAFT", seqNo: 1, chequeNumber: null, payeeBank: null, ...over }) as unknown as Cheque;

describe("cheque row checks", () => {
    it("needs a number and a date on a post-dated cheque, never the same number twice, and nothing on cash", () => {
        const rows = [c("a", "2028-01-01"), c("b", "2028-04-01"), c("x", "2028-07-01", "CASH"), c("d", "2028-10-01")];
        const edits = {
            a: { chequeNumber: "100", payeeBank: "", chequeDate: "2028-01-01" },
            b: { chequeNumber: " 100 ", payeeBank: "", chequeDate: "2028-04-01" },
            x: { chequeNumber: "", payeeBank: "", chequeDate: "" },
            d: { chequeNumber: "101", payeeBank: "", chequeDate: "" },
        };
        expect(chequeRowProblems(rows, edits)).toEqual({ b: "numberRepeated", d: "dateRequired" });
        expect(chequeRowProblems([c("e", "2028-01-01")], {})).toEqual({ e: "numberRequired" });
    });
});

describe("CSV import", () => {
    it("reads ISO and day-first dates", () => {
        expect(csvDate("2028-01-15")).toBe("2028-01-15");
        expect(csvDate("15/1/2028")).toBe("2028-01-15");
        expect(csvDate("15-01-2028")).toBe("2028-01-15");
        expect(csvDate("Jan 15")).toBeNull();
    });

    it("fills rows by contract and due date, skips a header, and reports what it could not match", () => {
        const drafts = [
            { draftId: "d1", keys: ["GLA-0042", "GLA-0007", "07-01", "d1"], rows: [c("r1", "2028-01-01"), c("r2", "2028-04-01")] },
            { draftId: "d2", keys: [null, "GLA-0008", "07-02", "d2"], rows: [c("s1", "2028-01-01")] },
        ];
        const text = [
            "contract,due_date,cheque_number,bank,cheque_date",
            "GLA-0007,01/01/2028,500001,Emirates NBD,",
            "gla-0042 ,2028-04-01,500002,,2028-04-05",
            "07-02;2028-01-01;600001;ADCB",
            "GLA-0009,2028-01-01,700001",
            "GLA-0008,2028-02-01,700002",
            "GLA-0008,2028-01-01,",
        ].join("\n");
        const r = applyChequeCsv(text, drafts, { r2: { chequeNumber: "", payeeBank: "FAB", chequeDate: "2028-04-01" } });
        expect(r.applied).toBe(3);
        expect(r.unmatched).toEqual([5, 6, 7]);
        expect(r.edits.r1).toEqual({ chequeNumber: "500001", payeeBank: "Emirates NBD", chequeDate: "2028-01-01" });
        expect(r.edits.r2).toEqual({ chequeNumber: "500002", payeeBank: "FAB", chequeDate: "2028-04-05" });
        expect(r.edits.s1).toEqual({ chequeNumber: "600001", payeeBank: "ADCB", chequeDate: "2028-01-01" });
    });
});
