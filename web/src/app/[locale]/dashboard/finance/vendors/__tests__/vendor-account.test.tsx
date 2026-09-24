import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/** A vendor's supplier-AP page (finance-ops spec §2): open items, advances with Apply, statement of account. */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("next/navigation", () => ({ useParams: () => ({ id: "gulf" }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));

import VendorAccountPage from "../[id]/page";

const ITEMS = [
    { kind: "PISR", id: "i81", vendorId: "gulf", vendorName: "Gulf AC", docNumber: "PISR-26/20", invoiceNumber: "INV-7781",
      docDate: "2026-08-01", invoiceDate: "2026-08-01", dueDate: "2026-08-31", daysOverdue: 0, bucket: "CURRENT",
      gross: 1450, allocated: 1450, open: 0, status: "PAID", propertyId: null },
    { kind: "PISR", id: "i90", vendorId: "gulf", vendorName: "Gulf AC", docNumber: "PISR-26/21", invoiceNumber: "INV-7790",
      docDate: "2026-08-20", invoiceDate: "2026-08-20", dueDate: "2026-09-19", daysOverdue: 5, bucket: "D1_30",
      gross: 2100, allocated: 600, open: 1500, status: "PART_PAID", propertyId: null },
];
const ADVANCES = [{ paymentId: "b60", vendorId: "gulf", vendorName: "Gulf AC", voucherNumber: "BPV-26/60", docDate: "2026-09-20",
    paymentMethod: "TRANSFER", reference: "TRF-9", paid: 5000, allocated: 0, unallocated: 5000 }];

let posts: { url: string; body: unknown }[];

beforeEach(() => {
    posts = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        if (init?.method === "POST") {
            posts.push({ url: u, body: JSON.parse(String(init.body)) });
            return new Response(JSON.stringify({ id: "a1" }), { status: 201, headers: { "Content-Type": "application/json" } });
        }
        const body = u.includes("/open-items") ? ITEMS
            : u.includes("/advances") ? ADVANCES
            : { id: "gulf", nameEn: "Gulf AC Services LLC", nameAr: null, trn: "100123456700003", paymentTermsDays: 30, active: true };
        return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
});
afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

function renderPage() {
    return render(<NextIntlClientProvider locale="en" messages={en}><VendorAccountPage /></NextIntlClientProvider>);
}

describe("vendor account page", () => {
    it("lists every invoice with its status, and the open and advance totals", async () => {
        renderPage();
        expect(await screen.findByTestId("vendor-name")).toHaveTextContent("Gulf AC Services LLC");
        const part = await screen.findByTestId("vendor-item-INV-7790");
        expect(within(part).getByText("Part-paid")).toBeInTheDocument();
        expect(within(screen.getByTestId("vendor-item-INV-7781")).getByText("Paid")).toBeInTheDocument();
        expect(screen.getByTestId("vendor-open")).toHaveTextContent("1,500.00");
        expect(screen.getByTestId("vendor-advance")).toHaveTextContent("5,000.00");
    });

    it("applies an advance to an open invoice", async () => {
        renderPage();
        await screen.findByTestId("vendor-item-INV-7790");
        fireEvent.click(screen.getByTestId("tab-advances"));
        fireEvent.click(await screen.findByTestId("apply-BPV-26/60"));
        fireEvent.change(screen.getByTestId("apply-target"), { target: { value: "PISR:i90" } });
        expect(screen.getByTestId("apply-amount")).toHaveValue("1500.00");
        fireEvent.click(screen.getByTestId("apply-confirm"));
        await waitFor(() => expect(posts).toHaveLength(1));
        expect(posts[0].url).toContain("/finance/voucher-allocations");
        expect(posts[0].body).toEqual({ paymentId: "b60", invoiceId: "i90", amount: 1500 });
    });

    it("offers the statement of account in English and Arabic", async () => {
        renderPage();
        await screen.findByTestId("vendor-item-INV-7790");
        fireEvent.click(screen.getByTestId("tab-statement"));
        fireEvent.change(screen.getByTestId("soa-from"), { target: { value: "2026-08-01" } });
        fireEvent.change(screen.getByTestId("soa-to"), { target: { value: "2026-09-30" } });
        expect(screen.getByTestId("soa-pdf-ar")).toHaveAttribute("href",
            "/api/proxy/v1/finance/vendors/gulf/statement.pdf?from=2026-08-01&to=2026-09-30&lang=ar");
    });
});
