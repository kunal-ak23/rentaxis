import { describe, expect, it } from "vitest";
import { idsInYear, MAX_COMBINED_RECEIPTS, receiptYears, receivedYear, type ReceiptRow } from "../receiptYears";

const row = (id: string, clearedAt: string | null, date: string): ReceiptRow => ({ id, clearedAt, date });

describe("receiptYears", () => {
    it("clearedAt wins over date", () => {
        expect(receivedYear(row("a", "2026-01-02T10:00:00Z", "2025-12-30"))).toBe(2026);
    });
    it("falls back to date when clearedAt is null", () => {
        expect(receivedYear(row("a", null, "2025-12-30"))).toBe(2025);
    });
    it("lists distinct years newest first", () => {
        const rows = [row("a", "2025-12-31", "2025-12-31"), row("b", "2026-01-01", "2026-01-01"), row("c", "2026-03-01", "2026-03-01")];
        expect(receiptYears(rows)).toEqual([2026, 2025]);
    });
    it("idsInYear keeps input order", () => {
        const rows = [row("a", "2025-12-31", "2025-12-31"), row("b", "2026-01-01", "2026-01-01"), row("c", "2026-03-01", "2026-03-01")];
        expect(idsInYear(rows, 2026)).toEqual(["b", "c"]);
    });
    it("empty input gives empty", () => {
        expect(receiptYears([])).toEqual([]);
        expect(idsInYear([], 2026)).toEqual([]);
    });
    it("caps at 100", () => expect(MAX_COMBINED_RECEIPTS).toBe(100));
});
