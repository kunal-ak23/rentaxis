import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";

/**
 * `[id]` catches `/dashboard/leases/new` — a guessable URL and the one a user
 * bookmarking "new lease" would try. Before #47, that reached
 * `GET /leases/new` and rendered the backend's raw "Invalid value for
 * parameter 'id'". A lease id is always a UUID, so a non-UUID id must never
 * reach the API and must instead land on the page's existing not-found panel.
 */

let routeId = "new";

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: routeId }),
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/leases/LeaseInteractionsPanel", () => ({ default: () => null }));

const api = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        leaseApi: { ...m.leaseApi, get: api.get },
    };
});

import LeaseDetailPage from "../page";

function renderPage(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <LeaseDetailPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    routeId = "new";
    api.get.mockReset();
    api.get.mockImplementation(async () => {
        throw new Error("Invalid value for parameter 'id'");
    });
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(cleanup);

describe("A non-UUID lease id (#47)", () => {
    it("never calls the API for a non-UUID id like 'new'", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText(en.Leasing.notFound)).toBeInTheDocument());
        expect(api.get).not.toHaveBeenCalled();
    });

    it("shows the friendly not-found message, not the raw backend error", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByText(en.Leasing.notFound)).toBeInTheDocument());
        expect(screen.queryByText(/Invalid value for parameter/)).not.toBeInTheDocument();
    });

    it("links back to the leases list", async () => {
        renderPage();
        const link = await screen.findByText(en.Leasing.backToLeases);
        expect(link.closest("a")).toHaveAttribute("href", "/dashboard/leases");
    });

    it("shows the Arabic not-found message on the Arabic locale", async () => {
        renderPage("ar");
        await waitFor(() => expect(screen.getByText(ar.Leasing.notFound)).toBeInTheDocument());
    });

    it("still loads a real UUID lease id normally", async () => {
        routeId = "11111111-2222-3333-4444-555555555555";
        api.get.mockImplementation(async () => {
            throw new Error("not found upstream");
        });
        renderPage();
        await waitFor(() => expect(api.get).toHaveBeenCalledWith(routeId));
    });
});
