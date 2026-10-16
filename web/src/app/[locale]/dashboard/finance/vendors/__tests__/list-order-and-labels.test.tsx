import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));

import VendorsPage from "../page";

/** Tutorial 19: the vendor list sorts oldest first (on the server since the scale follow-up), and the form's labels reach their fields. */

const base = {
    nameAr: "", tradeLicenseNumber: "", trn: "", email: "", phone: "", contactPerson: "", address: "",
    bankName: "", bankAccountNumber: "", iban: "", payableAccount: null, notes: "", active: true,
};
const vendors = [
    // The server's order (order=oldest): created first. The page keeps it as given.
    { ...base, id: "ffffffff-0000-0000-0000-000000000001", nameEn: "Al Noor Cleaning", createdAt: "2026-01-02T08:00:00" },
    { ...base, id: "88888888-0000-0000-0000-000000000001", nameEn: "Desert Cool AC", createdAt: "2026-01-03T08:00:00" },
    { ...base, id: "00000000-0000-0000-0000-000000000001", nameEn: "Gulf Shield", createdAt: "2026-10-05T09:00:00" },
];

const vendorCalls = () => (global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls
    .map(c => String(c[0])).filter(u => u.includes("/v1/vendors/paged"));

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/v1/vendors/paged")) {
            const q = new URL(u, "http://x").searchParams.get("q")?.toLowerCase();
            const rows = q ? vendors.filter(v => v.nameEn.toLowerCase().includes(q)) : vendors;
            return { ok: true, status: 200, json: async () => ({ content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 25 }) } as unknown as Response;
        }
        return { ok: true, status: 200, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("VendorsPage", () => {
    it("asks the server for one page, oldest first, and lists it in that order", async () => {
        render(<VendorsPage />);
        await screen.findByText("Gulf Shield");
        const first = new URL(vendorCalls()[0], "http://x").searchParams;
        expect(first.get("order")).toBe("oldest");
        expect(first.get("page")).toBe("0");
        expect(first.get("size")).toBe("25");
        expect(first.has("q")).toBe(false);
        // Scale follow-up: never the unbounded GET /vendors.
        expect((global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.some(c => String(c[0]).endsWith("/v1/vendors"))).toBe(false);
        expect(screen.getAllByText("Al Noor Cleaning")[0].compareDocumentPosition(screen.getAllByText("Desert Cool AC")[0]) & 4).toBeTruthy();
        expect(screen.getAllByText("Desert Cool AC")[0].compareDocumentPosition(screen.getAllByText("Gulf Shield")[0]) & 4).toBeTruthy();
    });

    it("searches on the server once typing pauses", async () => {
        render(<VendorsPage />);
        await screen.findByText("Gulf Shield");
        fireEvent.change(screen.getByPlaceholderText("search"), { target: { value: "  gulf " } });
        await waitFor(() => expect(new URL(vendorCalls().at(-1)!, "http://x").searchParams.get("q")).toBe("gulf"));
        await waitFor(() => expect(screen.queryByText("Al Noor Cleaning")).toBeNull());
        expect(screen.getByText("Gulf Shield")).toBeTruthy();
    });

    it("ties every form label to its field", async () => {
        render(<VendorsPage />);
        await screen.findByText("Gulf Shield");
        fireEvent.click(screen.getAllByRole("button", { name: /addVendor/ })[0]);
        for (const key of ["nameEn", "nameAr", "tradeLicense", "trn", "paymentTerms", "email", "phone",
            "contactPerson", "bankName", "address", "accountNumber", "iban", "notes"]) {
            expect(screen.getByLabelText(key), key).toBeTruthy();
        }
    });
});
