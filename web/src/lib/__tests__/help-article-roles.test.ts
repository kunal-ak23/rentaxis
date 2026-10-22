import { describe, expect, it } from "vitest";

import { filterArticlesByRole } from "../help";
import { getAllArticles, getArticleBySlug } from "../helpLoader";
import { PERMISSIONS } from "../rbac";
import "../helpArticles";

/**
 * `src/lib/helpArticles.ts` is the only source of the help articles (the two help
 * pages side-effect-import it to call registerArticle); the old unimported
 * markdown copies under src/content/help were removed so there is nothing to drift.
 */

const VALID_ROLES = new Set<string>([
    "SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT",
    "TENANT_USER", "RENTER", "SECURITY_GUARD",
]);

describe("help article roles", () => {
    it("serves the chart-of-accounts article to an accountant", () => {
        const article = getArticleBySlug("finance--chart-of-accounts");
        expect(article).toBeDefined();
        expect(article!.roles).toContain("ACCOUNTANT");
        expect(filterArticlesByRole(getAllArticles(), "ACCOUNTANT").map(a => a.slug))
            .toContain("finance--chart-of-accounts");
    });

    /** The article describes the CoA screens, which are exactly the canAccessFinance set. */
    it("serves it to every role that can reach the chart of accounts", () => {
        const article = getArticleBySlug("finance--chart-of-accounts")!;
        for (const role of PERMISSIONS.canManageAccountSetup) {
            if (role === "SUPER_ADMIN") continue; // super admins browse the superadmin help, not tenant help
            expect(article.roles, `${role} can open the page but is not served its article`).toContain(role);
        }
    });

    it("uses only real roles in every article's frontmatter", () => {
        for (const article of getAllArticles()) {
            for (const role of article.roles as string[]) {
                expect(VALID_ROLES, `${article.slug} names an unknown role "${role}"`).toContain(role);
            }
        }
    });

});
