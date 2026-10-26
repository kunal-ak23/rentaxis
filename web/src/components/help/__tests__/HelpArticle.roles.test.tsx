import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { UserRole } from "@/lib/rbac";
import type { HelpArticle as Article } from "@/lib/help";
import type { Tutorial } from "@/lib/tutorials/catalog";

/**
 * An article page offers only the videos and the tour made for the reader's role
 * (as the help button does), and its in-app links stay in the app.
 */

let role: UserRole = "PROPERTY_MANAGER";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a data-app-link href={href}>{children}</a>,
}));
const TOUR = { id: "finance-overview", name: "Finance Module Tour", description: "d", roles: ["TENANT_ADMIN", "ACCOUNTANT"], steps: [] };
vi.mock("@/components/tour/tours", () => ({ getTourById: () => TOUR }));
vi.mock("@/components/tour/TourTrigger", () => ({ default: ({ tour }: { tour: { name: string } }) => <button>{tour.name}</button> }));

import HelpArticleView from "../HelpArticle";

afterEach(cleanup);

const ARTICLE: Article = {
    slug: "finance--chart-of-accounts", title: "Chart of Accounts", description: "d", category: "finance",
    roles: ["TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER"], order: 1, relatedTour: "finance-overview",
    content: "See [Start here](/dashboard/help/getting-started--start-here) and [the FTA](https://tax.gov.ae).",
};
const video = (id: string, roles: UserRole[]): Tutorial => ({
    id, slug: `v-${id}`, title: { en: `Video ${id}`, ar: `فيديو ${id}` }, description: { en: "d", ar: "و" },
    topic: "accounting", roles, durationSec: 60, youtubeId: "abcdefghijk", relatedArticles: [ARTICLE.slug],
});
const VIDEOS = [video("17", ["TENANT_ADMIN", "ACCOUNTANT"]), video("35", ["PROPERTY_MANAGER", "ACCOUNTANT"])];
const videoHrefs = () => screen.queryAllByRole("link").map(a => a.getAttribute("href")).filter(h => h?.includes("?play="));

describe("HelpArticle for the reader's role", () => {
    it("shows an accountant both videos and the tour", () => {
        role = "ACCOUNTANT";
        render(<HelpArticleView article={ARTICLE} tutorials={VIDEOS} />);
        expect(videoHrefs()).toEqual(["/dashboard/help/videos?play=17", "/dashboard/help/videos?play=35"]);
        expect(screen.getByRole("button", { name: TOUR.name })).toBeInTheDocument();
    });

    it("hides from a property manager the video and the tour not made for them", () => {
        role = "PROPERTY_MANAGER";
        render(<HelpArticleView article={ARTICLE} tutorials={VIDEOS} />);
        expect(videoHrefs()).toEqual(["/dashboard/help/videos?play=35"]);
        expect(screen.queryByRole("button", { name: TOUR.name })).not.toBeInTheDocument();
    });

    it("keeps an in-app link in the app and opens an outside link in a new tab", () => {
        role = "ACCOUNTANT";
        render(<HelpArticleView article={ARTICLE} tutorials={[]} />);
        const inApp = screen.getByRole("link", { name: "Start here" });
        expect(inApp).toHaveAttribute("data-app-link");
        expect(inApp).toHaveAttribute("href", "/dashboard/help/getting-started--start-here");
        const outside = screen.getByRole("link", { name: "the FTA" });
        expect(outside).not.toHaveAttribute("data-app-link");
        expect(outside).toHaveAttribute("target", "_blank");
    });
});
