import type { Account, AccountType } from "@/lib/api/ledger";
import type { EditableVoucherType, VoucherStatus } from "@/lib/api/vouchers";

/**
 * Every server rule the voucher screens have to obey, in one place.
 *
 * The discipline: **the UI never offers what the server always refuses.**
 * Plans 2 and 3 shipped seventeen controls that did — a picker offering income
 * accounts to a field the server narrows to expenses, a Post button on a date
 * inside a locked period, an Edit link on a posted document. Each of those is a
 * user typing a whole form and then reading a Java sentence.
 *
 * So each predicate below names the Java it mirrors, by file and line, and every
 * surface (the form, the list, the pages) reads it from here rather than
 * re-deriving it. When the Java moves, this file is the one place to follow it.
 *
 * All paths are under backend/src/main/java/com/datagami/rentaxis/.
 */

// ---- line accounts ----

/**
 * `core/service/voucher/VoucherService.java:397-402` (draft) and `:294-298`
 * (post): a purchase-invoice line buys an expense or an asset — never income,
 * never a liability. A payment-voucher line may be any leaf (`:317-318`): a
 * vendor payable being settled, an expense paid without an invoice, a salary.
 *
 * Both are further narrowed by `requireLeaf` (`:415-426`) to an active, non-group
 * account.
 */
const PISR_LINE_TYPES: AccountType[] = ["EXPENSE", "ASSET"];

/** The `accountTypes` a line picker may offer, or undefined for "any type". */
export function lineAccountTypes(type: EditableVoucherType): AccountType[] | undefined {
    return type === "PISR" ? PISR_LINE_TYPES : undefined;
}

export function isLineAccountAllowed(type: EditableVoucherType, a: Account | null | undefined): boolean {
    if (!a || a.group || !a.active) return false;
    return type === "PISR" ? PISR_LINE_TYPES.includes(a.accountType) : true;
}

// ---- the payment account ----

/**
 * `core/service/cheque/ChequeService.java:1437-1441`, re-asserted for vouchers at
 * `VoucherService.java:382-386` (draft) and `:319-328` (post): an active,
 * non-group ASSET leaf whose sub-type is BANK or CASH, and nothing else.
 *
 * `components/finance/SettlementAccountPicker.tsx` is the picker built on this
 * predicate; the BPV form uses it rather than a second copy of the filter.
 */
export function isPaymentAccountAllowed(a: Account | null | undefined): boolean {
    return (
        !!a
        && !a.group
        && a.active
        && a.accountType === "ASSET"
        && (a.accountSubType === "BANK" || a.accountSubType === "CASH")
    );
}

// ---- VAT ----

/**
 * `VoucherService.ALLOWED_VAT_RATES` (`:56-57`): the UAE standard rate is 5%;
 * zero-rated and exempt supplies are 0. Nothing else is legal today, and
 * `:409-411` refuses anything else.
 */
export const ALLOWED_VAT_RATES = [0, 5] as const;

/**
 * `VoucherService.BPV_VAT_REFUSAL` (`:64-65`), checked at draft (`:406-408`) and
 * again at post (`:299-301`): a payment voucher's line carries no VAT — the VAT
 * belongs to the purchase invoice the payment settles.
 *
 * Which is why the BPV form renders no VAT column at all: a field whose only
 * legal value is zero is not a field.
 */
export function vatAllowedOn(type: EditableVoucherType): boolean {
    return type === "PISR";
}

// ---- the whole draft, in the order the server checks it ----

export type DraftLine = {
    accountId: string;
    amount: number;
    vatRate: number;
};

export type DraftShape = {
    type: EditableVoucherType;
    vendorId: string;
    paymentAccountId: string | null;
    lines: DraftLine[];
    /**
     * `accountId -> vendorId` for every vendor payable account in the chart,
     * built from the vendor list's `payableAccount`. Omitted when the vendor list
     * has not loaded, which relaxes only the payable rules below.
     */
    payableOwners?: Record<string, string>;
};

/**
 * The i18n key under `Vouchers` naming what the server would refuse, or null
 * when the document is one it would accept.
 *
 * A key, not a sentence, so the caller renders it in the user's own language —
 * and so this module stays free of React and of next-intl.
 */
export type DraftRefusal =
    | "noLines"
    | "vendorRequired"
    | "paymentAccountRequired"
    | "lineAccountRequired"
    | "lineAmountRequired"
    | "bpvNoVat"
    | "payableNeedsVendor"
    | "otherVendorPayable";

