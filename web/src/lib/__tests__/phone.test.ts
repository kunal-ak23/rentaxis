import { describe, expect, it } from "vitest";
import { isPlausiblePhone } from "../phone";

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
