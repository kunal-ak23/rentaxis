import { Suspense } from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * Scale PR B, task 5: the listing edit form's unit field stopped loading every
 * unit into the browser. It now asks the server (`UnitPicker`, `/units/search`),
 * and a listing's already-picked unit is still shown by label (resolved off
 * `/units/names` through the shared `useIdNames`/picker cache), even though the
 * field is disabled once the listing exists (a listing's unit does not change).
 */

const api = vi.hoisted(() => ({ fetchListing: vi.fn(), createListing: vi.fn(), updateListing: vi.fn() }));

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
    createListing: api.createListing,
    updateListing: api.updateListing,
    publishListing: vi.fn(),
    unlistListing: vi.fn(),
    uploadMedia: vi.fn(),
    deleteMedia: vi.fn(),
    reorderMedia: vi.fn(),
}));

const lookup = vi.hoisted(() => ({ searchUnits: vi.fn(), searchRenters: vi.fn(), unitNames: vi.fn(), renterNames: vi.fn() }));
vi.mock("@/lib/api/lookup", () => ({ lookupApi: lookup }));

import ListingEditPage from "../page";

/** The two unbounded reads this task removed: exactly `/units` or `/renters`, with or without a query. */
const UNBOUNDED = /\/api\/proxy\/v1\/(units|renters)(\?|$)/;
const urls = () => (global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.map(c => String(c[0]));

const UNIT = { id: "u1", unitNumber: "A-102", propertyId: "p1", propertyName: "L'Olivier", propertyType: null, buildingId: null, buildingName: null, status: null };

function listing(over: Partial<Record<string, unknown>> = {}) {
    return {
        id: "lst-1", unitId: "u1", status: "DRAFT", titleEn: "A-102", titleAr: null,
        descriptionEn: null, descriptionAr: null, bedrooms: 1, bathrooms: 1, sizeSqft: null,
        floor: null, parkingSpaces: null, furnishing: null, viewType: null, annualRent: 90000,
        securityDeposit: null, minLeaseMonths: null, chequesAccepted: null, dewaIncluded: null,
        chillerIncluded: null, utilitiesEstimate: null, availableFrom: null, tenantSlug: "miftah",
        slug: "a-102", seoTitle: null, seoDescription: null, seoKeywords: null, ogImageUrl: null,
        lat: null, lng: null, publishedAt: null, createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z", amenities: [], media: [],
        ...over,
    };
}

async function renderPage(id: string) {
    await act(async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <Suspense fallback={null}>
                    <ListingEditPage params={Promise.resolve({ id })} />
                </Suspense>
            </NextIntlClientProvider>,
        );
    });
}

beforeEach(() => {
    lookup.searchUnits.mockResolvedValue([UNIT]);
    lookup.unitNames.mockResolvedValue([UNIT]);
    lookup.searchRenters.mockResolvedValue([]);
    lookup.renterNames.mockResolvedValue([]);
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("Listing edit: unit field is the server-backed UnitPicker", () => {
    it("never calls /api/proxy/v1/units on load", async () => {
        api.fetchListing.mockResolvedValue(listing());
        await renderPage("lst-1");
        await screen.findByTestId("listing-unit-picker");
        expect(urls().filter(u => UNBOUNDED.test(u))).toEqual([]);
    });

    it("shows the existing listing's unit by label even though the field is disabled", async () => {
        api.fetchListing.mockResolvedValue(listing());
        await renderPage("lst-1");
        await waitFor(() => expect(screen.getByTestId("listing-unit-picker")).toHaveTextContent("A-102"));
        expect(screen.getByTestId("listing-unit-picker")).toBeDisabled();
    });

    it("searches units on the server for a new listing, and picks one by id", async () => {
        await renderPage("new");
        const picker = await screen.findByTestId("listing-unit-picker");
        expect(picker).not.toBeDisabled();
        fireEvent.click(picker);
        fireEvent.click(await screen.findByText("A-102"));
        expect(screen.getByTestId("listing-unit-picker")).toHaveTextContent("A-102");
    });
});
