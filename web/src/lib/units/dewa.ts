/** DEWA premise number: 6 to 12 digits (mirrors the server's `^\d{6,12}$`). */
export function normaliseDewa(s: string): string {
    return s.replace(/\s+/g, "");
}

/** `"invalid"` when a non-blank value is not 6 to 12 digits once spaces are stripped; blank means not set. */
export function dewaProblem(s: string): "invalid" | null {
    const n = normaliseDewa(s);
    if (n === "") return null;
    return /^\d{6,12}$/.test(n) ? null : "invalid";
}
