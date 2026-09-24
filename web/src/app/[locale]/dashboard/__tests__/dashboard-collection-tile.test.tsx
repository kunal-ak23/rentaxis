import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../messages/en.json";
import { collectionTile } from "@/components/dashboard/collectionTile";

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { name: "Tutorial Admin", role: "TENANT_ADMIN" } } }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("@/components/dashboard/FollowUpsWidget", () => ({ default: () => null }));
vi.mock("@/components/dashboard/OverduePaymentsWidget", () => ({ default: () => null }));
vi.mock("@/components/dashboard/ChequesToDepositWidget", () => ({ default: () => null }));

import DashboardPage from "../page";

/**
 * Gap #59: the tile read "AED 139,550 of AED 3,667 expected" after one
 * catch-up banking run — 15 old cheques cleared in September against a single
 * September PDC. Cleared-this-month was divided by dated-this-month. The
 * headline is now on one basis (this month's dues, and what of them came in),
 * and the catch-up is reported beside it as arrears.
 */

// What the server reports for that month: the September PDC is still in hand,
// and the 15 clearances were all for earlier months.
const CATCH_UP_MONTH = {
    totalProperties: 1,
    totalUnits: 16,
    occupiedUnits: 16,
    vacantUnits: 0,
    occupancyRate: 100,
    activeLeases: 16,
    draftLeases: 0,
    expiringLeases: 0,
    overdueAmount: 0,
    collectedAmount: 139550,
    pendingThisMonthAmount: 3667,
    totalRentRevenue: 0,
    receivedThisMonth: 139550,
    receivedLastMonth: 0,
    dueThisMonth: 3667,
    collectedForThisMonth: 0,
    collectedAgainstDueThisMonth: 0,
    collectedArrears: 139550,
    collectedAdvance: 0,
    recentActivity: [],
};

let summary: Record<string, unknown> = CATCH_UP_MONTH;

beforeEach(() => {
    summary = CATCH_UP_MONTH;
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("monthly")) return { ok: true, json: async () => [] } as Response;
        return { ok: true, json: async () => summary } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

function renderEn() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <DashboardPage />
        </NextIntlClientProvider>,
    );
}

/** The tile's own card: the label's paragraph-level container. */
async function tileText(): Promise<string> {
    const label = await screen.findByText(en.Dashboard.collectedAgainstDues);
    const card = label.closest("div.flex-col") as HTMLElement;
    // Bidi isolates wrap each amount and Intl puts a no-break space after "AED";
    // normalise both to read the sentence.
    return (card.textContent ?? "").replace(/[\u2068\u2069]/g, "").replace(/\u00a0/g, " ");
}

describe("collection tile (gap #59)", () => {
    it("reads 0 of 3,667 with the catch-up shown as arrears, not 139,550 of 3,667", async () => {
        renderEn();
        const text = await tileText();

        expect(text).toContain("AED 0");
        expect(text).toContain("of AED 3,667 due (0%)");
        expect(text).toContain("+ AED 139,550 arrears collected this month");
        // The old, mixed-basis reading is gone.
        expect(text).not.toMatch(/139,550\s*of/);
        expect(text).not.toContain("expected");
    });

    it("shows both arrears and advance when both came in", async () => {
        summary = { ...CATCH_UP_MONTH, collectedForThisMonth: 3667, collectedAgainstDueThisMonth: 3667, collectedAdvance: 3667 };
        renderEn();
        await waitFor(async () =>
            expect(await tileText()).toContain(
                "+ AED 139,550 arrears, + AED 3,667 advance collected this month"),
        );
        expect(await tileText()).toContain("of AED 3,667 due (100%)");
    });

    it("counts this month's instalment paid ahead last month in the headline (review P2-1)", async () => {
        // Cleared on 28 August for September: not in the against-due part of the
        // identity (it did not clear this month), but it is September's money.
        summary = {
            ...CATCH_UP_MONTH,
            dueThisMonth: 10000,
            collectedForThisMonth: 10000,
            collectedAgainstDueThisMonth: 0,
            collectedArrears: 0,
        };
        renderEn();
        await waitFor(async () => expect(await tileText()).toContain("of AED 10,000 due (100%)"));
        expect(await tileText()).toContain("AED 10,000");
    });

    it("says nothing is due rather than dividing by zero, and has no sub-line when nothing else came in", async () => {
        summary = { ...CATCH_UP_MONTH, dueThisMonth: 0, collectedArrears: 0 };
        renderEn();
        const text = await tileText();
        expect(text).toContain(en.Dashboard.nothingDueThisMonth);
        expect(text).not.toContain("arrears");
        expect(text).not.toContain("advance");
    });
});

describe("collectionTile", () => {
    it("takes the headline from the due-date fields, never from receivedThisMonth", () => {
        expect(collectionTile(CATCH_UP_MONTH)).toEqual({
            collected: 0, due: 3667, percent: 0, arrears: 139550, advance: 0,
        });
    });

    it("heads with collectedForThisMonth, which includes rows paid ahead", () => {
        expect(collectionTile({ dueThisMonth: 10000, collectedForThisMonth: 10000, collectedAgainstDueThisMonth: 0 }))
            .toEqual({ collected: 10000, due: 10000, percent: 100, arrears: 0, advance: 0 });
    });

    it("tolerates missing fields", () => {
        expect(collectionTile({})).toEqual({ collected: 0, due: 0, percent: null, arrears: 0, advance: 0 });
    });
});
