import { apiGet, apiSend } from "@/lib/api/ledger";
import type { PenaltyAssessment } from "@/lib/api/leasing";

/** F14-49: a maintenance ticket's vendor bill(s) and its recharge to the renter. */
export type TicketBill = {
  voucherId: string; voucherNumber: string | null; invoiceNumber: string | null; date: string;
  vendor: string | null; vendorAr: string | null; net: number; vat: number; status: string;
};
export type TicketCharges = {
  ticketId: string; reference: string | null; leaseId: string | null; bills: TicketBill[]; candidates: TicketBill[];
  billsNet: number; recharges: PenaltyAssessment[];
};

export const ticketChargesApi = {
  get: (ticketId: string) => apiGet<TicketCharges>(`/tickets/${ticketId}/charges`),
  link: (ticketId: string, voucherId: string) => apiSend<TicketCharges>("POST", `/tickets/${ticketId}/bills/${voucherId}`, {}),
  unlink: (ticketId: string, voucherId: string) => apiSend<TicketCharges>("DELETE", `/tickets/${ticketId}/bills/${voucherId}`),
  recharge: (ticketId: string, body: { amount: number; vatable: boolean | null; description?: string | null }) =>
    apiSend<TicketCharges>("POST", `/tickets/${ticketId}/recharge`, body),
};
