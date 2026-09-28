import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";

/**
 * Break-it round 3 (ops3) F1/F3.
 *  - F3: a building saved with -3, 0 or 99 999 floors. The form refuses outside
 *    1..200 and never posts.
 *  - F1: the server now refuses a unit number already in the building with a
 *    coded 400 (unit.numberTaken); the form says so in the user's language.
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

let posted: { url: string; body: unknown }[];

beforeEach(() => {
    posted = [];
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (init?.method === "POST") {
            posted.push({ url, body: JSON.parse(String(init.body)) });
            if (url.endsWith("/v1/units")) {
                const refusal = { error: true, status: 400, message: "Unit 101 already exists in Tower A",
                    code: "unit.numberTaken", args: { unitNumber: "101", place: "Tower A" } };
                return new Response(JSON.stringify(refusal), { status: 400 });
            }
            return new Response("{}", { status: 200 });
        }
        const body = url.endsWith("/v1/properties/p1")
            ? { id: "p1", nameEn: "Belle Vue", emirate: "DUBAI", type: "RESIDENTIAL" }
            : [];
        return new Response(JSON.stringify(body), { status: 200 });
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("property detail — Add Building floors (F3)", () => {
    for (const floors of ["-3", "0", "99999"]) {
        it(`refuses ${floors} floors with the range, and never posts`, async () => {
            render(<NextIntlClientProvider locale="en" messages={en}><PropertyDetailPage /></NextIntlClientProvider>);
            fireEvent.click(await screen.findByRole("button", { name: /^buildings$/i }));
            fireEvent.click(await screen.findByText("Add Building"));
            const inputs = screen.getAllByRole("textbox");
            fireEvent.change(inputs[0], { target: { value: "Tower A" } });
            const floorsInput = screen.getByRole("spinbutton");
            fireEvent.change(floorsInput, { target: { value: floors } });
            fireEvent.submit(floorsInput.closest("form")!);
            expect(await screen.findByText("Floors must be a whole number from 1 to 200.")).toBeInTheDocument();
            expect(posted).toHaveLength(0);
        });
    }
});

describe("property detail — Add Unit number taken (F1)", () => {
    it("says the unit already exists, in Arabic too", async () => {
        render(<NextIntlClientProvider locale="ar" messages={ar}><PropertyDetailPage /></NextIntlClientProvider>);
        fireEvent.click(await screen.findByRole("button", { name: /^units$/i }));
        fireEvent.click(await screen.findByText("Add Unit"));
        fireEvent.change(await screen.findByPlaceholderText(ar.MasterData.unitNumberPlaceholder), { target: { value: "101" } });
        fireEvent.click(screen.getByText("Save Unit"));
        await waitFor(() => expect(posted).toHaveLength(1));
        expect(await screen.findByText("الوحدة 101 موجودة بالفعل في Tower A. استخدم رقم وحدة مختلفاً.")).toBeInTheDocument();
    });
});
