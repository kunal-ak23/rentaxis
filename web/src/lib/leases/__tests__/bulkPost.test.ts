import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/facilities";
import { isContentionError, withOneRetry } from "../bulkPost";

describe("isContentionError", () => {
    it("is a 409, or a 500 naming a lock or optimistic failure — never a 400", () => {
        expect(isContentionError(new ApiError(409, "Please try again"))).toBe(true);
        expect(isContentionError(new ApiError(500, "Internal error", '{"message":"ObjectOptimisticLockingFailureException"}'))).toBe(true);
        expect(isContentionError(new ApiError(500, "could not obtain lock on row"))).toBe(true);
        expect(isContentionError(new ApiError(500, "NullPointerException"))).toBe(false);
        expect(isContentionError(new ApiError(400, "optimistic wording in a validation message"))).toBe(false);
        expect(isContentionError(new Error("lock"))).toBe(false);
    });
});

/**
 * Break-it round 1 (money) F3: an amount too large for the ledger came back as a
 * 409 "This action conflicts with existing related records" and was retried as
 * if it were lock contention. A 409 is contention only when it says so.
 */
describe("isContentionError — which 409s are contention (F3)", () => {
    it("retries the server's own lock / race refusals", () => {
        for (const m of [
            "This record was being changed by another request at the same time. Please try again.",
            "This lease is being posted by another request. Please try again.",
            "This cheque is being updated by another request. Please try again.",
        ]) {
            expect(isContentionError(new ApiError(409, m)), m).toBe(true);
        }
    });

    it("does not retry a data-integrity 409 or any other conflict on the merits", () => {
        expect(isContentionError(new ApiError(409, "This action conflicts with existing related records."))).toBe(false);
        expect(isContentionError(new ApiError(409,
            "This action conflicts with existing related records (uq_journal_entries_number).",
            '{"constraint":"uq_journal_entries_number"}'))).toBe(false);
        expect(isContentionError(new ApiError(409, "ALREADY_RECORDED"))).toBe(false);
    });
});

describe("withOneRetry", () => {
    it("retries a contended attempt once and returns its success", async () => {
        const attempt = vi.fn().mockRejectedValueOnce(new ApiError(409, "This lease is being posted by another request. Please try again.")).mockResolvedValueOnce("ok");
        await expect(withOneRetry(attempt, 1)).resolves.toBe("ok");
        expect(attempt).toHaveBeenCalledTimes(2);
    });
    it("gives up after the one retry with the second error", async () => {
        const attempt = vi.fn().mockRejectedValue(new ApiError(409, "Still locked by another request"));
        await expect(withOneRetry(attempt, 1)).rejects.toThrow("Still locked by another request");
        expect(attempt).toHaveBeenCalledTimes(2);
    });
    it("does not retry a refusal on the merits", async () => {
        const attempt = vi.fn().mockRejectedValue(new ApiError(400, "credit account is inactive"));
        await expect(withOneRetry(attempt, 1)).rejects.toThrow("inactive");
        expect(attempt).toHaveBeenCalledTimes(1);
    });
});
