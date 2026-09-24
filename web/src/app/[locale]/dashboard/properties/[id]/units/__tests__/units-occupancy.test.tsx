import { Suspense } from "react";
import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";
import ar from "../../../../../../../../messages/ar.json";

/**
 * F14-01b: the unit card used to read the old `status` field alone, so a
 * unit whose next lease has not started yet ("occupancy": "RESERVED", a
 * back-to-back letting) still showed "Vacant" — a manager could double-book
 * it. The card now prefers `occupancy` and, for a RESERVED unit, names the
 * date and the incoming tenant; an OCCUPIED unit with a lined-up successor
 * shows a small "Next" line.
 */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import UnitsPage from "../page";

const UNITS = [
    {
        id: "u-occupied", unitNumber: "101", type: "BHK1", sizeSqft: 850, status: "OCCUPIED",
        occupancy: "OCCUPIED", nextLeaseStart: null, nextTenantName: null,
        expectedRent: 60000, actualRent: 60000, currentTenantName: "Fatima Al Suwaidi",
    },
    {
        id: "u-reserved", unitNumber: "102", type: "BHK2", sizeSqft: 1100, status: "VACANT",
        occupancy: "RESERVED", nextLeaseStart: "2026-10-15", nextTenantName: "Omar Khan",
        expectedRent: 80000, actualRent: 0, currentTenantName: null,
    },
    {
        id: "u-back-to-back", unitNumber: "103", type: "BHK1", sizeSqft: 900, status: "OCCUPIED",
        occupancy: "OCCUPIED", nextLeaseStart: "2026-11-01", nextTenantName: "Sara Yousef",
        expectedRent: 65000, actualRent: 65000, currentTenantName: "Ali Hassan",
    },
];

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => UNITS }) as unknown as Response) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

async function renderPage(locale: "en" | "ar" = "en") {
    await act(async () => {
        render(
            <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
                <Suspense fallback={null}>
                    <UnitsPage params={Promise.resolve({ id: "prop-1" })} />
                </Suspense>
            </NextIntlClientProvider>,
        );
    });
}

describe("unit occupancy by date (F14-01b)", () => {
    it("shows a RESERVED badge with the date and incoming tenant, not Vacant", async () => {
        await renderPage();
        const badge = screen.getByTestId("unit-status-badge-u-reserved");
        expect(badge).toHaveTextContent("Reserved from 15/10/2026");
        expect(badge).toHaveTextContent("Omar Khan");
        expect(badge).not.toHaveTextContent("Vacant");
    });

    it("shows a small Next line for an OCCUPIED unit with a lined-up successor", async () => {
        await renderPage();
        const next = screen.getByTestId("unit-next-lease-u-back-to-back");
        expect(next).toHaveTextContent("Sara Yousef");
        expect(next).toHaveTextContent("01/11/2026");
    });

    it("shows no Next line for an OCCUPIED unit with nothing lined up", async () => {
        await renderPage();
        expect(screen.queryByTestId("unit-next-lease-u-occupied")).toBeNull();
    });

    it("renders the RESERVED badge and Next line in Arabic", async () => {
        await renderPage("ar");
        const badge = screen.getByTestId("unit-status-badge-u-reserved");
        expect(badge.textContent).not.toBe("");
        expect(badge.textContent).not.toContain("RESERVED");
        expect(badge.textContent).not.toContain("Reserved");

        const next = screen.getByTestId("unit-next-lease-u-back-to-back");
        expect(next.textContent).not.toContain("Next:");
    });
});
