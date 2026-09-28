/**
 * Break-it round 3 (ops3) F4/F6: an edit form sends only what the user changed.
 *
 * A form that PUTs every field it loaded silently undoes whatever another tab
 * saved in between — an organisation re-activated, a property manager's removed
 * property granted back. `changedFields` compares the values the form loaded with
 * what it holds now and returns the changed fields, plus `expected`: what the form
 * loaded for each of them, so the server can refuse (409) when one of those moved
 * meanwhile rather than overwrite it.
 *
 * `null`, `undefined` and `""` count as the same (empty) value.
 */
export function changedFields<T extends Record<string, unknown>, K extends keyof T & string>(
    loaded: T,
    current: T,
    keys: readonly K[],
): { changes: Partial<Pick<T, K>>; expected: Partial<Pick<T, K>> } {
    const changes: Partial<Pick<T, K>> = {};
    const expected: Partial<Pick<T, K>> = {};
    for (const key of keys) {
        if (!sameValue(loaded[key], current[key])) {
            changes[key] = current[key];
            expected[key] = loaded[key];
        }
    }
    return { changes, expected };
}

/** Two sets of ids equal regardless of order and repeats. */
export function sameIdSet(a: readonly string[], b: readonly string[]): boolean {
    const sa = new Set(a);
    const sb = new Set(b);
    if (sa.size !== sb.size) return false;
    for (const id of sa) if (!sb.has(id)) return false;
    return true;
}

function sameValue(a: unknown, b: unknown): boolean {
    const empty = (v: unknown) => v === null || v === undefined || v === "";
    if (empty(a) && empty(b)) return true;
    return a === b;
}
