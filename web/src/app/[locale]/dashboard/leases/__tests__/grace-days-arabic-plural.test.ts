import { describe, expect, it } from "vitest";
import { createTranslator } from "next-intl";

import ar from "../../../../../../messages/ar.json";
import en from "../../../../../../messages/en.json";

/**
 * Review P3-3: the grace day strings are ICU plurals in both languages. Arabic
 * has six categories; "{days} يوم" read "3 يوم" where Arabic says "3 أيام".
 */
describe("grace day strings", () => {
    const tAr = createTranslator({ locale: "ar", messages: ar, namespace: "Leasing" });
    const tEn = createTranslator({ locale: "en", messages: en, namespace: "Leasing" });

    it.each([
        [0, "0 يوم"],
        [1, "يوم واحد"],
        [2, "يومان"],
        [3, "3 أيام"],
        [10, "10 أيام"],
        [11, "11 يومًا"],
        [100, "100 يوم"],
    ])("Arabic says %i days as %s", (days, phrase) => {
        expect(tAr("graceFromProperty", { days })).toBe(`${phrase} (الافتراضي للعقار)`);
        expect(tAr("graceSetOnLease", { days })).toBe(`${phrase} (محدد في هذا العقد)`);
        expect(tAr("gracePropertyDefaultPlaceholder", { days })).toBe(`الافتراضي للعقار: ${phrase}`);
    });

    it("English says 1 day and 5 days", () => {
        expect(tEn("graceFromProperty", { days: 1 })).toBe("1 day (property default)");
        expect(tEn("gracePropertyDefaultPlaceholder", { days: 5 })).toBe("Property default: 5 days");
    });
});
