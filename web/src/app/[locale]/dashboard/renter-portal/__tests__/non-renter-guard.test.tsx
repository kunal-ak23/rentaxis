import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * The renter portal is the renter's own tenancy. Opened by anyone else — a
 * SUPER_ADMIN following the sidebar, say — it used to fire the renter-only
 * endpoints anyway, and the one that failed painted a red "Couldn't load this"
 * banner over a page greeting the admin by name with "Welcome to your Renter
 * Portal" and "NO LEASES FOUND". Nothing leaked, but the screen reported a
 * transport failure for what is really "you have no tenancy", and invited a
 * retry that could never succeed.
 *
 * A user with no tenancy is told so, and the requests are never made.
 */

let role = "SUPER_ADMIN";

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "System Admin", role } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/app/[locale]/dashboard/meetings/CreateMeetingModal", () => ({ default: () => null }));
vi.mock("@/components/renewals/RenewalBanner", () => ({ default: () => null }));

import RenterPortalPage from "../page";

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RenterPortalPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    // The renter endpoints this page calls all answer with arrays; returning an
    // object instead makes the page throw on `.map` and buries the assertion.
    global.fetch = vi.fn(async () => new Response("[]", { status: 200 })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Renter portal opened by someone with no tenancy", () => {
    beforeEach(() => { role = "SUPER_ADMIN"; });

    it("says so instead of reporting a load failure", async () => {
        renderPage();
        expect(await screen.findByText(en.MasterData.renterPortalNoTenancy)).toBeInTheDocument();
        expect(screen.queryByText(en.Common.loadFailed)).not.toBeInTheDocument();
    });

    it("does not call the renter-only endpoints at all", async () => {
        renderPage();
        await screen.findByText(en.MasterData.renterPortalNoTenancy);
        // Nothing to load for a user who has no tenancy — and the meetings call
        // is what produced the banner.
        expect(global.fetch).not.toHaveBeenCalled();
    });

    it("still loads normally for an actual renter", async () => {
        role = "RENTER";
        renderPage();
        await waitFor(() => expect(global.fetch).toHaveBeenCalled());
        expect(screen.queryByText(en.MasterData.renterPortalNoTenancy)).not.toBeInTheDocument();
    });
});
