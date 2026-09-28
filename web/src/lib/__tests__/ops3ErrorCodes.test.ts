import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import ar from "../../../messages/ar.json";

/**
 * Break-it R3 ops3 F7–F10: every code the booking and listing guards send has a
 * Common.errors entry in both languages with the same placeholders, so serverText
 * never falls back to the server's English.
 */
const CODES = [
    "booking.dateInPast", "booking.rangeTooLong", "booking.outsideLease", "booking.outsideRenterLease",
    "booking.noActiveLease", "booking.alreadyBooked", "booking.renterAlreadyBooked", "booking.pendingExists",
    "booking.notPending", "booking.decisionInProgress", "booking.spotTaken", "listing.unitLet",
];

function at(messages: unknown, path: string): unknown {
    return path.split(".").reduce<unknown>((o, k) => (o && typeof o === "object" ? (o as Record<string, unknown>)[k] : undefined), messages);
}

describe("ops3 server codes", () => {
    it.each(CODES)("%s is translated in EN and AR", code => {
        const e = at(en, `Common.errors.${code}`);
        const a = at(ar, `Common.errors.${code}`);
        expect(typeof e).toBe("string");
        expect(typeof a).toBe("string");
        const vars = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort();
        expect(vars(a as string)).toEqual(vars(e as string));
    });

    it("the drawer's own keys exist in both", () => {
        for (const k of ["alreadyDecided", "bookedFor"]) {
            expect(typeof at(en, `Bookings.${k}`)).toBe("string");
            expect(typeof at(ar, `Bookings.${k}`)).toBe("string");
        }
    });
});
