import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { EXTRACT_TIMEOUT_MS, useBulkChequeExtract, type BulkExtractItem } from "../useBulkChequeExtract";

// Tutorial 15: with blob storage down the scan hung with no feedback. A file that
// takes longer than EXTRACT_TIMEOUT_MS fails with a code the row can name.
afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
});

describe("useBulkChequeExtract timeout", () => {
    it("fails a file that hangs, with cheque_upload_timeout", async () => {
        vi.useFakeTimers();
        global.fetch = vi.fn((_url: string, init?: RequestInit) => new Promise((_, reject) => {
            init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        })) as unknown as typeof fetch;
        const { result } = renderHook(() => useBulkChequeExtract());
        const item: BulkExtractItem = {
            id: "a", file: new File([new Uint8Array([1])], "c.jpg", { type: "image/jpeg" }), previewUrl: "blob:x",
            status: "pending", response: null, error: null, errorCode: null,
        };
        let done: Promise<BulkExtractItem[]> = Promise.resolve([]);
        act(() => { done = result.current.start([item]); });
        await act(async () => { await vi.advanceTimersByTimeAsync(EXTRACT_TIMEOUT_MS + 10); });
        const [out] = await done;
        expect(out.status).toBe("failed");
        expect(out.errorCode).toBe("cheque_upload_timeout");
        // PR #400 review P3-3: the file's key travels with it, so a Retry is deduplicated server-side.
        const init = (global.fetch as unknown as { mock: { calls: [string, RequestInit][] } }).mock.calls[0][1];
        expect((init.headers as Record<string, string>)["Idempotency-Key"]).toBe("a");
    });
});
