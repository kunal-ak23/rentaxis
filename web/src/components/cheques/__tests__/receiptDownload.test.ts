import { afterEach, describe, expect, it, vi } from "vitest";
const saved = vi.hoisted(() => ({ names: [] as string[] }));
vi.mock("@/lib/download", () => ({ saveResponse: vi.fn(async (_r: Response, name: string) => { saved.names.push(name); }) }));
import { downloadChequeReceipt } from "../receiptDownload";

afterEach(() => { saved.names = []; vi.restoreAllMocks(); });

describe("downloadChequeReceipt (QA-26)", () => {
    it("fetches the receipt and saves it in place, no new tab", async () => {
        const open = vi.spyOn(window, "open").mockImplementation(() => null);
        global.fetch = vi.fn(async () => new Response("%PDF", { status: 200 })) as typeof fetch;
        expect(await downloadChequeReceipt("abcdef12-3456", "fallback")).toBeNull();
        expect(String(vi.mocked(global.fetch).mock.calls[0][0])).toContain("/cheques/abcdef12-3456/receipt");
        expect(saved.names).toEqual(["receipt-abcdef12.pdf"]);
        expect(open).not.toHaveBeenCalled();
    });

    it("hands back the server's reason on a refusal, else the fallback", async () => {
        global.fetch = vi.fn(async () => new Response(JSON.stringify({ message: "Not cleared yet" }), { status: 400 })) as typeof fetch;
        expect(await downloadChequeReceipt("c1", "fallback")).toBe("Not cleared yet");
        global.fetch = vi.fn(async () => new Response("oops", { status: 500 })) as typeof fetch;
        expect(await downloadChequeReceipt("c1", "fallback")).toBe("fallback");
        global.fetch = vi.fn(async () => { throw new TypeError("offline"); }) as typeof fetch;
        expect(await downloadChequeReceipt("c1", "fallback")).toBe("fallback");
        expect(saved.names).toEqual([]);
    });
});
