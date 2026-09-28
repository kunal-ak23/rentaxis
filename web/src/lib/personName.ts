/**
 * Break-it R3 portal3 F6: a person's name as the backend stores it
 * (UnicodeText.normalizeName): zero-width characters, bidi controls and
 * variation selectors removed, every run of Unicode spaces (NBSP, narrow NBSP,
 * ideographic space…) collapsed to one space, trimmed. Empty means "no name" —
 * the form refuses it instead of showing "Saved".
 */
const INVISIBLE = new RegExp(
    "[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF\\u061C\\u180E\\u00AD\\uFE00-\\uFE0F\\u{E0000}-\\u{E007F}\\u{E0100}-\\u{E01EF}]",
    "gu",
);
const SPACES = new RegExp("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+", "gu");

export function normalizePersonName(value: string): string {
    return value.replace(INVISIBLE, "").replace(SPACES, " ").trim();
}
