import { ApiError } from "./facilities";

/**
 * The outcome of a list page's initial GET (break round 1, F8).
 *
 * `if (res.ok) setRows(...)` left the list at its initial `[]` on a 403, so a
 * role the backend refuses saw a false "no data" state next to create buttons
 * it cannot use. Callers render the standard access-denied state for
 * "forbidden" and an error with a retry for "failed" — never an empty list.
 * (A 401 is handled once for the whole app by the session fetch guard.)
 */
export type ListLoad<T> =
    | { kind: "ok"; items: T[] }
    | { kind: "forbidden" }
    | { kind: "failed"; status: number };

export async function loadList<T>(url: string): Promise<ListLoad<T>> {
    try {
        const res = await fetch(url);
        if (res.ok) return { kind: "ok", items: (await res.json()) as T[] };
        res.body?.cancel().catch(() => {});
        if (res.status === 403) return { kind: "forbidden" };
        return { kind: "failed", status: res.status };
    } catch {
        return { kind: "failed", status: 0 };
    }
}

/** For callers of the typed API clients, which throw an ApiError. */
export function isForbidden(err: unknown): boolean {
    return err instanceof ApiError && err.status === 403;
}
