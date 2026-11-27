import { afterEach, describe, expect, it, vi } from "vitest";
import { lookupApi } from "../lookup";

afterEach(() => vi.restoreAllMocks());

// QA-37 review 2: the widened unit picker drops MAINTENANCE on the server, so a limited page stays full.
describe("lookupApi.searchUnits excludeStatus", () => {
    it("sends excludeStatus only when given", async () => {
        const fetchMock = vi.fn(async () => new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } }));
        global.fetch = fetchMock as unknown as typeof fetch;
        await lookupApi.searchUnits({ q: "1", excludeStatus: "MAINTENANCE", limit: 20 });
        await lookupApi.searchUnits({ q: "1", status: "VACANT", limit: 20 });
        const urls = fetchMock.mock.calls.map(c => String((c as unknown[])[0]));
        expect(urls[0]).toContain("excludeStatus=MAINTENANCE");
        expect(urls[0]).not.toContain("status=VACANT");
        expect(urls[1]).toContain("status=VACANT");
        expect(urls[1]).not.toContain("excludeStatus");
    });
});
