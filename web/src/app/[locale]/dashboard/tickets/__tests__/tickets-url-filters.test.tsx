import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * Scale PR B2, task 7: the Tickets list's search, status, priority and page
 * now live in the URL via the shared `useUrlState` hook (propertyId/buildingId
 * already did, from an earlier task) — a reload with
 * `?status=OPEN&priority=HIGH&q=leak&page=2` must restore all four and drive
 * `/tickets/paged` accordingly. Changing any filter resets the page to 1.
 *
 * Also covers the controller ruling: a bookmarked page beyond the last page
 * for the current query (server `totalElements > 0`, requested page comes
 * back empty) clamps to the last page instead of rendering a blank table.
 */

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
const pagedBody = (content: unknown[], overrides: Partial<{ totalElements: number; totalPages: number; number: number; size: number }> = {}) => ({
    content,
    totalElements: overrides.totalElements ?? content.length,
    totalPages: overrides.totalPages ?? 1,
    number: overrides.number ?? 0,
    size: overrides.size ?? 25,
});
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

describe("Tickets list — URL-persisted search/status/priority/page", () => {
    it("restores q, status, priority and page from the URL on reload and requests them from /tickets/paged", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?status=OPEN&priority=HIGH&q=leak&page=2");
        let requested: URLSearchParams | null = null;
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) {
                requested = new URL(u, "http://x").searchParams;
                return json(pagedBody([ticket("1", "Leaking tap")], { totalElements: 1 }));
            }
            if (u.endsWith("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Tower A" } }]);
            return json([]);
        }) as unknown as typeof fetch;

        renderPage();
        await screen.findByText("Leaking tap");

        expect(requested).not.toBeNull();
        expect(requested!.get("q")).toBe("leak");
        expect(requested!.get("status")).toBe("OPEN");
        expect(requested!.get("priority")).toBe("HIGH");
        // URL page is 1-based (page=2); the API is 0-based.
        expect(requested!.get("page")).toBe("1");

        const searchBox = screen.getByPlaceholderText(en.Tickets.searchPlaceholder) as HTMLInputElement;
        expect(searchBox.value).toBe("leak");
        expect((screen.getByDisplayValue(en.Tickets.status.OPEN) as HTMLSelectElement).value).toBe("OPEN");
        expect((screen.getByDisplayValue(en.Tickets.priority.HIGH) as HTMLSelectElement).value).toBe("HIGH");
    });

    it("changing the status filter resets the page to 1", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?page=3");
        let lastPageParam: string | null = null;
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) {
                lastPageParam = new URL(u, "http://x").searchParams.get("page");
                return json(pagedBody([ticket("1", "A ticket")]));
            }
            if (u.endsWith("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Tower A" } }]);
            return json([]);
        }) as unknown as typeof fetch;

        renderPage();
        await screen.findByText("A ticket");
        expect(lastPageParam).toBe("2"); // page=3, 0-based

        fireEvent.change(screen.getByDisplayValue(en.Tickets.allStatuses), { target: { value: "OPEN" } });

        await waitFor(() => expect(lastPageParam).toBe("0"));
        expect(new URL(window.location.href).searchParams.get("page")).toBeNull();
    });

    it("typing in the search box updates ?q= after a debounce and resets the page to 1", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?page=4");
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) return json(pagedBody([ticket("1", "A ticket")]));
            if (u.endsWith("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Tower A" } }]);
            return json([]);
        }) as unknown as typeof fetch;

        renderPage();
        const searchBox = await screen.findByPlaceholderText(en.Tickets.searchPlaceholder);
        fireEvent.change(searchBox, { target: { value: "leak" } });

        expect(new URL(window.location.href).searchParams.get("q")).toBeNull();

        await new Promise((r) => setTimeout(r, 400));

        await waitFor(() => expect(new URL(window.location.href).searchParams.get("q")).toBe("leak"));
        expect(new URL(window.location.href).searchParams.get("page")).toBeNull();
    });

    it("clamps to the last page when a bookmarked page is beyond the result set, and shows rows", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?page=9");
        const pageParams: string[] = [];
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) {
                const p = new URL(u, "http://x").searchParams.get("page")!;
                pageParams.push(p);
                if (p === "8") return json(pagedBody([], { totalElements: 30, size: 25 }));
                return json(pagedBody([ticket("1", "Leaking tap")], { totalElements: 30, size: 25 }));
            }
            if (u.endsWith("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Tower A" } }]);
            return json([]);
        }) as unknown as typeof fetch;

        renderPage();
        await screen.findByText("Leaking tap");

        expect(pageParams).toEqual(["8", "1"]);
        expect(new URL(window.location.href).searchParams.get("page")).toBe("2");
    });

    it("typing whitespace-only text leaves no q in the URL or the request", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets");
        let requested: URLSearchParams | null = null;
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) {
                requested = new URL(u, "http://x").searchParams;
                return json(pagedBody([ticket("1", "A ticket")]));
            }
            if (u.endsWith("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Tower A" } }]);
            return json([]);
        }) as unknown as typeof fetch;

        renderPage();
        const searchBox = await screen.findByPlaceholderText(en.Tickets.searchPlaceholder);
        fireEvent.change(searchBox, { target: { value: "   " } });

        await new Promise((r) => setTimeout(r, 400));

        expect(new URL(window.location.href).searchParams.has("q")).toBe(false);
        expect(requested!.has("q")).toBe(false);
    });

    it("a trailing space is stripped from the committed q", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets");
        let requested: URLSearchParams | null = null;
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) {
                requested = new URL(u, "http://x").searchParams;
                return json(pagedBody([ticket("1", "A ticket")]));
            }
            if (u.endsWith("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Tower A" } }]);
            return json([]);
        }) as unknown as typeof fetch;

        renderPage();
        const searchBox = await screen.findByPlaceholderText(en.Tickets.searchPlaceholder);
        fireEvent.change(searchBox, { target: { value: "leak " } });

        await new Promise((r) => setTimeout(r, 400));

        await waitFor(() => expect(new URL(window.location.href).searchParams.get("q")).toBe("leak"));
        expect(requested!.get("q")).toBe("leak");
    });

    it("clamps a negative bookmarked page to page 1 (API page 0)", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?page=-3");
        let requested: URLSearchParams | null = null;
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) {
                requested = new URL(u, "http://x").searchParams;
                return json(pagedBody([ticket("1", "A ticket")]));
            }
            if (u.endsWith("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Tower A" } }]);
            return json([]);
        }) as unknown as typeof fetch;

        renderPage();
        await screen.findByText("A ticket");

        expect(requested!.get("page")).toBe("0");
    });
});

// Break-it R3 data3 F6: an unknown status/priority in the URL used to reach the API as a 400
// ("Invalid value for parameter 'status'") that Retry could only repeat.
describe("Tickets list — unknown status/priority in the URL", () => {
    it("is no filter: the list loads without it and the URL drops it", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?status=NOPE&priority=HIGHEST&q=leak");
        const requested: URLSearchParams[] = [];
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) {
                requested.push(new URL(u, "http://x").searchParams);
                return json(pagedBody([ticket("1", "Leaking tap")], { totalElements: 1 }));
            }
            if (u.endsWith("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Tower A" } }]);
            return json([]);
        }) as unknown as typeof fetch;

        renderPage();
        await screen.findByText("Leaking tap");

        expect(requested.length).toBeGreaterThan(0);
        for (const sp of requested) {
            expect(sp.get("status")).toBeNull();
            expect(sp.get("priority")).toBeNull();
        }
        expect(requested[requested.length - 1].get("q")).toBe("leak");
        await waitFor(() => {
            const params = new URLSearchParams(window.location.search);
            expect(params.get("status")).toBeNull();
            expect(params.get("priority")).toBeNull();
            expect(params.get("q")).toBe("leak");
        });
    });
});
