import { describe, expect, it } from "vitest";
import * as fs from "node:fs";
import * as path from "node:path";

import ar from "../../../../../../messages/ar.json";
import en from "../../../../../../messages/en.json";

/**
 * The properties page must not carry untranslated literals.
 *
 * It is one of the largest screens in the app and a daily TENANT_ADMIN /
 * PROPERTY_MANAGER surface, and it shipped 32 occurrences of
 * `{/* TODO: t("key") *\/} Hardcoded Text` — table headers, the search box, the
 * view toggle and the confirmation dialogs all stayed English under the Arabic
 * locale and RTL layout.
 *
 * Asserted against the source file rather than a render because the failure
 * mode is a literal sitting next to a commented-out translation call. That
 * survives any amount of rendering in the English locale, which is exactly why
 * it went unnoticed.
 */

const PAGE = path.join(__dirname, "..", "page.tsx");
const source = fs.readFileSync(PAGE, "utf8");

describe("properties page localization", () => {
    it("has no TODO translation stubs left", () => {
        const stubs = source.match(/TODO: t\(/g) ?? [];
        expect(stubs, `found ${stubs.length} un-wired translation stub(s)`).toHaveLength(0);
    });

    it("resolves every t(...) key it calls in both catalogs", () => {
        // The page sets up useTranslations("MasterData").
        const keys = [...source.matchAll(/\bt\("([^"]+)"\)/g)].map((m) => m[1]);
        expect(keys.length).toBeGreaterThan(20);

        const enNs = en.MasterData as Record<string, string>;
        const arNs = ar.MasterData as Record<string, string>;
        const missingEn = [...new Set(keys)].filter((k) => !(k in enNs));
        const missingAr = [...new Set(keys)].filter((k) => !(k in arNs));

        expect(missingEn, "keys called but absent from en.json").toEqual([]);
        expect(missingAr, "keys called but absent from ar.json").toEqual([]);
    });

    it("has a genuinely different Arabic string for every key it calls", () => {
        const keys = [...new Set([...source.matchAll(/\bt\("([^"]+)"\)/g)].map((m) => m[1]))];
        const enNs = en.MasterData as Record<string, string>;
        const arNs = ar.MasterData as Record<string, string>;

        // A key present in ar.json but holding the English string still renders
        // English. Parity checks alone cannot see that.
        const untranslated = keys.filter((k) => arNs[k] === enNs[k]);
        expect(untranslated, "keys whose Arabic value is still the English text").toEqual([]);
    });

    it("does not label the Units column with the Arabic word for properties", () => {
        // MasterData.units carries AR 'العقارات' ("properties"), so wiring the
        // Units column to it would have labelled it Properties in Arabic. The
        // column uses unitsCount instead.
        expect(source).toContain('t("unitsCount")');
        expect(source).not.toContain('{t("units")}');
        expect((ar.MasterData as Record<string, string>).unitsCount).toBe("الوحدات");
    });
});