export function draftRefusal(d: DraftShape): DraftRefusal | null {
    // VoucherInputDTO's @NotEmpty lines / VoucherService.validate:361-363.
    if (d.lines.length === 0) return "noLines";
    // validate:364-372 — and the vendor must have a payable account, which the
    // server checks; the form only offers vendors, so that half is server-side.
    if (d.type === "PISR" && !d.vendorId) return "vendorRequired";
    // validate:373-376.
    if (d.type === "BPV" && !d.paymentAccountId) return "paymentAccountRequired";

    for (const l of d.lines) {
        // validate:389 and VoucherLineInputDTO's @NotNull accountId.
        if (!l.accountId) return "lineAccountRequired";
        // validate:391-393 and VoucherLineInputDTO's @NotNull @Positive amount.
        if (!(l.amount > 0)) return "lineAmountRequired";
        // BPV_VAT_REFUSAL.
        if (d.type === "BPV" && l.vatRate !== 0) return "bpvNoVat";

        /*
         * Landing in the backend alongside this screen: a BPV line that settles a
         * vendor payable must settle THIS voucher's vendor. Paying vendor A out of
         * vendor B's payable balance leaves both ledgers wrong in a way the
         * balanced journal will not reveal.
         *
         * Mirrored ahead of the server rather than after it, because the form is
         * where the mistake is made. With no vendor list loaded `payableOwners` is
         * absent and these two checks simply do not fire — the server still has
         * the last word.
         */
        const owner = d.payableOwners?.[l.accountId];
        if (owner) {
            if (!d.vendorId) return "payableNeedsVendor";
            if (owner !== d.vendorId) return "otherVendorPayable";
        }
    }
    return null;
}

// ---- status ----

/** `VoucherService.requireDraft` (`:346-352`): only a DRAFT may be edited or deleted. */
export function canEditVoucher(status: VoucherStatus): boolean {
    return status === "DRAFT";
}

/** `VoucherService.amend` (`:213-216`): "Only a POSTED voucher can be amended". */
export function canAmendVoucher(status: VoucherStatus): boolean {
    return status === "POSTED";
}

/**
 * `core/service/voucher/VoucherAttachmentService.requireMutable` (`:134-140`):
 * paperwork may arrive after posting — a scan filed a day later — but REVERSED
 * is a document's terminal state and its paper trail is frozen with it.
 */
export function canManageAttachments(status: VoucherStatus): boolean {
    return status !== "REVERSED";
}

// ---- the period lock ----

/**
 * `core/service/ledger/TenantFiscalSettingsService.assertOpen` (`:53-59`) refuses
 * a date that is **not after** `booksLockedThrough` — so the lock date itself is
 * closed, not open. Comparing the ISO strings is a correct date comparison for
 * `yyyy-MM-dd` and avoids parsing them into a timezone.
 */
export function isDateLocked(docDate: string, booksLockedThrough: string | null | undefined): boolean {
    if (!booksLockedThrough || !docDate) return false;
    return docDate.slice(0, 10) <= booksLockedThrough.slice(0, 10);
}

// ---- attachments ----

/** `VoucherAttachmentService:50` — an invoice scan, not a video. */
export const ATTACHMENT_MAX_BYTES = 25 * 1024 * 1024;

/** `VoucherAttachmentService:51-52`, verbatim, for the file input's `accept`. */
export const ATTACHMENT_ACCEPT =
    "application/pdf,image/jpeg,image/png,image/heic,image/heif,image/webp";

/** `VoucherAttachmentService:49` / `:73-76`. */
export const MAX_ATTACHMENTS_PER_VOUCHER = 10;

const ALLOWED_ATTACHMENT_TYPES = ATTACHMENT_ACCEPT.split(",");

export type AttachmentRefusal = "attachmentTooMany" | "attachmentTooBig" | "attachmentWrongType";

/**
 * What the server would say about this file, before it is uploaded — the whole
 * point being that a 25MB upload should not travel twice to learn it is 25MB.
 * Mirrors `VoucherAttachmentService.upload` (`:73-83`) in its own order.
 */
export function attachmentRefusal(file: File, existingCount: number): AttachmentRefusal | null {
    if (existingCount >= MAX_ATTACHMENTS_PER_VOUCHER) return "attachmentTooMany";
    if (file.size > ATTACHMENT_MAX_BYTES) return "attachmentTooBig";
    if (!file.type || !ALLOWED_ATTACHMENT_TYPES.includes(file.type.toLowerCase())) {
        return "attachmentWrongType";
    }
    return null;
}
