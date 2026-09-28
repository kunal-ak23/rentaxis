import { describe, expect, it } from "vitest";
import { normalizePersonName } from "../personName";

// Break-it R3 portal3 F6: the same rule as the backend (UnicodeText.normalizeName).
describe("normalizePersonName", () => {
    it("is empty for spaces, NBSPs, zero-width and bidi-control-only input", () => {
        for (const v of ["    ", "​​​", "   　\t", "‏‮‬﻿⁦⁩", ""]) {
            expect(normalizePersonName(v)).toBe("");
        }
    });

    it("trims and collapses Unicode spaces and drops invisible controls, keeping letters", () => {
        expect(normalizePersonName("  Rajesh  Kumar  ")).toBe("Rajesh Kumar");
        expect(normalizePersonName("O'Neil ‏‮evil‬")).toBe("O'Neil evil");
        expect(normalizePersonName(" بي آر ")).toBe("بي آر");
    });
});
