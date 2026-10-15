import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import ar from "../../../messages/ar.json";

/**
 * Break-it R3 money3: every code the money3 guards send has a Common.errors entry
 * in both languages with the same placeholders, so serverText never shows the
 * server's English in the Arabic UI (money3 note: "Period lock cannot move
 * backwards" was untranslated).
 */
const CODES = ["fiscal.booksStartTooFar", "fiscal.lockBackwards", "fiscal.changed", "ticket.rechargeLive",
    // Bug 46: the lock's planned-recognition and VAT refusals.
    "fiscal.recognitionPendingForLock", "fiscal.recognitionPendingInLock", "fiscal.vatPendingInLock"];

function at(messages: unknown, path: string): unknown {
    return path.split(".").reduce<unknown>((o, k) => (o && typeof o === "object" ? (o as Record<string, unknown>)[k] : undefined), messages);
}

describe("money3 server codes", () => {
    it.each(CODES)("%s is translated in EN and AR", code => {
        const e = at(en, `Common.errors.${code}`);
        const a = at(ar, `Common.errors.${code}`);
        expect(typeof e).toBe("string");
        expect(typeof a).toBe("string");
        const vars = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort();
        expect(vars(a as string)).toEqual(vars(e as string));
    });
});
