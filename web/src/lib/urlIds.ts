/**
 * Ids read from the URL (`?propertyId=`, `?buildingId=` …) are bookmarkable and
 * hand-editable, so they can be anything. Break round 1: the Contracts list sent
 * `propertyId=not-a-uuid` straight to the API, got a 400 and logged an unhandled
 * ApiError while the filter silently failed. A list uses such an id only when it
 * is UUID-shaped; otherwise it is "no filter" and is removed from the URL.
 */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(v: string | null | undefined): v is string {
    return typeof v === "string" && UUID.test(v);
}

/** The trimmed id when UUID-shaped, else `""` (no filter). */
export function idParam(v: string | null | undefined): string {
    const trimmed = (v ?? "").trim();
    return isUuid(trimmed) ? trimmed : "";
}

/** Whether a URL value is a usable id once trimmed — `idParam`'s rule as a predicate. */
export function isIdParam(v: string | null | undefined): boolean {
    return idParam(v) !== "";
}

/**
 * Removes each of `keys` whose URL value is present but not UUID-shaped, with
 * `history.replaceState` (no navigation, history state kept). Every other
 * parameter is left alone. Returns whether the URL changed, so a page with its
 * own `location.search` store can notify its subscribers.
 */
export function stripInvalidIdParams(keys: readonly string[]): boolean {
    if (typeof window === "undefined") return false;
    const url = new URL(window.location.href);
    let changed = false;
    for (const k of keys) {
        const v = url.searchParams.get(k);
        if (v !== null && !isIdParam(v)) {
            url.searchParams.delete(k);
            changed = true;
        }
    }
    if (changed) window.history.replaceState(window.history.state, "", url.toString());
    return changed;
}
