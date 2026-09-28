import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

import en from "../../messages/en.json";
import ar from "../../messages/ar.json";
import { adminOnboardingTour } from "@/components/tour/tours/admin-onboarding";

/**
 * The product is Miftah (مفتاح) on every surface a user reads (Kunal,
 * 2026-09-28). Code identifiers — packages, the X-Rentaxis-Forwarded header,
 * storage keys, the rentaxis.com domain — keep the old name; only text shown
 * to people is guarded here.
 */
const OLD_BRAND = /RentAxis|رينت\s*أكسس|رنت\s*أكسس/i;

const root = join(__dirname, "..");

function strings(node: unknown, path = ""): Array<[string, string]> {
    if (typeof node === "string") return [[path, node]];
    if (node && typeof node === "object") {
        return Object.entries(node).flatMap(([k, v]) => strings(v, path ? `${path}.${k}` : k));
    }
    return [];
}

describe("brand name", () => {
    it("no English or Arabic message carries the old brand", () => {
        const hits = [...strings(en).map(([k, v]) => [`en:${k}`, v]), ...strings(ar).map(([k, v]) => [`ar:${k}`, v])]
            .filter(([, v]) => OLD_BRAND.test(v));
        expect(hits).toEqual([]);
    });

    it("the welcome strings name Miftah in English and مفتاح in Arabic", () => {
        expect(en.Index.title).toContain("Miftah");
        expect(ar.Index.title).toContain("مفتاح");
        expect(en.Help.welcomeTitle).toContain("Miftah");
        expect(ar.Help.welcomeTitle).toContain("مفتاح");
        expect(ar.Help.subtitle).toContain("مفتاح");
    });

    it("the help articles and the onboarding tour say Miftah", () => {
        const helpDir = join(root, "content/help");
        const files = readdirSync(helpDir).filter(f => f.endsWith(".md")).map(f => join(helpDir, f));
        files.push(join(root, "lib/helpArticles.ts"));
        const offenders = files.filter(f => OLD_BRAND.test(readFileSync(f, "utf8")));
        expect(offenders).toEqual([]);
        expect(JSON.stringify(adminOnboardingTour)).not.toMatch(OLD_BRAND);
        expect(adminOnboardingTour.steps[0].title).toBe("Welcome to Miftah!");
    });

    it("the page title, login, register and public listing chrome say Miftah", () => {
        for (const f of [
            "app/[locale]/layout.tsx",
            "app/[locale]/auth/login/page.tsx",
            "app/[locale]/auth/register/page.tsx",
            "app/[locale]/auth/set-password/SetPasswordForm.tsx",
            "app/[locale]/privacy/page.tsx",
            "app/l/layout.tsx",
            "components/ui/MvpSidebar.tsx",
            "components/ui/MvpHero.tsx",
            "components/ui/MvpHeader.tsx",
        ]) {
            expect({ f, old: OLD_BRAND.test(readFileSync(join(root, f), "utf8")) }).toEqual({ f, old: false });
        }
    });
});
