import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../../../../messages/ar.json";
import en from "../../../../../../../messages/en.json";

/**
 * Finance → Payables (finance-ops spec §2): the aging page and the AP opening
 * items grid. The figures are the spec's worked example as of 30/09/2026.
 */

const session = vi.hoisted(() => ({ role: "ACCOUNTANT" }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: session.role } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));

import AgingPage from "../aging/page";
import OpeningItemsPage from "../opening-items/page";

const figures = (over: Record<string, number | null>) => ({
    current: 0, d1to30: 0, d31to60: 0, d61to90: 0, d90plus: 0, advances: 0, openTotal: 0, ledgerBalance: 0, delta: 0, ...over,
});

const AGING = {
    asOf: "2026-09-30", propertyId: null, vendorId: null, vendorLevel: true, advances: [],
    rows: [
        { vendorId: "noor", vendorName: "Al Noor Cleaning", vendorNameAr: "النور للتنظيف", active: true, payableAccountId: "p2",
          figures: figures({ current: 3150, openTotal: 3150, ledgerBalance: 3150 }), items: [] },
        { vendorId: "gulf", vendorName: "Gulf AC Services", vendorNameAr: null, active: true, payableAccountId: "p1",
          figures: figures({ d1to30: 1500, openTotal: 1500, ledgerBalance: 1750, delta: 250 }),
          items: [{ kind: "PISR", id: "i90", vendorId: "gulf", vendorName: "Gulf AC Services", docNumber: "PISR-26/21",
                    invoiceNumber: "INV-7790", docDate: "2026-08-20", invoiceDate: "2026-08-20", dueDate: "2026-09-19",
                    daysOverdue: 11, bucket: "D1_30", gross: 2100, allocated: 600, open: 1500, status: "PART_PAID", propertyId: null }] },
    ],
    totals: figures({ current: 3150, d1to30: 1500, openTotal: 4650, ledgerBalance: 4900, delta: 250 }),
};

let calls: string[];

function stubFetch(routes: Record<string, unknown>) {
    calls = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        calls.push(`${init?.method ?? "GET"} ${u}`);
        const key = Object.keys(routes).find(k => u.includes(k));
        return new Response(JSON.stringify(key ? routes[key] : []), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
}

function renderIn(locale: "en" | "ar", ui: React.ReactElement) {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <div dir={locale === "ar" ? "rtl" : "ltr"}>{ui}</div>
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    session.role = "ACCOUNTANT";
    stubFetch({ "/payables-aging": AGING, "/properties": [{ property: { id: "prop-1", nameEn: "Marina Tower", nameAr: "برج المارينا" } }] });
});
afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

describe("payables aging", () => {
    it("shows the buckets, the tie-out and a drill to the ledger when Δ is not zero", async () => {
        renderIn("en", <AgingPage />);
        const gulf = await screen.findByTestId("aging-row-gulf");
        expect(gulf).toHaveTextContent("Gulf AC Services");
        expect(gulf).toHaveTextContent("1,500.00");
        const delta = within(screen.getByTestId("aging-delta-gulf")).getByRole("link");
        expect(delta).toHaveAttribute("href", "/dashboard/finance/general-ledger?vendorId=gulf");
        expect(delta).toHaveTextContent("250.00");
        expect(screen.getByTestId("aging-delta-noor")).toHaveTextContent("0.00");
        expect(screen.getByTestId("aging-totals")).toHaveTextContent("4,650.00");
        // The vendor links to its account page.
        expect(within(gulf).getByRole("link", { name: "Gulf AC Services" })).toHaveAttribute("href", "/dashboard/finance/vendors/gulf");
        // Expanding shows the invoice in its bucket.
        fireEvent.click(within(gulf).getByRole("button", { name: en.Payables.showItems }));
        expect(await screen.findByText(/INV-7790/)).toBeInTheDocument();
        expect(screen.getByTestId("aging-csv").getAttribute("href")).toContain("/finance/reports/payables-aging.csv");
    });

    it("renders in Arabic with Latin-digit amounts isolated left-to-right", async () => {
        renderIn("ar", <AgingPage />);
        const row = await screen.findByTestId("aging-row-noor");
        expect(row).toHaveTextContent("النور للتنظيف");
        const amount = within(row).getAllByText("3,150.00")[0];
        expect(amount.tagName).toBe("BDI");
        expect(amount).toHaveAttribute("dir", "ltr");
        expect(screen.getByText(ar.Payables.aging)).toBeInTheDocument();
    });

    it("asks a property manager for a property before loading anything", async () => {
        session.role = "PROPERTY_MANAGER";
        renderIn("en", <AgingPage />);
        expect((await screen.findAllByText(en.Payables.chooseProperty)).length).toBeGreaterThan(0);
        expect(calls.some(c => c.includes("/payables-aging"))).toBe(false);
        await waitFor(() => expect(screen.getAllByRole("option", { name: "Marina Tower" }).length).toBeGreaterThan(0));
        fireEvent.change(screen.getByTestId("aging-property"), { target: { value: "prop-1" } });
        fireEvent.click(screen.getByTestId("aging-apply"));
        await waitFor(() => expect(calls.some(c => c.includes("/payables-aging?") && c.includes("propertyId=prop-1"))).toBe(true));
    });

    it("refuses a renter", () => {
        session.role = "RENTER";
        renderIn("en", <AgingPage />);
        expect(screen.getByText(en.Payables.accessDenied)).toBeInTheDocument();
    });
});

describe("AP opening items", () => {
    const SUMMARY = {
        items: [{ id: "o1", vendorId: "gulf", vendorName: "Gulf AC Services", invoiceNumber: "OLD-1", invoiceDate: "2026-06-15",
                  dueDate: "2026-07-15", amount: 2000, propertyId: null, allocated: 2000, open: 0 }],
        vendors: [{ vendorId: "gulf", vendorName: "Gulf AC Services", itemsTotal: 2000, openingBalance: 3000, difference: 1000 }],
    };

    beforeEach(() => {
        stubFetch({
            "/ap-opening-items": SUMMARY,
            "/v1/vendors": [{ id: "gulf", nameEn: "Gulf AC Services", nameAr: null, active: true }],
        });
    });

    it("lists the items, checks them against the opening balance and posts a new one", async () => {
        renderIn("en", <OpeningItemsPage />);
        expect(await screen.findByTestId("opening-item-OLD-1")).toHaveTextContent("2,000.00");
        expect(screen.getByTestId("ob-check")).toHaveTextContent("1,000.00");
        // An item with allocations cannot be deleted.
        expect(within(screen.getByTestId("opening-item-OLD-1")).getByRole("button", { name: en.Payables.deleteItem })).toBeDisabled();

        await waitFor(() => expect(screen.getAllByRole("option", { name: "Gulf AC Services" }).length).toBeGreaterThan(0));
        fireEvent.change(screen.getByTestId("oi-vendor"), { target: { value: "gulf" } });
        fireEvent.change(screen.getByTestId("oi-invoice"), { target: { value: "OLD-2" } });
        fireEvent.change(screen.getByTestId("oi-date"), { target: { value: "2026-07-01" } });
        fireEvent.change(screen.getByTestId("oi-amount"), { target: { value: "1000" } });
        fireEvent.click(screen.getByTestId("oi-add"));
        await waitFor(() => expect(calls).toContain("POST /api/proxy/v1/finance/ap-opening-items"));
    });
});
