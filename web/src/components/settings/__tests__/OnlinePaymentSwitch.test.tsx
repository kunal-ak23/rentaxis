// src/components/settings/__tests__/OnlinePaymentSwitch.test.tsx
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
import OnlinePaymentSwitch from "../OnlinePaymentSwitch";

const posted: Record<string, unknown>[] = [];
beforeEach(() => {
    posted.length = 0;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/v1/properties")) return { ok: true, status: 200, json: async () => [{ property: { id: "p1", nameEn: "Belle Vue" } }] } as unknown as Response;
        if (init?.method === "POST") { posted.push(JSON.parse(String(init.body))); return { ok: true, status: 200, json: async () => JSON.parse(String(init.body)) } as unknown as Response; }
        return { ok: true, status: 200, json: async () => ({ propertyId: "p1", onlinePaymentEnabled: false, dueDayOfMonth: 9, penaltyType: "PERCENTAGE", penaltyAmount: 2 }) } as unknown as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("OnlinePaymentSwitch", () => {
    it("flips only onlinePaymentEnabled and sends every other rent setting back unchanged", async () => {
        render(<OnlinePaymentSwitch />);
        await screen.findByText("Belle Vue");
        fireEvent.change(screen.getByRole("combobox"), { target: { value: "p1" } });
        fireEvent.click(await screen.findByRole("switch"));
        await waitFor(() => expect(posted).toHaveLength(1));
        expect(posted[0]).toMatchObject({ onlinePaymentEnabled: true, dueDayOfMonth: 9, penaltyType: "PERCENTAGE", penaltyAmount: 2 });
        expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/rent-settings/p1", expect.objectContaining({ method: "POST" }));
    });
});
