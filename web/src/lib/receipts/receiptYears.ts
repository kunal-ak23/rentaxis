/** One download = one contract, at most this many receipts. */
export const MAX_COMBINED_RECEIPTS = 100;

export type ReceiptRow = {
    id: string;
    clearedAt: string | null;
    /** chequeDate (staff) or dueDate (renter), ISO YYYY-MM-DD. */
    date: string;
};

/** Calendar year of the received date: clearedAt, else date. Read from the ISO text, no timezone shift. */
export function receivedYear(row: ReceiptRow): number {
    return Number((row.clearedAt ?? row.date).slice(0, 4));
}

/** Distinct years, newest first. */
export function receiptYears(rows: ReceiptRow[]): number[] {
    return [...new Set(rows.map(receivedYear))].sort((a, b) => b - a);
}

/** Ids received in the year, input order kept. */
export function idsInYear(rows: ReceiptRow[], year: number): string[] {
    return rows.filter(r => receivedYear(r) === year).map(r => r.id);
}
