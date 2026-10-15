import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

// Tutorial 15: the pills kept their page-load numbers after a deposit, bounce or
// replace. A successful cheque action announces itself; the pills read again.
const api = vi.hoisted(() => ({
    toDeposit: vi.fn(), summary: vi.fn(), penalties: vi.fn(), deposit: vi.fn(),
}));
vi.mock("@/lib/api/leasing", async () => {
    const { announcingChange } = await import("@/lib/countsStale");
    return {
        chequeApi: {
            toDeposit: api.toDeposit, summary: api.summary,
            deposit: (id: string) => announcingChange(api.deposit(id)),
        },
        penaltyApi: { list: api.penalties },
    };
});

import { usePillCounts } from "../usePillCounts";
import { chequeApi } from "@/lib/api/leasing";

afterEach(() => vi.clearAllMocks());

describe("usePillCounts", () => {
    it("re-reads the counts after a cheque action succeeds", async () => {
        api.toDeposit.mockResolvedValueOnce({ totalElements: 3 }).mockResolvedValueOnce({ totalElements: 2 });
        api.summary.mockResolvedValue({ dueCount: 1, overdueCount: 0, bouncedCount: 0 });
        api.penalties.mockResolvedValue({ totalElements: 0 });
        api.deposit.mockResolvedValue({ id: "c1" });
        const { result } = renderHook(() => usePillCounts("TENANT_ADMIN", ""));
        await waitFor(() => expect(result.current.deposit).toBe(3));
        await act(async () => { await (chequeApi as unknown as { deposit: (id: string) => Promise<unknown> }).deposit("c1"); });
        await waitFor(() => expect(result.current.deposit).toBe(2));
        expect(api.toDeposit).toHaveBeenCalledTimes(2);
    });

    it("does not re-read when the action fails", async () => {
        api.toDeposit.mockResolvedValue({ totalElements: 3 });
        api.summary.mockResolvedValue({ dueCount: 1, overdueCount: 0, bouncedCount: 0 });
        api.penalties.mockResolvedValue({ totalElements: 0 });
        api.deposit.mockRejectedValue(new Error("no"));
        const { result } = renderHook(() => usePillCounts("TENANT_ADMIN", ""));
        await waitFor(() => expect(result.current.deposit).toBe(3));
        await act(async () => { await (chequeApi as unknown as { deposit: (id: string) => Promise<unknown> }).deposit("c1").catch(() => {}); });
        expect(api.toDeposit).toHaveBeenCalledTimes(1);
    });
});
