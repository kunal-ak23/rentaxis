import { Suspense } from "react";
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";

/**
 * A unit has no detail page of its own — the units grid (this page) is the
 * closest thing to it. Each unit's card now links straight to its General
 * Ledger, pre-filtered to the property and unit, so every contract that unit
 * has ever had shows up (no leaseId filter). The link only appears for a role
 * the General Ledger itself is open to (`canAccessFinance`), same as the GL
 * page's own gate.
 */

const role = { current: "TENANT_ADMIN" as string };
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current, name: "U", tenantId: "t1" } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import UnitsPage from "../page";

const UNITS = [
    {
        id: "u1", unitNumber: "101", type: "BHK1", sizeSqft: 850, status: "OCCUPIED",
        occupancy: "OCCUPIED", nextLeaseStart: null, nextTenantName: null,
        expectedRent: 60000, actualRent: 60000, currentTenantName: "Fatima Al Suwaidi",
    },
];

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/v1/buildings/property/")) return { ok: true, json: async () => [] } as unknown as Response;
        return { ok: true, json: async () => ({ content: UNITS, totalElements: UNITS.length, totalPages: 1, number: 0, size: 200 }) } as unknown as Response;
    }) as unknown as typeof fetch;
    role.current = "TENANT_ADMIN";
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
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

describe("unit card — Ledger link", () => {
    it("links to the General Ledger pre-filtered to the property and unit, no leaseId", async () => {
        await renderPage();
        const link = screen.getByTestId("unit-ledger-link-u1");
        expect(link).toHaveAttribute("href", "/dashboard/finance/general-ledger?propertyId=prop-1&unitId=u1");
        expect(link.getAttribute("href")).not.toContain("leaseId");
    });

    it("hides the Ledger link for a role without General Ledger access", async () => {
        role.current = "RENTER";
        await renderPage();
        expect(screen.queryByTestId("unit-ledger-link-u1")).toBeNull();
    });

    it("shows the Ledger link for an ACCOUNTANT (canAccessFinance)", async () => {
        role.current = "ACCOUNTANT";
        await renderPage();
        expect(screen.getByTestId("unit-ledger-link-u1")).toBeInTheDocument();
    });
});
