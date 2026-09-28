import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import ar from "../../../messages/ar.json";

/**
 * Break-it R2 money2 review I2: every code the money2 guards send has a
 * Common.errors entry in both languages, so serverText never falls back to the
 * server's English. The Arabic never interpolates `{what}` (an English word).
 */
const CODES = [
    "date.inFuture", "posting.dateOutOfRange", "posting.numberTaken", "posting.dateTooFarAhead",
    "ticket.rechargeExceedsBill", "penalty.changed", "settlement.changed",
];

function at(messages: unknown, path: string): unknown {
    return path.split(".").reduce<unknown>((o, k) => (o && typeof o === "object" ? (o as Record<string, unknown>)[k] : undefined), messages);
}

describe("money2 server codes", () => {
    it.each(CODES)("%s is translated in EN and AR", code => {
        const e = at(en, `Common.errors.${code}`);
        const a = at(ar, `Common.errors.${code}`);
        expect(typeof e).toBe("string");
        expect(typeof a).toBe("string");
        expect(a).not.toContain("{what}");
        // The same placeholders in both.
        const vars = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort();
        expect(vars(a as string)).toEqual(vars(e as string));
    });
});
