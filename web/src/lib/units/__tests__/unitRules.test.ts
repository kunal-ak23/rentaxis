import { describe, expect, it } from "vitest";
import { BUILDING_FLOORS_MAX, BUILDING_FLOORS_MIN, floorsInRange, refusalOf, sizeIsValid } from "../unitRules";

describe("unit rules (break-it R3 ops3 F1/F3)", () => {
    it("floors are whole numbers from 1 to 200", () => {
        expect(BUILDING_FLOORS_MIN).toBe(1);
        expect(BUILDING_FLOORS_MAX).toBe(200);
        for (const bad of [-3, 0, 201, 99999, 2.5, NaN]) expect(floorsInRange(bad)).toBe(false);
        for (const ok of [1, 12, 200]) expect(floorsInRange(ok)).toBe(true);
    });

    it("a size, when given, is above zero", () => {
        expect(sizeIsValid("")).toBe(true);
        expect(sizeIsValid("850")).toBe(true);
        expect(sizeIsValid("0")).toBe(false);
        expect(sizeIsValid("-50")).toBe(false);
        expect(sizeIsValid("abc")).toBe(false);
    });

    it("reads a coded refusal", () => {
        expect(refusalOf(JSON.stringify({ code: "unit.numberTaken", args: { unitNumber: "101", place: "Tower A" } })))
            .toEqual({ code: "unit.numberTaken", args: { unitNumber: "101", place: "Tower A" } });
        expect(refusalOf(JSON.stringify({ message: "x" }))).toBeNull();
        expect(refusalOf("<html>")).toBeNull();
        expect(refusalOf(undefined)).toBeNull();
    });
});
