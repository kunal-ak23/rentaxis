/**
 * The dashboard's collection tile, on one basis (gap #59).
 *
 * The tile used to divide money *cleared* this month by money *dated* this
 * month, so one catch-up banking run of old cheques read "139,550 of 3,667".
 * The headline is now this month's dues against what of them has been
 * collected (`collectedForThisMonth`: every cleared row dated this month,
 * whenever it cleared, so an instalment paid ahead last month still counts);
 * money collected this month for earlier or later months is reported beside it
 * as arrears or advance.
 */
export type CollectionSummary = {
  dueThisMonth?: number | null;
  collectedForThisMonth?: number | null;
  collectedAgainstDueThisMonth?: number | null;
  collectedArrears?: number | null;
  collectedAdvance?: number | null;
};

export type CollectionTile = {
  collected: number;
  due: number;
  /** Whole percent of the month's dues collected; null when nothing is due. */
  percent: number | null;
  arrears: number;
  advance: number;
};

const n = (v: number | null | undefined): number => (v == null || Number.isNaN(v) ? 0 : v);

export function collectionTile(summary: CollectionSummary): CollectionTile {
  // Older servers lack collectedForThisMonth; the against-due part is the
  // nearest figure on the same basis.
  const collected = n(summary.collectedForThisMonth ?? summary.collectedAgainstDueThisMonth);
  const due = n(summary.dueThisMonth);
  return {
    collected,
    due,
    percent: due > 0 ? Math.round((collected / due) * 100) : null,
    arrears: n(summary.collectedArrears),
    advance: n(summary.collectedAdvance),
  };
}
