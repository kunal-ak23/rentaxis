import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

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
vi.mock("@/components/payments/MarkChequeFailedDialog", () => ({ MarkChequeFailedDialog: () => null }));
vi.mock("@/components/cheques/ChequeScanner", () => ({ default: () => null }));
vi.mock("@/components/ui/confirm-dialog", () => ({ ConfirmDialog: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));

import PaymentsPage from "../page";

let fetchCalls: string[] = [];

beforeEach(() => {
    fetchCalls = [];
    global.fetch = vi.fn(async (url: unknown) => {
        const u = String(url);
        fetchCalls.push(u);
        if (u.includes("/properties")) return { ok: true, json: async () => [] } as Response;
        if (u.includes("/payments/summary")) return { ok: true, json: async () => ({}) } as Response;
        return { ok: true, json: async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0 }) } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    h.status = null;
});

const listCalls = () => fetchCalls.filter((u) => u.includes("/v1/payments"));

describe("PaymentsPage to-deposit filter", () => {
    it("deep-links ?status=TO_DEPOSIT to the dedicated /payments/to-deposit endpoint", async () => {
        h.status = "TO_DEPOSIT";
        render(<PaymentsPage />);
        await waitFor(() => {
            expect(listCalls().some((u) => u.includes("/payments/to-deposit"))).toBe(true);
        });
    });

    it("selecting 'To deposit' in the dropdown queries the dedicated endpoint", async () => {
        render(<PaymentsPage />);
        await waitFor(() => expect(listCalls().length).toBeGreaterThan(0));

        const statusSelect = screen
            .getAllByRole("combobox")
            .find((s) => Array.from((s as HTMLSelectElement).options).some((o) => o.value === "TO_DEPOSIT")) as HTMLSelectElement;
        expect(statusSelect).toBeTruthy();

        fetchCalls = [];
        fireEvent.change(statusSelect, { target: { value: "TO_DEPOSIT" } });

        await waitFor(() => {
            expect(listCalls().some((u) => u.includes("/payments/to-deposit"))).toBe(true);
        });
    });
});
