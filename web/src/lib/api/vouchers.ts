import { apiGet, apiSend, qs } from "@/lib/api/ledger";
import { throwIfNotOk } from "@/lib/api/facilities";
import type { Page } from "@/lib/api/ledger";
// One definition of HALF_UP-to-2dp for the whole app; see lib/money.ts.
import { round2 } from "@/lib/money";

/**
 * Purchase/Service Invoices (PISR) and Bank/Cash Payment Vouchers (BPV) — spec
 * §10.1, §11.
 *
 * Every type here is read off the Java, not off the plan's sketch:
 * `api/dto/voucher/VoucherDTO.java`, `VoucherDetailDTO.java`,
 * `VoucherLineDTO.java`, `VoucherInputDTO.java`, `VoucherLineInputDTO.java`,
 * `AmendVoucherDTO.java` and `VoucherAttachmentDTO.java`, with the routes taken
 * from `api/VoucherController.java`. `BigDecimal` serialises as a JSON number,
 * `LocalDate` as `yyyy-MM-dd`, `Instant` as an ISO-8601 string, and a `UUID`
 * field that the record may leave null is `string | null` here rather than
 * optional — the server always sends the key.
 */

const BASE = "/api/proxy/v1";

// ---- enums (domain/entity/enums/VoucherType.java, VoucherStatus.java) ----

/** RCP exists in the Java enum but is refused by every voucher endpoint — see `voucherRules.ts`. */
export type VoucherType = "PISR" | "BPV" | "RCP";

/** The two types this screen can actually create. */
export type EditableVoucherType = "PISR" | "BPV";

export type VoucherStatus = "DRAFT" | "POSTED" | "REVERSED";

/** `VoucherPaymentMethod.java` (finance-ops spec §2): CASH pays from a cash leaf, the others from a bank leaf. */
export type PaymentMethod = "TRANSFER" | "CHEQUE" | "CASH";

// ---- responses ----

/** `VoucherLineDTO`. `vatAmount` is server-computed; the form previews it with {@link vatOf}. */
export type VoucherLine = {
    lineNo: number;
    accountId: string;
    accountCode: string;
    accountName: string;
    description: string | null;
    amount: number;
    vatRate: number;
    vatAmount: number;
    propertyId: string | null;
    unitId: string | null;
};

/**
 * `VoucherAttachmentDTO`. `uploadedAt` is an ISO-8601 instant.
 *
 * **`fileUrl` is deliberately absent.** A voucher attachment is a private
 * document — a supplier invoice with a TRN and bank details on it — and a
 * storage URL on the wire is a URL that can be forwarded, logged or guessed.
 * The server is dropping the field for that reason; leaving it out of this type
 * means any code that reaches for it fails to compile rather than quietly
 * shipping the link. Downloads go through
 * {@link voucherApi.attachments.downloadUrl} — the authenticated streaming
 * endpoint, which also sends the filename in `Content-Disposition`.
 */
export type VoucherAttachment = {
    id: string;
    voucherId: string;
    name: string;
    fileType: string | null;
    fileSize: number | null;
    uploadedAt: string;
};

/**
 * `VoucherDTO` — the list row. The three totals are derived from the lines on
 * every read (`VoucherMath`), never stored, so they are always in step with what
 * the document says.
 *
 * `voucherNumber` and `journalId` are null until the voucher is posted: the
 * number IS the journal's entry number, taken at posting time.
 */
export type Voucher = {
    id: string;
    docType: VoucherType;
    docDate: string;
    vendorId: string | null;
    vendorName: string | null;
    invoiceNumber: string | null;
    narration: string | null;
    propertyId: string | null;
    unitId: string | null;
    paymentAccountId: string | null;
    paymentAccountName: string | null;
    chequeNumber: string | null;
    chequeDate: string | null;
    status: VoucherStatus;
    journalId: string | null;
    voucherNumber: string | null;
    amendedFromId: string | null;
    netTotal: number;
    vatTotal: number;
    grossTotal: number;
    postedAt: string | null;
    /** PISR: the date on the supplier's invoice. */
    supplierInvoiceDate?: string | null;
    /** PISR: when it is due (supplier date + the vendor's terms unless edited). */
    dueDate?: string | null;
    /** BPV only. */
    paymentMethod?: PaymentMethod | null;
    paymentReference?: string | null;
};

/**
 * `VoucherDetailDTO.Settlement`, derived from live allocations (spec §2). PISR:
 * `amount` is the gross and `status` OPEN / PART_PAID / PAID. BPV: `amount` is
 * what it paid the vendor and `open` the unallocated advance. Null on a draft.
 */
export type Settlement = {
    amount: number;
    allocated: number;
    open: number;
    status: "OPEN" | "PART_PAID" | "PAID" | null;
};

/** `VoucherDetailDTO` — deliberately flat, with the lines and attachments appended. */
export type VoucherDetail = Voucher & {
    lines: VoucherLine[];
    attachments: VoucherAttachment[];
    settlement?: Settlement | null;
};

// ---- requests ----

/** `VoucherLineInputDTO`. `amount` is `@NotNull @Positive`; `vatRate` null means 0. */
export type VoucherLineInput = {
    accountId: string;
    description?: string | null;
    amount: number;
    vatRate: number;
    propertyId?: string | null;
    unitId?: string | null;
    /** "Shared / head office": no property on purpose. Not persisted; it answers the server's property check. */
    shared?: boolean;
};

