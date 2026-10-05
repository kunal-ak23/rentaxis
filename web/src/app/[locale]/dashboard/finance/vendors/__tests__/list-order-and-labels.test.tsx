import { cleanup, fireEvent, render, screen } from "@testing-library/react";
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

/** Tutorial 19: the vendor list sorts oldest first, and the form's labels reach their fields. */

const base = {
    nameAr: "", tradeLicenseNumber: "", trn: "", email: "", phone: "", contactPerson: "", address: "",
    bankName: "", bankAccountNumber: "", iban: "", payableAccount: null, notes: "", active: true,
};
const vendors = [
    // Ids sort the newest first — the order the list used to show.
    { ...base, id: "00000000-0000-0000-0000-000000000001", nameEn: "Gulf Shield", createdAt: "2026-10-05T09:00:00" },
    { ...base, id: "ffffffff-0000-0000-0000-000000000001", nameEn: "Al Noor Cleaning", createdAt: "2026-01-02T08:00:00" },
    { ...base, id: "88888888-0000-0000-0000-000000000001", nameEn: "Desert Cool AC", createdAt: "2026-01-03T08:00:00" },
];

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        if (u.includes("/v1/vendors")) return { ok: true, status: 200, json: async () => vendors } as unknown as Response;
        return { ok: true, status: 200, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("VendorsPage", () => {
    it("lists vendors oldest first", async () => {
        render(<VendorsPage />);
        await screen.findByText("Gulf Shield");
        const names = vendors.map(v => v.nameEn);
        const order = names.map(n => [n, screen.getAllByText(n)[0].compareDocumentPosition(screen.getAllByText("Gulf Shield")[0])]);
        // Al Noor and Desert Cool come before Gulf Shield (DOCUMENT_POSITION_FOLLOWING = 4).
        expect(order.filter(([n]) => n !== "Gulf Shield").every(([, pos]) => (pos as number) & 4)).toBe(true);
        expect(screen.getAllByText("Al Noor Cleaning")[0].compareDocumentPosition(screen.getAllByText("Desert Cool AC")[0]) & 4).toBeTruthy();
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
