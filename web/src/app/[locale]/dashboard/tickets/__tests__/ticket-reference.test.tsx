import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// #20: the tickets list shows the human reference ("TKT-26/14") instead of a
// UUID prefix, and the search box finds a ticket by it.

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key, useLocale: () => "en" }));
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

let posted: Record<string, unknown>[];

beforeEach(() => {
    posted = [];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.endsWith("/v1/tickets") && init?.method === "POST") {
            posted.push(JSON.parse(String(init.body)));
            return { ok: true, status: 200, json: async () => ({ id: "new" }) } as unknown as Response;
        }
        if (u.endsWith("/v1/renters")) {
            return { ok: true, status: 200, json: async () => [
                { id: "ren-1", nameEn: "Rajesh Kumar", nameAr: null, phone: "+971501234567" },
            ] } as unknown as Response;
        }
        if (u.endsWith("/v1/properties")) {
            return { ok: true, status: 200, json: async () => [{ property: { id: "p1", nameEn: "Tower" } }] } as unknown as Response;
        }
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

        const ref = await screen.findByText("TKT-26/14");
        expect(ref).toBeTruthy();
        // Web review I4: the reference is isolated LTR; its cell keeps the page
        // direction so it lines up with the header in Arabic.
        expect(ref.tagName).toBe("BDI");
        expect(ref.getAttribute("dir")).toBe("ltr");
        expect(ref.closest("td")?.hasAttribute("dir")).toBe(false);
        expect(screen.getByText("cccccccc")).toBeTruthy();
    });

    it("finds a ticket by its reference", async () => {
        render(<TicketsPage />);
        await screen.findByText("TKT-26/14");

        fireEvent.change(screen.getByPlaceholderText("Search tickets..."), { target: { value: "26/15" } });

        expect(screen.getByText("Lift stuck")).toBeTruthy();
        expect(screen.queryByText("Leaking tap")).toBeNull();
    });

    // #19: "on behalf of" is a renter picked from the org's renters, sent by id.
    it("logs a ticket on behalf of a picked renter, by id", async () => {
        render(<TicketsPage />);
        await screen.findByText("TKT-26/14");

        fireEvent.click(screen.getByText("Create Ticket"));
        fireEvent.change(screen.getByPlaceholderText("Brief summary of the issue"), { target: { value: "Noise" } });
        fireEvent.change(screen.getByDisplayValue("Select property"), { target: { value: "p1" } });
        const picker = await screen.findByLabelText("onBehalfOfRenter");
        await screen.findByText(/Rajesh Kumar/);
        fireEvent.change(picker, { target: { value: "ren-1" } });
        // The header button and the form's submit share the label; the submit is last.
        const buttons = screen.getAllByRole("button", { name: /Create Ticket/ });
        fireEvent.click(buttons[buttons.length - 1]);

        await waitFor(() => expect(posted).toHaveLength(1));
        expect(posted[0]).toMatchObject({ onBehalfOfRenterId: "ren-1" });
        expect(posted[0]).not.toHaveProperty("onBehalfOf");
    });
});
