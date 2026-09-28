import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Suspense } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Break-it R3 portal3 F2: saving or removing interest in a listing ended in
 * `catch {}` — the spinner stopped and nothing was said, so the renter thought
 * it had registered (or been withdrawn).
 */

const intl = vi.hoisted(() => ({ locale: "en" as "en" | "ar" }));
const api = vi.hoisted(() => ({
    fetchMarketplaceListing: vi.fn(),
    fetchWishlist: vi.fn(),
    addInterest: vi.fn(),
    removeInterest: vi.fn(),
}));
vi.mock("@/lib/api/listings", () => api);
const sessionData = vi.hoisted(() => ({ data: { user: { role: "RENTER", accessToken: "tok" } } }));
vi.mock("next-auth/react", () => ({ useSession: () => sessionData }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a> }));
vi.mock("next-intl", async () => {
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const en = (await import("../../../../../../../messages/en.json")).default;
    const ar = (await import("../../../../../../../messages/ar.json")).default;
    // One translator per locale+namespace: a new function each render would re-run the page's load effect forever.
    const cache = new Map<string, ReturnType<typeof createTranslator>>();
    return {
        useTranslations: (namespace: string) => {
            const key = `${intl.locale}:${namespace}`;
            if (!cache.has(key)) cache.set(key, createTranslator({ locale: intl.locale, messages: intl.locale === "ar" ? ar : en, namespace: namespace as never }));
            return cache.get(key)!;
        },
        useLocale: () => intl.locale,
    };
});

import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";
import ListingDetailPage from "../page";

const listing = {
    id: "lst-1", unitId: "u1", status: "PUBLISHED", titleEn: "Two-bed in Tower A", titleAr: null,
    descriptionEn: null, descriptionAr: null, bedrooms: 2, bathrooms: 2, sizeSqft: 1100, floor: 4, parkingSpaces: 1,
    furnishing: null, viewType: null, annualRent: 90000, securityDeposit: null, minLeaseMonths: null, chequesAccepted: null,
    dewaIncluded: null, chillerIncluded: null, utilitiesEstimate: null, availableFrom: null,
    tenantSlug: "acme", slug: "two-bed", seoTitle: null, seoDescription: null, seoKeywords: null, ogImageUrl: null,
    lat: null, lng: null, publishedAt: null, createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-09-01T00:00:00Z",
    amenities: [], media: [],
};

beforeEach(() => {
    intl.locale = "en";
    api.fetchMarketplaceListing.mockResolvedValue(listing);
    api.fetchWishlist.mockResolvedValue([]);
    api.addInterest.mockRejectedValue(new Error("Failed to add interest: 500"));
    api.removeInterest.mockRejectedValue(new Error("Failed to remove interest: 500"));
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

async function renderPage() {
    const params = Promise.resolve({ tenantSlug: "acme", slug: "two-bed" });
    await act(async () => {
        render(<Suspense fallback={null}><ListingDetailPage params={params} /></Suspense>);
    });
    await screen.findAllByText("Two-bed in Tower A");
}

describe("Listing detail — interest save/remove failures are shown", () => {
    it.each([["en", en], ["ar", ar]] as const)("a failed save says so (%s)", async (locale, m) => {
        intl.locale = locale;
        await renderPage();
        const save = screen.getAllByRole("button", { name: new RegExp(m.Marketplace.wishlistAdd) });
        fireEvent.click(save[0]);
        expect((await screen.findAllByText(m.Marketplace.wishlistAddFailed)).length).toBeGreaterThan(0);
        expect(api.addInterest).toHaveBeenCalledOnce();
    });

    it("a failed remove says so, and the listing stays saved", async () => {
        api.fetchWishlist.mockResolvedValue([{ id: "lst-1" }]);
        await renderPage();
        const remove = await screen.findAllByRole("button", { name: new RegExp(en.Marketplace.wishlistRemove) });
        fireEvent.click(remove[0]);
        expect((await screen.findAllByText(en.Marketplace.wishlistRemoveFailed)).length).toBeGreaterThan(0);
        expect(screen.getAllByRole("button", { name: new RegExp(en.Marketplace.wishlistRemove) }).length).toBeGreaterThan(0);
    });
});
