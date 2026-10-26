import { describe, expect, it } from "vitest";
import type { Cheque } from "@/lib/api/leasing";
import { applyChequeCsv, chequeRowProblems, crossDraftRepeats, csvCells, csvDate, isRealIsoDate } from "../chequeNumbering";

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
        // A date that is not real is flagged on its row, and never sent.
        expect(chequeRowProblems([c("f", "2028-01-01")], { f: { chequeNumber: "1", payeeBank: "", chequeDate: "2028-02-31" } }))
            .toEqual({ f: "dateInvalid" });
        // Arabic-Indic and ASCII digits are the same number.
        expect(chequeRowProblems([c("g", "2028-01-01"), c("h", "2028-02-01")], {
            g: { chequeNumber: "\u0665\u0660\u0660", payeeBank: "", chequeDate: "2028-01-01" },
            h: { chequeNumber: "500", payeeBank: "", chequeDate: "2028-02-01" },
        })).toEqual({ h: "numberRepeated" });
    });

    it("warns, without refusing, when one number is on two drafts of the batch", () => {
        const edits = { a1: { chequeNumber: "77", payeeBank: "", chequeDate: "2028-01-01" }, b1: { chequeNumber: "\u0667\u0667", payeeBank: "", chequeDate: "2028-01-01" },
            b2: { chequeNumber: "78", payeeBank: "", chequeDate: "2028-02-01" } };
        expect(crossDraftRepeats([
            { draftId: "A", rows: [c("a1", "2028-01-01")] },
            { draftId: "B", rows: [c("b1", "2028-01-01"), c("b2", "2028-02-01")] },
        ], edits)).toEqual({ a1: "B", b1: "A" });
    });
});

describe("CSV import", () => {
    it("accepts only yyyy-mm-dd and dd/mm/yyyy, real calendar days, Arabic-Indic digits included", () => {
        expect(csvDate("2028-01-15")).toBe("2028-01-15");
        expect(csvDate("15/1/2028")).toBe("2028-01-15");
        expect(csvDate("\u0661\u0665/\u0660\u0661/\u0662\u0660\u0662\u0668")).toBe("2028-01-15");
        expect(csvDate("29/02/2028")).toBe("2028-02-29");
        // Not guessed: US order, a day that does not exist, other separators.
        expect(csvDate("1/15/2028")).toBeNull();
        expect(csvDate("31/02/2028")).toBeNull();
        expect(csvDate("29/02/2027")).toBeNull();
        expect(csvDate("2028-13-01")).toBeNull();
        expect(csvDate("15-01-2028")).toBeNull();
        expect(csvDate("15.01.2028")).toBeNull();
        expect(csvDate("Jan 15")).toBeNull();
        expect(isRealIsoDate("2028-02-30")).toBe(false);
    });

    it("splits quoted cells", () => {
        expect(csvCells('GLA-1,2028-01-01,500001,"Emirates NBD, Dubai",', ",")).toEqual(["GLA-1", "2028-01-01", "500001", "Emirates NBD, Dubai", ""]);
        expect(csvCells('a,"say ""hi""",b', ",")).toEqual(["a", 'say "hi"', "b"]);
    });

    it("fills rows by contract and due date, skips a header, and reports what it could not match or read", () => {
        const drafts = [
            { draftId: "d1", keys: ["GLA-0042", "GLA-0007", "07-01", "d1"], rows: [c("r1", "2028-01-01"), c("r2", "2028-04-01")] },
            { draftId: "d2", keys: [null, "GLA-0008", "07-02", "d2"], rows: [c("s1", "2028-01-01")] },
        ];
        const text = [
            "contract,due_date,cheque_number,bank,cheque_date",
            'GLA-0007,01/01/2028,500001,"Emirates NBD, Dubai",',
            "gla-0042 ,2028-04-01,\u0665\u0660\u0660\u0660\u0660\u0662,,05/04/2028",
            "07-02,2028-01-01,600001,ADCB",
            "GLA-0009,2028-01-01,700001",
            "GLA-0008,2028-02-01,700002",
            "GLA-0008,2028-01-01,",
            "GLA-0008,1/15/2028,700003",
            "07-02,2028-01-01,600009,ADCB,31/02/2028",
        ].join("\n");
        const r = applyChequeCsv(text, drafts, { r2: { chequeNumber: "", payeeBank: "FAB", chequeDate: "2028-04-01" } });
        expect(r.applied).toBe(3);
        expect(r.unmatched).toEqual([5, 6, 7]);
        expect(r.invalidDates).toEqual([8, 9]);
        expect(r.edits.r1).toEqual({ chequeNumber: "500001", payeeBank: "Emirates NBD, Dubai", chequeDate: "2028-01-01" });
        expect(r.edits.r2).toEqual({ chequeNumber: "500002", payeeBank: "FAB", chequeDate: "2028-04-05" });
        expect(r.edits.s1).toEqual({ chequeNumber: "600001", payeeBank: "ADCB", chequeDate: "2028-01-01" });
    });

    /** Sim r22 S22-05: blank lines before the header, and an Arabic header, are not "invalid dates". */
    it("skips a header after leading blank lines, and an Arabic header", () => {
        const drafts = [{ draftId: "d1", keys: ["07-01"], rows: [c("r1", "2028-01-01")] }];
        const r = applyChequeCsv("\n\ncontract,due,number\n07-01,01/01/2028,910001", drafts, {});
        expect(r.invalidDates).toEqual([]);
        expect(r.applied).toBe(1);
        const ar = applyChequeCsv("العقد,تاريخ الاستحقاق,رقم الشيك\n07-01,01/01/2028,910002", drafts, {});
        expect(ar.invalidDates).toEqual([]);
        expect(ar.edits.r1?.chequeNumber).toBe("910002");
        // Sim r23: a title row above the header is preamble too.
        const titled = applyChequeCsv("Cheque numbers – Tower A\ncontract,due,number\n07-01,01/01/2028,910003", drafts, {});
        expect(titled.invalidDates).toEqual([]);
        expect(titled.edits.r1?.chequeNumber).toBe("910003");
        // A non-date on a later line is still reported.
        expect(applyChequeCsv("\ncontract,due,number\n07-01,soon,1", drafts, {}).invalidDates).toEqual([3]);
    });

    it("reads a semicolon file by its first line", () => {
        const drafts = [{ draftId: "d1", keys: ["07-01"], rows: [c("r1", "2028-01-01")] }];
        const r = applyChequeCsv("contract;due_date;cheque_number;bank\n07-01;01/01/2028;900001;Emirates NBD, Dubai", drafts, {});
        expect(r.edits.r1).toEqual({ chequeNumber: "900001", payeeBank: "Emirates NBD, Dubai", chequeDate: "2028-01-01" });
    });

    it("lets the due date pick between two drafts sharing a unit number, and refuses a line both could take", () => {
        const drafts = [
            { draftId: "d1", keys: ["07-04", "d1"], rows: [c("a1", "2027-01-04")] },
            { draftId: "d2", keys: ["07-04", "d2"], rows: [c("b1", "2027-01-20"), c("b2", "2027-01-04")] },
        ];
        const r = applyChequeCsv("07-04,20/01/2027,1\n07-04,04/01/2027,2", drafts, {});
        expect(r.edits.b1?.chequeNumber).toBe("1");
        expect(r.edits.a1).toBeUndefined();
        expect(r.unmatched).toEqual([2]);
    });
});
