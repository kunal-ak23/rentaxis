import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
    useSearchParams: () => ({ get: () => null }),
}));
vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));
vi.mock("@/components/payments/MarkChequeFailedDialog", () => ({ MarkChequeFailedDialog: () => null }));
vi.mock("@/components/cheques/ChequeScanner", () => ({ default: () => null }));
vi.mock("@/components/ui/confirm-dialog", () => ({ ConfirmDialog: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));

import PaymentsPage from "../page";

const collectedPayment = {
    id: "11111111-1111-1111-1111-111111111111",
    installmentNumber: 1,
    dueDate: "2026-01-01",
    amount: 5000,
    status: "COLLECTED",
    chequeNumber: "CHQ-1",
    bankName: "Bank",
    payerName: "Payer",
    chequeDate: null,
    chequeImageUrl: null,
    chequeImageBlobPath: null,
    propertyName: "Tower A",
    unitIdentifier: "101",
    renterName: "Renter",
    leaseId: "22222222-2222-2222-2222-222222222222",
};

let depositResponse: { ok: boolean; body: unknown };

beforeEach(() => {
    depositResponse = { ok: true, body: {} };
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.includes("/deposit")) {
            return {
                ok: depositResponse.ok,
                status: depositResponse.ok ? 200 : 400,
                json: async () => depositResponse.body,
            } as Response;
        }
        if (u.includes("/properties")) return { ok: true, json: async () => [] } as Response;
        if (u.includes("/payments/summary")) return { ok: true, json: async () => ({}) } as Response;
        void init;
        return {
            ok: true,
            json: async () => ({ content: [collectedPayment], totalElements: 1, totalPages: 1, number: 0 }),
        } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("PaymentsPage deposit error surfacing", () => {
    it("shows the backend message when deposit is rejected with 400 {message}", async () => {
        depositResponse = { ok: false, body: { message: "Can only deposit payments in COLLECTED status" } };
        render(<PaymentsPage />);

        const depositBtn = await screen.findByText("deposit");
        fireEvent.click(depositBtn);

        await waitFor(() => {
            expect(screen.getByText("Can only deposit payments in COLLECTED status")).toBeTruthy();
        });
    });

    it("falls back to the generic actionFailed string when the error body is empty", async () => {
        depositResponse = { ok: false, body: {} };
        render(<PaymentsPage />);

        const depositBtn = await screen.findByText("deposit");
        fireEvent.click(depositBtn);

        await waitFor(() => {
            expect(screen.getByText("actionFailed")).toBeTruthy();
        });
    });

    it("shows no error banner when deposit succeeds", async () => {
        render(<PaymentsPage />);

        const depositBtn = await screen.findByText("deposit");
        fireEvent.click(depositBtn);

        await waitFor(() => {
            expect((global.fetch as ReturnType<typeof vi.fn>).mock.calls.some((c) => String(c[0]).includes("/deposit"))).toBe(true);
        });
        expect(screen.queryByText("actionFailed")).toBeNull();
    });
});
