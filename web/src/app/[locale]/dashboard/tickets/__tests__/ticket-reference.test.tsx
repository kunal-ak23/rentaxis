import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// #20: the tickets list shows the human reference ("TKT-26/14") instead of a
// UUID prefix, and the search box finds a ticket by it.

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import TicketsPage from "../page";

const ticket = (id: string, reference: string | null, title: string) => ({
    id, reference, title, description: "", status: "OPEN", priority: "MEDIUM", category: "PLUMBING",
    propertyId: "p1", propertyName: "Tower", unitId: "u1", unitNumber: "101", reporterName: "A",
    assigneeName: null, onBehalfOf: null, createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-09-01T00:00:00Z",
});

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        const body = u.endsWith("/v1/tickets")
            ? [ticket("aaaaaaaa-1111", "TKT-26/14", "Leaking tap"), ticket("bbbbbbbb-2222", "TKT-26/15", "Lift stuck"),
               ticket("cccccccc-3333", null, "Legacy row")]
            : [];
        return { ok: true, status: 200, json: async () => body } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("Tickets list — reference", () => {
    it("shows the reference, falling back to the id prefix for a row without one", async () => {
        render(<TicketsPage />);

        expect(await screen.findByText("TKT-26/14")).toBeTruthy();
        expect(screen.getByText("cccccccc")).toBeTruthy();
    });

    it("finds a ticket by its reference", async () => {
        render(<TicketsPage />);
        await screen.findByText("TKT-26/14");

        fireEvent.change(screen.getByPlaceholderText("Search tickets..."), { target: { value: "26/15" } });

        expect(screen.getByText("Lift stuck")).toBeTruthy();
        expect(screen.queryByText("Leaking tap")).toBeNull();
    });
});
