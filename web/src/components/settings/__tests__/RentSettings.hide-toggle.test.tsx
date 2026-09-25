// src/components/settings/__tests__/RentSettings.hide-toggle.test.tsx
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
import RentSettings from "../RentSettings";

const posted: unknown[] = [];
beforeEach(() => {
    posted.length = 0;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.includes("/v1/properties")) return { ok: true, status: 200, json: async () => [{ property: { id: "p1", nameEn: "Belle Vue" } }] } as unknown as Response;
        if (url.includes("/v1/settings/fines")) return { ok: false, status: 404, json: async () => ({}) } as unknown as Response;
        if (url.includes("/v1/rent-settings/") && init?.method === "POST") {
            posted.push(JSON.parse(String(init.body)));
            return { ok: true, status: 200, json: async () => JSON.parse(String(init.body)) } as unknown as Response;
        }
        if (url.includes("/v1/rent-settings/")) return { ok: true, status: 200, json: async () => ({ propertyId: "p1", onlinePaymentEnabled: true, dueDayOfMonth: 3 }) } as unknown as Response;
        return { ok: true, status: 200, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("RentSettings inside Settings › Rent & fines", () => {
    it("hides the online-payment switch but saves the value it loaded, so Payments' choice is never clobbered", async () => {
        render(<RentSettings embedded hideOnlinePaymentToggle />);
        await screen.findByText("Belle Vue");
        fireEvent.change(screen.getAllByRole("combobox")[0], { target: { value: "p1" } });
        await screen.findByText("Settings");
        expect(screen.queryByRole("switch")).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: /save/i }));
        await waitFor(() => expect(posted).toHaveLength(1));
        expect(posted[0]).toMatchObject({ onlinePaymentEnabled: true, dueDayOfMonth: 3 });
    });

    it("renders its title as h2 when embedded", async () => {
        render(<RentSettings embedded />);
        expect(await screen.findByRole("heading", { level: 2, name: /rent collection settings/i })).toBeInTheDocument();
    });
});
