import { formatCurrency } from "@/lib/format";

export type ActivityItem = {
  type: string;
  /** Server-built English fallback. */
  description: string;
  timestamp: string;
  // The facts behind `description` (gap #60). Absent on an older server.
  chequeStatus?: string | null;
  chequeNumber?: string | null;
  seqNo?: number | null;
  unitNumber?: string | null;
  propertyName?: string | null;
  amount?: number | null;
};

/**
 * First-strong isolate / pop directional isolate. An Arabic line interpolates
 * several LTR values in a row ("G-01، Miftah Residences · AED 31,500.00"); left
 * bare, the bidi algorithm merges them and the comma into one LTR run, so in
 * the RTL line they read in the wrong order. Isolating each value keeps it a
 * single neutral unit that sits where the sentence puts it, in either locale.
 */
export const isolate = (v: string | number): string => `\u2068${v}\u2069`;

/**
 * One activity line in the viewer's language, with the amount grouped like
 * every other screen and the cheque named by its number (its schedule
 * position only when the paper has none). Falls back to the server's English
 * sentence when the structured fields are missing.
 */
export function activityText(
  item: ActivityItem,
  t: (key: string, values?: Record<string, string | number>) => string,
  tCheques: (key: string) => string,
): string {
  if (!item.chequeStatus || item.amount == null) return item.description;
  const ref = item.chequeNumber
    ? t("activityRefNumber", { number: isolate(item.chequeNumber) })
    : t("activityRefSeq", { n: item.seqNo ?? 0 });
  return t("activityCheque", {
    status: tCheques(`status.${item.chequeStatus}`),
    ref: isolate(ref),
    unit: isolate(item.unitNumber ?? "—"),
    property: isolate(item.propertyName ?? "—"),
    amount: isolate(formatCurrency(item.amount)),
  });
}
