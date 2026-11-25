import { Suspense } from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";

let role = "TENANT_ADMIN";
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role, name: "U", tenantId: "t1" } } }) }));
// The field must not depend on the RENTAL_INDEX flag: the flag is off here.
vi.mock("@/hooks/useTenantFeatures", () => ({ useTenantFeatures: () => ({ isEnabled: () => false }) }));

import UnitsPage from "../page";

const unit = (id: string, dewa: string | null) => ({
    id, unitNumber: "101", type: "BHK1", sizeSqft: 900, status: "VACANT", occupancy: "VACANT",
    nextLeaseStart: null, nextTenantName: null, expectedRent: 50000, actualRent: 0, currentTenantName: null,
    dewaPremiseNumber: dewa,
});
const json = (body: unknown, ok = true, status = 200) => ({ ok, status, json: async () => body, text: async () => JSON.stringify(body) }) as unknown as Response;

let puts: { url: string; body: string }[] = [];
let putResponse: () => Response;

beforeEach(() => {
    role = "TENANT_ADMIN";
    puts = [];
    putResponse = () => json(unit("u1", "681204553"));
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (init?.method === "PUT") { puts.push({ url: u, body: String(init.body) }); return putResponse(); }
        if (u.includes("/units/paged")) return json({ content: [unit("u1", null)], totalElements: 1, totalPages: 1, number: 0, size: 200 });
        return json([]);
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

async function renderPage() {
    await act(async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <Suspense fallback={null}><UnitsPage params={Promise.resolve({ id: "prop-1" })} /></Suspense>
            </NextIntlClientProvider>,
        );
    });
}

describe("Units — DEWA premise number", () => {
    it("shows the create field with the flag off", async () => {
        await renderPage();
        fireEvent.click((await screen.findAllByText("Add Property"))[0]);
        expect(await screen.findByTestId("unit-dewa")).toBeTruthy();
    });

    it("saves a normalised number through the PUT and shows it", async () => {
        await renderPage();
        fireEvent.click(await screen.findByTestId("unit-dewa-edit-u1"));
        fireEvent.change(screen.getByTestId("unit-dewa-input-u1"), { target: { value: "681 204 553" } });
        fireEvent.click(screen.getByTestId("unit-dewa-save-u1"));
        await waitFor(() => expect(puts).toHaveLength(1));
        expect(puts[0].url).toBe("/api/proxy/v1/units/u1/dewa-premise-number");
        expect(JSON.parse(puts[0].body)).toEqual({ dewaPremiseNumber: "681204553" });
        expect((await screen.findByTestId("unit-dewa-value-u1")).textContent).toBe("681204553");
    });

    it("sends null when cleared", async () => {
        await renderPage();
        fireEvent.click(await screen.findByTestId("unit-dewa-edit-u1"));
        fireEvent.click(screen.getByTestId("unit-dewa-save-u1"));
        await waitFor(() => expect(puts).toHaveLength(1));
        expect(JSON.parse(puts[0].body)).toEqual({ dewaPremiseNumber: null });
    });

    it("does not call the server for an invalid number and says why", async () => {
        await renderPage();
        fireEvent.click(await screen.findByTestId("unit-dewa-edit-u1"));
        fireEvent.change(screen.getByTestId("unit-dewa-input-u1"), { target: { value: "12AB" } });
        fireEvent.click(screen.getByTestId("unit-dewa-save-u1"));
        expect((await screen.findByTestId("unit-dewa-error-u1")).textContent).toBe("Enter the DEWA premise number as 6 to 12 digits.");
        expect(puts).toHaveLength(0);
    });

    it("shows the translated message for dewaTaken", async () => {
        putResponse = () => json({ code: "unit.dewaTaken", message: "taken" }, false, 400);
        await renderPage();
        fireEvent.click(await screen.findByTestId("unit-dewa-edit-u1"));
        fireEvent.change(screen.getByTestId("unit-dewa-input-u1"), { target: { value: "681204553" } });
        fireEvent.click(screen.getByTestId("unit-dewa-save-u1"));
        expect((await screen.findByTestId("unit-dewa-error-u1")).textContent).toBe("Another unit already has this DEWA premise number.");
    });

    it("hides the edit control from a role that cannot write", async () => {
        role = "ACCOUNTANT";
        await renderPage();
        await screen.findByTestId("unit-dewa-value-u1");
        expect(screen.queryByTestId("unit-dewa-edit-u1")).toBeNull();
    });
});
