// src/lib/pool.ts

/**
 * Runs `worker` over `items` with at most `limit` calls in flight, and returns
 * the results in the items' own order (not completion order). A worker that
 * throws does not stop the others — wrap failures into the result in `worker`.
 * `onSettled(done)` fires after each item finishes, for a progress count.
 */
export async function runPool<T, R>(
    items: readonly T[],
    limit: number,
    worker: (item: T, index: number) => Promise<R>,
    onSettled?: (done: number) => void,
): Promise<R[]> {
    const results = new Array<R>(items.length);
    let next = 0;
    let done = 0;
    const lane = async () => {
        while (next < items.length) {
            const i = next++;
            results[i] = await worker(items[i], i);
            onSettled?.(++done);
        }
    };
    await Promise.all(Array.from({ length: Math.max(1, Math.min(limit, items.length)) }, lane));
    return results;
}
