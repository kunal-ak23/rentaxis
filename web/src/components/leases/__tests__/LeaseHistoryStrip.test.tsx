import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";

const leaseApi = vi.hoisted(() => ({ chain: vi.fn() }));
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, leaseApi: { ...real.leaseApi, ...leaseApi } };
});
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import LeaseHistoryStrip from "../LeaseHistoryStrip";
import type { LeaseChainLink } from "@/lib/api/leasing";

const CHAIN: LeaseChainLink[] = [
    { id: "h", unitIdentifier: "A-102", displayContractNumber: "OLV/7", startDate: "2025-01-01", endDate: "2025-12-31", status: "RENEWED", link: null },
    { id: "a", unitIdentifier: "A-102", displayContractNumber: "OLV/12", startDate: "2026-01-01", endDate: "2026-05-15", status: "TERMINATED", link: "RENEWED" },
    { id: "b", unitIdentifier: "B-310", displayContractNumber: null, startDate: "2026-05-16", endDate: "2026-12-31", status: "ACTIVE", link: "TRANSFERRED" },
];

function renderStrip(leaseId: string, locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <div dir={locale === "ar" ? "rtl" : "ltr"}>
                <LeaseHistoryStrip leaseId={leaseId} />
            </div>
        </NextIntlClientProvider>,
    );
}

/** #100-e: the lease page's History strip lists the whole chain, each link labelled. */
describe("LeaseHistoryStrip", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("lists the chain in order, labels each link and links every other contract", async () => {
        leaseApi.chain.mockResolvedValue(CHAIN);
        renderStrip("a");
        const list = await screen.findByTestId("lease-history-list");
        expect(leaseApi.chain).toHaveBeenCalledWith("a");
        const cards = within(list).getAllByRole("listitem").filter(li => li.dataset.testid?.startsWith("lease-history-"));
        expect(cards.map(c => c.dataset.testid)).toEqual(["lease-history-h", "lease-history-a", "lease-history-b"]);
        expect(cards.map(c => c.dataset.link)).toEqual(["FIRST", "RENEWED", "TRANSFERRED"]);
        expect(within(list).getAllByText("Renewed").length).toBeGreaterThan(0);
        expect(within(list).getAllByText("Transferred").length).toBeGreaterThan(0);
        // The current contract is marked, not linked; the others link to their page.
        const current = screen.getByTestId("lease-history-a");
        expect(current.getAttribute("aria-current")).toBe("page");
        expect(within(current).queryByRole("link")).toBeNull();
        expect(within(current).getByText("This contract")).toBeTruthy();
        expect(within(screen.getByTestId("lease-history-b")).getByRole("link").getAttribute("href")).toBe("/dashboard/leases/b");
        expect(within(screen.getByTestId("lease-history-b")).getByText("Unit B-310")).toBeTruthy();
    });

    it("scrolls sideways instead of wrapping, so a long chain fits a phone", async () => {
        leaseApi.chain.mockResolvedValue(CHAIN);
        renderStrip("a");
        const list = await screen.findByTestId("lease-history-list");
        expect(list.className).toContain("overflow-x-auto");
        expect(list.className).not.toContain("flex-wrap");
        for (const card of within(list).getAllByRole("listitem")) expect(card.className).toContain("shrink-0");
    });

    it("renders in Arabic with the dates kept left-to-right and the arrow flipped", async () => {
        leaseApi.chain.mockResolvedValue(CHAIN);
        renderStrip("a", "ar");
        const list = await screen.findByTestId("lease-history-list");
        expect(within(list).getAllByText("تجديد").length).toBeGreaterThan(0);
        expect(within(list).getAllByText("نقل").length).toBeGreaterThan(0);
        expect(list.querySelectorAll("bdi[dir='ltr']").length).toBeGreaterThan(0);
        expect(list.querySelector("svg")?.getAttribute("class")).toContain("rtl:rotate-180");
    });

    it("shows nothing for a contract with no history or when the read fails", async () => {
        leaseApi.chain.mockResolvedValue([CHAIN[0]]);
        const { unmount } = renderStrip("h");
        await waitFor(() => expect(leaseApi.chain).toHaveBeenCalled());
        expect(screen.queryByTestId("lease-history")).toBeNull();
        unmount();
        leaseApi.chain.mockRejectedValue(new Error("boom"));
        renderStrip("h");
        await waitFor(() => expect(leaseApi.chain).toHaveBeenCalledTimes(2));
        expect(screen.queryByTestId("lease-history")).toBeNull();
    });
});
