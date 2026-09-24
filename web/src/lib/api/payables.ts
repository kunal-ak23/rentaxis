import { apiGet, apiSend, qs } from "@/lib/api/ledger";

/**
 * Supplier AP (finance-ops spec §2, PR 3a): open items, advances, allocations,
 * cut-over opening items, payables aging and the supplier statement of account.
 *
 * Types read off the Java: `api/dto/payables/*.java`, `api/dto/voucher/AllocationDTO.java`,
 * routes from `api/PayablesController.java`, `api/PayablesReportController.java`
 * and `api/VoucherController.java`. `BigDecimal` is a JSON number, `LocalDate`
 * `yyyy-MM-dd`.
 */

const PROXY = "/api/proxy/v1";

export type SettlementStatus = "OPEN" | "PART_PAID" | "PAID";
export type Bucket = "CURRENT" | "D1_30" | "D31_60" | "D61_90" | "D90_PLUS";

/** `OpenItemDTO`: a PISR (`kind` PISR, `id` the voucher) or a cut-over opening item. */
export type OpenItem = {
    kind: "PISR" | "OPENING";
    id: string;
    vendorId: string;
    vendorName: string | null;
    docNumber: string | null;
    invoiceNumber: string | null;
    docDate: string;
    invoiceDate: string;
    dueDate: string;
    daysOverdue: number;
    bucket: Bucket;
    gross: number;
    allocated: number;
    open: number;
    status: SettlementStatus;
    propertyId: string | null;
};

/** `AdvanceDTO`: a posted payment and what no invoice has taken of it yet. */
export type Advance = {
    paymentId: string;
    vendorId: string;
    vendorName: string | null;
    voucherNumber: string | null;
    docDate: string;
    paymentMethod: string | null;
    reference: string | null;
    paid: number;
    allocated: number;
    unallocated: number;
};

/** `AllocationDTO`. `live` is false once released. */
export type Allocation = {
    id: string;
    vendorId: string;
    paymentVoucherId: string;
    paymentNumber: string | null;
    invoiceVoucherId: string | null;
    openingItemId: string | null;
    invoiceNumber: string | null;
    invoiceVoucherNumber: string | null;
    amount: number;
    allocatedOn: string;
    releasedOn: string | null;
    releaseReason: string | null;
    live: boolean;
};

/** `PayablesAgingDTO.Figures`. The vendor-level ones are null under a property filter. */
export type AgingFigures = {
    current: number;
    d1to30: number;
    d31to60: number;
    d61to90: number;
    d90plus: number;
    advances: number | null;
    openTotal: number;
    ledgerBalance: number | null;
    delta: number | null;
};

export type AgingRow = {
    vendorId: string;
    vendorName: string | null;
    vendorNameAr: string | null;
    active: boolean;
    payableAccountId: string | null;
    figures: AgingFigures;
    items: OpenItem[];
};

export type PayablesAging = {
    asOf: string;
    propertyId: string | null;
    vendorId: string | null;
    vendorLevel: boolean;
    rows: AgingRow[];
    totals: AgingFigures;
    advances: Advance[];
};

export type ApOpeningItem = {
    id: string;
    vendorId: string;
    vendorName: string | null;
    invoiceNumber: string;
    invoiceDate: string;
    dueDate: string;
    amount: number;
    propertyId: string | null;
    allocated: number;
    open: number;
};

export type ApOpeningItemInput = {
    vendorId: string;
    invoiceNumber: string;
    invoiceDate: string;
    dueDate?: string | null;
    amount: number;
    propertyId?: string | null;
};

export type ApOpeningSummary = {
    items: ApOpeningItem[];
    vendors: { vendorId: string; vendorName: string | null; itemsTotal: number; openingBalance: number; difference: number }[];
};

export type AllocationInput = { invoiceId?: string | null; openingItemId?: string | null; amount: number };

/** Supplier date + the vendor's terms (default 30), as `VoucherService.defaultDueDate` computes it. */
export function dueDateFrom(supplierDate: string, terms: number | null | undefined): string {
    if (!supplierDate) return "";
    const [y, m, d] = supplierDate.split("-").map(Number);
    const due = new Date(Date.UTC(y, m - 1, d + (terms ?? 30)));
    return due.toISOString().slice(0, 10);
}

/** Overdue = every bucket past due. */
export function overdueOf(f: AgingFigures): number {
    return f.d1to30 + f.d31to60 + f.d61to90 + f.d90plus;
}

/**
 * Oldest first, as much as the payment allows: the Allocate panel's auto-fill.
 * Amounts in fils so 0.1 + 0.2 never leaves a stray cent unallocated.
 */
export function autoAllocate(items: Pick<OpenItem, "id" | "open" | "dueDate" | "invoiceDate">[], available: number): Record<string, number> {
    let left = Math.round(available * 100);
    const out: Record<string, number> = {};
    const sorted = [...items].sort((a, b) =>
        a.dueDate === b.dueDate ? a.invoiceDate.localeCompare(b.invoiceDate) : a.dueDate.localeCompare(b.dueDate));
    for (const i of sorted) {
        if (left <= 0) break;
        const take = Math.min(left, Math.round(i.open * 100));
        if (take > 0) {
            out[i.id] = take / 100;
            left -= take;
        }
    }
    return out;
}

export const payablesApi = {
    openItems: (q: { vendorId?: string; dueBefore?: string; propertyId?: string; includePartPaid?: boolean }) =>
        apiGet<OpenItem[]>(`/finance/vouchers/open-items${qs(q)}`),
    advances: (vendorId?: string) => apiGet<Advance[]>(`/finance/vouchers/advances${qs({ vendorId })}`),
    duplicateOf: (vendorId: string, invoiceNumber: string, excludeId?: string) =>
        apiGet<{ duplicateOf: string | null }>(`/finance/vouchers/duplicate-check${qs({ vendorId, invoiceNumber, excludeId })}`),
    allocationsOf: (voucherId: string) => apiGet<Allocation[]>(`/finance/vouchers/${voucherId}/allocations`),
    allocate: (body: { paymentId: string; invoiceId?: string | null; openingItemId?: string | null; amount: number; allocatedOn?: string | null }) =>
        apiSend<Allocation>("POST", "/finance/voucher-allocations", body),
    release: (id: string, reason: string) =>
        apiSend<Allocation>("DELETE", `/finance/voucher-allocations/${id}`, { reason }),
    vendorItems: (vendorId: string) => apiGet<OpenItem[]>(`/finance/vendors/${vendorId}/open-items`),
    statementPdfUrl: (vendorId: string, from: string, to: string, lang: string) =>
        `${PROXY}/finance/vendors/${vendorId}/statement.pdf${qs({ from, to, lang })}`,
    aging: (q: { asOf?: string; propertyId?: string; vendorId?: string }) =>
        apiGet<PayablesAging>(`/finance/reports/payables-aging${qs(q)}`),
    agingCsvUrl: (q: { asOf?: string; propertyId?: string; vendorId?: string }, lang: string) =>
        `${PROXY}/finance/reports/payables-aging.csv${qs({ ...q, lang })}`,
    openingItems: {
        list: (vendorId?: string) => apiGet<ApOpeningSummary>(`/finance/ap-opening-items${qs({ vendorId })}`),
        create: (body: ApOpeningItemInput) => apiSend<ApOpeningItem>("POST", "/finance/ap-opening-items", body),
        update: (id: string, body: ApOpeningItemInput) => apiSend<ApOpeningItem>("PUT", `/finance/ap-opening-items/${id}`, body),
        remove: (id: string) => apiSend<void>("DELETE", `/finance/ap-opening-items/${id}`),
    },
};
