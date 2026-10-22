// src/lib/__tests__/help-arabic-articles.test.ts
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import ar from "../../../messages/ar.json";
import en from "../../../messages/en.json";
import { getAllArticles } from "../helpLoader";
import "../helpArticles";

/**
 * The Arabic help articles: one per English article, written with the Arabic
 * UI's own labels, so a reader following a step finds the button it names.
 */

type Tree = { [k: string]: string | Tree };
const flatten = (tree: Tree, prefix = ""): [string, string][] =>
    Object.entries(tree).flatMap(([k, v]) =>
        typeof v === "string" ? [[prefix + k, v] as [string, string]] : flatten(v, `${prefix}${k}.`));
const AR = new Map(flatten(ar as Tree));
/** English UI string (lower case) → the Arabic strings of every key that carries it. */
const AR_FOR_EN = new Map<string, string[]>();
for (const [key, value] of flatten(en as Tree)) {
    const k = value.trim().toLowerCase();
    const arValue = AR.get(key);
    if (arValue) AR_FOR_EN.set(k, [...(AR_FOR_EN.get(k) ?? []), arValue]);
}

/**
 * Latin words an Arabic article may keep: file formats and acronyms the Arabic UI
 * itself shows, and the import workbook's sheet names, which are typed as they are.
 */
const LATIN_ALLOWED = new Set([
    "PDF", "CSV", "Excel", "xlsx", "PNG", "JPEG", "HEIC", "QR", "PACT", "TRN", "VAT", // the FTA's "VAT 201" form
    "Properties", "Units", "Tenants", "Tenancy", "Contracts", "Parking",
]);

/**
 * Bold English words that match a UI string by coincidence: the Arabic names the
 * thing the article means, not that string.
 */
const NOT_THAT_LABEL = new Set([
    "getting-started--welcome: Tenants", // the Tenant role (Roles.RENTER), not the Tenants list
    "leases--lease-lifecycle: Transfer", // the contract action "Transfer to another unit" (Leasing.transfer.open)
    "leases--payment-schedules: paid", // emphasis: paid vs earned
]);

const articles = getAllArticles();
const links = (md: string) => [...md.matchAll(/\]\(([^)]*)\)/g)].map(m => m[1]).sort();
/** The words a reader sees: no link targets, no inline code. */
const prose = (md: string) => md.replace(/\]\([^)]*\)/g, "]").replace(/`[^`]*`/g, " ");

describe("Arabic help articles", () => {
    it("registers 27 articles, each with an Arabic title, summary and body", () => {
        expect(articles).toHaveLength(27);
        const missing = articles.filter(a =>
            !a.ar || ![a.ar.title, a.ar.description, a.ar.content].every(t => /[؀-ۿ]/.test(t)));
        expect(missing.map(a => a.slug)).toEqual([]);
    });

    it("has no Arabic version for an article that does not exist", () => {
        const source = readFileSync(join(__dirname, "../helpArticlesAr.ts"), "utf8");
        const slugs = [...source.matchAll(/registerArticleAr\('([^']+)'/g)].map(m => m[1]);
        expect(slugs.filter(s => !articles.some(a => a.slug === s))).toEqual([]);
        expect(new Set(slugs).size).toBe(slugs.length);
    });

    it("leaves no English in an Arabic title, summary or body", () => {
        const leftovers = articles.flatMap(a =>
            [a.ar!.title, a.ar!.description, prose(a.ar!.content)].flatMap(text =>
                (text.match(/[A-Za-z]{2,}/g) ?? []).filter(w => !LATIN_ALLOWED.has(w)).map(w => `${a.slug}: ${w}`)));
        expect([...new Set(leftovers)]).toEqual([]);
    });

    it("links to the same articles, videos and pages as the English", () => {
        const differ = articles.filter(a => links(a.ar!.content).join(" ") !== links(a.content).join(" "));
        expect(differ.map(a => a.slug)).toEqual([]);
    });

    it("names every button, menu item and status by its Arabic UI label", () => {
        // Each bold English label (a menu path split on ›) that is a UI string must
        // appear in the Arabic body as one of that string's Arabic translations.
        const missing = articles.flatMap(a => {
            const labels = [...a.content.matchAll(/\*\*([^*]+)\*\*/g)]
                .flatMap(m => m[1].split("›").map(s => s.trim()))
                .filter(label => AR_FOR_EN.has(label.toLowerCase()));
            return [...new Set(labels)]
                .filter(label => !AR_FOR_EN.get(label.toLowerCase())!.some(arLabel => a.ar!.content.includes(arLabel)))
                .map(label => `${a.slug}: ${label}`)
                .filter(entry => !NOT_THAT_LABEL.has(entry));
        });
        expect(missing).toEqual([]);
    });
});
