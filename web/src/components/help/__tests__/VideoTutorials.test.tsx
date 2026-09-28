import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { Tutorial } from "@/lib/tutorials/catalog";
import type { HelpArticle as Article } from "@/lib/help";

let role: string | undefined = "PROPERTY_MANAGER";
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: role ? { user: { role } } : null }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    usePathname: () => "/dashboard/help/videos",
}));
vi.mock("@/components/tour/TourProvider", () => ({
    useTour: () => ({ availableTours: [], startTour: vi.fn(), completedTourIds: [] }),
    isTourCompleted: () => false,
}));
vi.mock("@/components/tour/tours", () => ({ getTourById: () => undefined }));

import VideoTutorials from "../VideoTutorials";
import HelpArticleView from "../HelpArticle";

const t = (over: Partial<Tutorial> & Pick<Tutorial, "id">): Tutorial => ({
    slug: `t-${over.id}`, title: { en: `Video ${over.id}`, ar: `فيديو ${over.id}` },
    description: { en: `About ${over.id}`, ar: `عن ${over.id}` }, topic: "getting-started",
    roles: ["PROPERTY_MANAGER"], durationSec: 156, youtubeId: "abcdefghijk", ...over,
});

const FIXTURE: Tutorial[] = [
    t({ id: "01", topic: "getting-started", title: { en: "Sign in", ar: "تسجيل الدخول" } }),
    t({ id: "06", topic: "portfolio", title: { en: "Build a portfolio", ar: "إنشاء محفظة" } }),
    t({ id: "10", topic: "leasing", title: { en: "Draft a tenancy contract", ar: "صياغة عقد إيجار" }, youtubeId: null }),
    t({ id: "20", topic: "accounting", roles: ["TENANT_ADMIN"], title: { en: "Financial reports", ar: "التقارير المالية" } }),
    t({ id: "21", topic: "tenant-portal", roles: ["RENTER"], title: { en: "Pay rent online", ar: "دفع الإيجار" }, youtubeId: "ZYXWVUTSRQP" }),
];

const renderIn = (locale: "en" | "ar", node: React.ReactNode) =>
    render(<NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>{node}</NextIntlClientProvider>);

beforeEach(() => {
    role = "PROPERTY_MANAGER";
});
afterEach(() => {
    cleanup();
});

const cardTitles = () => screen.queryAllByTestId("video-card").map((c) => within(c).getByRole("heading").textContent);

