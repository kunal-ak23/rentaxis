import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import en from "../../../../../../messages/en.json";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// #20: the tickets list shows the human reference ("TKT-26/14") instead of a
// UUID prefix, and the search box finds a ticket by it.

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("next-intl", async () => {
    // The real English catalog, so labels, plurals and enum names render as
    // a user sees them.
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const messages = (await import("../../../../../../messages/en.json")).default;
    // One translator per namespace, stable across renders like the real hook.
    const cache = new Map<string, ReturnType<typeof createTranslator>>();
    return {
        useTranslations: (namespace: string) => {
            if (!cache.has(namespace)) cache.set(namespace, createTranslator({ locale: "en", messages, namespace: namespace as never }));
            return cache.get(namespace)!;
        },
        useLocale: () => "en",
    };
});
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

// Scale PR B, task 5: "on behalf of" is now the server-searched `RenterPicker`
// (and the unit field the server-searched `UnitPicker`) instead of a page-wide
// `/renters`/`/units` list, so `lookupApi` is mocked directly.
const lookup = vi.hoisted(() => ({ searchUnits: vi.fn(), searchRenters: vi.fn(), unitNames: vi.fn(), renterNames: vi.fn() }));
vi.mock("@/lib/api/lookup", () => ({ lookupApi: lookup }));

import TicketsPage from "../page";

const ticket = (id: string, reference: string | null, title: string) => ({
    id, reference, title, description: "", status: "OPEN", priority: "MEDIUM", category: "PLUMBING",
    propertyId: "p1", propertyName: "Tower", unitId: "u1", unitNumber: "101", reporterName: "A",
    assigneeName: null, onBehalfOf: null, createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-09-01T00:00:00Z",
});

let posted: Record<string, unknown>[];

beforeEach(() => {
    posted = [];
    lookup.searchUnits.mockResolvedValue([]);
    lookup.unitNames.mockResolvedValue([]);
    lookup.searchRenters.mockResolvedValue([
        { id: "ren-1", nameEn: "Rajesh Kumar", nameAr: null, phone: "+971501234567", email: null },
    ]);
    lookup.renterNames.mockResolvedValue([]);
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.endsWith("/v1/tickets") && init?.method === "POST") {
            posted.push(JSON.parse(String(init.body)));
            return { ok: true, status: 200, json: async () => ({ id: "new" }) } as unknown as Response;
        }
        if (u.endsWith("/v1/properties")) {
            return { ok: true, status: 200, json: async () => [{ property: { id: "p1", nameEn: "Tower" } }] } as unknown as Response;
        }
        const all = [ticket("aaaaaaaa-1111", "TKT-26/14", "Leaking tap"), ticket("bbbbbbbb-2222", "TKT-26/15", "Lift stuck"),
            ticket("cccccccc-3333", null, "Legacy row")];
        // TENANT_ADMIN (this test's role) reads the server-paged list
        // (S16-02): the `q` param narrows by title/reference, same as before.
        if (u.includes("/v1/tickets/paged")) {
            const q = new URL(u, "http://x").searchParams.get("q")?.toLowerCase() ?? "";
            const content = q
                ? all.filter(t => t.title.toLowerCase().includes(q) || (t.reference ?? "").toLowerCase().includes(q))
                : all;
            return {
                ok: true, status: 200,
                json: async () => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 25 }),
            } as unknown as Response;
        }
        const body = u.endsWith("/v1/tickets") ? all : [];
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

        // "Lift stuck" is already on screen from the unfiltered first read, so
        // wait for the debounced, filtered fetch by the row that must go away.
        await waitFor(() => expect(screen.queryByText("Leaking tap")).toBeNull());
        expect(screen.getByText("Lift stuck")).toBeTruthy();
    });

    // #19: "on behalf of" is a renter picked from the org's renters, sent by id.
    it("logs a ticket on behalf of a picked renter, by id", async () => {
        render(<TicketsPage />);
        await screen.findByText("TKT-26/14");

        fireEvent.click(screen.getByText("Create Ticket"));
        fireEvent.change(screen.getByPlaceholderText("Brief summary of the issue"), { target: { value: "Noise" } });
        fireEvent.change(screen.getByDisplayValue("Select property"), { target: { value: "p1" } });
        const picker = await screen.findByLabelText(en.Tickets.onBehalfOfRenter);
        fireEvent.click(picker);
        fireEvent.click(await screen.findByText(/Rajesh Kumar/));
        // The header button and the form's submit share the label; the submit is last.
        const buttons = screen.getAllByRole("button", { name: /Create Ticket/ });
        fireEvent.click(buttons[buttons.length - 1]);

        await waitFor(() => expect(posted).toHaveLength(1));
        expect(posted[0]).toMatchObject({ onBehalfOfRenterId: "ren-1" });
        expect(posted[0]).not.toHaveProperty("onBehalfOf");
    });
});
