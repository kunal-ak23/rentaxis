import { describe, expect, it } from "vitest";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";

/**
 * Attached parking: every string the feature added exists in BOTH languages, the Arabic
 * is real (non-empty, not a copy of the English) and keeps the English placeholders.
 * Keys are enumerated explicitly so a new one cannot slip in untranslated.
 */
const KEYS = [
    // Facilities: manager list + dialog, renter page
    "Facilities.colAttached", "Facilities.attachedTo", "Facilities.notAttachedNote", "Facilities.attachedUnit",
    "Facilities.notAttachedOption", "Facilities.attachedUnitHint", "Facilities.unitsLoadError",
    "Facilities.allocatedParkingSection", "Facilities.allocatedParkingNote", "Facilities.allocated",
    "Facilities.bookedRange", "Facilities.bookedFrom", "Facilities.bookedUntil", "Facilities.bookedOpen",
    "Facilities.preferredEndDate", "Facilities.preferredEndDateHint", "Facilities.endBeforeStart",
    // Property Overview tile + portfolio import
    "MasterData.parkingAttached", "MasterData.portfolioSheets",
    "MasterData.resultParkingBaysCreated", "MasterData.resultParkingBaysUpdated",
    // Cut-over import
    "Cutover.importParkingCounts", "Cutover.cutoverHelpParking",
    // Coded server refusals
    "Common.errors.booking.spotAttached", "Common.errors.booking.spotTaken", "Common.errors.booking.spotPendingExists",
    "Common.errors.parking.attachHasBookings", "Common.errors.parking.attachUnitOutsideBay",
];

function at(messages: unknown, path: string): unknown {
    return path.split(".").reduce<unknown>((o, k) => (o && typeof o === "object" ? (o as Record<string, unknown>)[k] : undefined), messages);
}
const vars = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort();

describe("attached parking strings", () => {
    it.each(KEYS)("%s is translated (real Arabic, same placeholders)", key => {
        const e = at(en, key);
        const a = at(ar, key);
        expect(typeof e).toBe("string");
        expect((e as string).length).toBeGreaterThan(0);
        expect(typeof a).toBe("string");
        expect((a as string).trim().length).toBeGreaterThan(0);
        expect(a).not.toBe(e);
        expect(vars(a as string)).toEqual(vars(e as string));
    });

    it("the Arabic strings contain Arabic script", () => {
        // portfolioSheets keeps the sheet names in Latin, as the sheet headers are; the rest is prose.
        for (const key of KEYS) expect(at(ar, key) as string).toMatch(/[؀-ۿ]/);
    });
});
