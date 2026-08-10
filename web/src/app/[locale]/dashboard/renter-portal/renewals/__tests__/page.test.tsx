import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", () => {
    const translate = (key: string, vars?: Record<string, string | number>) => {
        if (!vars) return key;
        let out = key;
        for (const [k, v] of Object.entries(vars)) out = out.replaceAll(`{${k}}`, String(v));
        return out;
    };
    return {
        useTranslations: () => translate,
        useLocale: () => "en",
    };
});

import RenterRenewalsPage from "../page";

const lease = {
    leaseId: "22222222-2222-2222-2222-222222222222",
    unitNumber: "A-101",
    propertyNameEn: "Marina Tower",
    endDate: "2026-09-01",
    daysRemaining: 20,
    opportunityId: "33333333-3333-3333-3333-333333333333",
    stage: "OPEN",
    intent: null,
    reminders: [],
};

let summaryResponse: { ok: boolean; status: number; body: unknown };
let intentResponse: { ok: boolean; status: number; body: unknown };

beforeEach(() => {
    summaryResponse = { ok: true, status: 200, body: { leases: [lease] } };
    intentResponse = { ok: true, status: 200, body: { opportunityId: lease.opportunityId, intent: "RENEW", stage: "INTENT_CAPTURED" } };
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.includes("/intent") && init?.method === "POST") {
            return {
                ok: intentResponse.ok,
                status: intentResponse.status,
                json: async () => intentResponse.body,
            } as unknown as Response;
        }
        return {
            ok: summaryResponse.ok,
            status: summaryResponse.status,
            json: async () => summaryResponse.body,
        } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("RenterRenewalsPage load error state", () => {
    it("renders an error with retry instead of empty buckets when the fetch fails", async () => {
        summaryResponse = { ok: false, status: 500, body: {} };
        render(<RenterRenewalsPage />);

        await screen.findByText("loadFailed");
        expect(screen.getByText("retry")).toBeTruthy();
        expect(screen.queryByText("emptyBucket")).toBeNull();
    });

    it("recovers and renders data after clicking retry", async () => {
        summaryResponse = { ok: false, status: 500, body: {} };
        render(<RenterRenewalsPage />);

        await screen.findByText("loadFailed");
        summaryResponse = { ok: true, status: 200, body: { leases: [lease] } };
        fireEvent.click(screen.getByText("retry"));

        await screen.findByText("Marina Tower · A-101");
        expect(screen.queryByText("loadFailed")).toBeNull();
    });

    it("renders empty buckets (not the error state) when the renter has no renewals", async () => {
        summaryResponse = { ok: true, status: 200, body: { leases: [] } };
        render(<RenterRenewalsPage />);

        const empties = await screen.findAllByText("emptyBucket");
        expect(empties.length).toBe(4);
        expect(screen.queryByText("loadFailed")).toBeNull();
    });
});

describe("RenterRenewalsPage setIntent error surfacing", () => {
    it("shows intentAlreadyResolved when the backend rejects with 400", async () => {
        intentResponse = {
            ok: false,
            status: 400,
            body: { error: true, message: "Renewal is already resolved", status: 400 },
        };
        render(<RenterRenewalsPage />);

        fireEvent.click(await screen.findByText("renew"));

        await screen.findByText("intentAlreadyResolved");
    });

    it("shows genericError on a 404 (stale opportunity)", async () => {
        intentResponse = {
            ok: false,
            status: 404,
            body: { error: true, message: "Opportunity not found", status: 404 },
        };
        render(<RenterRenewalsPage />);

        fireEvent.click(await screen.findByText("renew"));

        await screen.findByText("genericError");
    });

    it("shows no error and reloads on success", async () => {
        render(<RenterRenewalsPage />);

        fireEvent.click(await screen.findByText("renew"));

        const fetchMock = global.fetch as ReturnType<typeof vi.fn>;
        await waitFor(() => {
            expect(fetchMock.mock.calls.some((c) => String(c[0]).includes("/intent"))).toBe(true);
            // initial load + reload after intent
            const summaryGets = fetchMock.mock.calls.filter((c) => !String(c[0]).includes("/intent"));
            expect(summaryGets.length).toBeGreaterThanOrEqual(2);
        });
        expect(screen.queryByText("intentAlreadyResolved")).toBeNull();
        expect(screen.queryByText("genericError")).toBeNull();
    });
});
