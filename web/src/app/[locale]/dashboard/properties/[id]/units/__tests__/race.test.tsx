import { Suspense } from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";

/**
 * R1 review of PR #373 (P3-1): switching the tower filter quickly could leave
 * the previous tower's units on screen — nothing stopped an older, slower
 * `/units/paged` response from overwriting a newer one.
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

let resolveSlow: (v: unknown) => void = () => {};
const slow = new Promise(r => { resolveSlow = r; });

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/v1/buildings/property/")) {
            return { ok: true, json: async () => [{ id: "bA", nameEn: "Tower A" }, { id: "bB", nameEn: "Tower B" }] } as unknown as Response;
        }
        if (u.includes("/units/paged")) {
            if (u.includes("buildingId=bA")) {
                await slow;
                return { ok: true, json: async () => page([unit("u1", "A-101")]) } as unknown as Response;
            }
            if (u.includes("buildingId=bB")) {
                return { ok: true, json: async () => page([unit("u2", "B-101")]) } as unknown as Response;
            }
            return { ok: true, json: async () => page([unit("u1", "A-101"), unit("u2", "B-101")]) } as unknown as Response;
        }
        return { ok: true, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
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

describe("Units — P3-1: no stale response wins", () => {
    it("keeps the newer tower's units on screen when an older, slower read lands after it", async () => {
        await renderPage();
        const select = await screen.findByTestId("units-building-filter");

        // Tower A hangs; switching straight to Tower B must not let A's
        // (eventually resolved) units replace B's once it lands.
        fireEvent.change(select, { target: { value: "bA" } });
        fireEvent.change(select, { target: { value: "bB" } });

        await screen.findByText("#B-101");
        expect(screen.queryByText("#A-101")).toBeNull();

        resolveSlow(undefined);
        await act(async () => { await new Promise(r => setTimeout(r, 20)); });
        await waitFor(() => expect(screen.getByText("#B-101")).toBeTruthy());
        expect(screen.queryByText("#A-101")).toBeNull();
    });
});
