// src/lib/tutorials/__tests__/catalog.test.ts
import { describe, expect, it } from "vitest";
import "@/lib/helpArticles";
import { getAllArticles } from "@/lib/helpLoader";
import type { UserRole } from "@/lib/rbac";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import {
    TUTORIALS,
    TUTORIAL_TOPICS,
    embedUrl,
    filterTutorialsByRole,
    formatDuration,
    publishedTutorials,
    searchTutorials,
    thumbnailUrl,
    tutorialsForArticle,
    type Tutorial,
} from "../catalog";

const ROLES: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "SECURITY_GUARD", "TENANT_USER", "RENTER", "ACCOUNTANT"];
const ARABIC = /[؀-ۿ]/;

const tut = (over: Partial<Tutorial>): Tutorial => ({
    id: "01", slug: "x", title: { en: "Title", ar: "عنوان" }, description: { en: "Desc", ar: "وصف" },
    topic: "getting-started", roles: ["TENANT_ADMIN"], durationSec: 90, youtubeId: null, ...over,
});

describe("tutorial catalogue shape", () => {
    it("seeds the web tutorials 01–15, 17–28 and 34–46 (16 dropped), the accounting track in track order", () => {
        const ids = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => String(from + i).padStart(2, "0"));
        const track = ["38", "39", "17", "34", "35", "40", "36", "41", "42", "43", "44", "19", "45", "18", "37", "20", "46"];
        expect(TUTORIALS.map((t) => t.id)).toEqual([...ids(1, 15), ...track, ...ids(21, 28)]);
    });

    it("opens the accounting topic with the overview and follows an accountant's year", () => {
        // .superpowers/accounting-tutorials.md: overview, books start, chart, posting, … year-end close.
        const accounting = TUTORIALS.filter((t) => t.topic === "accounting").map((t) => t.id);
        expect(accounting[0]).toBe("38");
        expect(accounting.at(-1)).toBe("46");
        expect(accounting).toEqual(["38", "39", "17", "34", "40", "36", "41", "42", "43", "44", "19", "45", "18", "37", "20", "46"]);
    });

    it.each(TUTORIALS.map((t) => [t.id, t] as const))("%s is complete and well-formed", (_id, t) => {
        expect(t.slug).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
        expect(t.title.en.trim()).not.toBe("");
        expect(t.description.en.trim()).not.toBe("");
        expect(t.title.ar).toMatch(ARABIC);
        expect(t.description.ar).toMatch(ARABIC);
        expect(TUTORIAL_TOPICS).toContain(t.topic);
        expect(t.roles.length).toBeGreaterThan(0);
        for (const r of t.roles) expect(ROLES).toContain(r);
        expect(Number.isInteger(t.durationSec) && t.durationSec > 0).toBe(true);
        if (t.youtubeId !== null) expect(t.youtubeId).toMatch(/^[A-Za-z0-9_-]{11}$/);
    });

    it("has unique slugs", () => {
        expect(new Set(TUTORIALS.map((t) => t.slug)).size).toBe(TUTORIALS.length);
    });

    it("links only to help articles that exist", () => {
        const slugs = new Set(getAllArticles().map((a) => a.slug));
        const broken = TUTORIALS.flatMap((t) => (t.relatedArticles ?? []).filter((s) => !slugs.has(s)).map((s) => `${t.id}: ${s}`));
        expect(broken).toEqual([]);
    });

    it("the seven topics are in the approved order", () => {
        expect(TUTORIAL_TOPICS).toEqual(["getting-started", "portfolio", "leasing", "collections", "accounting", "operations", "tenant-portal"]);
    });
});

describe("tutorial catalogue terminology", () => {
    // Binding terms (terminology.test.ts): Tenant / tenancy contract / Company Admin / organisation.
    const BANNED = [
        /\b(renters?|leases?)\b/i,
        /\btenant[\s-]*(admin|user)s?\b/i,
        /\borgani[z]ations?\b/i,
    ];
    it("no English title or description uses a retired term", () => {
        const stale = TUTORIALS.flatMap((t) =>
            [t.title.en, t.description.en].filter((s) => BANNED.some((re) => re.test(s))).map((s) => `${t.id}: ${s}`));
        expect(stale).toEqual([]);
    });

    it("the terminology guard itself catches a retired term (mutation check)", () => {
        expect(BANNED.some((re) => re.test("Draft a lease"))).toBe(true);
        expect(BANNED.some((re) => re.test("Tenant Admin tools"))).toBe(true);
        expect(BANNED.some((re) => re.test("Switch organization"))).toBe(true);
    });
});

