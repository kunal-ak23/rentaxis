/**
 * Report formatting (PACT report spec §1, D4). Report amounts arrive from the
 * server as decimal strings; these helpers only re-punctuate them — no Number
 * parsing, no float arithmetic — so a balance beyond 2^53 keeps its last cent
 * and the screen shows exactly what the Excel, PDF and CSV exports show.
 * Output uses Latin digits in both locales; callers wrap amounts in
 * `<bdi dir="ltr">`.
 */

const DECIMAL = /^(-?)(\d+)(?:\.(\d*))?$/;

interface Parts {
    negative: boolean;
    grouped: string;
    fraction: string;
}

/** Splits "-1234.5" into sign, "1,234" and "50"; null when it is not a plain decimal. */
function parts(v: string): Parts | null {
    const m = DECIMAL.exec(v.trim());
    if (!m) return null;
    const [, sign, intRaw, fracRaw = ""] = m;
    const int = intRaw.replace(/^0+(?=\d)/, "");
    const fraction = fracRaw.padEnd(2, "0");
    const zero = /^0*$/.test(int) && /^0*$/.test(fraction);
    return {
        negative: sign === "-" && !zero,
        grouped: int.replace(/\B(?=(\d{3})+(?!\d))/g, ","),
        fraction,
    };
}

/** "60000" → "60,000.00"; "-1234.5" → "-1,234.50"; "" / null / undefined → "". */
export function fmtMoney(v: string | null | undefined): string {
    if (v == null || v.trim() === "") return "";
    const p = parts(v);
    if (!p) return v;
    return `${p.negative ? "-" : ""}${p.grouped}.${p.fraction}`;
}

/** PACT's balance form: "12345" → "12,345.00Dr"; "-8000.5" → "8,000.50Cr"; "0" / "-0.00" → "0.00Dr"; null → "". */
export function fmtDrCr(v: string | null | undefined): string {
    if (v == null || v.trim() === "") return "";
    const p = parts(v);
    if (!p) return v;
    return `${p.grouped}.${p.fraction}${p.negative ? "Cr" : "Dr"}`;
}

/** "2026-09-16" → "16/09/2026" in every locale (the ar-AE locale would otherwise print Arabic-Indic digits). */
export function fmtReportDate(iso: string | null | undefined): string {
    if (!iso) return "";
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
    return m ? `${m[3]}/${m[2]}/${m[1]}` : iso;
}
