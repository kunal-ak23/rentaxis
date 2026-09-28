import { BUSINESS_TIME_ZONE } from "@/lib/businessDate";

/**
 * Break-it R3 portal3 F4: a meeting slot instant (ISO, UTC from the server) as
 * the Dubai wall-clock time, in the user's locale — "Fri, 2 Oct 2026, 10:30 AM"
 * / its Arabic form. The raw "2026-10-02T06:30:00Z" read as 06:30, four hours
 * off. Null when the value is not an instant (the server sends "unavailable").
 */
export function formatSlotInDubai(iso: string | null | undefined, locale: string): string | null {
    if (!iso) return null;
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return null;
    return new Intl.DateTimeFormat(locale === "ar" ? "ar-AE" : "en-GB", {
        timeZone: BUSINESS_TIME_ZONE,
        weekday: "short",
        day: "numeric",
        month: "short",
        year: "numeric",
        hour: "numeric",
        minute: "2-digit",
        hour12: true,
    }).format(d);
}
