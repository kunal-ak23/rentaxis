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
    ? t("activityRefNumber", { number: item.chequeNumber })
    : t("activityRefSeq", { n: item.seqNo ?? 0 });
  return t("activityCheque", {
    status: tCheques(`status.${item.chequeStatus}`),
    ref,
    unit: item.unitNumber ?? "—",
    property: item.propertyName ?? "—",
    amount: formatCurrency(item.amount),
  });
}
