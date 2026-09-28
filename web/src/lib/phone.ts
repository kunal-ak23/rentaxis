/**
 * The lenient phone rule PUT /auth/me enforces (AuthController.PHONE_PATTERN):
 * digits, spaces, "+", "-", parentheses, 7-15 digits; blank clears the phone.
 * Local UAE forms ("050 8831786", "04 123 9911") are valid. Keep the two in step.
 */
export const PHONE_PATTERN = /^\s*$|^\s*\+?[\s()-]*(?:\d[\s()-]*){7,15}$/;

/**
 * Break-it R2 M6: this regex's `\s` is JS's Unicode-aware whitespace class, so a
 * number pasted from iOS or WhatsApp with a non-breaking space (U+00A0) or narrow
 * no-break space (U+202F) between groups passed here — while the backend's Java
 * `\s` is ASCII-only and rejected the same string with a 400. The reverse gap
 * (Arabic-Indic digits) existed too: `\d` here is also Unicode-aware and accepted
 * them, but Java's `\d` would not. Normalize on both sides before validating, and
 * store the normalized text, so the two rules can never disagree over a form the
 * other treats as fine — see PhoneNumbers.normalizeUnicode on the backend, which
 * this mirrors exactly.
 */
export function normalizePhone(value: string): string {
    let out = "";
    for (const ch of value) {
        const code = ch.codePointAt(0)!;
        if (code >= 0x0660 && code <= 0x0669) {
            // Arabic-Indic digits ٠-٩
            out += String.fromCharCode(0x30 + (code - 0x0660));
        } else if (code >= 0x06f0 && code <= 0x06f9) {
            // Extended (Persian) Arabic-Indic digits ۰-۹
            out += String.fromCharCode(0x30 + (code - 0x06f0));
        } else if (/\s/.test(ch)) {
            // Every Unicode space (NBSP, narrow NBSP, etc.) collapses to a plain space.
            out += " ";
        } else {
            out += ch;
        }
    }
    return out;
}

export function isPlausiblePhone(value: string): boolean {
    return PHONE_PATTERN.test(normalizePhone(value));
}
