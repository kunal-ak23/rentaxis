import { afterEach, describe, expect, it, vi } from "vitest";
import { reportsApi } from "../reports";

const realFetch = global.fetch;
afterEach(() => { global.fetch = realFetch; });

describe("reportsApi.tenantLedger", () => {
    const q = { from: "2026-09-01", to: "2027-09-30", accountIds: ["a1", "a2"], onlyWithBalance: true, hideReversed: false };

    it("builds the file URL through the proxy with the filters and the language", () => {
        expect(reportsApi.tenantLedger.fileUrl("xlsx", q, "ar")).toBe(
            "/api/proxy/v1/finance/reports/tenant-ledger.xlsx?from=2026-09-01&to=2027-09-30&accountIds=a1%2Ca2&onlyWithBalance=true&lang=ar");
        expect(reportsApi.tenantLedger.fileUrl("pdf", { from: "2026-09-01", to: "2027-09-30", propertyId: "p1", accountIds: [] }, "en")).toBe(
            "/api/proxy/v1/finance/reports/tenant-ledger.pdf?from=2026-09-01&to=2027-09-30&propertyId=p1&lang=en");
    });

    it("asks for one page of the grid and for the receivable picker", async () => {
        const fetchMock = vi.fn<(url: string) => Promise<Response>>(async () => new Response("[]", { status: 200 }));
        global.fetch = fetchMock as unknown as typeof fetch;
        await reportsApi.tenantLedger.get(q, 0, "en");
        expect(fetchMock.mock.calls[0][0]).toBe(
            "/api/proxy/v1/finance/reports/tenant-ledger?from=2026-09-01&to=2027-09-30&accountIds=a1%2Ca2&onlyWithBalance=true&page=0&lang=en");
        await reportsApi.tenantLedger.accounts("p1");
        expect(fetchMock.mock.calls[1][0]).toBe("/api/proxy/v1/finance/reports/tenant-ledger/accounts?propertyId=p1");
        await reportsApi.tenantLedger.accounts();
        expect(fetchMock.mock.calls[2][0]).toBe("/api/proxy/v1/finance/reports/tenant-ledger/accounts");
    });
});
