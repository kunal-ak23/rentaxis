import { describe, expect, it } from "vitest";
import { PHONE_COUNTRIES, guardAuthErrorKey, guardSignInErrorKey, normaliseOtp, toGuardE164 } from "../phone";

const [UAE, INDIA] = PHONE_COUNTRIES;

describe("guard phone numbers — as the Security app reads them", () => {
    it("builds E.164 from a national number, dropping spaces and the trunk 0", () => {
        expect(toGuardE164(UAE, "50 123 4567")).toBe("+971501234567");
        expect(toGuardE164(UAE, "050-123-4567")).toBe("+971501234567");
        expect(toGuardE164(INDIA, "98765 43210")).toBe("+919876543210");
    });

    it("refuses a short, long or non-numeric number", () => {
        expect(toGuardE164(UAE, "5012345")).toBeNull();
        expect(toGuardE164(UAE, "5012345678")).toBeNull();
        expect(toGuardE164(UAE, "+971501234567")).toBeNull();
        expect(toGuardE164(INDIA, "abc")).toBeNull();
    });

    it("accepts only a six-digit code", () => {
        expect(normaliseOtp(" 123 456 ")).toBe("123456");
        expect(normaliseOtp("12345")).toBeNull();
        expect(normaliseOtp("12a456")).toBeNull();
    });

    it("maps Firebase and sign-in errors to messages, never the raw code", () => {
        expect(guardAuthErrorKey("auth/too-many-requests")).toBe("errTooManyRequests");
        expect(guardAuthErrorKey("auth/invalid-verification-code")).toBe("errInvalidCode");
        expect(guardAuthErrorKey("auth/something-new")).toBe("errGeneric");
        expect(guardSignInErrorKey("RATE_LIMITED")).toBe("errTooManyRequests");
        expect(guardSignInErrorKey("CredentialsSignin")).toBe("errNotAGuard");
    });
});
