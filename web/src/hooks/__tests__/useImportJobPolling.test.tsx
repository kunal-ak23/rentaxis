import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook } from "@testing-library/react";
import type { ContractImportResult } from "@/lib/api/cutover";
import { isImportJobTerminal } from "@/lib/cutoverRules";

/**
 * The cut-over import poll.
 *
 * Shaped after the inline loop on the properties page (`page.tsx:264-303`) —
 * terminal states stop it, it gives up rather than running forever, and it is
 * cleaned up on unmount — with two things that one does not have: a backoff, so
 * a six-hundred-contract workbook is not 150 requests at a fixed two seconds,
 * and a recoverable job id so a page reload rejoins the job instead of orphaning
 * it.
 */

const api = vi.hoisted(() => ({ status: vi.fn() }));
vi.mock("@/lib/api/cutover", async orig => {
    const m = await orig<typeof import("@/lib/api/cutover")>();
    return {
        ...m,
        cutoverApi: { ...m.cutoverApi, contractImport: { ...m.cutoverApi.contractImport, status: api.status } },
    };
});

import { importJobStorageKey, useImportJobPolling } from "@/hooks/useImportJobPolling";

/** The scope a signed-in accountant of one organisation polls under. */
const SCOPE = { tenantId: "tenant-1", userId: "user-1" };
const KEY = importJobStorageKey("contract-import", SCOPE);

function setup() {
    return renderHook(() =>
        useImportJobPolling<ContractImportResult>({
            kind: "contract-import",
            scope: SCOPE,
            fetchStatus: (jobId: string) => api.status(jobId) as Promise<ContractImportResult>,
            isTerminal: job => isImportJobTerminal(job.status),
        }),
    );
}

function result(over: Partial<ContractImportResult> = {}): ContractImportResult {
    return {
        jobId: "job-1", status: "VALIDATING",
        propertiesCreated: 0, buildingsCreated: 0, unitsCreated: 0, rentersCreated: 0,
        leasesCreated: 0, chequesCreated: 0, chequesFromSheet: 0, bookingDepositsCreated: 0,
        importBatchId: null, contractsCreated: 0, mappingsCreated: 0,
        errors: [], warnings: [],
        ...over,
    };
}

beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    window.sessionStorage.clear();
});
afterEach(() => {
    // Every case unmounts its own hook; this is the backstop so a forgotten one
    // cannot leave a timer running into the next test.
    cleanup();
    vi.clearAllTimers();
    vi.useRealTimers();
});

/** Let the timer fire and its promise settle. */
async function tick(ms: number) {
    await act(async () => {
        await vi.advanceTimersByTimeAsync(ms);
    });
}

/**
 * Testing Library's `waitFor` polls on REAL timers, so under `vi.useFakeTimers`
 * it simply waits out its own timeout while the hook's clock never moves. This
 * advances the fake clock instead, which both fires the next poll and flushes
 * the promise it returns.
 */
async function until(check: () => boolean, maxMs = 120_000) {
    const step = 250;
    for (let elapsed = 0; elapsed <= maxMs; elapsed += step) {
        if (check()) return;
        await tick(step);
    }
    if (!check()) throw new Error("condition was never met");
}

