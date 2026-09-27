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

    it("unitNames chunks 450 ids into 6 requests of at most 80 repeated ids and concatenates", async () => {
        const ids = Array.from({ length: 450 }, (_, i) => `id-${i}`);
        const fetchMock = vi.fn(async (url: string) => {
            const sent = new URL(url, "http://x").searchParams.getAll("ids");
            return ok(sent.map((id) => ({ id, unitNumber: id })));
        });
        global.fetch = fetchMock as unknown as typeof fetch;

        const { rows, failedIds } = await lookupApi.unitNames(ids);

        expect(fetchMock).toHaveBeenCalledTimes(6);
        const sizes = fetchMock.mock.calls.map(([url]) => new URL(url, "http://x").searchParams.getAll("ids").length);
        expect(sizes.every((n) => n <= 80)).toBe(true);
        expect(sizes).toEqual([80, 80, 80, 80, 80, 50]);
        expect(fetchMock.mock.calls.every(([url]) => url.startsWith("/api/proxy/v1/units/names?ids="))).toBe(true);
        expect(rows.map((r) => r.id)).toEqual(ids);
        expect(failedIds).toEqual([]);
    });

    it("keeps the longest names URL the client can produce under 4096 characters", async () => {
        // 80 real-length UUIDs (36 chars each): the largest single chunk unitNames
        // or renterNames will ever send. Guards the 8 KB request-line/header budget
        // (see the NAMES_CHUNK comment in lib/api/lookup.ts) from the client side.
        const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
        const ids = Array.from({ length: 80 }, (_, i) => uuid(i));
        const fetchMock = vi.fn(async () => ok([]));
        global.fetch = fetchMock as unknown as typeof fetch;

        await lookupApi.unitNames(ids);

        expect(fetchMock).toHaveBeenCalledTimes(1);
        const url = (fetchMock.mock.calls[0] as unknown[])[0] as string;
        expect(url.length).toBeLessThan(4096);
    });

    it("one failed chunk does not lose the rows of the others", async () => {
        const ok80 = Array.from({ length: 80 }, (_, i) => `ok-${i}`);
        const bad80 = Array.from({ length: 80 }, (_, i) => `bad-${i}`);
        const fetchMock = vi.fn(async (url: string) => {
            if (url.includes("bad-0")) throw new Error("network error");
            const sent = new URL(url, "http://x").searchParams.getAll("ids");
            return ok(sent.map((id) => ({ id, unitNumber: id })));
        });
        global.fetch = fetchMock as unknown as typeof fetch;

        const { rows, failedIds } = await lookupApi.unitNames([...ok80, ...bad80]);

        expect(fetchMock).toHaveBeenCalledTimes(2);
        expect(rows.map((r) => r.id)).toEqual(ok80);
        expect(failedIds).toEqual(bad80);
    });

    it("renterNames makes no request for an empty id list", async () => {
        const fetchMock = vi.fn();
        global.fetch = fetchMock as unknown as typeof fetch;

        expect(await lookupApi.renterNames([])).toEqual({ rows: [], failedIds: [] });
        expect(fetchMock).not.toHaveBeenCalled();
    });

    it("renterNames uses the renter names endpoint", async () => {
        const fetchMock = vi.fn(async () => ok([{ id: "r1", nameEn: "Ali" }]));
        global.fetch = fetchMock as unknown as typeof fetch;

        const { rows } = await lookupApi.renterNames(["r1", "r2"]);

        expect((fetchMock.mock.calls[0] as unknown[])[0]).toBe("/api/proxy/v1/renters/names?ids=r1&ids=r2");
        expect(rows).toHaveLength(1);
    });
});
