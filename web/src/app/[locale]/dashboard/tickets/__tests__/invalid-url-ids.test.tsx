import { cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

// Break round 1 (nav): a malformed propertyId/buildingId in the URL must not
// reach /tickets/paged; it is treated as no filter and dropped from the URL.

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", id: "me-1" } } }) }));
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
let requested: URLSearchParams[];

beforeEach(() => {
    requested = [];
    lookup.searchUnits.mockResolvedValue([]);
    lookup.unitNames.mockResolvedValue({ rows: [], failedIds: [] });
    lookup.searchRenters.mockResolvedValue([]);
    lookup.renterNames.mockResolvedValue({ rows: [], failedIds: [] });
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/v1/tickets/paged")) {
            requested.push(new URL(u, "http://x").searchParams);
            return json({ content: [], totalElements: 0, totalPages: 1, number: 0, size: 25 });
        }
        return json([]);
    }) as unknown as typeof fetch;
});
afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

describe("tickets list with a malformed id in the URL", () => {
    it("never sends propertyId=not-a-uuid or a truncated buildingId, and cleans the URL", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?propertyId=not-a-uuid&buildingId=b394c93d-94d8-40e2-91b6-0740f&status=OPEN");
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <TicketsPage />
            </NextIntlClientProvider>,
        );
        await waitFor(() => expect(requested.length).toBeGreaterThan(0));
        for (const sp of requested) {
            expect(sp.has("propertyId")).toBe(false);
            expect(sp.has("buildingId")).toBe(false);
        }
        await waitFor(() => {
            const sp = new URLSearchParams(window.location.search);
            expect(sp.has("propertyId")).toBe(false);
            expect(sp.has("buildingId")).toBe(false);
        });
        expect(new URLSearchParams(window.location.search).get("status")).toBe("OPEN");
    });
});
