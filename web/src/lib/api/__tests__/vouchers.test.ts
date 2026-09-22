import { beforeEach, describe, expect, it, vi } from "vitest";
import { voucherApi, vatOf, vatTotalOf, netTotalOf, grossTotalOf } from "../vouchers";

/**
 * The client half of the voucher contract (spec §10.1, §11).
 *
 * `vatOf` is the one piece of arithmetic on this screen that the ledger cannot
 * catch: a balanced journal with the wrong VAT split is still balanced. It
 * mirrors `VoucherMath.vat`
 * (backend/src/main/java/com/datagami/rentaxis/core/service/voucher/VoucherMath.java:31-35)
 * — HALF_UP to two decimals, **per line**, then summed. 5% of the net total
 * would disagree with the vendor's own invoice by a fil or two.
 */
describe("vatOf", () => {
    it("matches the backend's per-line HALF_UP rounding", () => {
        expect(vatOf(1000, 5)).toBe(50);
        expect(vatOf(1234.57, 5)).toBe(61.73);
        // 100.10 * 5 / 100 = 5.005 exactly; HALF_UP takes it up, and binary
        // floating point on its own would take it down.
        expect(vatOf(100.1, 5)).toBe(5.01);
        expect(vatOf(1000, 0)).toBe(0);
        expect(vatOf(0, 5)).toBe(0);
    });

    it("sums per-line VAT rather than taking VAT of the total", () => {
        const lines = [
            { amount: 100.1, vatRate: 5 },
            { amount: 100.1, vatRate: 5 },
            { amount: 100.1, vatRate: 5 },
        ];
        // 3 x 5.01 = 15.03, NOT 5% of 300.30 = 15.02.
        expect(vatTotalOf(lines)).toBe(15.03);
        expect(netTotalOf(lines)).toBe(300.3);
        expect(grossTotalOf(lines)).toBe(315.33);
    });

    it("rounds the running totals so repeated addition cannot drift", () => {
        const lines = [
            { amount: 0.1, vatRate: 0 },
            { amount: 0.2, vatRate: 0 },
        ];
        expect(netTotalOf(lines)).toBe(0.3);
    });
});

describe("voucherApi", () => {
    beforeEach(() => {
        vi.stubGlobal(
            "fetch",
            vi.fn(
                async () =>
                    new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }),
            ),
        );
    });

    it("builds the list query and drops empty filters", async () => {
        await voucherApi.list({ docType: "PISR", status: "", page: 0, size: 25 });
        expect(fetch).toHaveBeenCalledWith(
            "/api/proxy/v1/finance/vouchers?docType=PISR&page=0&size=25",
            expect.anything(),
        );
    });

    it("posts a voucher by id", async () => {
        await voucherApi.post("abc");
        expect(fetch).toHaveBeenCalledWith(
            "/api/proxy/v1/finance/vouchers/abc/post",
            expect.objectContaining({ method: "POST" }),
        );
    });

    it("amends with a reversal date, a reason and the replacement document", async () => {
        await voucherApi.amend("abc", {
            reversalDate: "2026-09-30",
            reason: "wrong amount",
            replacement: {
                docType: "PISR",
                docDate: "2026-09-30",
                vendorId: "v1",
                lines: [{ accountId: "a1", amount: 100, vatRate: 5 }],
            },
        });
        const [url, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
        expect(url).toBe("/api/proxy/v1/finance/vouchers/abc/amend");
        expect(JSON.parse((init as RequestInit).body as string).reversalDate).toBe("2026-09-30");
    });

    it("sends an attachment as multipart without a JSON content-type", async () => {
        const file = new File(["x"], "invoice.pdf", { type: "application/pdf" });
        await voucherApi.attachments.upload("abc", "Vendor invoice", file);
        const [url, init] = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1)!;
        expect(url).toBe("/api/proxy/v1/finance/vouchers/abc/attachments");
        expect((init as RequestInit).body).toBeInstanceOf(FormData);
        // The browser has to set the multipart boundary itself.
        expect((init as RequestInit).headers).toBeUndefined();
    });

    it("deletes an attachment on the controller's flat path, not under its voucher", async () => {
        vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 204 })));
        await voucherApi.attachments.remove("att-1");
        expect(fetch).toHaveBeenCalledWith(
            "/api/proxy/v1/finance/vouchers/attachments/att-1",
            expect.objectContaining({ method: "DELETE" }),
        );
    });
});
