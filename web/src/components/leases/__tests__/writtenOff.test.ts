import { describe, expect, it } from "vitest";
import { stillDueOf, writtenOffTotals } from "../writtenOff";

describe("writtenOff (#102, review P2-1)", () => {
    const ws = [
        { status: "WRITTEN_OFF" as const, amount: 2000, writeOffDate: "2026-03-10" }, // mid-tenancy bounced cheque
        { status: "WRITTEN_OFF" as const, amount: 1000.1, writeOffDate: "2026-08-01" }, // on the settlement date
        { status: "WRITTEN_OFF" as const, amount: 0.2, writeOffDate: "2026-09-15" },
        { status: "REVERSED" as const, amount: 500, writeOffDate: "2026-09-01" },
        { status: "PROPOSED" as const, amount: 40, writeOffDate: "2026-09-02" },
        { status: "REJECTED" as const, amount: 7, writeOffDate: "2026-09-03" },
    ];

    it("splits the write-offs in force at the settlement date", () => {
        expect(writtenOffTotals(ws, "2026-08-01")).toEqual({ total: 3000.3, sinceMoveOut: 1000.3 });
        expect(writtenOffTotals(ws, null)).toEqual({ total: 3000.3, sinceMoveOut: 0 });
        expect(writtenOffTotals([], "2026-08-01")).toEqual({ total: 0, sinceMoveOut: 0 });
    });

    it("still due is the server's ledger figure, never a local difference", () => {
        expect(stillDueOf({ stillDue: 4500 })).toBe(4500);
        expect(stillDueOf({ stillDue: -3 })).toBe(0);
        expect(stillDueOf({ stillDue: null })).toBeNull();
        expect(stillDueOf(null)).toBeNull();
    });
});
