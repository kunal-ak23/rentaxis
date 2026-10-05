import { describe, expect, it } from "vitest";
import { oldestFirst } from "../oldestFirst";

describe("oldestFirst", () => {
    it("sorts by createdAt, then name, then id; rows without a date go last", () => {
        const rows = [
            { id: "3", name: "B", createdAt: "2026-01-01T00:00:00" },
            { id: "2", name: "A", createdAt: "2026-01-01T00:00:00" },
            { id: "1", name: "Z", createdAt: null },
            { id: "4", name: "C", createdAt: "2025-12-31T00:00:00" },
        ];
        expect([...rows].sort(oldestFirst<typeof rows[number]>(r => r.name)).map(r => r.id)).toEqual(["4", "2", "3", "1"]);
    });
});
