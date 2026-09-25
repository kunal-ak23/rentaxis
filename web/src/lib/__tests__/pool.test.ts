import { describe, expect, it } from "vitest";
import { runPool } from "../pool";

const tick = (ms: number) => new Promise(r => setTimeout(r, ms));

describe("runPool", () => {
    it("never has more than `limit` calls in flight, and runs them concurrently up to it", async () => {
        let inFlight = 0, peak = 0;
        await runPool([1, 2, 3, 4, 5, 6, 7], 3, async () => {
            inFlight++; peak = Math.max(peak, inFlight);
            await tick(5);
            inFlight--;
        });
        expect(peak).toBe(3);
    });

    it("returns results in the items' order, not completion order", async () => {
        const out = await runPool([30, 5, 15, 1], 4, async ms => { await tick(ms); return ms; });
        expect(out).toEqual([30, 5, 15, 1]);
    });

    it("reports progress once per item", async () => {
        const seen: number[] = [];
        await runPool(["a", "b", "c"], 2, async x => x, d => seen.push(d));
        expect(seen).toEqual([1, 2, 3]);
    });

    it("handles an empty list and a limit above the item count", async () => {
        expect(await runPool([], 4, async x => x)).toEqual([]);
        expect(await runPool([1], 10, async x => x * 2)).toEqual([2]);
    });
});
