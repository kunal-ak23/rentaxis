import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * Task 6 (Scale PR B2): the Renters list reads the server-paged, server-searched
 * `GET /renters/paged?q=&page=&size=` instead of the unbounded `GET /renters`,
 * with search/page/size persisted in the URL (1-based in the URL, 0-based to
 * the API) via the new shared `useUrlState` hook — same shape as the Tickets
 * and Contracts lists' URL-persisted filters, including a `fetchSeq` guard so
 * a slow, older response can never overwrite a newer one.
 */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
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

import RentersPage from "../page";

type RenterRow = { id: string; nameEn: string; nameAr: string; email: string; phone: string; primaryLanguage: string };
const renter = (id: string, nameEn: string): RenterRow => ({
    id, nameEn, nameAr: "", email: `${id}@example.com`, phone: "+971500000000", primaryLanguage: "EN",
});
const pagedBody = (content: unknown[], overrides: Partial<{ totalElements: number; totalPages: number; number: number; size: number }> = {}) => ({
    content,
    totalElements: overrides.totalElements ?? content.length,
    totalPages: overrides.totalPages ?? 1,
    number: overrides.number ?? 0,
    size: overrides.size ?? 25,
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

describe("Renters list — server-paged with URL-persisted search/page/size", () => {
    it("never calls the unbounded GET /v1/renters", async () => {
        const calls: string[] = [];
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            calls.push(u);
            if (u.includes("/v1/renters/paged")) return { ok: true, status: 200, json: async () => pagedBody([renter("r1", "Ahmed")]) } as unknown as Response;
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);
        await screen.findByText("Ahmed");

        const unboundedGet = calls.some((u) => /\/v1\/renters(\?|$)/.test(u));
        expect(unboundedGet).toBe(false);
        expect(calls.some((u) => u.includes("/v1/renters/paged"))).toBe(true);
    });

    it("restores q, page and size from the URL on first render and requests page-1/size accordingly", async () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?q=sara&page=3&size=10");
        let requested: URLSearchParams | null = null;
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/renters/paged")) {
                requested = new URL(u, "http://x").searchParams;
                return { ok: true, status: 200, json: async () => pagedBody([renter("r2", "Sara Ali")], { totalElements: 21, size: 10, number: 2 }) } as unknown as Response;
            }
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);
        await screen.findByText("Sara Ali");

        expect(requested).not.toBeNull();
        expect(requested!.get("q")).toBe("sara");
        // URL page is 1-based (page=3); the API is 0-based.
        expect(requested!.get("page")).toBe("2");
        expect(requested!.get("size")).toBe("10");

        const searchBox = screen.getByPlaceholderText("Search...") as HTMLInputElement;
        expect(searchBox.value).toBe("sara");
    });

    it("typing updates ?q= after a debounce and resets the page to 1", async () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?page=4");
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/renters/paged")) return { ok: true, status: 200, json: async () => pagedBody([renter("r1", "Ahmed")]) } as unknown as Response;
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);
        const searchBox = await screen.findByPlaceholderText("Search...");
        fireEvent.change(searchBox, { target: { value: "ahmed" } });

        // Before the debounce fires, the URL hasn't moved yet.
        expect(new URL(window.location.href).searchParams.get("q")).toBeNull();

        await act(async () => { await new Promise((r) => setTimeout(r, 400)); });

        expect(new URL(window.location.href).searchParams.get("q")).toBe("ahmed");
        expect(new URL(window.location.href).searchParams.get("page")).toBeNull(); // reset to the 1-based default, so removed
    });

    it("typing whitespace-only text leaves no q in the URL, and a trailing space is stripped", async () => {
        let requested: URLSearchParams | null = null;
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/renters/paged")) {
                requested = new URL(u, "http://x").searchParams;
                return { ok: true, status: 200, json: async () => pagedBody([renter("r1", "Ahmed")]) } as unknown as Response;
            }
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);
        const searchBox = await screen.findByPlaceholderText("Search...");

        fireEvent.change(searchBox, { target: { value: "   " } });
        await act(async () => { await new Promise((r) => setTimeout(r, 350)); });
        expect(new URL(window.location.href).searchParams.has("q")).toBe(false);
        expect(requested!.has("q")).toBe(false);

        fireEvent.change(searchBox, { target: { value: "ahmed " } });
        await act(async () => { await new Promise((r) => setTimeout(r, 350)); });
        expect(new URL(window.location.href).searchParams.get("q")).toBe("ahmed");
        expect(requested!.get("q")).toBe("ahmed");
    });

    it("clamps a negative bookmarked page to page 1 (API page 0)", async () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?page=-3");
        let requested: URLSearchParams | null = null;
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/renters/paged")) {
                requested = new URL(u, "http://x").searchParams;
                return { ok: true, status: 200, json: async () => pagedBody([renter("r1", "Ahmed")]) } as unknown as Response;
            }
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);
        await screen.findByText("Ahmed");

        expect(requested!.get("page")).toBe("0");
    });

    it("never lets an older, slower response overwrite a newer one", async () => {
        let resolveSlow: (v: unknown) => void = () => {};
        const slow = new Promise((r) => { resolveSlow = r; });
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/renters/paged")) {
                if (u.includes("q=new")) return { ok: true, status: 200, json: async () => pagedBody([renter("new", "Newest Result")]) } as unknown as Response;
                if (u.includes("q=old")) {
                    await slow;
                    return { ok: true, status: 200, json: async () => pagedBody([renter("old", "Stale Result")]) } as unknown as Response;
                }
                return { ok: true, status: 200, json: async () => pagedBody([renter("r0", "Unfiltered")]) } as unknown as Response;
            }
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);
        await screen.findByText("Unfiltered");

        const searchBox = screen.getByPlaceholderText("Search...");
        fireEvent.change(searchBox, { target: { value: "old" } });
        await act(async () => { await new Promise((r) => setTimeout(r, 320)); });
        fireEvent.change(searchBox, { target: { value: "new" } });
        await act(async () => { await new Promise((r) => setTimeout(r, 320)); });

        await screen.findByText("Newest Result");
        resolveSlow(undefined);
        await act(async () => { await new Promise((r) => setTimeout(r, 20)); });

        expect(screen.getByText("Newest Result")).toBeTruthy();
        expect(screen.queryByText("Stale Result")).toBeNull();
    });

    it("changing the page requests the right 0-based page and keeps size in the URL", async () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?size=10");
        const rows = Array.from({ length: 10 }, (_, i) => renter(`r${i}`, `Renter ${i}`));
        let lastPageParam: string | null = null;
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/renters/paged")) {
                const params = new URL(u, "http://x").searchParams;
                lastPageParam = params.get("page");
                return { ok: true, status: 200, json: async () => pagedBody(rows, { totalElements: 25, size: 10 }) } as unknown as Response;
            }
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);
        await screen.findByText("Renter 0");
        expect(lastPageParam).toBe("0");

        const nextButtons = screen.getAllByRole("button").filter((b) => b.querySelector("svg"));
        // Pagination's "next" chevron button is the last of the page-nav buttons.
        fireEvent.click(nextButtons[nextButtons.length - 1]);

        await waitFor(() => expect(lastPageParam).toBe("1"));
        expect(new URL(window.location.href).searchParams.get("page")).toBe("2");
        expect(new URL(window.location.href).searchParams.get("size")).toBe("10");
    });
});

