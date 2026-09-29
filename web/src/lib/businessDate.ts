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

/**
 * Break-it round 1 (money) F4: the last date a manual posting — a journal
 * voucher, a payment or receipt voucher, a cash receipt — may carry: one year
 * after the business today. The server refuses anything later
 * (`ManualPostingDates`), because journal numbers carry a two-digit year and a
 * date a century out (2126 for 2026) would take this year's numbers.
 *
 * Java's `LocalDate.plusYears(1)` turns 29 February into 28 February; so does this.
 */
export function maxManualPostingDateIso(): string {
    const [y, m, d] = businessTodayIso().split("-").map(Number);
    const year = y + 1;
    const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
    const day = m === 2 && d === 29 && !leap ? 28 : d;
    return `${year}-${String(m).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

/** True when an ISO date is after {@link maxManualPostingDateIso}. A blank date is not "beyond" anything. */
export function isBeyondManualPostingWindow(iso: string | null | undefined): boolean {
    if (!iso) return false;
    // A date input accepts five-digit years; "20260-01-01" sorts below "2027-…" as text.
    const year = iso.split("-")[0];
    if (year.length > 4) return true;
    return iso > maxManualPostingDateIso();
}

/**
 * Break-it round 2 (money2) F1/F2/F3: true when an ISO date is after the business
 * today — a deposit, clearing, bounce, write-off or period lock the server refuses
 * (`ManualPostingDates.requireNotAfterToday`). A blank date is not "after" anything.
 */
export function isAfterBusinessToday(iso: string | null | undefined): boolean {
    if (!iso) return false;
    // A date input accepts five-digit years; "20260-01-01" sorts below "2027-…" as text.
    if (iso.split("-")[0].length > 4) return true;
    return iso > businessTodayIso();
}

/** `iso` (`yyyy-MM-dd`) plus `days` calendar days, as `yyyy-MM-dd`. Pure date arithmetic in UTC, so no zone can shift it. */
export function addDaysIso(iso: string, days: number): string {
    const [y, m, d] = iso.split("-").map(Number);
    return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/**
 * `iso` plus `months` calendar months, the day clamped to the target month's
 * last day (31 January + 1 month = 28/29 February), as Java's `plusMonths` does.
 */
export function addMonthsIso(iso: string, months: number): string {
    const [y, m, d] = iso.split("-").map(Number);
    const target = new Date(Date.UTC(y, m - 1 + months, 1));
    const last = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate();
    target.setUTCDate(Math.min(d, last));
    return target.toISOString().slice(0, 10);
}