describe("VideoTutorials", () => {
    it("shows only published entries for the viewer's role, grouped under topic headings", () => {
        renderIn("en", <VideoTutorials tutorials={FIXTURE} />);
        expect(cardTitles()).toEqual(["Sign in", "Build a portfolio"]);
        expect(screen.getByRole("heading", { level: 2, name: en.Help.videos.topics["getting-started"] })).toBeInTheDocument();
        expect(screen.getByRole("heading", { level: 2, name: en.Help.videos.topics.portfolio })).toBeInTheDocument();
        // Null-id entry (10) is never shown, nor its topic.
        expect(screen.queryByText("Draft a tenancy contract")).not.toBeInTheDocument();
        expect(screen.queryByRole("heading", { level: 2, name: en.Help.videos.topics.leasing })).not.toBeInTheDocument();
    });

    it("renders thumbnail, duration and topic on each card", () => {
        renderIn("en", <VideoTutorials tutorials={FIXTURE} />);
        const card = screen.getAllByTestId("video-card")[0];
        const img = within(card).getByRole("img");
        expect(img).toHaveAttribute("src", "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg");
        expect(img.getAttribute("alt")).toContain("Sign in");
        expect(within(card).getByText("2:36")).toBeInTheDocument();
        expect(within(card).getByText(en.Help.videos.topics["getting-started"])).toBeInTheDocument();
    });

    it("a renter sees only renter videos", () => {
        role = "RENTER";
        renderIn("en", <VideoTutorials tutorials={FIXTURE} />);
        expect(cardTitles()).toEqual(["Pay rent online"]);
    });

    it("no session: nothing for the role", () => {
        role = undefined;
        renderIn("en", <VideoTutorials tutorials={FIXTURE} />);
        expect(cardTitles()).toEqual([]);
        expect(screen.getByText(en.Help.videos.noneForRole)).toBeInTheDocument();
    });

    it("admins get a show-all toggle; others do not", () => {
        renderIn("en", <VideoTutorials tutorials={FIXTURE} />);
        expect(screen.queryByLabelText(en.Help.showAllRoles)).not.toBeInTheDocument();
        cleanup();

        role = "TENANT_ADMIN";
        renderIn("en", <VideoTutorials tutorials={FIXTURE} />);
        expect(cardTitles()).toEqual(["Financial reports"]);
        fireEvent.click(screen.getByLabelText(en.Help.showAllRoles));
        expect(cardTitles()).toEqual(["Sign in", "Build a portfolio", "Financial reports", "Pay rent online"]);
    });

    it("search narrows by title or description", async () => {
        role = "SUPER_ADMIN";
        renderIn("en", <VideoTutorials tutorials={FIXTURE} />);
        fireEvent.click(screen.getByLabelText(en.Help.showAllRoles));
        fireEvent.change(screen.getByPlaceholderText(en.Help.videos.searchPlaceholder), { target: { value: "portfolio" } });
        await waitFor(() => expect(cardTitles()).toEqual(["Build a portfolio"]));
        fireEvent.change(screen.getByPlaceholderText(en.Help.videos.searchPlaceholder), { target: { value: "zzz" } });
        await waitFor(() => expect(screen.getByText(en.Help.videos.noMatch)).toBeInTheDocument());
    });

    it("topic chips filter to one topic; All topics resets", () => {
        role = "SUPER_ADMIN";
        renderIn("en", <VideoTutorials tutorials={FIXTURE} />);
        fireEvent.click(screen.getByLabelText(en.Help.showAllRoles));
        const chips = screen.getByRole("group", { name: en.Help.videos.topicsLabel });
        // Only topics with a visible video get a chip.
        expect(within(chips).queryByRole("button", { name: new RegExp(en.Help.videos.topics.leasing) })).not.toBeInTheDocument();
        const accounting = within(chips).getByRole("button", { name: new RegExp(en.Help.videos.topics.accounting) });
        fireEvent.click(accounting);
        expect(accounting).toHaveAttribute("aria-pressed", "true");
        expect(cardTitles()).toEqual(["Financial reports"]);
        fireEvent.click(within(chips).getByRole("button", { name: new RegExp(en.Help.videos.allTopics) }));
        expect(cardTitles()).toHaveLength(4);
    });

    it("empty state when no entry has a YouTube id (EN)", () => {
        renderIn("en", <VideoTutorials tutorials={FIXTURE.map((x) => ({ ...x, youtubeId: null }))} />);
        expect(screen.getByText(en.Help.videos.comingSoon)).toBeInTheDocument();
        expect(screen.queryByPlaceholderText(en.Help.videos.searchPlaceholder)).not.toBeInTheDocument();
    });

    it("the real catalogue renders the empty state today", () => {
        renderIn("en", <VideoTutorials />);
        expect(screen.getByText("Video tutorials are coming soon")).toBeInTheDocument();
    });

    it("empty state and tabs in Arabic", () => {
        renderIn("ar", <VideoTutorials tutorials={[]} />);
        expect(screen.getByText(ar.Help.videos.comingSoon)).toBeInTheDocument();
        expect(screen.getByRole("link", { name: ar.Help.tabs.videos })).toHaveAttribute("aria-current", "page");
        expect(screen.getByRole("link", { name: ar.Help.tabs.articles })).toHaveAttribute("href", "/dashboard/help");
    });

    it("Arabic cards show the Arabic title", () => {
        renderIn("ar", <VideoTutorials tutorials={FIXTURE} />);
        expect(cardTitles()).toEqual(["تسجيل الدخول", "إنشاء محفظة"]);
    });
});

