import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/facilities";
import { isContentionError, withOneRetry } from "../bulkPost";

describe("isContentionError", () => {
    it("is a 409, or a 500 naming a lock or optimistic failure — never a 400", () => {
        expect(isContentionError(new ApiError(409, "Conflict"))).toBe(true);
        expect(isContentionError(new ApiError(500, "Internal error", '{"message":"ObjectOptimisticLockingFailureException"}'))).toBe(true);
        expect(isContentionError(new ApiError(500, "could not obtain lock on row"))).toBe(true);
        expect(isContentionError(new ApiError(500, "NullPointerException"))).toBe(false);
        expect(isContentionError(new ApiError(400, "optimistic wording in a validation message"))).toBe(false);
        expect(isContentionError(new Error("lock"))).toBe(false);
    });
});

describe("withOneRetry", () => {
    it("retries a contended attempt once and returns its success", async () => {
        const attempt = vi.fn().mockRejectedValueOnce(new ApiError(409, "Conflict")).mockResolvedValueOnce("ok");
        await expect(withOneRetry(attempt, 1)).resolves.toBe("ok");
        expect(attempt).toHaveBeenCalledTimes(2);
    });
    it("gives up after the one retry with the second error", async () => {
        const attempt = vi.fn().mockRejectedValue(new ApiError(409, "Still locked"));
        await expect(withOneRetry(attempt, 1)).rejects.toThrow("Still locked");
        expect(attempt).toHaveBeenCalledTimes(2);
    });
    it("does not retry a refusal on the merits", async () => {
        const attempt = vi.fn().mockRejectedValue(new ApiError(400, "credit account is inactive"));
        await expect(withOneRetry(attempt, 1)).rejects.toThrow("inactive");
        expect(attempt).toHaveBeenCalledTimes(1);
    });
});
