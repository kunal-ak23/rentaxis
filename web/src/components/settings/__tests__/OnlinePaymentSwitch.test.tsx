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

describe("OnlinePaymentSwitch — a late response for the previous property (PR #363 R1)", () => {
    it("ignores A's row when it lands after B was picked, and saves B's own row to B", async () => {
        const pending: Record<string, (body: unknown) => void> = {};
        global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
            const url = String(input);
            if (url.endsWith("/v1/properties")) {
                return Promise.resolve({ ok: true, status: 200, json: async () => [
                    { property: { id: "pA", nameEn: "Alpha" } }, { property: { id: "pB", nameEn: "Bravo" } }] } as unknown as Response);
            }
            if (init?.method === "POST") {
                posted.push({ url, ...JSON.parse(String(init.body)) });
                return Promise.resolve({ ok: true, status: 200, json: async () => ({}) } as unknown as Response);
            }
            const id = url.split("/").pop()!;
            return new Promise(resolve => {
                pending[id] = body => resolve({ ok: true, status: 200, json: async () => body } as unknown as Response);
            });
        }) as unknown as typeof fetch;

        render(<OnlinePaymentSwitch />);
        await screen.findByText("Bravo");
        fireEvent.change(screen.getByRole("combobox"), { target: { value: "pA" } });
        fireEvent.change(screen.getByRole("combobox"), { target: { value: "pB" } });
        // B answers first, then A's slow response arrives.
        pending.pB({ propertyId: "pB", onlinePaymentEnabled: false, dueDayOfMonth: 2, penaltyType: "NONE", penaltyAmount: 0 });
        const sw = await screen.findByRole("switch");
        pending.pA({ propertyId: "pA", onlinePaymentEnabled: true, dueDayOfMonth: 28, penaltyType: "PERCENTAGE", penaltyAmount: 9 });
        await new Promise(r => setTimeout(r, 0));
        expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "false");

        fireEvent.click(sw);
        await waitFor(() => expect(posted).toHaveLength(1));
        expect(posted[0]).toMatchObject({ url: "/api/proxy/v1/rent-settings/pB", dueDayOfMonth: 2, penaltyType: "NONE", onlinePaymentEnabled: true });
    });

    it("shows no switch (so nothing can be saved) while the selected property's row is still loading", async () => {
        global.fetch = vi.fn((input: RequestInfo | URL) => {
            const url = String(input);
            if (url.endsWith("/v1/properties")) {
                return Promise.resolve({ ok: true, status: 200, json: async () => [{ property: { id: "pA", nameEn: "Alpha" } }] } as unknown as Response);
            }
            return new Promise(() => {});
        }) as unknown as typeof fetch;
        render(<OnlinePaymentSwitch />);
        await screen.findByText("Alpha");
        fireEvent.change(screen.getByRole("combobox"), { target: { value: "pA" } });
        expect(screen.queryByRole("switch")).toBeNull();
    });
});
