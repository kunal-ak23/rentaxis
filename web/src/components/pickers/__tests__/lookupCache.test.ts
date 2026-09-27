import { describe, expect, it, vi } from "vitest";
import { createLookupCache } from "@/components/pickers/lookupCache";

type Row = { id: string; label: string };

/**
 * createLookupCache against a stubbed fetchNames (the shape `lookupApi.unitNames`/
 * `renterNames` return: `{ rows, failedIds }` from Promise.allSettled over the
 * server's chunks — see lib/api/lookup.ts). Exercises the missing-vs-failed
 * distinction directly, since `useIdNames`'s `isPending` treats both the same
 * way and cannot tell them apart from the outside:
 *  - an id a *successful* chunk answered without → "missing", never retried.
 *  - an id whose chunk *failed* → retryable by the next `resolveMany` call.
 * A batch that mixes both must not let one contaminate the other.
 */
describe("createLookupCache — partial chunk failure", () => {
    it("marks only the failed chunk's ids as retryable, and only a successful chunk's unanswered id as missing", async () => {
        const fetchNames = vi.fn(async (ids: string[]) => {
            const rows: Row[] = [];
            const failedIds: string[] = [];
            for (const id of ids) {
                if (id.startsWith("ok-missing")) continue; // successful chunk, id not in the answer
                if (id.startsWith("bad-")) failedIds.push(id); // this id's chunk failed
                else rows.push({ id, label: `L-${id}` });
            }
            return { rows, failedIds };
        });
        const cache = createLookupCache<Row>(fetchNames);

        await cache.resolveMany(["ok-1", "ok-missing", "bad-1", "bad-2"]);

        expect(cache.get("ok-1")).toEqual({ id: "ok-1", label: "L-ok-1" });
        // Missing: settled (not pending), never labeled, not retried.
        expect(cache.isPending("ok-missing")).toBe(false);
        expect(cache.get("ok-missing")).toBeUndefined();
        // Failed: settled for now, but retryable.
        expect(cache.isPending("bad-1")).toBe(false);
        expect(cache.isPending("bad-2")).toBe(false);

        fetchNames.mockClear();
        await cache.resolveMany(["ok-1", "ok-missing", "bad-1", "bad-2"]);

        // Only the failed chunk's ids are asked for again — not the successful
        // chunk's answered id, and not its unanswered (missing) id either.
        expect(fetchNames).toHaveBeenCalledTimes(1);
        expect(fetchNames).toHaveBeenCalledWith(["bad-1", "bad-2"]);
    });

    it("a retried id that now succeeds is labeled and stops being retried", async () => {
        let fail = true;
        const fetchNames = vi.fn(async (ids: string[]) => {
            if (fail) return { rows: [], failedIds: ids };
            return { rows: ids.map((id) => ({ id, label: `L-${id}` })), failedIds: [] };
        });
        const cache = createLookupCache<Row>(fetchNames);

        await cache.resolveMany(["x1"]);
        expect(cache.isPending("x1")).toBe(false);
        expect(cache.get("x1")).toBeUndefined();

        fail = false;
        await cache.resolveMany(["x1"]);
        expect(cache.get("x1")).toEqual({ id: "x1", label: "L-x1" });

        fetchNames.mockClear();
        await cache.resolveMany(["x1"]);
        expect(fetchNames).not.toHaveBeenCalled();
    });
});
