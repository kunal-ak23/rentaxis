import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const toDeposit = vi.fn();
const summary = vi.fn();
vi.mock("@/lib/api/leasing", () => ({ chequeApi: { toDeposit: (...a: unknown[]) => toDeposit(...a), summary: (...a: unknown[]) => summary(...a) } }));
vi.mock("@/lib/api/ledger", () => ({ ledgerApi: { fiscal: { get: async () => ({ booksStartDate: null, booksLockedThrough: null }) } } }));

import { act } from "@testing-library/react";
import { NAV_COUNTS_MAX_AGE_MS, NAV_COUNTS_STALE_EVENT, useNavCounts } from "../useNavCounts";

let now = 1_000_000;
beforeEach(() => {
    now = 1_000_000;
    vi.spyOn(Date, "now").mockImplementation(() => now);
    toDeposit.mockResolvedValue({ totalElements: 3 });
    summary.mockResolvedValue({ overdueCount: 2 });
});
afterEach(() => { vi.restoreAllMocks(); toDeposit.mockReset(); summary.mockReset(); });

describe("useNavCounts (PR #363 R1 P3): badges refresh within a session", () => {
    it("re-reads on a navigation once the counts are stale, and not before", async () => {
        const { result, rerender } = renderHook(({ path }) => useNavCounts("TENANT_ADMIN", path), { initialProps: { path: "/en/dashboard" } });
        await waitFor(() => expect(result.current.collectionBadge).toBe(5));
        expect(toDeposit).toHaveBeenCalledTimes(1);

        rerender({ path: "/en/dashboard/leases" });
        expect(toDeposit).toHaveBeenCalledTimes(1);

        toDeposit.mockResolvedValue({ totalElements: 1 });
        now += NAV_COUNTS_MAX_AGE_MS + 1;
        rerender({ path: "/en/dashboard/collections" });
        await waitFor(() => expect(result.current.collectionBadge).toBe(3));
        expect(toDeposit).toHaveBeenCalledTimes(2);
    });

    it("keeps a read that was in flight when the page changed", async () => {
        let resolve!: (v: { totalElements: number }) => void;
        toDeposit.mockReturnValue(new Promise(r => { resolve = r; }));
        const { result, rerender } = renderHook(({ path }) => useNavCounts("TENANT_ADMIN", path), { initialProps: { path: "/a" } });
        rerender({ path: "/b" });
        resolve({ totalElements: 4 });
        await waitFor(() => expect(result.current.chequesToDeposit).toBe(4));
    });

    it("re-reads at once when a page says the counts are stale (R4 money4 F4)", async () => {
        const { result } = renderHook(() => useNavCounts("TENANT_ADMIN", "/en/dashboard/finance/fiscal"));
        await waitFor(() => expect(result.current.collectionBadge).toBe(5));
        toDeposit.mockResolvedValue({ totalElements: 0 });
        // Well inside NAV_COUNTS_MAX_AGE_MS: only the event can make it read again.
        act(() => { window.dispatchEvent(new Event(NAV_COUNTS_STALE_EVENT)); });
        await waitFor(() => expect(result.current.collectionBadge).toBe(2));
    });
});
