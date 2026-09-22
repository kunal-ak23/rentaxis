import { describe, it, expect, vi, beforeEach } from "vitest";
import { ledgerApi, fmtAmount, fmtBalance } from "../ledger";

describe("ledger formatters", () => {
  it("formats amounts with two decimals and thousands separators", () => {
    expect(fmtAmount(61000)).toBe("61,000.00");
    expect(fmtAmount(978.082)).toBe("978.08");
  });
  it("formats signed balances as Dr/Cr", () => {
    expect(fmtBalance(61000)).toBe("61,000.00 Dr");
    expect(fmtBalance(-3000)).toBe("3,000.00 Cr");
    expect(fmtBalance(0)).toBe("0.00");
  });
});

describe("ledgerApi urls", () => {
  beforeEach(() => { vi.stubGlobal("fetch", vi.fn(async () => new Response("[]", { status: 200, headers: { "Content-Type": "application/json" } }))); });
  it("builds the general ledger query", async () => {
    await ledgerApi.ledger.general({ accountIds: ["a", "b"], from: "2026-09-01", to: "2026-09-30", propertyId: "p" });
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/ledger?accountIds=a%2Cb&from=2026-09-01&to=2026-09-30&propertyId=p", expect.anything());
  });
  it("omits undefined params", async () => {
    await ledgerApi.trialBalance({ asOf: "2026-09-30" });
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/finance/trial-balance?asOf=2026-09-30", expect.anything());
  });
});
