import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { UserRole } from "@/lib/rbac";
import { getArticleBySlug } from "@/lib/helpLoader";
import "@/lib/helpArticles";

/**
 * The Welcome article names the admin welcome tour; a Company User, whose rail
 * has no Leasing or Accounting, is offered their own welcome tour there instead.
 */

let role: UserRole = "TENANT_USER";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("@/components/tour/TourTrigger", () => ({ default: ({ tour }: { tour: { id: string } }) => <button>{tour.id}</button> }));

import HelpArticleView from "../HelpArticle";

afterEach(cleanup);

const welcome = () => getArticleBySlug("getting-started--welcome")!;
const tourButtons = () => screen.queryAllByRole("button").map(b => b.textContent);

describe("Welcome article tour per role", () => {
    it("offers a Company User their own welcome tour", () => {
        role = "TENANT_USER";
        render(<HelpArticleView article={welcome()} tutorials={[]} />);
        expect(tourButtons()).toEqual(["company-user-onboarding"]);
    });

    it("offers a Company Admin the admin welcome tour", () => {
        role = "TENANT_ADMIN";
        render(<HelpArticleView article={welcome()} tutorials={[]} />);
        expect(tourButtons()).toEqual(["admin-onboarding"]);
    });

    it("offers no tour to a role with no welcome tour (accountant)", () => {
        role = "ACCOUNTANT";
        render(<HelpArticleView article={welcome()} tutorials={[]} />);
        expect(tourButtons()).toEqual([]);
    });
});
