import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";
import type { HelpArticle as Article } from "@/lib/help";

/**
 * #81: the Help Center's navigation and chrome were English under /ar. An
 * article with an Arabic version is shown in Arabic, laid out RTL; one without
 * is shown as English, marked lang="en" and laid out LTR, with a note that says so.
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
const ARTICLE_AR: Article = {
    ...ARTICLE, slug: "leases--lease-lifecycle",
    ar: { title: "دورة حياة عقد الإيجار", description: "حالات العقد وإجراءاته", content: "## الحالات\n\nافتح **عقود الإيجار** واختر العقد." },
};
// Content, not chrome: the article and tour text (the brand is مفتاح in Arabic).
const CONTENT = [ARTICLE.title, ARTICLE.description, "Creating a lease", "Open the lease wizard and follow the steps.",
    TOUR.name, TOUR.description];

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

    it("the index: an article with an Arabic version shows its Arabic title and no English note", () => {
        const { container } = renderAr(<HelpCenter articles={[ARTICLE_AR]} />);

        expect(screen.getByText(ARTICLE_AR.ar!.title)).toHaveAttribute("lang", "ar");
        expect(screen.getByText(ARTICLE_AR.ar!.description)).toHaveAttribute("lang", "ar");
        expect(screen.queryByText(ar.Help.contentEnglishOnly)).toBeNull();
        expect(leftoverLatinWords(visibleText(container), [TOUR.name, TOUR.description])).toEqual([]);
    });

    it("an article with an Arabic version: the Arabic body, marked and laid out RTL, with no English note", () => {
        const { container } = renderAr(<HelpArticleView article={ARTICLE_AR} />);

        expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(ARTICLE_AR.ar!.title);
        const body = screen.getByText("واختر العقد.", { exact: false }).closest("[lang]")!;
        expect(body).toHaveAttribute("lang", "ar");
        expect(body).toHaveAttribute("dir", "rtl");
        expect(screen.queryByText(ar.Help.contentEnglishOnly)).toBeNull();
        expect(screen.queryByText("Open the lease wizard and follow the steps.")).toBeNull();
        expect(leftoverLatinWords(visibleText(container), [TOUR.name, TOUR.description])).toEqual([]);
    });

    it("an article with no Arabic version: chrome in Arabic, the English body marked and laid out LTR", () => {
        const { container } = renderAr(<HelpArticleView article={ARTICLE} />);

        expect(screen.getByText(ar.Help.backToHelp)).toBeInTheDocument();
        expect(screen.getByText(ar.Help.tourAvailable)).toBeInTheDocument();
        const body = screen.getByText("Open the lease wizard and follow the steps.").closest("[lang]")!;
        expect(body).toHaveAttribute("dir", "ltr");
        expect(screen.getByText(ar.Help.contentEnglishOnly)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), CONTENT)).toEqual([]);
    });
});
