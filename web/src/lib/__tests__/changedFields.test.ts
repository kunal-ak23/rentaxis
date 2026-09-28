import { describe, expect, it } from "vitest";
import { changedFields, sameIdSet } from "../changedFields";

describe("changedFields (break-it R3 ops3 F4/F6)", () => {
    const loaded = { name: "Acme", address: "Old", trn: "", ticketOtpRequired: true, phone: undefined as string | undefined };

    it("returns only the changed fields and what the form loaded for them", () => {
        const current = { ...loaded, address: "New" };
        expect(changedFields(loaded, current, ["name", "address", "trn", "ticketOtpRequired", "phone"])).toEqual({
            changes: { address: "New" },
            expected: { address: "Old" },
        });
    });

    it("treats empty string, null and undefined as the same", () => {
        const current = { ...loaded, trn: undefined as unknown as string, phone: "" };
        expect(changedFields(loaded, current, ["trn", "phone"]).changes).toEqual({});
    });

    it("sees a boolean flip", () => {
        expect(changedFields(loaded, { ...loaded, ticketOtpRequired: false }, ["ticketOtpRequired"])).toEqual({
            changes: { ticketOtpRequired: false },
            expected: { ticketOtpRequired: true },
        });
    });

    it("ignores keys it was not asked about", () => {
        expect(changedFields({ a: 1, b: 1 }, { a: 1, b: 2 }, ["a"]).changes).toEqual({});
    });
});

describe("sameIdSet", () => {
    it("ignores order and repeats", () => {
        expect(sameIdSet(["a", "b"], ["b", "a", "a"])).toBe(true);
        expect(sameIdSet(["a", "b"], ["a"])).toBe(false);
        expect(sameIdSet([], [])).toBe(true);
    });
});
