import { Suspense } from "react";
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * A listing that does not load used to render the edit form empty, with Save
 * live: a PUT of blanks over the listing, or a second 404. A 404 now says the
 * listing was not found, any other failure that it could not be loaded, and
 * neither offers the form.
 */

const api = vi.hoisted(() => ({
    fetchListing: vi.fn(),
    updateListing: vi.fn(),
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
    updateListing: api.updateListing,
    publishListing: vi.fn(),
    createListing: vi.fn(),
    unlistListing: vi.fn(),
    uploadMedia: vi.fn(),
    deleteMedia: vi.fn(),
    reorderMedia: vi.fn(),
}));

import ListingEditPage from "../page";

async function renderPage() {
    await act(async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <Suspense fallback={null}>
                    <ListingEditPage params={Promise.resolve({ id: "missing-id" })} />
                </Suspense>
            </NextIntlClientProvider>,
        );
    });
}

function failWith(status: number) {
    api.fetchListing.mockRejectedValue(
        Object.assign(new Error(`Failed to fetch listing: ${status}`), { status }),
    );
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Opening a listing that does not load", () => {
    it("says not found on a 404 and offers no form and no Save", async () => {
        failWith(404);
        await renderPage();

        expect(await screen.findByText(en.Listings.listingNotFound)).toBeInTheDocument();
        expect(screen.getByRole("link", { name: en.Listings.backToListingsLink }))
            .toHaveAttribute("href", "/dashboard/listings");
        expect(screen.queryByRole("button", { name: en.Listings.saveChanges })).toBeNull();
        expect(screen.queryByRole("textbox")).toBeNull();
        expect(api.updateListing).not.toHaveBeenCalled();
    });

    it("says it could not be loaded on any other failure", async () => {
        failWith(500);
        await renderPage();

        expect(await screen.findByText(en.Listings.listingLoadFailed)).toBeInTheDocument();
        expect(screen.queryByText(en.Listings.listingNotFound)).toBeNull();
        expect(screen.queryByRole("textbox")).toBeNull();
    });
});
