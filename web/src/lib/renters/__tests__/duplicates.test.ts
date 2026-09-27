import { afterEach, describe, expect, it, vi } from "vitest";
import { findDuplicateRenters, normaliseEmail, phoneDigits, phonesMatch } from "../duplicates";

const jsonRes = (body: unknown, ok = true, status = 200) =>
    ({ ok, status, json: async () => body, text: async () => JSON.stringify(body) }) as unknown as Response;

afterEach(() => vi.restoreAllMocks());

describe("renter duplicate matching", () => {
    it("normalises email case-insensitively and trimmed", () => {
        expect(normaliseEmail("  Ali@Example.COM ")).toBe("ali@example.com");
        expect(normaliseEmail("   ")).toBe("");
    });

    it("compares phones by digits, ignoring formatting and the UAE country/trunk prefix", () => {
        expect(phoneDigits("+971 50 123-4567")).toBe("971501234567");
        expect(phonesMatch("+971 50 123 4567", "0501234567")).toBe(true);
        expect(phonesMatch("00971501234567", "050-123-4567")).toBe(true);
        expect(phonesMatch("0501234567", "0501234568")).toBe(false);
        // Short numbers compare exactly — "4567" is not the same phone as "0501234567".
        expect(phonesMatch("4567", "0501234567")).toBe(false);
        expect(phonesMatch("", "")).toBe(false);
    });

    it("finds an existing renter by email via the server search", async () => {
        const urls: string[] = [];
        global.fetch = vi.fn(async (url: unknown) => {
            urls.push(String(url));
            return jsonRes([
                { id: "r1", nameEn: "Ali", nameAr: null, email: "ALI@example.com", phone: null },
                { id: "r2", nameEn: "Alina", nameAr: null, email: "alina@example.com", phone: null },
            ]);
        }) as unknown as typeof fetch;

        const matches = await findDuplicateRenters({ email: " ali@EXAMPLE.com ", phone: "" });
        expect(urls[0]).toContain("/api/proxy/v1/renters/search?q=ali%40example.com");
        expect(matches.map((m) => [m.renter.id, m.by])).toEqual([["r1", ["email"]]]);
    });

    it("finds an existing renter by phone whatever the formatting", async () => {
        const urls: string[] = [];
        global.fetch = vi.fn(async (url: unknown) => {
            urls.push(String(url));
            return jsonRes([
                { id: "r3", nameEn: "Sara", nameAr: null, email: null, phone: "+971 50 123 4567" },
                { id: "r4", nameEn: "Other", nameAr: null, email: null, phone: "+971 55 000 4567" },
            ]);
        }) as unknown as typeof fetch;

        const matches = await findDuplicateRenters({ email: "", phone: "050-123-4567" });
        // The search is by the number's last four digits (contiguous in every
        // common format); the exact comparison is digits-normalised.
        expect(urls[0]).toContain("/renters/search?q=4567");
        expect(matches.map((m) => m.renter.id)).toEqual(["r3"]);
    });

    it("merges an email and a phone match on the same renter", async () => {
        global.fetch = vi.fn(async () =>
            jsonRes([{ id: "r5", nameEn: "Both", nameAr: null, email: "b@x.com", phone: "0501234567" }]),
        ) as unknown as typeof fetch;
        const matches = await findDuplicateRenters({ email: "B@x.com", phone: "+971501234567" });
        expect(matches).toHaveLength(1);
        expect(matches[0].by).toEqual(["email", "phone"]);
    });

    it("returns nothing (and does not search) with no email and no phone", async () => {
        const f = vi.fn();
        global.fetch = f as unknown as typeof fetch;
        expect(await findDuplicateRenters({ email: " ", phone: "" })).toEqual([]);
        expect(f).not.toHaveBeenCalled();
    });
});
