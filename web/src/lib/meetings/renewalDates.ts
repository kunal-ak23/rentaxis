/**
 * Tutorial 23: a renewal proposed from a meeting started ON the current
 * contract's last day ("ends 31/12/2026, renewal starts 31/12/2026"), so the
 * two terms overlapped by a day. A renewal starts the day after the old end and
 * runs `months` months, ending the day before the same date `months` later
 * (start 1 Jan 2027 + 12 months → ends 31 Dec 2027). Pure calendar arithmetic on
 * `yyyy-MM-dd` strings, in UTC, so the browser's zone cannot shift a day.
 */

function parse(iso: string): Date {
    const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
    return new Date(Date.UTC(y, m - 1, d));
}

function format(d: Date): string {
    return d.toISOString().slice(0, 10);
}

/** The day after `iso`. */
export function nextDay(iso: string): string {
    const d = parse(iso);
    d.setUTCDate(d.getUTCDate() + 1);
    return format(d);
}

/** Adds calendar months, clamping to the month's last day (31 Jan + 1 month → 28/29 Feb). */
function addMonths(d: Date, months: number): Date {
    const day = d.getUTCDate();
    const out = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + months, 1));
    const last = new Date(Date.UTC(out.getUTCFullYear(), out.getUTCMonth() + 1, 0)).getUTCDate();
    out.setUTCDate(Math.min(day, last));
    return out;
}

/** The proposed renewal term for a contract ending `currentEnd`, `months` long. */
export function renewalTerm(currentEnd: string, months: number | null): { start: string; end: string | null } {
    const start = nextDay(currentEnd);
    if (!months || months <= 0) return { start, end: null };
    const end = addMonths(parse(start), months);
    end.setUTCDate(end.getUTCDate() - 1);
    return { start, end: format(end) };
}

/**
 * Months in a term from `start` to `end` inclusive (1 Jan–31 Dec is 12), to the
 * nearest month — so a proposal saved before the start moved (31 Dec 2026 –
 * 31 Dec 2027, a day long) still reads 12, not 13.
 */
export function termMonths(start: string, end: string): number {
    const days = (parse(nextDay(end)).getTime() - parse(start).getTime()) / 86_400_000;
    return Math.round(days / (365.25 / 12));
}
