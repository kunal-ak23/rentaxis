import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * Scale PR B2 final-review fix (task 2): a renter (or TENANT_USER) is not
 * admitted to `/tickets/paged` — their list is the plain `GET /tickets`,
 * filtered and paged in the browser (`canPage` false). The server-paged
 * (staff) path already clamps a bookmarked out-of-range page — see
 * `tickets-url-filters.test.tsx` — but this client-side path had no
 * equivalent guard, so `?page=9` with only a few tickets rendered a blank
 * table instead of falling back to page 1.
 */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "RENTER", id: "renter-1" } } }) }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

const lookup = vi.hoisted(() => ({ searchUnits: vi.fn(), searchRenters: vi.fn(), unitNames: vi.fn(), renterNames: vi.fn() }));
vi.mock("@/lib/api/lookup", () => ({ lookupApi: lookup }));

import TicketsPage from "../page";

const json = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as unknown as Response;
const ticket = (id: string, title: string) => ({
    id, reference: `TKT-26/${id}`, title, description: "", status: "OPEN", priority: "MEDIUM", category: "OTHER",
    propertyId: "p1", propertyName: "Tower A", unitId: "u1", unitNumber: "101",
    reporterName: "Someone", assigneeName: null, onBehalfOf: null,
    createdAt: "2026-09-01T08:00:00Z", updatedAt: "2026-09-01T08:00:00Z",
});

beforeEach(() => {
    lookup.searchUnits.mockResolvedValue([]);
    lookup.unitNames.mockResolvedValue({ rows: [], failedIds: [] });
    lookup.searchRenters.mockResolvedValue([]);
    lookup.renterNames.mockResolvedValue({ rows: [], failedIds: [] });
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <TicketsPage />
        </NextIntlClientProvider>,
    );
}

describe("Tickets list (client-paged, renter/TENANT_USER): out-of-range page clamp", () => {
    it("clamps a bookmarked ?page=9 to page 1 when there are only a few tickets, and shows their rows", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?page=9");
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.endsWith("/v1/tickets")) {
                return json([ticket("1", "Leaking tap"), ticket("2", "Broken AC"), ticket("3", "Noisy fridge")]);
            }
            if (u.endsWith("/v1/leases/my-leases")) return json([]);
            return json([]);
        }) as unknown as typeof fetch;

        renderPage();

        await screen.findByText("Leaking tap");
        expect(screen.getByText("Broken AC")).toBeInTheDocument();
        expect(screen.getByText("Noisy fridge")).toBeInTheDocument();

        // useUrlState drops the key from the URL when set back to its "1" fallback
        // rather than writing it out explicitly — so the clamp shows as the `page`
        // param disappearing, not becoming "1".
        await waitFor(() => expect(new URL(window.location.href).searchParams.get("page")).toBeNull());
    });
});
