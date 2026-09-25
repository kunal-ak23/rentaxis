import { apiGet, apiSend, qs } from "@/lib/api/ledger";

/** F14-38: bad-debt write-offs (BadDebtController). */
export type BadDebtItem = {
  chequeId: string; seqNo: number; chequeNumber: string | null; date: string; amount: number;
  status: string; mode: string; narration: string | null;
};
export type BadDebtRecovery = {
  id: string; amount: number; recoveredOn: string; accountId: string; note: string | null; journalId: string;
  journalNumber?: string | null;
};
/** F15-21: where a recovery may be banked — the receipts rule for the lease's property. */
export type RecoveryAccount = {
  id: string; code: string | null; name: string; nameAr: string | null; kind: "CASH" | "BANK"; bankAccount: string | null;
};
export type BadDebtWriteOff = {
  id: string; leaseId: string; renterId: string | null; amount: number; writeOffDate: string; reason: string;
  status: "PROPOSED" | "WRITTEN_OFF" | "REJECTED" | "REVERSED"; vatLease: boolean; itemIds: string[];
  proposedAt: string; decidedAt: string | null; decisionNote: string | null; journalId: string | null;
  reversalJournalId: string | null; recovered: number; recoveries: BadDebtRecovery[];
  journalNumber?: string | null; reversalJournalNumber?: string | null;
};

export const badDebtsApi = {
  candidates: (leaseId: string, on?: string) => apiGet<BadDebtItem[]>(`/finance/bad-debts/candidates${qs({ leaseId, on })}`),
  forLease: (leaseId: string) => apiGet<BadDebtWriteOff[]>(`/finance/bad-debts${qs({ leaseId })}`),
  propose: (body: { leaseId: string; chequeIds: string[]; date: string; reason: string }) =>
    apiSend<BadDebtWriteOff>("POST", "/finance/bad-debts", body),
  approve: (id: string, note?: string) => apiSend<BadDebtWriteOff>("POST", `/finance/bad-debts/${id}/approve`, { note: note ?? null }),
  reject: (id: string, note: string) => apiSend<BadDebtWriteOff>("POST", `/finance/bad-debts/${id}/reject`, { note }),
  reverse: (id: string, date: string, note: string) => apiSend<BadDebtWriteOff>("POST", `/finance/bad-debts/${id}/reverse`, { date, note }),
  recoveryAccounts: (id: string) => apiGet<RecoveryAccount[]>(`/finance/bad-debts/${id}/recovery-accounts`),
  recover: (id: string, body: { amount: number; date: string; accountId: string; note?: string | null }) =>
    apiSend<BadDebtWriteOff>("POST", `/finance/bad-debts/${id}/recoveries`, body),
};
