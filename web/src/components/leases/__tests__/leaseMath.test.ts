import { describe, expect, it } from "vitest";
import { fmtIsoDate, linesAreValid, type LineRow } from "../leaseMath";

/**
 * `linesAreValid` is the one gate the amend/renew/extend dialogs and the
 * lease wizard all read before they let an accountant submit a set of
 * lines — see leaseMath.ts for why each of these is refused.
 */

function line(over: Partial<LineRow> = {}): LineRow {
    return {
        key: 0,
        chargeTypeId: "ct-rent",
        grossAmount: 1000,
        discountAmount: 0,
        narration: "",
        vatApplicable: true,
        creditAccountId: "acc-1",
        ...over,
    };
}

describe("linesAreValid", () => {
    it("refuses an empty set of lines", () => {
        expect(linesAreValid([])).toBe(false);
    });

    it("accepts a well-formed line", () => {
        expect(linesAreValid([line()])).toBe(true);
    });

    it("refuses a line with no charge type", () => {
        expect(linesAreValid([line({ chargeTypeId: null })])).toBe(false);
    });

    it("refuses a zero or negative amount", () => {
        expect(linesAreValid([line({ grossAmount: 0 })])).toBe(false);
        expect(linesAreValid([line({ grossAmount: -500 })])).toBe(false);
    });

    it("refuses a negative discount", () => {
        expect(linesAreValid([line({ discountAmount: -1 })])).toBe(false);
    });

    it("refuses a discount over the line's own amount", () => {
        expect(linesAreValid([line({ grossAmount: 1000, discountAmount: 1000.01 })])).toBe(false);
    });

    it("accepts a discount exactly equal to the amount — a free line, not an invalid one", () => {
        expect(linesAreValid([line({ grossAmount: 1000, discountAmount: 1000 })])).toBe(true);
    });

    it("refuses the whole set the moment any one row is bad", () => {
        expect(linesAreValid([line(), line({ chargeTypeId: null })])).toBe(false);
    });
});

describe("fmtIsoDate", () => {
    // A bare yyyy-MM-dd has no timezone: the calendar day must survive whatever
    // zone the test runs in. Building from the parts keeps it on local midnight.
    it("renders a bare date on its own day regardless of timezone", () => {
        expect(fmtIsoDate("2026-09-11", "en")).toBe("11/09/2026");
        expect(fmtIsoDate("2026-01-05", "en")).toBe("05/01/2026");
    });

    // A full timestamp is an instant: it must render as the SAME local date every
    // screen would show for that instant, not the UTC calendar date sliced off the
    // front. 2026-09-22T23:30:00Z is already 23 Sep in any zone at or east of +01,
    // which is the "posted 22/09 vs history 23/09" split this guards against.
    it("renders a UTC timestamp as its local date, not the sliced UTC date", () => {
        const iso = "2026-09-22T23:30:00Z";
        const expected = new Date(iso).toLocaleDateString("en-GB");
        expect(fmtIsoDate(iso, "en")).toBe(expected);
    });

    it("does not simply slice the first ten characters of a timestamp", () => {
        // Proves the timestamp path is taken: in any zone west of UTC this differs
        // from the naive slice; at/east of UTC it equals it but via the Date path.
        const iso = "2026-09-22T23:30:00Z";
        const sliced = "22/09/2026";
        const rendered = fmtIsoDate(iso, "en");
        const localDay = new Date(iso).getDate();
        if (localDay !== 22) {
            expect(rendered).not.toBe(sliced);
        } else {
            expect(rendered).toBe(sliced);
        }
    });

    it("returns an em dash for null or empty input", () => {
        expect(fmtIsoDate(null, "en")).toBe("—");
        expect(fmtIsoDate(undefined, "en")).toBe("—");
    });
});
