import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  chargeTypeApi,
  leaseApi,
  chequeApi,
  penaltyApi,
  onlinePayApi,
  ApiError,
} from "../leasing";

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({})));
});

function lastCall() {
  const mock = fetch as unknown as ReturnType<typeof vi.fn>;
  const [url, init] = mock.mock.calls[mock.mock.calls.length - 1];
  return { url: url as string, init: init as RequestInit | undefined };
}

// ---- query-string building ----

describe("chequeApi query building", () => {
  it("builds the URL in field order, skipping unset params (brief's worked example)", async () => {
    await chequeApi.list({ status: "REGISTERED", propertyId: "p" });
    expect(fetch).toHaveBeenCalledWith(
      "/api/proxy/v1/cheques?status=REGISTERED&propertyId=p",
      expect.anything(),
    );
  });

  it("omits undefined and empty-string params", async () => {
    await chequeApi.list({ propertyId: "p", mode: undefined, search: "" });
    const { url } = lastCall();
    expect(url).toBe("/api/proxy/v1/cheques?propertyId=p");
  });

  it("due() and toDeposit() build their own query", async () => {
    await chequeApi.due({ propertyId: "p1", asOf: "2026-09-19" });
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/cheques/due?propertyId=p1&asOf=2026-09-19", expect.anything());

    await chequeApi.toDeposit({});
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/cheques/to-deposit", expect.anything());
  });

  it("summary() and aging() accept the controller's optional asOf alongside propertyId", async () => {
    await chequeApi.summary("p1", "2026-09-19");
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/cheques/summary?propertyId=p1&asOf=2026-09-19", expect.anything());

    await chequeApi.aging();
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/cheques/aging", expect.anything());
  });

  it("postDated() builds propertyId/month", async () => {
    await chequeApi.postDated({ propertyId: "p1", month: "2026-09" });
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/cheques/post-dated?propertyId=p1&month=2026-09", expect.anything());
  });
});

// ---- URL / method / body per api object ----

describe("chargeTypeApi", () => {
  it("list() hits GET /finance/charge-types with activeOnly", async () => {
    await chargeTypeApi.list(true);
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/finance/charge-types?activeOnly=true");
    expect(init?.method ?? "GET").toBe("GET");
  });

  it("create() POSTs the body as JSON", async () => {
    const body = {
      id: "", code: "RENT", nameEn: "Rent", nameAr: null, role: "RENTAL_INCOME",
      behaviour: "RENT", vatApplicableDefault: false, active: true, displayOrder: 1,
    } as const;
    await chargeTypeApi.create(body as never);
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/finance/charge-types");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(init!.body as string)).toEqual(body);
  });

  it("update() PUTs to the id path", async () => {
    await chargeTypeApi.update("ct-1", { id: "ct-1" } as never);
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/finance/charge-types/ct-1");
    expect(init?.method).toBe("PUT");
  });
});

describe("leaseApi", () => {
  it("get() reads a single lease", async () => {
    await leaseApi.get("lease-1");
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/leases/lease-1", expect.anything());
  });

  it("createDraft() POSTs to /leases", async () => {
    const body = { unitId: "u1", renterId: "r1", startDate: "2026-01-01", endDate: "2026-12-31", lines: [] };
    await leaseApi.createDraft(body);
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/leases");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(init!.body as string)).toEqual(body);
  });

  it("updateDraft() PUTs to /leases/{id}", async () => {
    await leaseApi.updateDraft("lease-1", { unitId: "u1", renterId: "r1", startDate: "a", endDate: "b", lines: [] });
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/leases/lease-1");
    expect(init?.method).toBe("PUT");
  });

  it("post() POSTs with no dryRun param", async () => {
    await leaseApi.post("lease-1");
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/leases/lease-1/post");
    expect(init?.method).toBe("POST");
  });

  it("dryRunPost() hits ?dryRun=true", async () => {
    await leaseApi.dryRunPost("lease-1");
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/leases/lease-1/post?dryRun=true");
    expect(init?.method).toBe("POST");
  });

  it("amendLines() POSTs lines and reason", async () => {
    await leaseApi.amendLines("lease-1", { lines: [], reason: "correction" });
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/leases/lease-1/amend-lines");
    expect(JSON.parse(init!.body as string)).toEqual({ lines: [], reason: "correction" });
  });

  it("renew() POSTs to /renew", async () => {
    await leaseApi.renew("lease-1", { startDate: "a", endDate: "b", carryDepositForward: true });
    const { url } = lastCall();
    expect(url).toBe("/api/proxy/v1/leases/lease-1/renew");
  });

  it("extend() POSTs to /extend", async () => {
    await leaseApi.extend("lease-1", { newEndDate: "2027-01-01", lines: [], cheques: [] });
    const { url } = lastCall();
    expect(url).toBe("/api/proxy/v1/leases/lease-1/extend");
  });

  it("cheques() reads the lease's grid", async () => {
    await leaseApi.cheques("lease-1");
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/leases/lease-1/cheques", expect.anything());
  });

  it("generateCheques() POSTs the optional request body", async () => {
    await leaseApi.generateCheques("lease-1", { installments: 12 });
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/leases/lease-1/cheques/generate");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(init!.body as string)).toEqual({ installments: 12 });
  });

  it("generateChequeNumbers() wraps the starting number in an object (preserves zero-padding)", async () => {
    await leaseApi.generateChequeNumbers("lease-1", "000028");
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/leases/lease-1/cheques/numbers");
    expect(JSON.parse(init!.body as string)).toEqual({ startingNumber: "000028" });
  });

  it("saveCheques() PUTs the whole row list", async () => {
    await leaseApi.saveCheques("lease-1", [{ amount: 100 }]);
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/leases/lease-1/cheques");
    expect(init?.method).toBe("PUT");
  });

  it("journals() filters the plan-1 journals list by leaseId", async () => {
    await leaseApi.journals("lease-1");
    const { url } = lastCall();
    expect(url).toBe("/api/proxy/v1/finance/journals?leaseId=lease-1&page=0&size=25");
  });
});

