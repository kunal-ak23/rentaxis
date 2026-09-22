import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { filterArticlesByRole } from "../help";
import { getAllArticles, getArticleBySlug } from "../helpLoader";
import { PERMISSIONS, type UserRole } from "../rbac";
import "../helpArticles";

/**
 * Help articles exist twice: `src/lib/helpArticles.ts` is the ONLY runtime source
 * (the two help pages side-effect-import it to call registerArticle), and
 * `src/content/help/*.md` is the authoring copy that nothing imports. Editing the
 * markdown alone changes nothing a user sees — which is the failure this file is
 * here to catch.
 */

const CONTENT_DIR = path.resolve(__dirname, "../../content/help");

const VALID_ROLES = new Set<string>([
    "SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT",
    "TENANT_USER", "RENTER", "SECURITY_GUARD",
]);

function rolesInFrontmatter(raw: string): string[] {
    const line = raw.split("\n").find(l => l.startsWith("roles:"));
    if (!line) return [];
    return line.slice("roles:".length).trim().replace(/^\[|\]$/g, "").split(",").map(v => v.trim());
}

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

    /**
     * The registry and the markdown copy must not drift: a reader who edits only the
     * .md sees nothing change in the app, and a reader who edits only the .ts leaves
     * the authoring copy lying about who the article is for.
     */
    it("keeps the markdown copies and the runtime registry in agreement on roles", () => {
        for (const file of readdirSync(CONTENT_DIR).filter(f => f.endsWith(".md"))) {
            const slug = file.replace(/\.md$/, "");
            const article = getArticleBySlug(slug);
            expect(article, `${file} has no registerArticle entry in helpArticles.ts`).toBeDefined();
            expect(rolesInFrontmatter(readFileSync(path.join(CONTENT_DIR, file), "utf8")),
                `roles drifted between ${file} and helpArticles.ts`)
                .toEqual(article!.roles as UserRole[]);
        }
    });
});
