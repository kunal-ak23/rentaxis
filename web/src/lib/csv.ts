/**
 * CSV serialisation for client-side exports.
 *
 * Lives in `lib` rather than inline in the page that uses it because the escaping
 * is the part that breaks, and the only way to pin it is to test it away from the
 * DOM. See `__tests__/csv.test.ts`.
 */

/**
 * A leading =, +, -, @ (or a tab/CR, which Excel strips before parsing) makes a
 * spreadsheet treat the cell as a formula rather than text. Gate-pass guest names
 * and purposes are free text typed by renters, so a cell can be attacker-chosen —
 * `=cmd|'/c calc'!A1` in a guest name is a live DDE payload the moment a manager
 * opens the export. Quoting alone does NOT stop this: the quotes are consumed by
 * the CSV parser and the formula is what's left.
 */
const FORMULA_TRIGGER = /^[=+\-@\t\r]/;

/**
 * Renders one cell.
 *
 * Every cell is quoted rather than only the ones that need it. Conditional quoting
 * is a correct-looking optimisation that costs a branch per cell and buys nothing —
 * a reader cannot tell "unquoted because safe" from "unquoted because we forgot".
 * Quoting unconditionally makes commas, quotes and newlines a non-question.
 */
function cell(value: string | number | null | undefined): string {
    if (value === null || value === undefined) return '""';

    let text = String(value);

    // Prefix rather than strip: the value stays readable and comparable, and the
    // leading quote is consumed by the spreadsheet on display. Stripping would
    // silently corrupt legitimate values like "-5" or a purpose of "+1 guest".
    if (FORMULA_TRIGGER.test(text)) {
        text = `'${text}`;
    }

    // RFC 4180: a literal " inside a quoted field is written as "".
    return `"${text.replace(/"/g, '""')}"`;
}

/**
 * Builds an RFC 4180 CSV document.
 *
 * CRLF line endings per the spec — Excel on Windows renders a bare LF as one
 * run-on row, and a gate-traffic report exists to be opened in Excel.
 */
export function toCsv(
    headers: string[],
    rows: (string | number | null | undefined)[][],
): string {
    return [headers, ...rows]
        .map((row) => row.map(cell).join(","))
        .join("\r\n");
}

/**
 * Triggers a browser download of `content` as a UTF-8 CSV file.
 *
 * The BOM is load-bearing, not cargo cult: Excel decodes a BOM-less file as the
 * system codepage, which turns every Arabic guest name — and this app is
 * bilingual by requirement — into mojibake. `charset=utf-8` on the Blob type does
 * not reach Excel; only the BOM does.
 */
export function downloadCsv(filename: string, content: string): void {
    const blob = new Blob(["\ufeff", content], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(url);
}