describe("chequeApi lifecycle calls", () => {
  it("statsByLeases() POSTs a bare array of ids", async () => {
    await chequeApi.statsByLeases(["l1", "l2"]);
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/cheques/stats-by-leases");
    expect(JSON.parse(init!.body as string)).toEqual(["l1", "l2"]);
  });

  it("deposit()/clear()/receive() PUT to their verb", async () => {
    await chequeApi.deposit("c1", { date: "2026-09-19" });
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/cheques/c1/deposit", expect.objectContaining({ method: "PUT" }));

    await chequeApi.clear("c1");
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/cheques/c1/clear", expect.objectContaining({ method: "PUT" }));

    await chequeApi.receive("c1");
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/cheques/c1/receive", expect.objectContaining({ method: "PUT" }));
  });

  it("bounce() requires a failureReason in the body", async () => {
    await chequeApi.bounce("c1", { failureReason: "SIGNATURE_MISMATCH" });
    const { init } = lastCall();
    expect(JSON.parse(init!.body as string)).toEqual({ failureReason: "SIGNATURE_MISMATCH" });
  });

  it("depositBatch() POSTs to the batch endpoint", async () => {
    await chequeApi.depositBatch({ chequeIds: ["c1", "c2"] });
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/cheques/deposit-batch");
    expect(init?.method).toBe("POST");
  });

  it("replace() POSTs replacements to /{id}/replace", async () => {
    await chequeApi.replace("c1", { replacements: [{ amount: 50 }] });
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/cheques/c1/replace");
    expect(init?.method).toBe("POST");
  });

  it("cancel() PUTs to /{id}/cancel", async () => {
    await chequeApi.cancel("c1");
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/cheques/c1/cancel", expect.objectContaining({ method: "PUT" }));
  });

  it("updateDetails() PUTs a ChequeRowInput to /{id}/details", async () => {
    await chequeApi.updateDetails("c1", { amount: 100, chequeNumber: "5" });
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/cheques/c1/details");
    expect(init?.method).toBe("PUT");
  });

  it("cashReceipt() POSTs a row under the lease", async () => {
    await chequeApi.cashReceipt("lease-1", { amount: 500 });
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/cheques/lease/lease-1/cash-receipt");
    expect(init?.method).toBe("POST");
  });

  it("receiptUrl() returns the proxy path without calling fetch", () => {
    const before = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length;
    expect(chequeApi.receiptUrl("c1")).toBe("/api/proxy/v1/cheques/c1/receipt");
    expect((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length).toBe(before);
  });
});

describe("penaltyApi", () => {
  it("list() builds the query", async () => {
    await penaltyApi.list({ leaseId: "l1", status: "PROPOSED" });
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/penalties?leaseId=l1&status=PROPOSED", expect.anything());
  });

  it("propose() POSTs a ProposePenaltyRequest", async () => {
    await penaltyApi.propose({ leaseId: "l1", reason: "OTHER", amount: 100 });
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/penalties");
    expect(init?.method).toBe("POST");
  });

  it("approve()/waive()/reverse() POST decisions to their verb", async () => {
    await penaltyApi.approve("pa-1", "2026-09-19");
    expect(fetch).toHaveBeenCalledWith(
      "/api/proxy/v1/penalties/pa-1/approve",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ date: "2026-09-19" }) }),
    );

    await penaltyApi.waive("pa-1", "not our fault");
    expect(fetch).toHaveBeenCalledWith(
      "/api/proxy/v1/penalties/pa-1/waive",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ note: "not our fault" }) }),
    );

    await penaltyApi.reverse("pa-1", { date: "2026-09-19", note: "approved in error" });
    expect(fetch).toHaveBeenCalledWith(
      "/api/proxy/v1/penalties/pa-1/reverse",
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("mine() reads the renter's own penalties", async () => {
    await penaltyApi.mine();
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/penalties/mine", expect.anything());
  });
});

describe("onlinePayApi", () => {
  it("myPayments() reads the renter's payment rows", async () => {
    await onlinePayApi.myPayments();
    expect(fetch).toHaveBeenCalledWith("/api/proxy/v1/online-payments/my-payments", expect.anything());
  });

  it("createOrder() POSTs the chequeId", async () => {
    await onlinePayApi.createOrder("c1");
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/online-payments/create-order");
    expect(JSON.parse(init!.body as string)).toEqual({ chequeId: "c1" });
  });

  it("verify() POSTs the gateway callback fields", async () => {
    await onlinePayApi.verify({ gatewayOrderId: "o1", gatewayPaymentId: "p1", gatewaySignature: "sig" });
    const { url } = lastCall();
    expect(url).toBe("/api/proxy/v1/online-payments/verify");
  });

  it("cancel() POSTs to /cancel/{chequeId}", async () => {
    await onlinePayApi.cancel("c1");
    const { url, init } = lastCall();
    expect(url).toBe("/api/proxy/v1/online-payments/cancel/c1");
    expect(init?.method).toBe("POST");
  });
});

// ---- error surfacing ----

describe("ApiError", () => {
  it("surfaces the backend's message on a 400", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ message: "Cheques total 500 but contract value is 600" }, 400)));
    await expect(leaseApi.post("lease-1")).rejects.toMatchObject({
      status: 400,
      message: "Cheques total 500 but contract value is 600",
    });
  });

  it("is the same class re-exported for callers that only import from leasing.ts", () => {
    expect(ApiError).toBeDefined();
  });
});
