// src/lib/__tests__/help-coverage.test.ts
import { describe, expect, it } from "vitest";
import "../helpArticles";
import { getAllArticles, getArticleBySlug } from "../helpLoader";
import { HELP_PAGE_MAP, HELP_CATEGORIES, filterArticlesByRole, getContextualHelp } from "../help";
import { buildNav, flattenNav } from "../nav/navModel";
import { PERMISSIONS, type UserRole } from "../rbac";
import { TUTORIALS, publishedTutorials } from "../tutorials/catalog";
import { ALL_TOURS, getTourById } from "@/components/tour/tours";

/**
 * The help button must have something for every page a role can reach from the
 * rail, and nothing it points at — article, tour, video — may be missing.
 */

const ROLES: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT", "SECURITY_GUARD", "TENANT_USER", "RENTER"];
const PUBLISHED = new Set(publishedTutorials(TUTORIALS).map(t => t.id));

/** Every page on a role's rail and panels, with every feature on and the books both live and not. */
function navPaths(role: UserRole): string[] {
    const hrefs = [true, false].flatMap(booksLive =>
        flattenNav(buildNav({ role, isEnabled: () => true, tenantSlug: "acme", booksLive })));
    return [...new Set(hrefs.map(h => h.split(/[?#]/)[0]))]
        // The marketplace is the public listings site, outside the dashboard and its help button.
        .filter(p => !p.startsWith("/marketplace/"));
}

const entries = () => Object.entries(HELP_PAGE_MAP).flatMap(([path, { byRole, ...own }]) =>
    [[path, own] as const, ...Object.entries(byRole ?? {}).map(([r, e]) => [`${path} (${r})`, e!] as const)]);

describe("help for every page on the rail", () => {
    it.each(ROLES)("%s: every rail page has its own help entry", role => {
        const missing = navPaths(role).filter(p => !HELP_PAGE_MAP[p]);
        expect(missing).toEqual([]);
    });

    it.each(ROLES.filter(r => r !== "SUPER_ADMIN"))("%s: every rail page's article is one the role is served", role => {
        // Super admins read the tenant articles through "Show all roles"; every other role
        // must get an article written for it, or the help center hides what the button opens.
        const wrong = navPaths(role).flatMap(p => {
            const slug = getContextualHelp(p, role).article;
            const article = slug ? getArticleBySlug(slug) : undefined;
            return article?.roles.includes(role) ? [] : [`${p} → ${slug ?? "nothing"}`];
        });
        expect(wrong).toEqual([]);
    });

    it("sends the tenants page to the tenants article, not the properties one", () => {
        expect(getContextualHelp("/dashboard/renters").article).toBe("leases--tenants-and-portal-access");
    });

    it("gives a tenant their own tickets article and staff the ticket queue's", () => {
        expect(getContextualHelp("/dashboard/tickets", "RENTER").article).toBe("renter--submitting-tickets");
        expect(getContextualHelp("/dashboard/tickets", "PROPERTY_MANAGER").article).toBe("operations--tickets");
        expect(getContextualHelp("/ar/dashboard/tickets/123", "TENANT_ADMIN").article).toBe("operations--tickets");
    });

    it("falls back from a detail page to its list page", () => {
        expect(getContextualHelp("/en/dashboard/leases/abc").article).toBe("leases--creating-a-lease");
        expect(getContextualHelp("/dashboard/finance/bank-reconciliation/xyz").article).toBe("finance--month-end");
    });

    it("mutation check: an unmapped rail page is caught", () => {
        const path = "/dashboard/finance/bank-reconciliation";
        const saved = HELP_PAGE_MAP[path];
        delete HELP_PAGE_MAP[path];
        try {
            expect(navPaths("ACCOUNTANT").filter(p => !HELP_PAGE_MAP[p])).toEqual([path]);
        } finally {
            HELP_PAGE_MAP[path] = saved;
        }
    });
});

describe("nothing the help points at is missing", () => {
    it("every page entry names a real article, tour and published videos", () => {
        const broken = entries().flatMap(([path, e]) => [
            ...(e.article && !getArticleBySlug(e.article) ? [`${path}: article ${e.article}`] : []),
            ...(e.tour && !getTourById(e.tour) ? [`${path}: tour ${e.tour}`] : []),
            ...(e.videos ?? []).filter(v => !PUBLISHED.has(v)).map(v => `${path}: video ${v}`),
        ]);
        expect(broken).toEqual([]);
    });

    it("no article names a tour that does not exist", () => {
        const dangling = getAllArticles().filter(a => a.relatedTour && !getTourById(a.relatedTour))
            .map(a => `${a.slug}: ${a.relatedTour}`);
        expect(dangling).toEqual([]);
    });

    it("defines the contract-list tour the contract pages point at", () => {
        expect(getTourById("lease-workflow")?.steps.length).toBeGreaterThan(0);
        expect(new Set(ALL_TOURS.map(t => t.id)).size).toBe(ALL_TOURS.length);
    });

    it("every in-article link opens a real article or a published video", () => {
        const broken = getAllArticles().flatMap(a =>
            [...a.content.matchAll(/\]\((\/[^)]*)\)/g)].map(m => m[1]).filter(href => {
                const play = href.match(/^\/dashboard\/help\/videos\?play=(\w+)$/);
                if (play) return !PUBLISHED.has(play[1]);
                const slug = href.match(/^\/dashboard\/help\/([\w-]+)$/);
                return !slug || !getArticleBySlug(slug[1]);
            }).map(href => `${a.slug}: ${href}`));
        expect(broken).toEqual([]);
    });

    it("every article sits in a category the help center shows", () => {
        const ids = new Set(HELP_CATEGORIES.map(c => c.id));
        expect(getAllArticles().filter(a => !ids.has(a.category)).map(a => a.slug)).toEqual([]);
    });
});

describe("help articles go to the roles that can do what they describe", () => {
    const roles = (slug: string) => getArticleBySlug(slug)!.roles;

    it("keeps property and contract creation away from property managers", () => {
        for (const r of roles("getting-started--first-property-setup")) expect(PERMISSIONS.canCreateProperties).toContain(r);
        for (const r of roles("leases--creating-a-lease")) {
            expect([...PERMISSIONS.canManageLeases, ...PERMISSIONS.canPostLeases]).toContain(r);
        }
        expect(roles("getting-started--first-property-setup")).not.toContain("PROPERTY_MANAGER");
        expect(roles("leases--creating-a-lease")).not.toContain("PROPERTY_MANAGER");
    });

    it("serves the welcome and roles articles to every role, accountants included", () => {
        for (const slug of ["getting-started--welcome", "getting-started--roles-and-permissions"]) {
            expect([...roles(slug)].sort()).toEqual([...ROLES].sort());
        }
    });

    it("gives a new organisation's admin and accountant the start-here checklist first", () => {
        for (const role of ["TENANT_ADMIN", "ACCOUNTANT"] as const) {
            expect(filterArticlesByRole(getAllArticles(), role)[0].slug).toBe("getting-started--start-here");
        }
        expect(roles("getting-started--start-here")).not.toContain("RENTER");
    });

    it("links the start-here checklist to the setup videos", () => {
        const content = getArticleBySlug("getting-started--start-here")!.content;
        for (const id of ["38", "39", "17", "06", "08", "09", "10", "34"]) expect(content, id).toContain(`play=${id})`);
    });

    it("has the new articles", () => {
        for (const slug of ["getting-started--start-here", "leases--renewal-season", "leases--tenants-and-portal-access",
            "properties--parking", "leases--unit-transfer", "operations--gate-passes"]) {
            expect(getArticleBySlug(slug), slug).toBeDefined();
        }
    });

    it("serves a security guard the gate article and the gate video", () => {
        expect(filterArticlesByRole(getAllArticles(), "SECURITY_GUARD").map(a => a.slug)).toContain("operations--gate-passes");
        expect(TUTORIALS.find(t => t.id === "25")!.roles).toContain("SECURITY_GUARD");
    });

    it("tells no one unit rent is monthly, about Razorpay, or about a Total Units field", () => {
        const stale = /Monthly rent amount|Razorpay|Total Units|Create Tenancy Contract|Property Manager or Company User/;
        expect(getAllArticles().filter(a => stale.test(a.content)).map(a => a.slug)).toEqual([]);
    });
});
