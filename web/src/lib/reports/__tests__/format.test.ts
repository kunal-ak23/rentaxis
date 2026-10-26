import { describe, expect, it } from "vitest";
import { fmtDrCr, fmtMoney, fmtReportDate } from "../format";

/**
 * PACT report spec §1 (D4): amounts arrive as decimal strings and are formatted
 * by string handling alone, so a 17-digit balance keeps its last cent; Dr/Cr
 * reads like PACT's (no space, Latin digits) and dates are dd/mm/yyyy in every
 * locale.
 */
describe("report formats", () => {
    it("prints Dr/Cr in PACT's form without float arithmetic", () => {
        expect(fmtDrCr("12345")).toBe("12,345.00Dr");
        expect(fmtDrCr("-8000.5")).toBe("8,000.50Cr");
        expect(fmtDrCr("0")).toBe("0.00Dr");
        expect(fmtDrCr("-0.00")).toBe("0.00Dr");
        expect(fmtDrCr("90071992547409.93")).toBe("90,071,992,547,409.93Dr");
        expect(fmtMoney("1234567.8")).toBe("1,234,567.80");
        expect(fmtMoney(undefined)).toBe("");
    });
    it("prints dd/mm/yyyy whatever the locale", () => {
        expect(fmtReportDate("2026-09-16")).toBe("16/09/2026");
        expect(fmtReportDate(null)).toBe("");
    });
    it("handles the edges a server string can carry", () => {
        expect(fmtMoney("60000")).toBe("60,000.00");
        expect(fmtMoney("")).toBe("");
        expect(fmtMoney(null)).toBe("");
        expect(fmtMoney("-1234.5")).toBe("-1,234.50");
        expect(fmtMoney("-0.00")).toBe("0.00");
        expect(fmtMoney("999")).toBe("999.00");
        expect(fmtMoney("1000")).toBe("1,000.00");
        expect(fmtMoney("0.1")).toBe("0.10");
        expect(fmtDrCr(null)).toBe("");
        expect(fmtDrCr(undefined)).toBe("");
        expect(fmtDrCr("")).toBe("");
        expect(fmtDrCr("-0")).toBe("0.00Dr");
        expect(fmtDrCr("100.00")).toBe("100.00Dr");
        expect(fmtDrCr("-1000000")).toBe("1,000,000.00Cr");
        expect(fmtReportDate(undefined)).toBe("");
        expect(fmtReportDate("")).toBe("");
        expect(fmtReportDate("2026-01-05T10:00:00")).toBe("05/01/2026");
    });
});
