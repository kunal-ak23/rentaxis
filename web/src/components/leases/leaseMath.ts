/**
 * The arithmetic the PACT lease screens share, kept in one file so the lines
 * grid, the cheque grid and the review step cannot disagree about a figure the
 * accountant is reading off three panels at once.
 *
 * Every rule here mirrors the backend's `LeaseVat` exactly (spec §6.2):
 * 5% on the line's NET, rounded to the fils PER LINE and then summed — never
 * rounded on the sum — and never on a DEPOSIT-behaviour line, whatever that
 * line's own `vatApplicable` flag says. A deposit is refundable money held
 * against the tenancy, not consideration for a supply.
 */

import type { ChargeBehaviour, ChargeType, LeaseLine, LeaseLineInput } from "@/lib/api/leasing";

/** UAE standard rate. Not configurable: a change is a tax event, not a setting. */
export const VAT_RATE = 0.05;

/** Half-up to the fils. `+ Number.EPSILON` keeps 1.005 from falling to 1.00. */
export function round2(n: number): number {
    if (!Number.isFinite(n)) return 0;
    return Math.round((n + Number.EPSILON) * 100) / 100;
}

/**
 * One row of either grid mode. A draft row carries only what the caller can
 * edit; a row read back from the server also carries the account's code and
 * name, which is all the read-only view needs (it never resolves the picker).
 */
export type LineRow = {
    /** Stable across inserts and removals so React does not re-key the grid. */
    key: number;
    id?: string | null;
    chargeTypeId: string | null;
    grossAmount: number;
    discountAmount: number;
    narration: string;
    vatApplicable: boolean;
    creditAccountId: string | null;
    creditAccountCode?: string | null;
    creditAccountName?: string | null;
};

export function blankLine(key: number): LineRow {
    return {
        key,
        chargeTypeId: null,
        grossAmount: 0,
        discountAmount: 0,
        narration: "",
        vatApplicable: false,
        creditAccountId: null,
    };
}

/** A persisted line, as the grid wants it. */
export function toRow(line: LeaseLine, key: number): LineRow {
    return {
        key,
        id: line.id,
        chargeTypeId: line.chargeTypeId,
        grossAmount: line.grossAmount ?? 0,
        discountAmount: line.discountAmount ?? 0,
        narration: line.narration ?? "",
        vatApplicable: !!line.vatApplicable,
        creditAccountId: line.creditAccountId,
        creditAccountCode: line.creditAccountCode,
        creditAccountName: line.creditAccountName,
    };
}

export function toRows(lines: LeaseLine[]): LineRow[] {
    return lines.map((l, i) => toRow(l, i));
}

/** The wire shape. Blank narrations go over as null, not "". */
export function toInput(row: LineRow): LeaseLineInput {
    return {
        chargeTypeId: row.chargeTypeId,
        grossAmount: row.grossAmount || 0,
        discountAmount: row.discountAmount || 0,
        narration: row.narration.trim() || null,
        vatApplicable: row.vatApplicable,
        creditAccountId: row.creditAccountId,
    };
}

export function toInputs(rows: LineRow[]): LeaseLineInput[] {
    return rows.map(toInput);
}

export function behaviourOf(row: LineRow, chargeTypes: ChargeType[]): ChargeBehaviour | null {
    return chargeTypes.find(c => c.id === row.chargeTypeId)?.behaviour ?? null;
}

/** What the line actually charges, before tax. */
export function netOf(row: LineRow): number {
    return round2((row.grossAmount || 0) - (row.discountAmount || 0));
}

/** VAT on the line — zero when not applicable, and always zero on a deposit. */
export function vatOf(row: LineRow, chargeTypes: ChargeType[]): number {
    if (!row.vatApplicable) return 0;
    if (behaviourOf(row, chargeTypes) === "DEPOSIT") return 0;
    const net = netOf(row);
    return net > 0 ? round2(net * VAT_RATE) : 0;
}

export type LineTotals = {
    gross: number;
    discount: number;
    net: number;
    vat: number;
    /** Contract value including VAT — what the cheque grid must add up to. */
    inclVat: number;
};

export function totalsOf(rows: LineRow[], chargeTypes: ChargeType[]): LineTotals {
    let gross = 0, discount = 0, net = 0, vat = 0;
    for (const r of rows) {
        gross = round2(gross + (r.grossAmount || 0));
        discount = round2(discount + (r.discountAmount || 0));
        net = round2(net + netOf(r));
        vat = round2(vat + vatOf(r, chargeTypes));
    }
    return { gross, discount, net, vat, inclVat: round2(net + vat) };
}

