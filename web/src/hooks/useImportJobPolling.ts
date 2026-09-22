"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/api/facilities";

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
 * The key is scoped by TENANT, USER and job KIND: an unscoped one meant a
 * SUPER_ADMIN who started an import, switched organisation and reloaded rejoined
 * the previous tenant's job, and got a 404 whose message blamed the wrong thing.
 *
 * **A transient failure is not the end of the job.** A 404 means the job is
 * genuinely gone and the poll stops; anything else is retried, because a single
 * 503 from a restarting pod should not convince the screen that a six-hundred
 * contract import has failed.
 */

/** Who is watching, so one person's job is never resumed as another's. */
export type JobScope = { tenantId: string; userId: string };

/**
 * Session-scoped, not local: a finished cut-over is not something to carry to
 * tomorrow. Keyed by kind + tenant + user, so a contract import and a bulk post,
 * or two organisations, never share a slot.
 */
export function importJobStorageKey(kind: string, scope: JobScope): string {
    return `rentaxis.cutover.job.${kind}.${scope.tenantId}.${scope.userId}`;
}

const FIRST_DELAY_MS = 1_000;
const MAX_DELAY_MS = 10_000;
/** Roughly a quarter of an hour of backed-off polling. */
const GIVE_UP_AFTER_MS = 15 * 60_000;

/** `sessionStorage` throws in a locked-down browser; a missing job id is not worth a crash. */
function readStoredJobId(key: string | null): string | null {
    if (!key) return null;
    try {
        return window.sessionStorage.getItem(key);
    } catch {
        return null;
    }
}

function storeJobId(key: string | null, jobId: string | null): void {
    if (!key) return;
    try {
        if (jobId) window.sessionStorage.setItem(key, jobId);
        else window.sessionStorage.removeItem(key);
    } catch {
        // A job that cannot be remembered still polls; it just will not survive a reload.
    }
}

export type ImportJobPolling<T> = {
    job: T | null;
    polling: boolean;
    /** Set when the poll gave up on a job that never finished — not a job failure. */
    timedOut: boolean;
    /**
     * Set when the job could not be read at all. A 404 here means one of exactly
     * two things and the copy has to allow for both: the job belongs to another
     * organisation, or it no longer exists.
     */
    gone: boolean;
    start: (jobId: string) => void;
    reset: () => void;
};

export type ImportJobPollingOptions<T> = {
    /** Distinguishes one kind of job from another in storage; e.g. "contract-import", "bulk-post". */
    kind: string;
    /** Null until the session resolves — nothing is persisted until it does. */
    scope: JobScope | null;
    fetchStatus: (jobId: string) => Promise<T>;
    isTerminal: (job: T) => boolean;
};

export function useImportJobPolling<T>({
    kind,
    scope,
    fetchStatus,
    isTerminal,
}: ImportJobPollingOptions<T>): ImportJobPolling<T> {
    const [job, setJob] = useState<T | null>(null);
    const [polling, setPolling] = useState(false);
    const [timedOut, setTimedOut] = useState(false);
    const [gone, setGone] = useState(false);

    const storageKey = scope ? importJobStorageKey(kind, scope) : null;
    // Read through refs inside the poll so a re-render with a new callback
    // identity does not need to restart a running job.
    const latest = useRef({ fetchStatus, isTerminal, storageKey });
    latest.current = { fetchStatus, isTerminal, storageKey };

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
            setGone(false);
            setPolling(true);
            storeJobId(latest.current.storageKey, jobId);

            const poll = async () => {
                if (!alive.current) return;
                try {
                    const next = await latest.current.fetchStatus(jobId);
                    if (!alive.current) return;
                    setJob(next);
                    if (latest.current.isTerminal(next)) {
                        storeJobId(latest.current.storageKey, null);
                        stop();
                        return;
                    }
                } catch (e) {
                    if (!alive.current) return;
                    // Gone, or another organisation's — retrying will not change it.
                    if (e instanceof ApiError && (e.status === 404 || e.status === 403)) {
                        storeJobId(latest.current.storageKey, null);
                        setGone(true);
                        stop();
                        return;
                    }
                    // Anything else is transient; fall through and try again.
                }
                if (!alive.current) return;
                if (Date.now() - startedAt.current >= GIVE_UP_AFTER_MS) {
                    storeJobId(latest.current.storageKey, null);
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
        storeJobId(latest.current.storageKey, null);
        stop();
        setJob(null);
        setTimedOut(false);
        setGone(false);
    }, [stop]);

    // Rejoin a job left running by a reload. Mount-only: `start` is stable, and
    // re-reading storage on every render would restart a job the user has since
    // dismissed.
    useEffect(() => {
        alive.current = true;
        const stored = readStoredJobId(storageKey);
        if (stored) start(stored);
        return () => {
            alive.current = false;
            if (timer.current) clearTimeout(timer.current);
            timer.current = null;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [storageKey]);

    return { job, polling, timedOut, gone, start, reset };
}

export default useImportJobPolling;
