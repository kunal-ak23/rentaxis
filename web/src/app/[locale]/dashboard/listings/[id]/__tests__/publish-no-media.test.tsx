import { Suspense } from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * Gap #58: a listing with zero media published silently, and the public page
 * then invited visitors to "view all photos". Publishing stays allowed, but a
 * listing with no media asks first.
 */

const api = vi.hoisted(() => ({
    fetchListing: vi.fn(),
    publishListing: vi.fn(),
}));

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
    publishListing: api.publishListing,
    createListing: vi.fn(),
    updateListing: vi.fn(),
    unlistListing: vi.fn(),
    uploadMedia: vi.fn(),
    deleteMedia: vi.fn(),
    reorderMedia: vi.fn(),
}));

import ListingEditPage from "../page";

function listing(media: unknown[]) {
    return {
        id: "lst-1", unitId: "u1", status: "DRAFT", titleEn: "A-102", titleAr: null,
        descriptionEn: null, descriptionAr: null, bedrooms: 1, bathrooms: 1, sizeSqft: null,
        floor: null, parkingSpaces: null, furnishing: null, viewType: null, annualRent: 90000,
        securityDeposit: null, minLeaseMonths: null, chequesAccepted: null, dewaIncluded: null,
        chillerIncluded: null, utilitiesEstimate: null, availableFrom: null, tenantSlug: "miftah",
        slug: "a-102", seoTitle: null, seoDescription: null, seoKeywords: null, ogImageUrl: null,
        lat: null, lng: null, publishedAt: null, createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z", amenities: [], media,
    };
}

async function renderPage() {
    await act(async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <Suspense fallback={null}>
                    <ListingEditPage params={Promise.resolve({ id: "lst-1" })} />
                </Suspense>
            </NextIntlClientProvider>,
        );
    });
    return screen.findByRole("button", { name: en.Listings.actionPublish });
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    api.publishListing.mockResolvedValue(undefined);
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Publishing a listing (#58)", () => {
    it("asks before publishing a listing with no media, and publishes on confirm", async () => {
        api.fetchListing.mockResolvedValue(listing([]));
        fireEvent.click(await renderPage());

        expect(await screen.findByText(en.Listings.confirmPublishNoMedia)).toBeInTheDocument();
        expect(api.publishListing).not.toHaveBeenCalled();

        fireEvent.click(screen.getByRole("button", { name: en.Listings.confirmPublishAnyway }));
        await waitFor(() => expect(api.publishListing).toHaveBeenCalledWith("lst-1", "tok"));
    });

    it("publishes a listing with photos straight away", async () => {
        api.fetchListing.mockResolvedValue(
            listing([{ id: "m1", mediaType: "PHOTO", url: "https://x/1.jpg", caption: null, sortOrder: 0, isCover: true }]),
        );
        fireEvent.click(await renderPage());

        await waitFor(() => expect(api.publishListing).toHaveBeenCalledWith("lst-1", "tok"));
        expect(screen.queryByText(en.Listings.confirmPublishNoMedia)).toBeNull();
    });
});
