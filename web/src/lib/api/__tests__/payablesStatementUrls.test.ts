import { describe, expect, it } from "vitest";
import { payablesApi } from "../payables";

/** Tutorial 19: the vendor statement downloads as CSV beside the PDF, through the proxy. */
describe("payablesApi statement links", () => {
    it("points the CSV at the same statement as the PDF", () => {
        const pdf = payablesApi.statementPdfUrl("v-1", "2026-08-01", "2026-09-30", "ar");
        const csv = payablesApi.statementCsvUrl("v-1", "2026-08-01", "2026-09-30", "ar");
        expect(csv).toBe(pdf.replace("/statement.pdf", "/statement.csv"));
        expect(csv).toMatch(/^\/api\/proxy\/v1\/finance\/vendors\/v-1\/statement\.csv\?/);
        expect(csv).toContain("lang=ar");
    });
});
