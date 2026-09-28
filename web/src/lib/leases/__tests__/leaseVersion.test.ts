import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/facilities";
import { isLeaseChanged } from "../leaseVersion";
import { isContentionError } from "../bulkPost";
import { leaseApi } from "@/lib/api/leasing";

/**
 * Break-it round 2 (contracts2) F2/F3: the server refuses a write naming a lease
 * version it has moved past with 409 `lease.changed`. That is "review it again",
 * never contention to retry.
 */
const CHANGED = new ApiError(409, "This contract changed since you opened it — review it again",
    JSON.stringify({ error: true, status: 409, code: "lease.changed", message: "This contract changed since you opened it — review it again" }));

describe("isLeaseChanged", () => {
    it("is the 409 carrying code lease.changed, nothing else", () => {
        expect(isLeaseChanged(CHANGED)).toBe(true);
        expect(isLeaseChanged(new ApiError(409, "This record was being changed by another request at the same time. Please try again."))).toBe(false);
        expect(isLeaseChanged(new ApiError(400, "x", '{"code":"lease.changed"}'))).toBe(false);
        expect(isLeaseChanged(new ApiError(409, "x", "<html>"))).toBe(false);
        expect(isLeaseChanged(new Error("lease.changed"))).toBe(false);
    });

    it("is not retried by the bulk post as contention", () => {
        expect(isContentionError(CHANGED)).toBe(false);
    });
});

describe("leaseApi sends the version it was given", () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response("{}", { status: 200 }));
    afterEach(() => { fetchMock.mockClear(); vi.unstubAllGlobals(); });

    it("post: in the body; none when there is no version (older server's lease)", async () => {
        vi.stubGlobal("fetch", fetchMock);
        await leaseApi.post("L1", 7);
        expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "POST", body: JSON.stringify({ version: 7 }) });
        await leaseApi.post("L1");
        expect(fetchMock.mock.calls[1][1]!.body).toBeUndefined();
    });

    it("cheque grid and rent-free writes: as If-Match", async () => {
        vi.stubGlobal("fetch", fetchMock);
        await leaseApi.saveCheques("L1", [], 7);
        await leaseApi.generateCheques("L1", { installments: 4 } as never, 7);
        await leaseApi.setRentFreePeriods("L1", [], 7);
        for (const call of fetchMock.mock.calls) {
            expect(call[1]!.headers).toMatchObject({ "If-Match": "\"7\"" });
        }
        await leaseApi.saveCheques("L1", []);
        expect(fetchMock.mock.calls[3][1]!.headers).not.toHaveProperty("If-Match");
    });
});
