import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// R1 review of PR #373: the Tickets list lost focus in its search box on every
// keystroke (a full-page spinner replaced the whole page, search box and all,
// on every refetch), and on a hard load could show the whole organisation's
// tickets — unpaged, unfiltered — because the first fetch ran before the
// session had a role, and nothing stopped a slower, older response from
// overwriting a newer one.

let sessionState: { status: "loading" | "authenticated"; role?: string } = { status: "authenticated", role: "TENANT_ADMIN" };
vi.mock("next-auth/react", () => ({
    useSession: () => ({
        data: sessionState.status === "authenticated" ? { user: { role: sessionState.role, id: "me-1" } } : null,
        status: sessionState.status,
    }),
}));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock("next-intl", async () => {
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const messages = (await import("../../../../../../messages/en.json")).default;
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

import TicketsPage from "../page";

const ticket = (id: string, title: string) => ({
    id, reference: `TKT-26/${id}`, title, description: "", status: "OPEN", priority: "MEDIUM", category: "PLUMBING",
    propertyId: "p1", propertyName: "Tower", unitId: "u1", unitNumber: "101", reporterName: "A",
    assigneeName: null, onBehalfOf: null, createdAt: "2026-09-01T00:00:00Z", updatedAt: "2026-09-01T00:00:00Z",
});
const pagedBody = (content: unknown[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 25 });

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("Tickets list — P1-1: the search box keeps focus while typing", () => {
    beforeEach(() => {
        sessionState = { status: "authenticated", role: "TENANT_ADMIN" };
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) return { ok: true, status: 200, json: async () => pagedBody([ticket("1", "Leaking tap")]) } as unknown as Response;
            return { ok: true, status: 200, json: async () => [] } as unknown as Response;
        }) as unknown as typeof fetch;
    });

    it("never unmounts the search input across a refetch, so a keystroke never has to be re-clicked", async () => {
        render(<TicketsPage />);
        const input = await screen.findByPlaceholderText("Search tickets...") as HTMLInputElement;
        input.focus();
        expect(document.activeElement).toBe(input);

        // Three keystrokes, one at a time, each landing inside the 350 ms debounce
        // window of the one before it — the old code re-fetched (and re-rendered
        // the whole page under a full-page spinner) on every single keystroke.
        for (const ch of ["T", "K", "T"]) {
            fireEvent.change(input, { target: { value: input.value + ch } });
            await act(async () => { await new Promise(r => setTimeout(r, 50)); });
            expect(document.activeElement, `focus after typing "${ch}"`).toBe(input);
        }

        // Let the debounced fetch actually land; the box (same node) still has focus.
        await act(async () => { await new Promise(r => setTimeout(r, 400)); });
        expect(document.activeElement).toBe(input);
        expect(screen.getByPlaceholderText("Search tickets...")).toBe(input);
    });
});

describe("Tickets list — P1-2: no org-wide read, and no stale response wins", () => {
    it("fetches nothing before the session has a role, and reads the paged endpoint once it does", async () => {
        sessionState = { status: "loading" };
        const calls: string[] = [];
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            calls.push(u);
            if (u.includes("/v1/tickets/paged")) return { ok: true, status: 200, json: async () => pagedBody([ticket("1", "Leaking tap")]) } as unknown as Response;
            if (u.endsWith("/v1/tickets")) return { ok: true, status: 200, json: async () => [ticket("1", "Leaking tap"), ticket("2", "Org-wide leak")] } as unknown as Response;
            return { ok: true, status: 200, json: async () => [] } as unknown as Response;
        }) as unknown as typeof fetch;

        const { rerender } = render(<TicketsPage />);
        await act(async () => { await new Promise(r => setTimeout(r, 20)); });
        expect(calls.some(u => u.includes("/tickets"))).toBe(false);

        sessionState = { status: "authenticated", role: "TENANT_ADMIN" };
        rerender(<TicketsPage />);

        await screen.findByText("Leaking tap");
        expect(calls.some(u => u.endsWith("/v1/tickets") && !u.includes("/paged"))).toBe(false);
        expect(calls.some(u => u.includes("/v1/tickets/paged"))).toBe(true);
    });

    it("never lets an older, slower response overwrite a newer one", async () => {
        sessionState = { status: "authenticated", role: "TENANT_ADMIN" };
        // The initial (unfiltered) read resolves at once, so the page renders.
        // The first filter change (status=OPEN) then hangs; the second
        // (status=RESOLVED, fired right after) resolves immediately. Without a
        // request guard, the slow OPEN response lands after RESOLVED's and
        // overwrites it — showing the wrong page for the filter now on screen.
        let resolveSlow: (v: unknown) => void = () => {};
        const slow = new Promise(r => { resolveSlow = r; });
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) {
                if (u.includes("status=RESOLVED")) return { ok: true, status: 200, json: async () => pagedBody([ticket("2", "Newest filter")]) } as unknown as Response;
                if (u.includes("status=OPEN")) {
                    await slow;
                    return { ok: true, status: 200, json: async () => pagedBody([ticket("3", "Stale filter")]) } as unknown as Response;
                }
                return { ok: true, status: 200, json: async () => pagedBody([ticket("1", "Unfiltered")]) } as unknown as Response;
            }
            return { ok: true, status: 200, json: async () => [] } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<TicketsPage />);
        await screen.findByText("Unfiltered");

        const statusSelect = screen.getByDisplayValue("All Statuses");
        fireEvent.change(statusSelect, { target: { value: "OPEN" } });
        fireEvent.change(statusSelect, { target: { value: "RESOLVED" } });
        await screen.findByText("Newest filter");

        // Now let the slow, stale OPEN response land — it must not replace
        // the RESOLVED result already on screen.
        resolveSlow(undefined);
        await act(async () => { await new Promise(r => setTimeout(r, 20)); });
        expect(screen.getByText("Newest filter")).toBeTruthy();
        expect(screen.queryByText("Stale filter")).toBeNull();
    });
});

describe("Tickets list — P3-3: a SUPER_ADMIN with no organisation sees why the list is empty", () => {
    it("surfaces the backend's message instead of a bare 'No tickets'", async () => {
        sessionState = { status: "authenticated", role: "SUPER_ADMIN" };
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/tickets/paged")) {
                return { ok: false, status: 400, json: async () => ({ message: "Select an organisation first" }) } as unknown as Response;
            }
            return { ok: true, status: 200, json: async () => [] } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<TicketsPage />);
        await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Select an organisation first"));
    });
});