describe("video player dialog", () => {
    it("creates the nocookie iframe only when opened, and removes it on Esc", () => {
        renderIn("en", <VideoTutorials tutorials={FIXTURE} />);
        expect(document.querySelector("iframe")).toBeNull();

        const play = screen.getByRole("button", { name: new RegExp("Sign in") });
        play.focus();
        fireEvent.click(play);

        const dialog = screen.getByRole("dialog", { name: "Sign in" });
        expect(dialog).toHaveAttribute("aria-modal", "true");
        const iframe = dialog.querySelector("iframe")!;
        expect(iframe.getAttribute("src")).toBe("https://www.youtube-nocookie.com/embed/abcdefghijk?rel=0");
        expect(iframe).toHaveAttribute("title", "Sign in");
        expect(document.querySelectorAll('iframe[src*="youtube.com/"]')).toHaveLength(0);

        fireEvent.keyDown(window, { key: "Escape" });
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(document.querySelector("iframe")).toBeNull();
        expect(document.activeElement).toBe(play);
    });

    it("closes from the close button and traps Tab inside", () => {
        renderIn("en", <VideoTutorials tutorials={FIXTURE} />);
        fireEvent.click(screen.getByRole("button", { name: new RegExp("Build a portfolio") }));
        const dialog = screen.getByRole("dialog");
        const close = within(dialog).getByRole("button", { name: en.Help.videos.close });
        expect(document.activeElement).toBe(close);

        const iframe = dialog.querySelector("iframe")!;
        // Tab from the last focusable wraps to the first; Shift+Tab from the first wraps to the last.
        act(() => iframe.focus());
        fireEvent.keyDown(window, { key: "Tab" });
        expect(document.activeElement).toBe(close);
        fireEvent.keyDown(window, { key: "Tab", shiftKey: true });
        expect(document.activeElement).toBe(iframe);
        // Focus that escaped the dialog is pulled back in.
        act(() => (document.body as HTMLElement).focus());
        fireEvent.keyDown(window, { key: "Tab" });
        expect(dialog.contains(document.activeElement)).toBe(true);

        fireEvent.click(close);
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("opens straight to a requested video (?play=)", () => {
        renderIn("en", <VideoTutorials tutorials={FIXTURE} initialPlayId="06" />);
        expect(screen.getByRole("dialog", { name: "Build a portfolio" })).toBeInTheDocument();
    });

    it("ignores a requested video that is not published", () => {
        renderIn("en", <VideoTutorials tutorials={FIXTURE} initialPlayId="10" />);
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
});

describe("related article link", () => {
    const ARTICLE: Article = {
        slug: "leases--creating-a-lease", title: "Creating a contract", description: "Steps",
        category: "leases", roles: ["TENANT_ADMIN"], order: 1, content: "Body",
    };

    it("shows Watch the video when a published tutorial names the article", () => {
        const list = [t({ id: "10", youtubeId: "abcdefghijk", relatedArticles: [ARTICLE.slug] })];
        renderIn("en", <HelpArticleView article={ARTICLE} tutorials={list} />);
        const link = screen.getByRole("link", { name: new RegExp(en.Help.videos.watchVideo) });
        expect(link).toHaveAttribute("href", "/dashboard/help/videos?play=10");
    });

    it("no link while that tutorial has no YouTube id", () => {
        const list = [t({ id: "10", youtubeId: null, relatedArticles: [ARTICLE.slug] })];
        renderIn("en", <HelpArticleView article={ARTICLE} tutorials={list} />);
        expect(screen.queryByRole("link", { name: new RegExp(en.Help.videos.watchVideo) })).not.toBeInTheDocument();
    });

    it("no link for an unrelated article", () => {
        const list = [t({ id: "10", youtubeId: "abcdefghijk", relatedArticles: ["other"] })];
        renderIn("en", <HelpArticleView article={ARTICLE} tutorials={list} />);
        expect(screen.queryByRole("link", { name: new RegExp(en.Help.videos.watchVideo) })).not.toBeInTheDocument();
    });
});
