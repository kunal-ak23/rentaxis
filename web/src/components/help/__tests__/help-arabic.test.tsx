import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";
import type { HelpArticle as Article } from "@/lib/help";

/**
 * #81: the Help Center's navigation and chrome were English under /ar. The
 * articles are content with no translation yet (a follow-up); they are shown
 * as English, marked lang="en" and laid out LTR, with a note that says so.
 */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
const TOUR = { id: "admin-onboarding", name: "Admin onboarding", description: "Walk through the portal", roles: ["TENANT_ADMIN"], steps: [] };
vi.mock("@/components/tour/TourProvider", () => ({
    useTour: () => ({ availableTours: [TOUR], startTour: vi.fn(), completedTourIds: [] }),
    isTourCompleted: () => false,
}));
vi.mock("@/components/tour/tours", () => ({ getTourById: () => TOUR }));

import HelpCenter from "../HelpCenter";
import HelpArticleView from "../HelpArticle";

const ARTICLE: Article = {
    slug: "leases--creating-a-lease", title: "Creating a Lease", description: "Step by step lease setup",
    category: "leases", roles: ["TENANT_ADMIN"], order: 1, relatedTour: "admin-onboarding",
    content: "## Creating a lease\n\nOpen the lease wizard and follow the steps.",
};
// Content, not chrome: the article and tour text, and the brand name.
const CONTENT = [ARTICLE.title, ARTICLE.description, "Creating a lease", "Open the lease wizard and follow the steps.",
    TOUR.name, TOUR.description, "RentAxis"];

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

const renderAr = (node: React.ReactNode) =>
    render(<NextIntlClientProvider locale="ar" messages={ar}>{node}</NextIntlClientProvider>);

describe("help center in Arabic", () => {
    it("the index: title, categories, search, counts and tours chrome carry no English", () => {
        const { container } = renderAr(<HelpCenter articles={[ARTICLE]} />);

        expect(screen.getByText(ar.Help.title)).toBeInTheDocument();
        expect(screen.getByText(ar.Help.categories.leases)).toBeInTheDocument();
        expect(screen.getByText(ar.Help.contentEnglishOnly)).toBeInTheDocument();
        expect(screen.getByText(ARTICLE.title)).toHaveAttribute("lang", "en");
        expect(leftoverLatinWords(visibleText(container), CONTENT)).toEqual([]);
    });

    it("an article: chrome in Arabic, the English body marked and laid out LTR", () => {
        const { container } = renderAr(<HelpArticleView article={ARTICLE} />);

        expect(screen.getByText(ar.Help.backToHelp)).toBeInTheDocument();
        expect(screen.getByText(ar.Help.tourAvailable)).toBeInTheDocument();
        const body = screen.getByText("Open the lease wizard and follow the steps.").closest("[lang]")!;
        expect(body).toHaveAttribute("dir", "ltr");
        expect(leftoverLatinWords(visibleText(container), CONTENT)).toEqual([]);
    });
});
