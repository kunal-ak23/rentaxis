import { describe, expect, it } from "vitest";
import { linesAreValid, type LineRow } from "../leaseMath";

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
