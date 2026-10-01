import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import ar from "../../../messages/ar.json";

/**
 * Review of R4-B: every code the new guards send has a Common.errors entry in both
 * languages with the same placeholders, so serverText never shows the server's
 * English in the Arabic UI.
 */
const CODES = ["cheque.tooManyInstalments"];

function at(messages: unknown, path: string): unknown {
    return path.split(".").reduce<unknown>((o, k) => (o && typeof o === "object" ? (o as Record<string, unknown>)[k] : undefined), messages);
}

describe("R4-B review server codes", () => {
    it.each(CODES)("%s is translated in EN and AR", code => {
        const e = at(en, `Common.errors.${code}`);
        const a = at(ar, `Common.errors.${code}`);
        expect(typeof e).toBe("string");
        expect(typeof a).toBe("string");
        const vars = (s: string) => [...new Set(s.match(/\{\w+\}/g) ?? [])].sort();
        expect(vars(a as string)).toEqual(vars(e as string));
    });
});
