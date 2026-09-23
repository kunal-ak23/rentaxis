/**
 * The business time zone. The backend runs with `app.time-zone` (Asia/Dubai) as
 * its default zone, so every "today" it validates against is Dubai's date.
 */
export const BUSINESS_TIME_ZONE = "Asia/Dubai";

/**
 * Today's date in the business zone, as `yyyy-MM-dd`.
 *
 * Use it wherever the server compares a submitted date with *its* today — a
 * default or `max` taken from the browser's own date is a day ahead for a
 * viewer east of Dubai just after their midnight, and the server refuses it as
 * a future date. `en-CA` formats as ISO `yyyy-MM-dd`.
 */
export function businessTodayIso(): string {
    return new Intl.DateTimeFormat("en-CA", {
        timeZone: BUSINESS_TIME_ZONE,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
    }).format(new Date());
}
