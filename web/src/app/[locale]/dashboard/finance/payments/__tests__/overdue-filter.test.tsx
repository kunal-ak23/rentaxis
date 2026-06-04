import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Mutable holder for the ?status= search param, set per test (vi.mock is hoisted).
const h = vi.hoisted(() => ({ status: null as string | null }));

vi.mock("next/navigation", () => ({
    useSearchParams: () => ({ get: (k: string) => (k === "status" ? h.status : null) }),
}));
vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));
// Heavy children are irrelevant to the filter logic — stub them out.
vi.mock("@/components/payments/MarkChequeFailedDialog", () => ({ MarkChequeFailedDialog: () => null }));
vi.mock("@/components/cheques/ChequeScanner", () => ({ default: () => null }));
vi.mock("@/components/ui/confirm-dialog", () => ({ ConfirmDialog: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));

import PaymentsPage from "../page";

let fetchCalls: string[] = [];

function emptySummary() {
    return {
        totalPayments: 0, pendingCount: 0, collectedCount: 0, depositedCount: 0,
        clearedCount: 0, bouncedCount: 0, overdueCount: 0, totalAmount: 0,
        pendingAmount: 0, collectedAmount: 0, clearedAmount: 0, overdueAmount: 0,
    };
}

beforeEach(() => {
    fetchCalls = [];
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        fetchCalls.push(u);
        if (u.includes("/properties")) return { ok: true, json: async () => [] } as Response;
        if (u.includes("/payments/summary")) return { ok: true, json: async () => emptySummary() } as Response;
        return { ok: true, json: async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0 }) } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    h.status = null;
});

const paymentsListCalls = () => fetchCalls.filter((u) => u.includes("/v1/payments?"));

describe("PaymentsPage overdue filter", () => {
    it("deep-links ?status=OVERDUE into an overdue=true API query (not a literal status)", async () => {
        h.status = "OVERDUE";
        render(<PaymentsPage />);

        await waitFor(() => {
            expect(paymentsListCalls().some((u) => u.includes("overdue=true"))).toBe(true);
        });
        expect(fetchCalls.some((u) => u.includes("status=OVERDUE"))).toBe(false);
    });

    it("selecting Overdue in the status dropdown queries overdue=true", async () => {
        render(<PaymentsPage />);
        await waitFor(() => expect(paymentsListCalls().length).toBeGreaterThan(0));

        const statusSelect = screen
            .getAllByRole("combobox")
            .find((s) => Array.from((s as HTMLSelectElement).options).some((o) => o.value === "OVERDUE")) as HTMLSelectElement;
        expect(statusSelect).toBeTruthy();

        fetchCalls = [];
        fireEvent.change(statusSelect, { target: { value: "OVERDUE" } });

        await waitFor(() => {
            expect(paymentsListCalls().some((u) => u.includes("overdue=true"))).toBe(true);
        });
    });

    it("a normal status filter sends status= and not overdue=true", async () => {
        render(<PaymentsPage />);
        await waitFor(() => expect(paymentsListCalls().length).toBeGreaterThan(0));

        const statusSelect = screen
            .getAllByRole("combobox")
            .find((s) => Array.from((s as HTMLSelectElement).options).some((o) => o.value === "OVERDUE")) as HTMLSelectElement;

        fetchCalls = [];
        fireEvent.change(statusSelect, { target: { value: "PENDING" } });

        await waitFor(() => {
            expect(paymentsListCalls().some((u) => u.includes("status=PENDING"))).toBe(true);
        });
        expect(paymentsListCalls().some((u) => u.includes("overdue=true"))).toBe(false);
    });
});
