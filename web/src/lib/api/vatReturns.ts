import { apiGet, apiSend, qs } from "@/lib/api/ledger";

/** #55: the quarterly VAT return (VatReturnController). Box codes follow the FTA VAT 201 form. */
export type VatBox = { code: string; key: string; amount: number | null; vat: number | null; documents: number; total: boolean };
export type VatReturn = {
  id: string | null;
  periodStart: string;
  periodEnd: string;
  status: "OPEN" | "FILED" | "REOPENED";
  filedAt: string | null;
  filedByName: string | null;
  filingReference: string | null;
  boxes: VatBox[];
  netVat: number;
  outputCheck: { documents: number; ledger: number; difference: number; ok: boolean } | null;
  commercialWithoutVat: number;
  canFile: boolean;
  cannotFileReason: string | null;
};
export type VatDocument = {
  kind: string; id: string; number: string; date: string; party: string | null; partyAr: string | null;
  amount: number | null; vat: number | null; journalId: string | null; entryNumber: string | null; leaseId: string | null;
};
export type VatFiling = {
  id: string; periodStart: string; periodEnd: string; status: string; netVat: number | null; filingReference: string | null;
  filedAt: string; filedByName: string | null; reopenedAt: string | null; reopenReason: string | null;
};

const PROXY = "/api/proxy/v1";

export const vatReturnsApi = {
  get: (periodStart: string) => apiGet<VatReturn>(`/finance/vat-returns${qs({ periodStart })}`),
  filings: () => apiGet<VatFiling[]>("/finance/vat-returns/filings"),
  documents: (periodStart: string, box: string) => apiGet<VatDocument[]>(`/finance/vat-returns/documents${qs({ periodStart, box })}`),
  file: (periodStart: string, filingReference: string) =>
    apiSend<VatReturn>("POST", "/finance/vat-returns/file", { periodStart, filingReference }),
  reopen: (id: string, reason: string) => apiSend<VatReturn>("POST", `/finance/vat-returns/${id}/reopen`, { reason }),
  pdfUrl: (periodStart: string, lang: string) => `${PROXY}/finance/vat-returns/return.pdf${qs({ periodStart, lang })}`,
  csvUrl: (periodStart: string, lang: string) => `${PROXY}/finance/vat-returns/return.csv${qs({ periodStart, lang })}`,
};

/** The first day of the calendar quarter before the one holding `today` (the return usually being prepared). */
export function lastQuarterStart(today = new Date()): string {
  const q = Math.floor(today.getMonth() / 3);
  const d = new Date(today.getFullYear(), (q - 1) * 3, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-01`;
}
