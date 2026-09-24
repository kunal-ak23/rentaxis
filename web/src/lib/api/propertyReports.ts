import { apiGet, qs } from "@/lib/api/ledger";

/**
 * Finance → Reports: the per-property P&L and the property statement pack
 * (finance-ops spec §1, PropertyReportController). Amount maps are keyed by
 * column key — a property id, "UNASSIGNED" or "TOTAL".
 */

export type Compare = "NONE" | "PREVIOUS" | "LAST_YEAR";
export type AllocateBasis = "NONE" | "UNITS" | "RENT" | "EQUAL";

export const UNASSIGNED = "UNASSIGNED";
export const TOTAL = "TOTAL";

export type PnlColumn = {
  key: string;
  propertyId: string | null;
  kind: "PROPERTY" | "UNASSIGNED" | "TOTAL";
  name: string;
  nameAr: string | null;
};

export type PnlAmount = {
  amount: number;
  prior: number | null;
  delta: number | null;
  deltaPct: number | null;
};

export type PnlRow = {
  key: string;
  reportLine: string | null;
  label: string;
  labelAr: string | null;
  accountIds: string[];
  cells: Record<string, PnlAmount>;
};

export type PnlGroup = {
  groupId: string;
  code: string;
  name: string;
  nameAr: string | null;
  accountType: "INCOME" | "EXPENSE";
  rows: PnlRow[];
  subtotal: Record<string, PnlAmount>;
};

export type PropertyPnl = {
  from: string;
  to: string;
  compare: Compare;
  priorFrom: string | null;
  priorTo: string | null;
  scoped: boolean;
  columns: PnlColumn[];
  groups: PnlGroup[];
  income: Record<string, PnlAmount>;
  expenses: Record<string, PnlAmount>;
  noi: Record<string, PnlAmount>;
  allocation: {
    basis: AllocateBasis;
    basisUsed: AllocateBasis;
    unassignedCost: number;
    allocated: Record<string, number>;
    noiAfter: Record<string, number>;
  } | null;
  check: { ledgerNet: number; reportNet: number; difference: number; ok: boolean } | null;
  dataQuality: { lineAccountPropertyMismatches: number };
};

export type PnlLine = {
  entryId: string;
  entryNumber: string;
  entryDate: string;
  docType: string;
  narration: string | null;
  accountId: string;
  accountCode: string;
  accountName: string;
  accountNameAr: string | null;
  debit: number;
  credit: number;
  linePropertyId: string | null;
  accountPropertyId: string | null;
};

export type PnlLines = { lines: PnlLine[]; totalDebit: number; totalCredit: number; truncated: boolean };

export type StatementFigure = { key: string; amount: number; count: number | null };
export type StatementTable = { key: string; columns: string[]; rows: (string | number | null)[][] };
export type StatementSection = {
  key: string;
  number: number;
  source: "LEDGER" | "REGISTER" | "MIXED" | "SUBLEDGER" | "DERIVED";
  figures: StatementFigure[];
  tables: StatementTable[];
  notes: string[];
  meta: Record<string, string>;
};

export type PropertyStatement = {
  propertyId: string;
  propertyName: string;
  propertyNameAr: string | null;
  emirate: string | null;
  from: string;
  to: string;
  sections: StatementSection[];
  footer: { isFinal: boolean; booksLockedThrough: string | null; generatedAt: string; generatedBy: string | null };
};

export type ReportLineOption = { key: string; labelEn: string; labelAr: string | null };

export type PnlQuery = {
  from: string;
  to: string;
  propertyIds?: string[];
  compare?: Compare;
  allocate?: AllocateBasis;
};

/**
 * `propertyId` repeats once per property; `qs` would join an array with commas,
 * which Spring also binds, but the spec names the repeated form.
 */
function pnlQuery(q: PnlQuery, extra: Record<string, string> = {}): string {
  const sp = new URLSearchParams();
  sp.set("from", q.from);
  sp.set("to", q.to);
  for (const id of q.propertyIds ?? []) sp.append("propertyId", id);
  if (q.compare && q.compare !== "NONE") sp.set("compare", q.compare);
  if (q.allocate && q.allocate !== "NONE") sp.set("allocate", q.allocate);
  for (const [k, v] of Object.entries(extra)) sp.set(k, v);
  return `?${sp.toString()}`;
}

const PROXY = "/api/proxy/v1";

export const propertyReportsApi = {
  pnl: (q: PnlQuery) => apiGet<PropertyPnl>(`/finance/reports/property-pl${pnlQuery(q)}`),
  pnlCsvUrl: (q: PnlQuery, lang: string) => `${PROXY}/finance/reports/property-pl.csv${pnlQuery(q, { lang })}`,
  lines: (q: { from: string; to: string; column: string; accountIds: string[] }) =>
    apiGet<PnlLines>(`/finance/reports/property-pl/lines${qs(q)}`),
  statement: (q: { propertyId: string; from: string; to: string }) =>
    apiGet<PropertyStatement>(`/finance/reports/property-statement${qs(q)}`),
  statementPdfUrl: (q: { propertyId: string; from: string; to: string; lang: string }) =>
    `${PROXY}/finance/reports/property-statement.pdf${qs(q)}`,
  statementCsvUrl: (q: { propertyId: string; from: string; to: string; lang: string }) =>
    `${PROXY}/finance/reports/property-statement.csv${qs(q)}`,
  reportLines: () => apiGet<ReportLineOption[]>("/finance/reports/report-lines"),
};

// ---- periods ----

const pad = (n: number) => String(n).padStart(2, "0");
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

export type PeriodKind = "month" | "quarter" | "year" | "custom";

/** The [from, to] of a month / quarter / year containing `anchor` (YYYY-MM-DD, local time). */
export function periodRange(kind: Exclude<PeriodKind, "custom">, anchor: string): { from: string; to: string } {
  const [y, m] = anchor.split("-").map(Number);
  if (kind === "year") return { from: `${y}-01-01`, to: `${y}-12-31` };
  const startMonth = kind === "quarter" ? Math.floor((m - 1) / 3) * 3 + 1 : m;
  const months = kind === "quarter" ? 3 : 1;
  const from = new Date(y, startMonth - 1, 1);
  const to = new Date(y, startMonth - 1 + months, 0);
  return { from: iso(from), to: iso(to) };
}

/** The previous calendar month, the report's default period. */
export function lastMonth(today = new Date()): { from: string; to: string } {
  const d = new Date(today.getFullYear(), today.getMonth() - 1, 1);
  return periodRange("month", iso(d));
}
