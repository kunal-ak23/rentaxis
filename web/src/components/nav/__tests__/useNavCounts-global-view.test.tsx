import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Cookies from "js-cookie";

const toDeposit = vi.fn();
const summary = vi.fn();
const fiscalGet = vi.fn();
vi.mock("@/lib/api/leasing", () => ({ chequeApi: { toDeposit: (...a: unknown[]) => toDeposit(...a), summary: (...a: unknown[]) => summary(...a) } }));
vi.mock("@/lib/api/ledger", () => ({ ledgerApi: { fiscal: { get: (...a: unknown[]) => fiscalGet(...a) } } }));

import { useNavCounts } from "../useNavCounts";

/**
 * Break round 1, F7: a super admin with no organisation selected (Global View)
 * fired GET /finance/fiscal-settings (500 without a tenant) and the cheque
 * counts (aggregated across every organisation) on the first render.
 */
beforeEach(() => {
    toDeposit.mockResolvedValue({ totalElements: 3 });
    summary.mockResolvedValue({ overdueCount: 2 });
    fiscalGet.mockResolvedValue({ booksStartDate: null, booksLockedThrough: null });
});
afterEach(() => { vi.clearAllMocks(); Cookies.remove("active_tenant_id", { path: "/" }); });

describe("useNavCounts in Global View (F7)", () => {
    it("makes no org-scoped call for a super admin with no organisation selected", async () => {
        const { result } = renderHook(() => useNavCounts("SUPER_ADMIN", "/en/superadmin/tenants"));
        await new Promise(r => setTimeout(r, 0));
        expect(fiscalGet).not.toHaveBeenCalled();
        expect(toDeposit).not.toHaveBeenCalled();
        expect(summary).not.toHaveBeenCalled();
        expect(result.current.collectionBadge).toBeNull();
    });

    it("reads the counts once the super admin has an organisation", async () => {
        Cookies.set("active_tenant_id", "brk1", { path: "/" });
        const { result } = renderHook(() => useNavCounts("SUPER_ADMIN", "/en/dashboard"));
        await waitFor(() => expect(result.current.collectionBadge).toBe(5));
        expect(fiscalGet).toHaveBeenCalledTimes(1);
    });
});
