"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/api/facilities";
import { cutoverApi, type ContractImportResult } from "@/lib/api/cutover";
import { isImportJobTerminal } from "@/lib/cutoverRules";

/**
 * Follow a cut-over import job to its end.
 *
 * Shaped after the inline loop the properties page runs for the v1 import
 * (`properties/page.tsx:264-303`): poll, stop on a terminal status, give up
 * rather than run forever, clean up on unmount. Three things are different, and
 * each is why this is a hook rather than a second copy of that loop.
 *
 * **It backs off.** The v1 loop asks every two seconds for up to five minutes —
 * 150 requests for a job that usually finishes in ten. This starts at a second
 * and grows to ten, which is roughly forty requests over a quarter of an hour
 * and answers a fast job just as quickly.
 *
 * **It survives a reload.** The job id is written to `sessionStorage` while the
 * job is in flight, so refreshing the page rejoins it instead of orphaning a
 * running import the user can no longer see. It is removed the moment the job
 * reaches a terminal state, so a later visit does not resurrect a finished one.
 *
 * **A transient failure is not the end of the job.** A 404 means the job is
 * genuinely gone and the poll stops; anything else is retried, because a single
 * 503 from a restarting pod should not convince the screen that a six-hundred
 * contract import has failed.
 */

/** Session-scoped, not local: a finished cut-over is not something to carry to tomorrow. */
export const CUTOVER_JOB_STORAGE_KEY = "rentaxis.cutover.importJobId";

const FIRST_DELAY_MS = 1_000;
const MAX_DELAY_MS = 10_000;
/** Roughly a quarter of an hour of backed-off polling. */
const GIVE_UP_AFTER_MS = 15 * 60_000;

/** `sessionStorage` throws in a locked-down browser; a missing job id is not worth a crash. */
function readStoredJobId(): string | null {
    try {
        return window.sessionStorage.getItem(CUTOVER_JOB_STORAGE_KEY);
    } catch {
        return null;
    }
}

function storeJobId(jobId: string | null): void {
    try {
        if (jobId) window.sessionStorage.setItem(CUTOVER_JOB_STORAGE_KEY, jobId);
        else window.sessionStorage.removeItem(CUTOVER_JOB_STORAGE_KEY);
    } catch {
        // A job that cannot be remembered still polls; it just will not survive a reload.
    }
}

export type ImportJobPolling = {
    job: ContractImportResult | null;
    polling: boolean;
    /** Set when the poll gave up on a job that never finished — not a job failure. */
    timedOut: boolean;
    /** Set when the job could not be read at all (gone, or not this organisation's). */
    error: string | null;
    start: (jobId: string) => void;
    reset: () => void;
};

export function useImportJobPolling(): ImportJobPolling {
    const [job, setJob] = useState<ContractImportResult | null>(null);
    const [polling, setPolling] = useState(false);
    const [timedOut, setTimedOut] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
    const alive = useRef(true);
    const startedAt = useRef(0);
    const delay = useRef(FIRST_DELAY_MS);

    const stop = useCallback(() => {
        if (timer.current) clearTimeout(timer.current);
        timer.current = null;
        setPolling(false);
    }, []);

    const start = useCallback(
        (jobId: string) => {
            if (timer.current) clearTimeout(timer.current);
            startedAt.current = Date.now();
            delay.current = FIRST_DELAY_MS;
            setJob(null);
            setTimedOut(false);
            setError(null);
            setPolling(true);
            storeJobId(jobId);

            const poll = async () => {
                if (!alive.current) return;
                try {
                    const next = await cutoverApi.contractImport.status(jobId);
                    if (!alive.current) return;
                    setJob(next);
                    if (isImportJobTerminal(next.status)) {
                        storeJobId(null);
                        stop();
                        return;
                    }
                } catch (e) {
                    if (!alive.current) return;
                    // Gone, or another organisation's — retrying will not change it.
                    if (e instanceof ApiError && (e.status === 404 || e.status === 403)) {
                        storeJobId(null);
                        setError(e.message);
                        stop();
                        return;
                    }
                    // Anything else is transient; fall through and try again.
                }
                if (!alive.current) return;
                if (Date.now() - startedAt.current >= GIVE_UP_AFTER_MS) {
                    storeJobId(null);
                    setTimedOut(true);
                    stop();
                    return;
                }
                delay.current = Math.min(delay.current * 1.6, MAX_DELAY_MS);
                timer.current = setTimeout(poll, delay.current);
            };

            // Ask once straight away: a workbook that fails validation in 200ms
            // should not spend a second looking like it is still running.
            void poll();
        },
        [stop],
    );

    const reset = useCallback(() => {
        storeJobId(null);
        stop();
        setJob(null);
        setTimedOut(false);
        setError(null);
    }, [stop]);

    // Rejoin a job left running by a reload. Mount-only: `start` is stable, and
    // re-reading storage on every render would restart a job the user has since
    // dismissed.
    useEffect(() => {
        alive.current = true;
        const stored = readStoredJobId();
        if (stored) start(stored);
        return () => {
            alive.current = false;
            if (timer.current) clearTimeout(timer.current);
            timer.current = null;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return { job, polling, timedOut, error, start, reset };
}

export default useImportJobPolling;
