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

    it.each(["Promoter", "Promotions"] as const)("uses the same rich-text tags in both languages (%s)", ns => {
        const tags = (s: string) => [...s.matchAll(/<(\w+)>/g)].map(m => m[1]).sort().join();
        const enNs: Record<string, string> = en[ns];
        const arNs: Record<string, string> = ar[ns];
        expect(Object.keys(enNs).filter(k => tags(enNs[k]) !== tags(arNs[k]))).toEqual([]);
        // The redirect summary lines are rich text (their URL and names are isolated in the markup).
        if (ns === "Promotions") {
            expect(tags(en.Promotions.redirectSummary)).toBe("url");
            expect(tags(en.Promotions.redirectSummaryParams)).toBe("list");
            // ...and the summary line names which link it is, as a plain placeholder in both languages.
            expect(placeholders(en.Promotions.redirectSummary)).toEqual(["label"]);
            expect(placeholders(ar.Promotions.redirectSummary)).toEqual(["label"]);
        }
    });

    it("labels every redirect source and every redirect error in both languages", () => {
        const keys = [
            ...["COUPON_CODE", "RENTER_NAME", "RENTER_PHONE", "RENTER_EMAIL", "PROPERTY_NAME", "UNIT_NUMBER", "PROPERTY_ID", "UNIT_ID", "STATIC"].map(s => `redirectSource${s}`),
            "redirectUrlTooLong", "redirectParamsNeedUrl", "redirectParamNameInvalid", "redirectParamNameDuplicate",
            "redirectParamValueRequired", "httpsRequired", "domainNotAllowed",
            // Addendum C: the Android / iPhone links, their hint and the labels that tell the three apart.
            "redirectUrlAndroid", "redirectUrlIos", "redirectPlatformHint",
            "redirectLinkDefault", "redirectLinkAndroid", "redirectLinkIos",
        ];
        for (const cat of [en.Promotions, ar.Promotions] as Record<string, string>[]) {
            expect(keys.filter(k => !cat[k])).toEqual([]);
        }
    });

    it("has the delete, archive and restore copy in both languages", () => {
        const keys = [
            "confirmDeleteAd", "confirmDeleteBusiness", "toastDeleted", "toastArchived", "toastRestored",
            "statusARCHIVED", "showArchived", "archivedNote", "restore", "emptyArchivedAds", "emptyArchivedBusinesses",
        ];
        for (const cat of [en.Promotions, ar.Promotions] as Record<string, string>[]) {
            expect(keys.filter(k => !cat[k])).toEqual([]);
        }
    });

    it("words the removed-promoter pill in both languages", () => {
        expect(en.Promotions.statusPROMOTER_REMOVED).toBe("Not shown — promoter removed");
        expect(ar.Promotions.statusPROMOTER_REMOVED).toBe("غير معروض — تمت إزالة المروّج");
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
