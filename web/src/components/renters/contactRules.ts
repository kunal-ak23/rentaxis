import { isPlausiblePhone, normalizePhone } from "@/lib/phone";

/**
 * Client request 2026-10-29: every tenant has an email and a mobile. The Add and
 * Edit tenant forms check both before anything is sent, with the same lenient
 * mobile rule the server applies (`PhoneNumbers.LENIENT_PATTERN`, `@/lib/phone`):
 * digits, spaces, "+", "-" and brackets, 7-15 digits, after normalization.
 *
 * The answer is a `MasterData` message key, or null when both are fine.
 */
export type ContactProblem = "emailRequired" | "phoneRequired" | "phoneInvalid";

export function contactProblem(email: string, phone: string): ContactProblem | null {
    if (!email.trim()) return "emailRequired";
    if (!phone.trim()) return "phoneRequired";
    if (!isPlausiblePhone(phone)) return "phoneInvalid";
    return null;
}

/** The mobile as it is sent and stored: Unicode spaces and Arabic-Indic digits made plain, trimmed. */
export function sentPhone(phone: string): string {
    return normalizePhone(phone).trim();
}
