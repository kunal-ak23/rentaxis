import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import ar from "../../../messages/ar.json";

/**
 * The promoter role's copy: every string exists in both languages with the same
 * placeholders, and every key the promoter and promotions screens ask for is in
 * the catalog. A missing key does not fail a build or a render — next-intl logs
 * it and shows the key name — so this is the guard.
 */
const APP = path.resolve(__dirname, "../../app/[locale]/dashboard");
const SCREENS = ["promoter", "promotions"].map(d => path.join(APP, d));

function sourceFiles(dir: string): string[] {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) return e.name === "__tests__" ? [] : sourceFiles(full);
        return /\.tsx?$/.test(e.name) ? [full] : [];
    });
}

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\s*[,}]/g)].map(m => m[1]).sort();

describe("promoter copy", () => {
    it("has the Promoter namespace in both languages with the same keys", () => {
        expect(Object.keys(ar.Promoter).sort()).toEqual(Object.keys(en.Promoter).sort());
    });

    it("keeps Promotions in step across languages", () => {
        expect(Object.keys(ar.Promotions).sort()).toEqual(Object.keys(en.Promotions).sort());
    });

    it.each(["Promoter", "Promotions"] as const)("uses the same placeholders in both languages (%s)", ns => {
        const enNs: Record<string, string> = en[ns];
        const arNs: Record<string, string> = ar[ns];
        const mismatched = Object.keys(enNs).filter(k => placeholders(enNs[k]).join() !== placeholders(arNs[k]).join());
        expect(mismatched).toEqual([]);
    });

    it("labels every redirect source and every redirect error in both languages", () => {
        const keys = [
            ...["COUPON_CODE", "RENTER_NAME", "RENTER_PHONE", "RENTER_EMAIL", "PROPERTY_NAME", "UNIT_NUMBER", "STATIC"].map(s => `redirectSource${s}`),
            "redirectUrlTooLong", "redirectParamsNeedUrl", "redirectParamNameInvalid", "redirectParamNameDuplicate",
            "redirectParamValueRequired", "httpsRequired", "domainNotAllowed",
        ];
        for (const cat of [en.Promotions, ar.Promotions] as Record<string, string>[]) {
            expect(keys.filter(k => !cat[k])).toEqual([]);
        }
    });

    it("labels the role and its nav link in both languages", () => {
        expect(en.Roles.PROMOTER).toBe("Promoter");
        expect(ar.Roles.PROMOTER).toBe("مروّج");
        expect(en.Navigation.myAds).toBeTruthy();
        expect(ar.Navigation.myAds).toBeTruthy();
    });

    it("defines every literal key the promoter and promotions screens use", () => {
        const catalogs = en as unknown as Record<string, Record<string, string>>;
        const missing: string[] = [];
        for (const file of SCREENS.flatMap(sourceFiles)) {
            const src = fs.readFileSync(file, "utf8");
            // Which namespace each translator in this file belongs to: `const t = useTranslations("Promoter")`.
            for (const decl of src.matchAll(/const (\w+) = useTranslations\("(\w+)"\)/g)) {
                const [, fn, ns] = decl;
                for (const m of src.matchAll(new RegExp(`\\b${fn}\\(\\s*"(\\w+)"`, "g"))) {
                    if (!(m[1] in (catalogs[ns] ?? {}))) missing.push(`${path.basename(file)}: ${ns}.${m[1]}`);
                }
            }
        }
        expect(missing).toEqual([]);
    });
});
