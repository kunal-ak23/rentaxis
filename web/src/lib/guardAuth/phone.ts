/**
 * Guard phone sign-in on the web (tutorial 25): the same countries and number
 * rules as the Security app (mobile/apps/security/lib/auth/phone_country.dart),
 * so a guard types their number the same way on both. UAE first: it is the
 * production country; India is where the app is tested from.
 */
export type PhoneCountry = { iso: "AE" | "IN"; dialCode: string; nationalDigits: number; example: string };

export const PHONE_COUNTRIES: PhoneCountry[] = [
    { iso: "AE", dialCode: "+971", nationalDigits: 9, example: "501234567" },
    { iso: "IN", dialCode: "+91", nationalDigits: 10, example: "9876543210" },
];

/**
 * The E.164 number for what the guard typed, or null when it is not a full
 * national number for the country. Spaces, dashes and brackets are ignored, and
 * a trunk '0' (050 123 4567) is dropped, as guards habitually type it.
 */
export function toGuardE164(country: PhoneCountry, typed: string): string | null {
    let digits = typed.replace(/[\s\-().]/g, "");
    if (!/^\d+$/.test(digits)) return null;
    if (digits.length === country.nationalDigits + 1 && digits.startsWith("0")) digits = digits.slice(1);
    if (digits.length !== country.nationalDigits) return null;
    return `${country.dialCode}${digits}`;
}

/** A six-digit SMS code, after dropping spaces the guard may have typed. */
export function normaliseOtp(typed: string): string | null {
    const digits = typed.replace(/\s/g, "");
    return /^\d{6}$/.test(digits) ? digits : null;
}

/** Seconds before "Send a new code" is offered again — the Security app's cooldown. */
export const RESEND_COOLDOWN_SECONDS = 60;

/**
 * The GuardSignIn translation key for a Firebase Auth error code (as the
 * Security app's firebase_auth_errors.dart). Anything unknown is the generic
 * message: an unexpected code must never be shown raw.
 */
export function guardAuthErrorKey(code: string | undefined | null): string {
    switch (code) {
        case "auth/invalid-phone-number": return "errInvalidPhone";
        case "auth/too-many-requests": return "errTooManyRequests";
        case "auth/quota-exceeded": return "errQuotaExceeded";
        case "auth/network-request-failed": return "errNetwork";
        case "auth/invalid-verification-code": return "errInvalidCode";
        case "auth/code-expired":
        case "auth/session-expired": return "errCodeExpired";
        case "auth/captcha-check-failed": return "errCaptcha";
        default: return "errGeneric";
    }
}

/** The GuardSignIn key for what NextAuth's guard provider reported after Firebase verified the code. */
export function guardSignInErrorKey(error: string | null | undefined): string {
    switch (error) {
        case "RATE_LIMITED": return "errTooManyRequests";
        case "GUARD_PHONE_UNAVAILABLE": return "errUnavailable";
        default: return "errNotAGuard";
    }
}
