"use client";

import { useCallback, useEffect, useRef } from "react";

export type RequestTicket = {
    /** Pass to fetch: aborted when a newer request begins or the component unmounts. */
    signal: AbortSignal;
    /** False once superseded or unmounted — check after every await before setting state. */
    isCurrent: () => boolean;
};

/**
 * One in-flight load per component (break round 3, F1). `begin()` aborts the
 * previous request and returns a ticket for the new one; unmount aborts the
 * last. With `isAbortError(err) || !isCurrent()` in the catch, a load the
 * user walked away from (navigated off, or changed a filter) ends silently,
 * and a slow old response can never overwrite a newer one — while a real
 * failure still reaches the page's error state.
 *
 *     const begin = useLatestRequest();
 *     useEffect(() => {
 *         const { signal, isCurrent } = begin();
 *         fetch(url, { signal }).then(r => r.json()).then(d => { if (isCurrent()) setRows(d); })
 *             .catch(err => { if (isAbortError(err) || !isCurrent()) return; setError(true); });
 *     }, [url, begin]);
 *
 * `begin` is stable, so it is safe in dependency lists. A component with two
 * independent loads uses two hooks.
 */
export function useLatestRequest(): () => RequestTicket {
    const current = useRef<AbortController | null>(null);
    useEffect(() => () => {
        current.current?.abort();
        current.current = null;
    }, []);
    return useCallback(() => {
        current.current?.abort();
        const controller = new AbortController();
        current.current = controller;
        return {
            signal: controller.signal,
            isCurrent: () => current.current === controller && !controller.signal.aborted,
        };
    }, []);
}