/** `VoucherInputDTO`. `docType`, `docDate` and a non-empty `lines` are required. */
export type VoucherInput = {
    docType: EditableVoucherType;
    docDate: string;
    vendorId?: string | null;
    invoiceNumber?: string | null;
    narration?: string | null;
    propertyId?: string | null;
    unitId?: string | null;
    paymentAccountId?: string | null;
    chequeNumber?: string | null;
    chequeDate?: string | null;
    lines: VoucherLineInput[];
    supplierInvoiceDate?: string | null;
    dueDate?: string | null;
    paymentMethod?: PaymentMethod | null;
    paymentReference?: string | null;
};

/** One invoice a payment settles: `AllocationInputDTO`. */
export type VoucherAllocationInput = { invoiceId?: string | null; openingItemId?: string | null; amount: number };

/** `AmendVoucherDTO`. `reversalDate` is `@NotNull`; `reason` is free text. */
export type AmendVoucherInput = {
    reversalDate: string;
    reason: string;
    replacement: VoucherInput;
    /** The invoices a replacement payment voucher settles. */
    allocations?: VoucherAllocationInput[];
};

export type VoucherQuery = {
    docType?: VoucherType | "";
    status?: VoucherStatus | "";
    vendorId?: string;
    propertyId?: string;
    from?: string;
    to?: string;
    page: number;
    size: number;
};

// ---- arithmetic that has to agree with the server, to the fil ----

/**
 * VAT on ONE line: `amount * rate/100`, HALF_UP to 2dp.
 *
 * Mirrors `VoucherMath.vat`
 * (backend/.../core/service/voucher/VoucherMath.java:31-35). The form previews
 * this number before the round trip, so the total an accountant watches while
 * typing is the total that posts.
 */
export function vatOf(amount: number, rate: number): number {
    if (!amount || !rate) return 0;
    return round2((amount * rate) / 100);
}

type AmountLike = { amount: number; vatRate: number };

/** Mirrors `VoucherMath.netTotal` — the sum of the line amounts, re-rounded. */
export function netTotalOf(lines: AmountLike[]): number {
    return round2(lines.reduce((s, l) => s + l.amount, 0));
}

/**
 * Mirrors `VoucherMath.vatTotal` — the sum of the **already rounded** per-line
 * VAT figures. Taking VAT of the net total instead disagrees with the vendor's
 * invoice on multi-line documents (3 x 100.10 @ 5% is 15.03, not 15.02).
 */
export function vatTotalOf(lines: AmountLike[]): number {
    return round2(lines.reduce((s, l) => s + vatOf(l.amount, l.vatRate), 0));
}

/** Mirrors `VoucherMath.grossTotal` — net plus VAT, both already rounded. */
export function grossTotalOf(lines: AmountLike[]): number {
    return round2(netTotalOf(lines) + vatTotalOf(lines));
}

// ---- the client (api/VoucherController.java) ----

export const voucherApi = {
    /** `GET /finance/vouchers` — a Spring `Page`, default size 25, capped at 200. */
    list: (q: VoucherQuery) => apiGet<Page<Voucher>>(`/finance/vouchers${qs(q)}`),
    get: (id: string) => apiGet<VoucherDetail>(`/finance/vouchers/${id}`),
    /** `POST /finance/vouchers` → 201 with the detail body. */
    create: (body: VoucherInput) => apiSend<VoucherDetail>("POST", "/finance/vouchers", body),
    /** `PUT /finance/vouchers/{id}` — DRAFT only; the server refuses anything else. */
    update: (id: string, body: VoucherInput) => apiSend<VoucherDetail>("PUT", `/finance/vouchers/${id}`, body),
    /** `DELETE /finance/vouchers/{id}` → 204. DRAFT only. */
    remove: (id: string) => apiSend<void>("DELETE", `/finance/vouchers/${id}`),
    /** For a BPV, `allocations` names the invoices it settles; the rest is an advance. */
    post: (id: string, allocations?: VoucherAllocationInput[]) =>
        apiSend<VoucherDetail>("POST", `/finance/vouchers/${id}/post`,
            allocations && allocations.length ? { allocations } : undefined),
    /** Reverses this voucher's journal and posts the replacement; returns the NEW, posted voucher. */
    amend: (id: string, body: AmendVoucherInput) =>
        apiSend<VoucherDetail>("POST", `/finance/vouchers/${id}/amend`, body),
    attachments: {
        list: (voucherId: string) => apiGet<VoucherAttachment[]>(`/finance/vouchers/${voucherId}/attachments`),
        /**
         * `POST /finance/vouchers/{id}/attachments`, multipart with `name` and
         * `file` request parts. No `Content-Type` header is set deliberately —
         * only the browser can write the multipart boundary.
         */
        upload: async (voucherId: string, name: string, file: File) => {
            const fd = new FormData();
            fd.append("name", name);
            fd.append("file", file);
            const res = await fetch(`${BASE}/finance/vouchers/${voucherId}/attachments`, {
                method: "POST",
                body: fd,
            });
            await throwIfNotOk(res);
            return res.json() as Promise<VoucherAttachment>;
        },
        /** The controller hangs delete and download off `/vouchers/attachments/{id}`, not off the voucher. */
        remove: (attachmentId: string) =>
            apiSend<void>("DELETE", `/finance/vouchers/attachments/${attachmentId}`),
        downloadUrl: (attachmentId: string) =>
            `${BASE}/finance/vouchers/attachments/${attachmentId}/download`,
    },
};
