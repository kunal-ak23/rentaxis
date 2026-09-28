import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * Final round (batch 4 review #2): the "Add Unit" form's expected-rent field
 * used to be a plain `<input type="number">` fed through `Number(...)` — a
 * third decimal, or an amount past the unit's own decimal(12,2) column, was
 * sent to the (now-validated) POST /units endpoint and came back as a raw
 * server 400. It is now the shared money input, so the form itself refuses to
 * submit and shows a readable message.
 */

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "p1" }),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
    usePathname: () => "/en/dashboard/properties/p1",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "U", tenantId: "t1" } } }) }));

import PropertyDetailPage from "../page";

let postedUnits: unknown[];

beforeEach(() => {
    postedUnits = [];
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/api/proxy/v1/units") && init?.method === "POST") {
            postedUnits.push(JSON.parse(String(init.body)));
            return { ok: true, status: 200, json: async () => ({ id: "u-new" }) } as unknown as Response;
        }
        const body = url.endsWith("/v1/properties/p1")
            ? { id: "p1", nameEn: "Belle Vue", emirate: "DUBAI", type: "RESIDENTIAL" }
            : [];
        return { ok: true, status: 200, json: async () => body } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("property detail — Add Unit expected rent", () => {
    it("refuses an amount past the column with a message, and never posts it", async () => {
        render(<NextIntlClientProvider locale="en" messages={en}><PropertyDetailPage /></NextIntlClientProvider>);
        fireEvent.click(await screen.findByRole("button", { name: /^units$/i }));
        fireEvent.click(await screen.findByText("Add Unit"));
        const unitNumber = await screen.findByPlaceholderText(en.MasterData.unitNumberPlaceholder);
        fireEvent.change(unitNumber, { target: { value: "U-1" } });
        const rent = screen.getByPlaceholderText("e.g. 85000") as HTMLInputElement;
        fireEvent.change(rent, { target: { value: "10000000000.00" } });
        expect(rent.dataset.moneyInvalid).toBe("true");
        expect(rent.validationMessage).not.toBe("");
        fireEvent.click(screen.getByText("Save Unit"));
        await new Promise((r) => setTimeout(r, 0));
        expect(postedUnits).toHaveLength(0);
    });

    it("posts a valid amount at the column's own limit", async () => {
        render(<NextIntlClientProvider locale="en" messages={en}><PropertyDetailPage /></NextIntlClientProvider>);
        fireEvent.click(await screen.findByRole("button", { name: /^units$/i }));
        fireEvent.click(await screen.findByText("Add Unit"));
        const unitNumber = await screen.findByPlaceholderText(en.MasterData.unitNumberPlaceholder);
        fireEvent.change(unitNumber, { target: { value: "U-2" } });
        const rent = screen.getByPlaceholderText("e.g. 85000") as HTMLInputElement;
        fireEvent.change(rent, { target: { value: "9999999999.99" } });
        expect(rent.dataset.moneyInvalid).toBeUndefined();
        fireEvent.click(screen.getByText("Save Unit"));
        await waitFor(() => expect(postedUnits).toHaveLength(1));
        expect((postedUnits[0] as { expectedRent: number }).expectedRent).toBe(9999999999.99);
    });
});
