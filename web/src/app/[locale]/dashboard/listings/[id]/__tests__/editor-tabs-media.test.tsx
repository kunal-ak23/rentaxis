import { Suspense } from "react";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";

/**
 * Tutorial 26 gaps: the editor's sections are a real tablist (role=tab,
 * aria-selected, arrow keys, mirrored in RTL), and a stored photo's thumbnail
 * loads through the authenticated proxy (bug 26/27 — the API names it by the
 * staff media route, never by its private blob URL).
 */

const api = vi.hoisted(() => ({ fetchListing: vi.fn() }));

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN", accessToken: "tok" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock("next/dynamic", () => ({ default: () => () => null }));
vi.mock("../../_components/InterestsDrawer", () => ({ InterestsDrawer: () => null }));
vi.mock("@/lib/api/listings", () => ({
    fetchListing: api.fetchListing,
    publishListing: vi.fn(), createListing: vi.fn(), updateListing: vi.fn(), unlistListing: vi.fn(),
    uploadMedia: vi.fn(), deleteMedia: vi.fn(), reorderMedia: vi.fn(),
}));

import ListingEditPage from "../page";

const listing = {
    id: "lst-1", unitId: "u1", status: "DRAFT", titleEn: "A-102", titleAr: null,
    descriptionEn: null, descriptionAr: null, bedrooms: 1, bathrooms: 1, sizeSqft: null,
    floor: null, parkingSpaces: null, furnishing: null, viewType: null, annualRent: 90000,
    securityDeposit: null, minLeaseMonths: null, chequesAccepted: null, dewaIncluded: null,
    chillerIncluded: null, utilitiesEstimate: null, availableFrom: null, tenantSlug: "miftah",
    slug: "a-102", seoTitle: null, seoDescription: null, seoKeywords: null, ogImageUrl: null,
    lat: null, lng: null, publishedAt: null, createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z", amenities: [],
    media: [{ id: "m1", mediaType: "PHOTO", url: "/api/listings/lst-1/media/m1/file", caption: null, sortOrder: 0, isCover: true }],
};

async function renderPage(locale: "en" | "ar" = "en") {
    await act(async () => {
        render(
            <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
                <Suspense fallback={null}>
                    <ListingEditPage params={Promise.resolve({ id: "lst-1" })} />
                </Suspense>
            </NextIntlClientProvider>,
        );
    });
    return screen.findByRole("tablist");
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    api.fetchListing.mockResolvedValue(listing);
});

afterEach(() => {
    cleanup();
    document.documentElement.dir = "";
    vi.clearAllMocks();
});

describe("Listing editor sections", () => {
    it("are tabs with one selected, each controlling its panel", async () => {
        const list = await renderPage();
        expect(list.getAttribute("aria-label")).toBe(en.Listings.editorSections);
        const tabs = screen.getAllByRole("tab");
        expect(tabs.map(t => t.textContent)).toEqual([
            en.Listings.tabDetails, en.Listings.tabMedia, en.Listings.tabAmenities,
            en.Listings.tabSeo, en.Listings.tabPricing, en.Listings.tabLocation,
        ]);
        expect(tabs.filter(t => t.getAttribute("aria-selected") === "true")).toHaveLength(1);
        expect(tabs[0].getAttribute("tabindex")).toBe("0");
        expect(tabs[1].getAttribute("tabindex")).toBe("-1");
        expect(screen.getByRole("tabpanel").getAttribute("aria-labelledby")).toBe(tabs[0].id);
    });

    it("move with the arrow keys, Home and End", async () => {
        const list = await renderPage();
        fireEvent.keyDown(list, { key: "ArrowRight" });
        expect(screen.getByRole("tab", { name: en.Listings.tabMedia }).getAttribute("aria-selected")).toBe("true");
        expect(document.activeElement).toBe(screen.getByRole("tab", { name: en.Listings.tabMedia }));
        fireEvent.keyDown(list, { key: "End" });
        expect(screen.getByRole("tab", { name: en.Listings.tabLocation }).getAttribute("aria-selected")).toBe("true");
        fireEvent.keyDown(list, { key: "ArrowRight" });
        expect(screen.getByRole("tab", { name: en.Listings.tabDetails }).getAttribute("aria-selected")).toBe("true");
        fireEvent.keyDown(list, { key: "ArrowLeft" });
        expect(screen.getByRole("tab", { name: en.Listings.tabLocation }).getAttribute("aria-selected")).toBe("true");
        fireEvent.keyDown(list, { key: "Home" });
        expect(screen.getByRole("tab", { name: en.Listings.tabDetails }).getAttribute("aria-selected")).toBe("true");
    });

    it("mirror the arrow keys in Arabic (RTL)", async () => {
        document.documentElement.dir = "rtl";
        const list = await renderPage("ar");
        fireEvent.keyDown(list, { key: "ArrowLeft" });
        expect(screen.getByRole("tab", { name: ar.Listings.tabMedia }).getAttribute("aria-selected")).toBe("true");
    });

    it("show a stored photo through the authenticated proxy, not as a blob URL", async () => {
        await renderPage();
        fireEvent.click(screen.getByRole("tab", { name: en.Listings.tabMedia }));
        const img = screen.getByRole("tabpanel").querySelector("img");
        expect(img?.getAttribute("src")).toBe("/api/proxy/listings/lst-1/media/m1/file");
        // The route is not shown to the admin as a caption.
        expect(screen.queryByText("/api/listings/lst-1/media/m1/file")).toBeNull();
    });
});