/**
 * Whether a set of lines may be submitted at all.
 *
 * One definition, used by the wizard and by all three dialogs that send lines
 * (amend, renew with its own lines, extend). They had drifted: the wizard
 * refused to advance past a line with no charge type or a discount over its
 * amount, while the dialogs happily posted the same rows and collected the
 * server's 400 — after the accountant had typed the whole grid.
 *
 * Non-positive amounts are refused here too. A zero line raises no posting
 * pair at all (`LeasePostingService#planLines` only pairs `lineNet > 0`), so
 * it reaches the ledger as nothing while still occupying a row on the printed
 * contract; a negative one is a refund, which is a credit note, not a lease
 * line.
 */
export function linesAreValid(rows: LineRow[]): boolean {
    if (rows.length === 0) return false;
    return rows.every(
        r =>
            !!r.chargeTypeId &&
            (r.grossAmount || 0) > 0 &&
            (r.discountAmount || 0) >= 0 &&
            (r.discountAmount || 0) <= (r.grossAmount || 0),
    );
}

/**
 * The credit account a charge type wants. Deposits and advance rent are money
 * the landlord owes back or has not yet earned, so they sit on the liability
 * side; a fee is earned on signature and is income. Used only to narrow the
 * picker — the server re-checks on post and its 400 is what actually decides.
 */
export function accountTypeFor(behaviour: ChargeBehaviour | null): string | undefined {
    if (behaviour === "DEPOSIT" || behaviour === "RENT") return "LIABILITY";
    if (behaviour === "FEE") return "INCOME";
    return undefined;
}

/**
 * The backend addresses a line's validation failure by its 1-based seqNo:
 * `Line 2 (ADMIN_FEE): credit account 200100 is inactive.` Pulling the number
 * out puts the complaint next to the row it is about; anything that does not
 * parse stays whole in the banner rather than being dropped.
 */
export function splitLineErrors(errors: string[]): { bySeq: Map<number, string[]>; rest: string[] } {
    const bySeq = new Map<number, string[]>();
    const rest: string[] = [];
    for (const raw of errors) {
        const m = /^Line\s+(\d+)\s*\(([^)]*)\)\s*:?\s*(.*)$/.exec(raw);
        if (!m) {
            rest.push(raw);
            continue;
        }
        const seq = Number(m[1]);
        const text = (m[3] || raw).trim();
        const list = bySeq.get(seq) ?? [];
        list.push(text);
        bySeq.set(seq, list);
    }
    return { bySeq, rest };
}

/**
 * `new Date("2026-09-11")` parses as UTC midnight, so west of Greenwich
 * `toLocaleDateString` renders the day before — a cheque grid that shifts every
 * date by one day is worse than no dates at all. Built from the parts, the Date
 * lands on local midnight.
 */
export function fmtIsoDate(iso: string | null | undefined, locale: string): string {
    if (!iso) return "—";
    const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
    if (!y || !m || !d) return iso;
    return new Date(y, m - 1, d).toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB");
}

/** Today in the `yyyy-MM-dd` shape every date input and date field expects. */
export function todayIso(): string {
    const d = new Date();
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * The day after an ISO date, as an ISO date.
 *
 * `books_locked_through` is inclusive on the server — both
 * `LeaseTerminationService.validate` (:355-363) and
 * `SettlementService.requireUsableDate` (:669-679) refuse a date that is *not
 * after* it — so the earliest date a picker may offer is the day after, not the
 * lock itself. Built in UTC because a `yyyy-MM-dd` has no time zone and adding
 * a day through local midnight would land on the same date across a DST
 * boundary.
 */
export function isoDayAfter(iso: string | null | undefined): string | null {
    if (!iso) return null;
    const d = new Date(`${iso.slice(0, 10)}T00:00:00Z`);
    if (Number.isNaN(d.getTime())) return null;
    d.setUTCDate(d.getUTCDate() + 1);
    return d.toISOString().slice(0, 10);
}

/** The later of two ISO dates; either may be absent. `yyyy-MM-dd` sorts lexically. */
export function maxIso(a: string | null | undefined, b: string | null | undefined): string | undefined {
    if (!a) return b ?? undefined;
    if (!b) return a;
    return a > b ? a : b;
}

/** `iso`, pulled inside `[min, max]`. Either bound may be absent. */
export function clampIso(iso: string, min?: string | null, max?: string | null): string {
    if (min && iso < min) return min;
    if (max && iso > max) return max;
    return iso;
}
