import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/** #81: the vendors table kept its Status and Actions headers in English under /ar. */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import VendorsPage from "../page";

const VENDOR = {
    id: "v1", nameEn: "Acme Maintenance", nameAr: "أكمي للصيانة", tradeLicenseNumber: "", trn: "", email: "ops@acme.ae",
    phone: "+97145550000", contactPerson: "", address: "", bankName: "", bankAccountNumber: "", iban: "",
    payableAccount: null, notes: "", active: true,
};

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        const body = String(url).includes("/finance/accounts") ? [] : [VENDOR];
        return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("vendors in Arabic", () => {
    it("the list carries no English chrome", async () => {
        const { container } = render(
            <NextIntlClientProvider locale="ar" messages={ar}><VendorsPage /></NextIntlClientProvider>,
        );
        await screen.findByText(ar.Vendors.status);
        expect(screen.getByText(ar.Vendors.actions)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), ["Acme Maintenance", "ops@acme.ae", "+97145550000"])).toEqual([]);
    });
});
