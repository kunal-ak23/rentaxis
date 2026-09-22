/**
 * Auto-matching for the bulk cheque upload flow: which scanned cheque image
 * belongs to which lease cheque row.
 *
 * Originally matched a scanned cheque to a payment-schedule installment by
 * date; accounting-v2 replaced schedules with the lease's own cheque grid
 * (`ChequeRowInput` / `ChequeDTO`), so the target is now a cheque row rather
 * than a schedule. The matching stays date-first (the OCR'd cheque date
 * against the row's own posting date) with the amount as a tie-breaker only:
 * an exact-amount match at the same date distance wins, but amount never
 * overrides a closer date — two rows a week apart are more likely confused by
 * a landlord's own rounding than by two cheques of the same amount landing on
 * different weeks.
 */

export type AutoMapItem = {
  id: string;
  chequeDate: string | null;
  /** OCR'd amount, when the extraction found one. */
  amount?: number | null;
  pinned: boolean;
  assignedRowId: string | null;
};

export type AutoMapChequeRow = {
  id: string;
  /** The row's own posting/maturity date — what a matching cheque should be dated near. */
  dueDate: string;
  amount?: number | null;
};

function dayDistance(a: string, b: string): number {
  const ay = Number(a.slice(0, 4));
  const am = Number(a.slice(5, 7)) - 1;
  const ad = Number(a.slice(8, 10));
  const by = Number(b.slice(0, 4));
  const bm = Number(b.slice(5, 7)) - 1;
  const bd = Number(b.slice(8, 10));
  return Math.abs(Math.round((Date.UTC(ay, am, ad) - Date.UTC(by, bm, bd)) / 86_400_000));
}

/**
 * Combined distance: date dominates (scaled up so it is never crossed by an
 * amount difference), amount only breaks a tie between otherwise-equal dates.
 * When either side has no amount, the amount term is zero and the result is
 * exactly the old date-only distance — so callers that never pass amounts
 * (or tests written before amounts existed) see unchanged behaviour.
 */
function distance(item: AutoMapItem, row: AutoMapChequeRow): number {
  const dateDist = dayDistance(item.chequeDate!, row.dueDate);
  const amountDist =
    item.amount != null && row.amount != null ? Math.min(Math.abs(item.amount - row.amount), 999) : 0;
  return dateDist * 1000 + amountDist;
}

export function autoMapChequesToRows(items: AutoMapItem[], rows: AutoMapChequeRow[]): Map<string, string> {
  // Reserve rows locked by pinned items.
  const remaining = new Set(rows.map(r => r.id));
  for (const item of items) {
    if (item.pinned && item.assignedRowId) {
      remaining.delete(item.assignedRowId);
    }
  }

  // Build all (item, row) pairs for non-pinned, dated items.
  const pairs: { itemId: string; rowId: string; dist: number }[] = [];
  for (const item of items) {
    if (item.pinned || !item.chequeDate) continue;
    for (const row of rows) {
      if (!remaining.has(row.id)) continue;
      pairs.push({ itemId: item.id, rowId: row.id, dist: distance(item, row) });
    }
  }
  pairs.sort((a, b) => a.dist - b.dist);

  const assigned = new Set<string>();
  const result = new Map<string, string>();
  for (const p of pairs) {
    if (assigned.has(p.itemId) || !remaining.has(p.rowId)) continue;
    result.set(p.itemId, p.rowId);
    assigned.add(p.itemId);
    remaining.delete(p.rowId);
  }
  return result;
}
