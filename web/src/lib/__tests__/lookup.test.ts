import { afterEach, describe, expect, it, vi } from "vitest";
import { lookupApi } from "@/lib/api/lookup";

const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

afterEach(() => {
    vi.restoreAllMocks();
});

describe("lookupApi", () => {
    it("searchUnits hits the proxied search endpoint and omits empty params", async () => {
        const fetchMock = vi.fn(async () => ok([{ id: "u1", unitNumber: "07" }]));
        global.fetch = fetchMock as unknown as typeof fetch;

        const rows = await lookupApi.searchUnits({ q: "07", propertyId: "", status: "VACANT", limit: 20 });

        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect((fetchMock.mock.calls[0] as unknown[])[0]).toBe("/api/proxy/v1/units/search?q=07&status=VACANT&limit=20");
        expect(rows).toEqual([{ id: "u1", unitNumber: "07" }]);
    });

    it("searchRenters hits the renter search endpoint", async () => {
        const fetchMock = vi.fn(async () => ok([]));
        global.fetch = fetchMock as unknown as typeof fetch;

        await lookupApi.searchRenters({ q: "ali" });

        expect((fetchMock.mock.calls[0] as unknown[])[0]).toBe("/api/proxy/v1/renters/search?q=ali");
    });

    it("unitNames chunks 450 ids into 3 requests of at most 200 repeated ids and concatenates", async () => {
        const ids = Array.from({ length: 450 }, (_, i) => `id-${i}`);
        const fetchMock = vi.fn(async (url: string) => {
            const sent = new URL(url, "http://x").searchParams.getAll("ids");
            return ok(sent.map((id) => ({ id, unitNumber: id })));
        });
        global.fetch = fetchMock as unknown as typeof fetch;

        const rows = await lookupApi.unitNames(ids);

        expect(fetchMock).toHaveBeenCalledTimes(3);
        const sizes = fetchMock.mock.calls.map(([url]) => new URL(url, "http://x").searchParams.getAll("ids").length);
        expect(sizes).toEqual([200, 200, 50]);
        expect(fetchMock.mock.calls.every(([url]) => url.startsWith("/api/proxy/v1/units/names?ids="))).toBe(true);
        expect(rows.map((r) => r.id)).toEqual(ids);
    });

    it("renterNames makes no request for an empty id list", async () => {
        const fetchMock = vi.fn();
        global.fetch = fetchMock as unknown as typeof fetch;

        expect(await lookupApi.renterNames([])).toEqual([]);
        expect(fetchMock).not.toHaveBeenCalled();
    });

    it("renterNames uses the renter names endpoint", async () => {
        const fetchMock = vi.fn(async () => ok([{ id: "r1", nameEn: "Ali" }]));
        global.fetch = fetchMock as unknown as typeof fetch;

        const rows = await lookupApi.renterNames(["r1", "r2"]);

        expect((fetchMock.mock.calls[0] as unknown[])[0]).toBe("/api/proxy/v1/renters/names?ids=r1&ids=r2");
        expect(rows).toHaveLength(1);
    });
});
