import { describe, expect, it } from "vitest";
import type { LeaseLine } from "@/lib/api/leasing";
import { blankLine, fmtIsoDate, linesAreValid, renewalRows, toInput, toInputs, toRow, type LineRow } from "../leaseMath";

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

/**
 * Review I-2: an amend re-inserts every line, and the backend defaults a RENT
 * line with no period to the lease's whole term — so a mid-term addendum's rent
 * re-sent without its period would be recognised from the lease start.
 */
describe("line periods", () => {
    const persisted: LeaseLine = {
        id: "line-3",
        seqNo: 3,
        chargeTypeId: "ct-rent",
        chargeTypeCode: "RENT",
        chargeTypeName: "Rent",
        behaviour: "RENT",
        creditAccountId: "acc-1",
        creditAccountCode: "4100",
        creditAccountName: "Rent income",
        grossAmount: 4000,
        discountAmount: 0,
        netAmount: 4000,
        narration: "Storage room",
        vatApplicable: false,
        periodStart: "2027-02-15",
        periodEnd: "2027-10-01",
    };

    it("round-trips a line's period through toRow and toInput", () => {
        const input = toInput(toRow(persisted, 0));
        expect(input.periodStart).toBe("2027-02-15");
        expect(input.periodEnd).toBe("2027-10-01");
    });

    it("sends no period for a newly added blank line", () => {
        const input = toInput(blankLine(7));
        expect(input.periodStart ?? null).toBeNull();
        expect(input.periodEnd ?? null).toBeNull();
    });

    it("drops periods when the term itself is being (re)set, so the server re-defaults them", () => {
        const [input] = toInputs([toRow(persisted, 0)], { keepPeriods: false });
        expect(input.periodStart ?? null).toBeNull();
        expect(input.periodEnd ?? null).toBeNull();
    });
});

/**
 * Re-review round 3: an amend re-inserts every line, and a line an addendum
 * charged must keep naming that addendum — otherwise a renewal copies the
 * addendum's part-term fee onto a whole new year.
 */
describe("line addendum tie", () => {
    const tied: LeaseLine = {
        id: "line-4",
        seqNo: 4,
        chargeTypeId: "ct-parking",
        chargeTypeCode: "PARKING_FEE",
        chargeTypeName: "Parking fee",
        behaviour: "FEE",
        creditAccountId: "acc-2",
        creditAccountCode: "4300",
        creditAccountName: "Parking income",
        grossAmount: 1500,
        discountAmount: 0,
        netAmount: 1500,
        narration: "Parking bay P-12",
        vatApplicable: false,
        periodStart: null,
        periodEnd: null,
        addendumId: "add-1",
    };

    it("round-trips a line's addendum through toRow and toInputs on the amend path", () => {
        const [input] = toInputs([toRow(tied, 0)]);
        expect(input.addendumId).toBe("add-1");
    });

    it("sends no addendum for a newly added blank line", () => {
        expect(toInput(blankLine(7)).addendumId ?? null).toBeNull();
    });

    it("drops the addendum when the term is being (re)set, as a renewal or draft does", () => {
        const [input] = toInputs([toRow(tied, 0)], { keepPeriods: false });
        expect(input.addendumId ?? null).toBeNull();
    });
});

describe("renewalRows", () => {
    const base: LeaseLine = {
        id: "line-1",
        seqNo: 1,
        chargeTypeId: "ct-rent",
        chargeTypeCode: "RENT",
        chargeTypeName: "Rent",
        behaviour: "RENT",
        creditAccountId: "acc-1",
        creditAccountCode: "2100",
        creditAccountName: "Advance rent",
        grossAmount: 48000,
        discountAmount: 0,
        netAmount: 48000,
        narration: "Annual rent 01 Oct 2024 - 30 Sep 2025",
        vatApplicable: false,
        periodStart: "2024-10-01",
        periodEnd: "2025-09-30",
        addendumId: null,
    };
    const fee: LeaseLine = { ...base, id: "line-2", seqNo: 2, chargeTypeCode: "ADMIN_FEE",
        behaviour: "FEE", narration: "Contract admin fee", periodStart: null, periodEnd: null };
    const addendum: LeaseLine = { ...fee, id: "line-3", seqNo: 3, narration: "Parking bay", addendumId: "add-1" };
    const extension: LeaseLine = { ...base, id: "line-4", seqNo: 4, narration: "Extension to 2025-12-31",
        periodStart: "2025-10-01", periodEnd: "2025-12-31" };

    it("clears a rent line's narration and keeps a fee's", () => {
        const rows = renewalRows([base, fee], "2024-10-01");
        expect(rows.map((r) => r.narration)).toEqual(["", "Contract admin fee"]);
    });

    it("leaves out an addendum's charge and an extension's rent, as the server copy does", () => {
        const rows = renewalRows([base, fee, addendum, extension], "2024-10-01");
        expect(rows.map((r) => r.id)).toEqual(["line-1", "line-2"]);
    });
});
