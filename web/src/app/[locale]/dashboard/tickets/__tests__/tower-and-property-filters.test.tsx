import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * R1 review of PR #373:
 * - P2-2: a `buildingId` left in the URL applied with no way to clear it once
 *   the Tower select stopped showing (GET /buildings/property/{id} refuses
 *   ACCOUNTANT).
 * - P3-2: the property and tower filters live in the URL (a reload or a
 *   shared link keeps them), and a property option's label uses the locale
 *   name under AR.
 */

let locale = "en";
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock("next-intl", async () => {
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const messages = (await import("../../../../../../messages/en.json")).default;
    const cache = new Map<string, ReturnType<typeof createTranslator>>();
    return {
        useTranslations: (namespace: string) => {
            const key = `${locale}:${namespace}`;
            if (!cache.has(key)) cache.set(key, createTranslator({ locale, messages, namespace: namespace as never }));
            return cache.get(key)!;
        },
        useLocale: () => locale,
    };
});
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import TicketsPage from "../page";

const pagedBody = (content: unknown[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 25 });

beforeEach(() => {
    locale = "en";
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

describe("Tickets list — P3-2: property + tower filters are URL-persisted", () => {
    beforeEach(() => {
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.endsWith("/v1/properties")) return { ok: true, json: async () => [{ property: { id: "p1", nameEn: "Sweep House", nameAr: "بيت المسح" } }] } as unknown as Response;
            if (u.includes("/v1/buildings/property/")) return { ok: true, json: async () => [] } as unknown as Response;
            if (u.includes("/v1/tickets/paged")) return { ok: true, status: 200, json: async () => pagedBody([]) } as unknown as Response;
            return { ok: true, json: async () => [] } as unknown as Response;
        }) as unknown as typeof fetch;
    });

    it("keeps a deep-linked propertyId across a reload (a fresh mount)", async () => {
        window.history.replaceState(null, "", "/en/dashboard/tickets?propertyId=p1");
        const { unmount } = render(<TicketsPage />);
        const select = await screen.findByTestId("ticket-property-filter") as HTMLSelectElement;
        await waitFor(() => expect(select.value).toBe("p1"));
        unmount();

        // A fresh mount (what a reload gives) reads the same URL straight back.
        render(<TicketsPage />);
        const select2 = await screen.findByTestId("ticket-property-filter") as HTMLSelectElement;
        await waitFor(() => expect(select2.value).toBe("p1"));
    });

    it("writes propertyId/buildingId to the URL when the selects change, and clears buildingId when the property changes", async () => {
        render(<TicketsPage />);
        const select = await screen.findByTestId("ticket-property-filter");
        fireEvent.change(select, { target: { value: "p1" } });
        await waitFor(() => expect(new URL(window.location.href).searchParams.get("propertyId")).toBe("p1"));

        fireEvent.change(select, { target: { value: "" } });
        await waitFor(() => expect(new URL(window.location.href).searchParams.get("propertyId")).toBeNull());
        expect(new URL(window.location.href).searchParams.get("buildingId")).toBeNull();
    });

    it("shows a property's Arabic name under the Arabic locale", async () => {
        locale = "ar";
        render(<TicketsPage />);
        const select = await screen.findByTestId("ticket-property-filter");
        expect(select).toHaveTextContent("بيت المسح");
        expect(select).not.toHaveTextContent("Sweep House");
    });
});

describe("Tickets list — P2-2: no invisible filter with no way to clear", () => {
    it("drops a buildingId from the URL once GET /buildings/property/{id} refuses this role (403)", async () => {
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.endsWith("/v1/properties")) return { ok: true, json: async () => [{ property: { id: "p1", nameEn: "Sweep House" } }] } as unknown as Response;
            if (u.includes("/v1/buildings/property/")) return { ok: false, status: 403, json: async () => ({}) } as unknown as Response;
            if (u.includes("/v1/tickets/paged")) return { ok: true, status: 200, json: async () => pagedBody([]) } as unknown as Response;
            return { ok: true, json: async () => [] } as unknown as Response;
        }) as unknown as typeof fetch;
        window.history.replaceState(null, "", "/en/dashboard/tickets?propertyId=p1&buildingId=bA");

        render(<TicketsPage />);
        await screen.findByTestId("ticket-property-filter");
        await waitFor(() => expect(new URL(window.location.href).searchParams.get("buildingId")).toBeNull());
        expect(screen.queryByTestId("ticket-building-filter")).toBeNull();
        expect(new URL(window.location.href).searchParams.get("propertyId")).toBe("p1");
    });
});