describe("catalogue helpers", () => {
    const list = [
        tut({ id: "01", youtubeId: "abcdefghijk", roles: ["TENANT_ADMIN"] }),
        tut({ id: "02", youtubeId: null, roles: ["TENANT_ADMIN", "RENTER"] }),
        tut({ id: "03", youtubeId: "not-a-valid-youtube-id", roles: ["RENTER"] }),
        tut({ id: "04", youtubeId: "ZYXWVUTSRQP", roles: ["RENTER"], title: { en: "Pay rent", ar: "دفع الإيجار" } }),
    ];

    it("publishedTutorials drops entries without a (valid) YouTube id", () => {
        expect(publishedTutorials(list).map((t) => t.id)).toEqual(["01", "04"]);
    });

    it("the real catalogue publishes the uploaded tutorials, each with an 11-character YouTube id", () => {
        const published = publishedTutorials(TUTORIALS);
        expect(published.map((t) => t.id)).toEqual([
            "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14", "15",
            // The accounting topic lists its entries in track order (38, the overview, first).
            "38", "39", "17", "34", "35", "40", "36", "41", "42", "43", "44", "19", "18", "37",
        ]);
        for (const t of published) expect(t.youtubeId).toMatch(/^[A-Za-z0-9_-]{11}$/);
    });

    it("filterTutorialsByRole keeps only the role's entries, none without a role", () => {
        expect(filterTutorialsByRole(list, "RENTER").map((t) => t.id)).toEqual(["02", "03", "04"]);
        expect(filterTutorialsByRole(list, undefined)).toEqual([]);
    });

    it("searchTutorials matches English and Arabic titles and descriptions", () => {
        expect(searchTutorials(list, "pay").map((t) => t.id)).toEqual(["04"]);
        expect(searchTutorials(list, "الإيجار").map((t) => t.id)).toEqual(["04"]);
        expect(searchTutorials(list, "  ")).toHaveLength(4);
    });

    it("tutorialsForArticle returns published entries that name the article", () => {
        const withRel = [
            tut({ id: "01", youtubeId: "abcdefghijk", relatedArticles: ["a"] }),
            tut({ id: "02", youtubeId: null, relatedArticles: ["a"] }),
            tut({ id: "03", youtubeId: "abcdefghijk", relatedArticles: ["b"] }),
        ];
        expect(tutorialsForArticle("a", withRel).map((t) => t.id)).toEqual(["01"]);
    });

    it("builds the privacy-enhanced embed and the thumbnail URLs", () => {
        expect(embedUrl("abcdefghijk")).toBe("https://www.youtube-nocookie.com/embed/abcdefghijk?rel=0");
        expect(thumbnailUrl("abcdefghijk")).toBe("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg");
    });

    it("formats durations as m:ss", () => {
        expect(formatDuration(156)).toBe("2:36");
        expect(formatDuration(60)).toBe("1:00");
        expect(formatDuration(5)).toBe("0:05");
    });
});

describe("Help video keys: EN/AR parity", () => {
    type Tree = { [k: string]: string | Tree };
    const keys = (tree: Tree, prefix = ""): string[] =>
        Object.entries(tree).flatMap(([k, v]) => (typeof v === "string" ? [prefix + k] : keys(v, `${prefix}${k}.`)));
    const pick = (m: { Help: Record<string, unknown> }) => ({ tabs: m.Help.tabs, videos: m.Help.videos }) as Tree;

    it("every new key exists in both locales", () => {
        expect(keys(pick(en)).length).toBeGreaterThan(0);
        expect(keys(pick(ar)).sort()).toEqual(keys(pick(en)).sort());
    });

    it("every topic has a label, and Arabic labels are Arabic", () => {
        for (const topic of TUTORIAL_TOPICS) {
            expect((en.Help.videos.topics as Record<string, string>)[topic]).toBeTruthy();
            expect((ar.Help.videos.topics as Record<string, string>)[topic]).toMatch(ARABIC);
        }
        const flat = (tree: Tree): string[] => Object.values(tree).flatMap((v) => (typeof v === "string" ? [v] : flat(v)));
        const notArabic = flat(pick(ar)).filter((v) => !ARABIC.test(v));
        expect(notArabic).toEqual([]);
    });
});
