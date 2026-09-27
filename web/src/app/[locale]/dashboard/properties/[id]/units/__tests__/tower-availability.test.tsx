import { Suspense } from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";

/**
 * R1 review of PR #373 (P2-2): a `buildingId` left in the URL applied with no
 * way to clear it once the Tower select stopped showing — a shared/bookmarked
 * link with `?buildingId=`, opened by a role `GET /buildings/property/{id}`
 * refuses (ACCOUNTANT), silently narrowed the list with no selector, chip or
 * hint to say so.
 */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import UnitsPage from "../page";

const page = (content: unknown[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 200 });
const unit = (id: string, unitNumber: string) => ({
    id, unitNumber, type: "BHK1", sizeSqft: 900, status: "VACANT", occupancy: "VACANT",
    nextLeaseStart: null, nextTenantName: null, expectedRent: 50000, actualRent: 0, currentTenantName: null,
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

async function renderPage() {
    await act(async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <Suspense fallback={null}>
                    <UnitsPage params={Promise.resolve({ id: "prop-1" })} />
                </Suspense>
            </NextIntlClientProvider>,
        );
    });
}

describe("Units tower filter — P2-2: no invisible filter with no way to clear", () => {
    it("drops a buildingId from the URL once GET /buildings/property/{id} refuses this role (403)", async () => {
        window.history.replaceState(null, "", "/en/dashboard/properties/prop-1/units?buildingId=baaaaaaa-0000-4000-8000-00000000000a");
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/buildings/property/")) return { ok: false, status: 403, json: async () => ({}) } as unknown as Response;
            if (u.includes("/units/paged")) {
                // The unit fetch itself still accepts buildingId=baaaaaaa-0000-4000-8000-00000000000a (only the
                // buildings *read* is refused) — but nothing should keep asking
                // for it once the page knows the select can't show.
                return { ok: true, json: async () => page(u.includes("buildingId=baaaaaaa-0000-4000-8000-00000000000a") ? [unit("u1", "A-101")] : [unit("u1", "A-101"), unit("u2", "B-101")]) } as unknown as Response;
            }
            return { ok: true, json: async () => [] } as unknown as Response;
        }) as unknown as typeof fetch;

        await renderPage();

        await waitFor(() => expect(new URL(window.location.href).searchParams.get("buildingId")).toBeNull());
        expect(screen.queryByTestId("units-building-filter")).toBeNull();
        await waitFor(() => expect(screen.getByText("#B-101")).toBeTruthy());
    });

    it("shows a removable chip next to the select while a tower is active", async () => {
        window.history.replaceState(null, "", "/en/dashboard/properties/prop-1/units?buildingId=baaaaaaa-0000-4000-8000-00000000000a");
        global.fetch = vi.fn(async (url: unknown) => {
            const u = String(url);
            if (u.includes("/v1/buildings/property/")) {
                return { ok: true, json: async () => [{ id: "baaaaaaa-0000-4000-8000-00000000000a", nameEn: "Tower A" }] } as unknown as Response;
            }
            if (u.includes("/units/paged")) return { ok: true, json: async () => page([unit("u1", "A-101")]) } as unknown as Response;
            return { ok: true, json: async () => [] } as unknown as Response;
        }) as unknown as typeof fetch;

        await renderPage();

        const chip = await screen.findByTestId("units-building-filter-chip");
        expect(chip).toHaveTextContent("Tower A");
        fireEvent.click(screen.getByTestId("units-building-filter-chip-remove"));
        await waitFor(() => expect(new URL(window.location.href).searchParams.get("buildingId")).toBeNull());
    });
});
