// src/lib/__tests__/help-nav-paths.test.ts
import { describe, expect, it } from "vitest";
import "../helpArticles";
import { getAllArticles } from "../helpLoader";
import { HELP_PAGE_MAP } from "../help";
import { legacyRedirect } from "../nav/routeMap";

describe("help centre follows the new navigation", () => {
    it("maps no help page to a moved URL", () => {
        const moved = Object.keys(HELP_PAGE_MAP).filter(p => legacyRedirect(new URL(p, "http://x")) !== null);
        expect(moved).toEqual([]);
    });
    it("covers the new pages", () => {
        for (const p of ["/dashboard/settings", "/dashboard/finance/journals"]) expect(HELP_PAGE_MAP[p], p).toBeTruthy();
    });
    it("tells no one to find a page by an old sidebar path", () => {
        const stale = /Finance > Payments|Settings > (Account|Payment Gateway|Rent Settings)|\*\*Staff\*\* from the sidebar|\*\*Users\*\* from the sidebar|Finance > (Chart|Journal)/;
        const hits = getAllArticles().filter(a => stale.test(a.content)).map(a => a.slug);
        expect(hits).toEqual([]);
    });
});
