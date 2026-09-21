import { apiGet, apiSend, qs } from "@/lib/api/ledger";
import { throwIfNotOk } from "@/lib/api/facilities";
import type { Page } from "@/lib/api/ledger";

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

/** `VoucherAttachmentDTO`. `uploadedAt` is an ISO-8601 instant. */
export type VoucherAttachment = {
    id: string;
    voucherId: string;
    name: string;
    fileUrl: string;
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
};

/** `VoucherDetailDTO` — deliberately flat, with the lines and attachments appended. */
export type VoucherDetail = Voucher & {
    lines: VoucherLine[];
    attachments: VoucherAttachment[];
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
};

/** `AmendVoucherDTO`. `reversalDate` is `@NotNull`; `reason` is free text. */
export type AmendVoucherInput = {
    reversalDate: string;
    reason: string;
    replacement: VoucherInput;
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

/** Two decimals, HALF_UP — the shape `BigDecimal.setScale(2, HALF_UP)` gives. */
function round2(n: number): number {
    // `Number.EPSILON * n` nudges a value that binary floating point has stored
    // a hair BELOW its decimal .xx5 (100.10 * 5 / 100 is 5.00499999…) back onto
    // the boundary, so Math.round takes it up exactly where HALF_UP does. The
    // nudge is proportional, not absolute, so it stays negligible at invoice
    // magnitudes and cannot move a value that is not already on the boundary.
    return Math.round((n + Math.sign(n) * Math.abs(n) * Number.EPSILON) * 100) / 100;
}

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
    post: (id: string) => apiSend<VoucherDetail>("POST", `/finance/vouchers/${id}/post`),
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