/**
 * Fix round 1 (Task 6 review): `renters` now holds only the current search's
 * server page, so gating the "you haven't added any renters yet — Add Tenant"
 * first-run CTA on `renters.length` told an admin searching for a renter who
 * exists that none do, and offered to create a duplicate. The CTA must only
 * show for a true empty tenant list (no search, server total 0); a search
 * with no matches gets its own message and never offers to create one.
 */
describe("Renters list — a search with no matches is not the same as no renters at all", () => {
    it("shows a 'no matches' message (not the first-run Add Tenant CTA) when a search returns nothing", async () => {
        window.history.replaceState(null, "", "/en/dashboard/renters?q=zzz-nomatch");
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/renters/paged")) return { ok: true, status: 200, json: async () => pagedBody([], { totalElements: 0 }) } as unknown as Response;
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);

        await screen.findByTestId("renters-no-search-results");
        expect(screen.queryByText("No Tenants Found")).toBeNull();
        // The header's own "Add Tenant" button is fine; the first-run empty
        // state's second one must not also render.
        expect(screen.getAllByText("Add Tenant")).toHaveLength(1);
    });

    it("shows the first-run 'Add Tenant' CTA when there is no search and the tenant list is truly empty", async () => {
        window.history.replaceState(null, "", "/en/dashboard/renters");
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/renters/paged")) return { ok: true, status: 200, json: async () => pagedBody([], { totalElements: 0 }) } as unknown as Response;
            return { ok: true, status: 200, json: async () => ({}) } as unknown as Response;
        }) as unknown as typeof fetch;

        render(<RentersPage />);

        await screen.findByText("No Tenants Found");
        expect(screen.queryByTestId("renters-no-search-results")).toBeNull();
        expect(screen.getAllByText("Add Tenant")).toHaveLength(2); // header + CTA
    });
});
