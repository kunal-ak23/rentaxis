import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string, vars?: Record<string, string | number>) => {
        if (!vars) return key;
        let out = key;
        for (const [k, v] of Object.entries(vars)) out = out.replaceAll(`{${k}}`, String(v));
        return out;
    },
    useLocale: () => "en",
}));

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: null }),
}));

import RenterPaymentsPage from "../page";

const samplePayments = [
    {
        id: "p1",
        installmentNumber: 3,
        dueDate: "2026-06-01",
        amount: 12500,
        status: "PENDING",
        chequeNumber: "CHQ-3",
        propertyName: "Belle Vue",
        unitIdentifier: "A-204",
        renterName: "Tenant",
        leaseId: "l1",
        penaltyAmount: 0,
        totalPayable: 12500,
        daysOverdue: 0,
        gracePeriodDays: 5,
        statusChangedAt: null,
        failureReason: null,
    },
    {
        id: "p2",
        installmentNumber: 2,
        dueDate: "2026-04-01",
        amount: 12500,
        status: "DEPOSITED",
        chequeNumber: "CHQ-2",
        propertyName: "Belle Vue",
        unitIdentifier: "A-204",
        renterName: "Tenant",
        leaseId: "l1",
        penaltyAmount: 0,
        totalPayable: 12500,
        daysOverdue: 0,
        gracePeriodDays: 5,
        statusChangedAt: "2026-04-03T10:00:00Z",
        failureReason: null,
    },
];

beforeEach(() => {
    global.fetch = vi.fn(async (url: any) => {
        const u = String(url);
        if (u.includes("my-payments")) {
            return { ok: true, json: async () => samplePayments } as Response;
        }
        return { ok: true, json: async () => null } as Response;
    }) as any;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("RenterPaymentsPage", () => {
    it("renders the next-cheque hero with the earliest PENDING amount", async () => {
        render(<RenterPaymentsPage />);
        await waitFor(() => expect(screen.getAllByText(/AED 12,500/i).length).toBeGreaterThan(0));
        expect(screen.getByText(/nextChequeDue/i)).toBeTruthy();
    });

    it("shows the Deposited on subtitle for DEPOSITED rows", async () => {
        render(<RenterPaymentsPage />);
        await waitFor(() => expect(screen.getAllByText(/depositedOn/).length).toBeGreaterThan(0));
    });

    it("never renders a Pay Now button", async () => {
        render(<RenterPaymentsPage />);
        await waitFor(() => expect(screen.getByText(/nextChequeDue/i)).toBeTruthy());
        expect(screen.queryByText(/payNow/i)).toBeNull();
    });
});
