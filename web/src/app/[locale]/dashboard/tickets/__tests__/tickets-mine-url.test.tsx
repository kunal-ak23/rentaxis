import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * Scale #22: a maintenance-team TENANT_USER's "My tickets" toggle lives in the
 * URL (`?mine=1`) like the list's other filters, so a reload or a shared link
 * keeps the worklist; switching it goes back to page 1.
 */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_USER", id: "me-1" } } }) }));
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
const ticket = (id: string, title: string, assignedTo: string | null) => ({
    id, reference: `TKT-26/${id}`, title, description: "", status: "OPEN", priority: "MEDIUM", category: "OTHER",
    propertyId: "p1", propertyName: "Tower A", unitId: "u1", unitNumber: "101", assignedTo,
    reporterName: "Someone", assigneeName: null, onBehalfOf: null,
    createdAt: "2026-09-01T08:00:00Z", updatedAt: "2026-09-01T08:00:00Z",
});

beforeEach(() => {
    lookup.searchUnits.mockResolvedValue([]);
    lookup.unitNames.mockResolvedValue({ rows: [], failedIds: [] });
    lookup.searchRenters.mockResolvedValue([]);
    lookup.renterNames.mockResolvedValue({ rows: [], failedIds: [] });
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.endsWith("/v1/tickets")) return json([ticket("1", "Mine to fix", "me-1"), ticket("2", "Someone else's", "other")]);
        return json([]);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

const renderPage = () =>
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <TicketsPage />
        </NextIntlClientProvider>,
    );
const param = (k: string) => new URL(window.location.href).searchParams.get(k);

describe("Tickets list: My tickets in the URL", () => {
    it("restores ?mine=1 on load and shows only the user's tickets", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?mine=1");
        renderPage();
        await screen.findByText("Mine to fix");
        expect(screen.queryByText("Someone else's")).not.toBeInTheDocument();

        fireEvent.click(screen.getByTestId("ticket-filter-all"));
        await screen.findByText("Someone else's");
        expect(param("mine")).toBeNull();
    });

    it("writes ?mine=1 and goes back to page 1 when switched on", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?page=2&status=OPEN");
        renderPage();
        await waitFor(() => expect(screen.getByTestId("ticket-filter-mine")).toBeInTheDocument());

        fireEvent.click(screen.getByTestId("ticket-filter-mine"));
        await screen.findByText("Mine to fix");
        expect(screen.queryByText("Someone else's")).not.toBeInTheDocument();
        expect(param("mine")).toBe("1");
        expect(param("page")).toBeNull();
        expect(param("status")).toBe("OPEN");
    });

    it("ignores a malformed ?mine= value", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?mine=yes");
        renderPage();
        await screen.findByText("Someone else's");
        expect(screen.getByText("Mine to fix")).toBeInTheDocument();
    });
});
