import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

/**
 * R1 review of PR #373 (P2-2): a `buildingId` left in the URL applied with no
 * way to clear it once the Tower select stopped showing (GET
 * /buildings/property/{id} refuses ACCOUNTANT).
 */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("../LeaseWizard", () => ({ default: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => <div data-testid="pagination" /> }));
vi.mock("@/lib/businessDate", () => ({ businessTodayIso: () => "2026-09-25" }));

const api = vi.hoisted(() => ({ paged: vi.fn(async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 })) }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, paged: api.paged } };
});
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ options: [{ id: "11111111-1111-4111-8111-111111111111", en: "Sweep House", ar: "Sweep House" }], name: () => "Sweep House", loading: false }),
}));

import LeasesPage from "../page";

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeasesPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/v1/buildings/property/")) return { ok: false, status: 403, json: async () => ({}) } as unknown as Response;
        return { ok: true, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

describe("Contracts list tower filter — P2-2: no invisible filter with no way to clear", () => {
    it("drops a buildingId from the URL once GET /buildings/property/{id} refuses this role (403)", async () => {
        window.history.replaceState(null, "", "/en/dashboard/leases?propertyId=11111111-1111-4111-8111-111111111111&buildingId=baaaaaaa-0000-4000-8000-00000000000a");
        await act(async () => { renderPage(); });

        await waitFor(() => expect(new URL(window.location.href).searchParams.get("buildingId")).toBeNull());
        expect(screen.queryByTestId("lease-building-filter")).toBeNull();
        // The property filter itself is untouched — only the invisible tower
        // filter (a control the caller can no longer see) is dropped.
        expect(new URL(window.location.href).searchParams.get("propertyId")).toBe("11111111-1111-4111-8111-111111111111");
    });

    it("shows a removable chip next to the select while a tower is active", async () => {
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/buildings/property/")) return { ok: true, json: async () => [{ id: "baaaaaaa-0000-4000-8000-00000000000a", nameEn: "Tower A" }] } as unknown as Response;
            return { ok: true, json: async () => [] } as unknown as Response;
        }) as unknown as typeof fetch;
        window.history.replaceState(null, "", "/en/dashboard/leases?propertyId=11111111-1111-4111-8111-111111111111&buildingId=baaaaaaa-0000-4000-8000-00000000000a");
        await act(async () => { renderPage(); });

        const chip = await screen.findByTestId("lease-building-filter-chip");
        expect(chip).toHaveTextContent("Tower A");
        fireEvent.click(screen.getByTestId("lease-building-filter-chip-remove"));
        await waitFor(() => expect(new URL(window.location.href).searchParams.get("buildingId")).toBeNull());
    });
});
