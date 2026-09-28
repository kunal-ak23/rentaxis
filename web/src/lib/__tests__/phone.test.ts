import { describe, expect, it } from "vitest";
import { isPlausiblePhone, normalizePhone } from "../phone";

// Same lists as the backend's UpdateProfileRequestValidationTest: the two rules must agree.
describe("isPlausiblePhone", () => {
    it.each(["+971501234567", "+971 50 123 4567", "+971-4-555-0100", "050 8831786", "0501234567",
        "04 123 9911", "00971505555555", "+971 (4) 555 0100", "+971412399 11", "", "  "])("accepts %j", (p) => {
        expect(isPlausiblePhone(p)).toBe(true);
    });
    it.each(["notaphone!!!123", "abc", "12345", "+971 50 abc 4567", "((((((((", "1234567890123456",
        "+971501234567; drop"])("refuses %j", (p) => {
        expect(isPlausiblePhone(p)).toBe(false);
    });
});

// Break-it round 2 M6: web and backend must agree on Unicode spaces and Arabic-Indic
// digits too, not just ASCII. Mirrors PhoneNumbersTest / UpdateProfileRequestValidationTest.
describe("isPlausiblePhone — Unicode normalization (M6)", () => {
    it.each([
        "+971 50 123 4567", // non-breaking space (U+00A0)
        "+971 50 123 4567", // narrow no-break space (U+202F)
        "+971 50 123 4567", // thin space (U+2009)
    ])("accepts a number with Unicode spaces %j", (p) => {
        expect(isPlausiblePhone(p)).toBe(true);
    });

    it("accepts Arabic-Indic digits", () => {
        expect(isPlausiblePhone("+٩٧١٥٠١٢٣٤٥٦٧")).toBe(true);
    });
});

describe("normalizePhone", () => {
    it("collapses Unicode spaces to a plain space", () => {
        expect(normalizePhone("+971 50 123 4567")).toBe("+971 50 123 4567");
    });

    it("maps Arabic-Indic and extended (Persian) Arabic-Indic digits to ASCII", () => {
        expect(normalizePhone("+٩٧١٥٠١٢٣٤٥٦٧"))
            .toBe("+971501234567");
        expect(normalizePhone("+۹۷۱۵۰۱۲۳۴۵۶۷"))
            .toBe("+971501234567");
    });

    it("leaves plain ASCII untouched", () => {
        expect(normalizePhone("+971 50 123 4567")).toBe("+971 50 123 4567");
    });
});
