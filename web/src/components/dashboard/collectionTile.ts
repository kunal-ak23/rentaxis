/**
 * The dashboard's collection tile, on one basis (gap #59).
 *
 * The tile used to divide money *cleared* this month by money *dated* this
 * month, so one catch-up banking run of old cheques read "139,550 of 3,667".
 * The headline is now this month's dues against what of them has been
 * collected; money collected this month for earlier or later months is
 * reported beside it as arrears or advance. The server's split adds up to its
 * `receivedThisMonth` exactly.
 */
export type CollectionSummary = {
  dueThisMonth?: number | null;
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
  const collected = n(summary.collectedAgainstDueThisMonth);
  const due = n(summary.dueThisMonth);
  return {
    collected,
    due,
    percent: due > 0 ? Math.round((collected / due) * 100) : null,
    arrears: n(summary.collectedArrears),
    advance: n(summary.collectedAdvance),
  };
}
