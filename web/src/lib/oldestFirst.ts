/**
 * The house order for entity lists: oldest first (createdAt ascending), then by name, then
 * by id so the order is stable when rows share a timestamp (a seed or an import writes many
 * in one transaction). A row without createdAt — just created, not yet re-read — goes last.
 */
export function oldestFirst<T extends { id?: string | null; createdAt?: string | null }>(
    name: (row: T) => string | null | undefined,
): (a: T, b: T) => number {
    const LAST = "￿";
    return (a, b) =>
        (a.createdAt || LAST).localeCompare(b.createdAt || LAST)
        || (name(a) || "").localeCompare(name(b) || "")
        || (a.id || "").localeCompare(b.id || "");
}
