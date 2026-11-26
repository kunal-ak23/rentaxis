import { describe, expect, it } from "vitest";
import { dewaProblem, normaliseDewa } from "../dewa";

describe("DEWA premise number", () => {
    it("strips spaces", () => expect(normaliseDewa("681 204 553")).toBe("681204553"));
    it("strips other whitespace", () => expect(normaliseDewa(" 681\t204\n553 ")).toBe("681204553"));
    it("rejects letters", () => expect(dewaProblem("12AB")).toBe("invalid"));
    it("rejects too short and too long", () => {
        expect(dewaProblem("12345")).toBe("invalid");
        expect(dewaProblem("1234567890123")).toBe("invalid");
    });
    it("accepts 6 to 12 digits, with spaces", () => {
        expect(dewaProblem("123456")).toBeNull();
        expect(dewaProblem("123 456 789 012")).toBeNull();
    });
    it("treats blank as not set", () => {
        expect(dewaProblem("")).toBeNull();
        expect(dewaProblem("   ")).toBeNull();
    });
});
