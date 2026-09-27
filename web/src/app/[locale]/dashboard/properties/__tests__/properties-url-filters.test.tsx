import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * Scale PR B2, task 7: the Properties list's search, page, size and the
 * table/cards view toggle now live in the URL via the shared `useUrlState`
 * hook (same pattern as Tickets/Renters), so a reload restores all four. The
 * list itself stays client-side (small, unpaged server read) — only the
 * bookmark shape changes.
 *
 * Also covers the controller ruling for a client-side list: a bookmarked page
 * beyond the last page for the current (filtered) result set clamps to the
 * last page instead of rendering a blank table.
 */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));

import PropertiesPage from "../page";

function property(id: string, nameEn: string) {
    return {
        property: { id, nameEn, nameAr: "", emirate: "DUBAI", address: null, makaniNumber: "1", type: "RESIDENTIAL", fixedExpenses: 0 },
        propertyCount: 1, revenueAtCapacity: 1000, actualRevenue: 800, vacancies: 0, assignedManagers: [],
    };
}

const json = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as unknown as Response;

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <PropertiesPage />
        </NextIntlClientProvider>,
    );
}

describe("Properties list — URL-persisted search/page/size/view", () => {
    it("restores the search box and the cards view from the URL on reload", async () => {
        window.history.replaceState(null, "", "/en/dashboard/properties?q=marina&view=cards");
        global.fetch = vi.fn(async () => json([property("p1", "Marina Tower"), property("p2", "Downtown Loft")])) as unknown as typeof fetch;

        renderPage();
        await screen.findByText("Marina Tower");

        const searchBox = screen.getByPlaceholderText(en.MasterData.search) as HTMLInputElement;
        expect(searchBox.value).toBe("marina");
        // Downtown Loft doesn't match the "marina" search — filtered out.
        expect(screen.queryByText("Downtown Loft")).toBeNull();
        // Cards view renders "Manage Property" footer CTAs, not a "Manage" table link.
        expect(screen.getByText(en.MasterData.manageProperty)).toBeInTheDocument();
    });

    it("restores the page and size from the URL on reload", async () => {
        window.history.replaceState(null, "", "/en/dashboard/properties?page=2&size=1");
        const stats = [property("p1", "First Tower"), property("p2", "Second Tower")];
        global.fetch = vi.fn(async () => json(stats)) as unknown as typeof fetch;

        renderPage();
        await screen.findByText("Second Tower");
        expect(screen.queryByText("First Tower")).toBeNull();
    });

    it("typing in the search box resets the page to 1", async () => {
        window.history.replaceState(null, "", "/en/dashboard/properties?page=2&size=1");
        const stats = [property("p1", "First Tower"), property("p2", "Second Tower")];
        global.fetch = vi.fn(async () => json(stats)) as unknown as typeof fetch;

        renderPage();
        await screen.findByText("Second Tower");

        const searchBox = screen.getByPlaceholderText(en.MasterData.search);
        fireEvent.change(searchBox, { target: { value: "First" } });

        await screen.findByText("First Tower");
        expect(new URL(window.location.href).searchParams.get("page")).toBeNull();
    });

    it("switching to the cards view persists it in the URL", async () => {
        window.history.replaceState(null, "", "/en/dashboard/properties");
        global.fetch = vi.fn(async () => json([property("p1", "Marina Tower")])) as unknown as typeof fetch;

        renderPage();
        await screen.findByText("Marina Tower");

        fireEvent.click(screen.getByText(en.MasterData.cards));

        await waitFor(() => expect(new URL(window.location.href).searchParams.get("view")).toBe("cards"));
    });

    it("clamps to the last page when a bookmarked page is beyond the filtered result set", async () => {
        window.history.replaceState(null, "", "/en/dashboard/properties?page=9&size=2");
        const stats = [
            property("p1", "Tower One"), property("p2", "Tower Two"), property("p3", "Tower Three"),
            property("p4", "Tower Four"), property("p5", "Tower Five"),
        ];
        global.fetch = vi.fn(async () => json(stats)) as unknown as typeof fetch;

        renderPage();
        // 5 items, size 2 -> 3 pages; page 9 clamps to page 3 (items 5).
        await screen.findByText("Tower Five");
        expect(new URL(window.location.href).searchParams.get("page")).toBe("3");
    });
});