describe("useImportJobPolling", () => {
    it("polls until a terminal state and then stops", async () => {
        api.status
            .mockResolvedValueOnce(result({ status: "VALIDATING" }))
            .mockResolvedValueOnce(result({ status: "PERSISTING" }))
            .mockResolvedValueOnce(result({ status: "COMPLETED", importBatchId: "b-1", leasesCreated: 12 }));

        const { result: hook, unmount } = setup();
        act(() => hook.current.start("job-1"));

        await until(() => api.status.mock.calls.length >= 1);
        await tick(10_000);
        await tick(10_000);

        await until(() => hook.current.job?.status === "COMPLETED");
        expect(hook.current.polling).toBe(false);

        const callsAtRest = api.status.mock.calls.length;
        await tick(60_000);
        // Terminal means terminal: not one more request.
        expect(api.status).toHaveBeenCalledTimes(callsAtRest);
        unmount();
    });

    it("backs off rather than hammering at a fixed interval", async () => {
        api.status.mockResolvedValue(result({ status: "VALIDATING" }));
        const { result: hook, unmount } = setup();
        act(() => hook.current.start("job-1"));
        await until(() => api.status.mock.calls.length >= 1);

        // A fixed 1s poll would have made ~30 requests in 30s; a backing-off one
        // makes far fewer.
        await tick(30_000);
        expect(api.status.mock.calls.length).toBeLessThan(15);
        expect(api.status.mock.calls.length).toBeGreaterThan(3);
        unmount();
    });

    it("stops on unmount", async () => {
        api.status.mockResolvedValue(result({ status: "VALIDATING" }));
        const { result: hook, unmount } = setup();
        act(() => hook.current.start("job-1"));
        await until(() => api.status.mock.calls.length >= 1);

        unmount();
        const after = api.status.mock.calls.length;
        await tick(60_000);
        expect(api.status).toHaveBeenCalledTimes(after);
    });

    it("gives up instead of polling forever", async () => {
        api.status.mockResolvedValue(result({ status: "VALIDATING" }));
        const { result: hook, unmount } = setup();
        act(() => hook.current.start("job-1"));

        await tick(20 * 60_000);
        await until(() => hook.current.polling === false);
        expect(hook.current.timedOut).toBe(true);
        unmount();
    });

    /** A reload must rejoin the job, not orphan it. */
    it("remembers the job id and resumes it on the next mount", async () => {
        api.status.mockResolvedValue(result({ status: "VALIDATING" }));
        const first = setup();
        act(() => first.result.current.start("job-1"));
        await until(() => window.sessionStorage.getItem(KEY) === "job-1");
        first.unmount();

        api.status.mockClear();
        api.status.mockResolvedValue(result({ status: "COMPLETED", importBatchId: "b-1" }));
        const second = setup();
        await until(() => api.status.mock.calls.some(c => c[0] === "job-1"));
        await until(() => second.result.current.job?.status === "COMPLETED");
    });

    it("forgets the job id once it reaches a terminal state", async () => {
        api.status.mockResolvedValue(result({ status: "VALIDATION_FAILED" }));
        const { result: hook, unmount } = setup();
        act(() => hook.current.start("job-1"));
        await until(() => hook.current.job?.status === "VALIDATION_FAILED");
        expect(window.sessionStorage.getItem(KEY)).toBeNull();
        unmount();
    });

    it("rides out a transient failure rather than giving up on the job", async () => {
        const { ApiError } = await import("@/lib/api/facilities");
        api.status
            .mockRejectedValueOnce(new ApiError(503, "upstream"))
            .mockResolvedValue(result({ status: "COMPLETED", importBatchId: "b-1" }));

        const { result: hook, unmount } = setup();
        act(() => hook.current.start("job-1"));
        await tick(20_000);
        await until(() => hook.current.job?.status === "COMPLETED");
        expect(hook.current.gone).toBe(false);
        unmount();
    });

    it("clears everything on reset", async () => {
        api.status.mockResolvedValue(result({ status: "VALIDATING" }));
        const { result: hook, unmount } = setup();
        act(() => hook.current.start("job-1"));
        await until(() => hook.current.polling === true);

        act(() => hook.current.reset());
        expect(hook.current.polling).toBe(false);
        expect(hook.current.job).toBeNull();
        expect(window.sessionStorage.getItem(KEY)).toBeNull();
        unmount();
    });

    /**
     * Review item (a): the key was global, so a SUPER_ADMIN who started an import,
     * switched organisation and reloaded rejoined the previous tenant's job.
     */
    it("scopes the stored job id by tenant, user and job kind", () => {
        expect(importJobStorageKey("contract-import", SCOPE)).toBe(
            "rentaxis.cutover.job.contract-import.tenant-1.user-1",
        );
        expect(importJobStorageKey("bulk-post", SCOPE)).not.toBe(KEY);
        expect(importJobStorageKey("contract-import", { tenantId: "tenant-2", userId: "user-1" })).not.toBe(KEY);
        expect(importJobStorageKey("contract-import", { tenantId: "tenant-1", userId: "user-2" })).not.toBe(KEY);
    });

    it("does not resume a job stored under another organisation's key", async () => {
        window.sessionStorage.setItem(
            importJobStorageKey("contract-import", { tenantId: "other", userId: "user-1" }),
            "job-9",
        );
        api.status.mockResolvedValue(result({ status: "VALIDATING" }));
        const { unmount } = setup();
        await tick(2_000);
        expect(api.status).not.toHaveBeenCalled();
        unmount();
    });

    /** A second job KIND, not a second hook. */
    it("polls a different job kind through the same hook", async () => {
        const fetchStatus = vi.fn(async () => ({ status: "COMPLETED" }));
        const { result: hook, unmount } = renderHook(() =>
            useImportJobPolling<{ status: string }>({
                kind: "bulk-post",
                scope: SCOPE,
                fetchStatus,
                isTerminal: j => j.status === "COMPLETED",
            }),
        );
        act(() => hook.current.start("post-job-1"));
        await until(() => hook.current.job?.status === "COMPLETED");
        expect(fetchStatus).toHaveBeenCalledWith("post-job-1");
        expect(window.sessionStorage.getItem(importJobStorageKey("bulk-post", SCOPE))).toBeNull();
        unmount();
    });

    /** Review item (a): the 404 message has to be truthful about both causes. */
    it("reports a 404 as belonging to another organisation or gone", async () => {
        const { ApiError } = await import("@/lib/api/facilities");
        api.status.mockRejectedValue(new ApiError(404, "Import job not found"));
        const { result: hook, unmount } = setup();
        act(() => hook.current.start("job-1"));
        await until(() => hook.current.gone === true);
        expect(hook.current.polling).toBe(false);
        expect(window.sessionStorage.getItem(KEY)).toBeNull();
        unmount();
    });

    it("does not persist anything when there is no scope yet", async () => {
        api.status.mockResolvedValue(result({ status: "VALIDATING" }));
        const { result: hook, unmount } = renderHook(() =>
            useImportJobPolling<ContractImportResult>({
                kind: "contract-import",
                scope: null,
                fetchStatus: (jobId: string) => api.status(jobId) as Promise<ContractImportResult>,
                isTerminal: job => isImportJobTerminal(job.status),
            }),
        );
        act(() => hook.current.start("job-1"));
        await until(() => api.status.mock.calls.length >= 1);
        expect(window.sessionStorage.length).toBe(0);
        unmount();
    });
});
