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
 * `core/service/voucher/VoucherService.java:404-409` (draft) and `:301-305`
 * (post): a purchase-invoice line buys an expense or an asset — never income,
 * never a liability. A payment-voucher line may be any leaf (`:324-325`): a
 * vendor payable being settled, an expense paid without an invoice, a salary.
 *
 * Both are further narrowed by `requireLeaf` (`:422-433`) to an active, non-group
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
 * `VoucherService.java:389-393` (draft) and `:326-335` (post): an active,
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
 * `:416-418` refuses anything else.
 */
export const ALLOWED_VAT_RATES = [0, 5] as const;

/**
 * `VoucherService.BPV_VAT_REFUSAL` (`:64-65`), checked at draft (`:413-415`) and
 * again at post (`:306-308`): a payment voucher's line carries no VAT — the VAT
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
    /** The line's own property; blank falls back to the header's on the server. */
    propertyId?: string | null;
    /** "Shared / head office" was chosen for this line (finance-ops spec §1, S12). */
    shared?: boolean;
};

export type DraftShape = {
    type: EditableVoucherType;
    vendorId: string;
    paymentAccountId: string | null;
    lines: DraftLine[];
    /**
     * Every account that is SOME vendor's payable leaf.
     *
     * A set of account ids, not an `accountId -> vendorId` map, because the map
     * could only hold one owner per account and nothing in the schema stops two
     * vendors sharing one leaf (`vendors.payable_account_id` has no unique
     * constraint). The old map was last-write-wins, so with a shared account one
     * of the two vendors was refused a payment the server accepts.
     *
     * Omitted when the vendor list has not loaded, which relaxes only the payable
     * rules below.
     */
    payableAccountIds?: string[];
    /** The selected vendor's own payable leaf — the account the rule compares against. */
    vendorPayableAccountId?: string | null;
    /**
     * The chart of accounts by id, when it has loaded.
     *
     * The pickers already refuse to OFFER an account the server would reject, so
     * this is the second layer — and it is not redundant. A line loaded from a
     * saved voucher was never offered by a picker: it was already on the row. If
     * that account has since been reclassified (an expense turned into income) or
     * deactivated, the picker has no say and only this check stands between the
     * accountant and a 400 on submit. Omitted before the chart loads, which
     * relaxes these two checks only.
     */
    accounts?: Record<string, Account>;
    /** The header's property, which a line with none of its own inherits. */
    headerPropertyId?: string | null;
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
    | "paymentAccountNotAllowed"
    | "lineAccountRequired"
    | "lineAccountNotAllowed"
    | "lineAmountRequired"
    | "bpvNoVat"
    | "payableNeedsVendor"
    | "otherVendorPayable"
    | "linePropertyRequired";

/**
 * A refusal, plus the 1-based line it is about where the rule is per-line. An
 * invoice has more than one row, and "Every line needs an amount" on a six-line
 * document is a hunt rather than an answer.
 */
export type DraftRefusalResult = { key: DraftRefusal; line?: number };

export function draftRefusal(d: DraftShape): DraftRefusalResult | null {
    // VoucherInputDTO's @NotEmpty lines / VoucherService.validate:368-370.
    if (d.lines.length === 0) return { key: "noLines" };
    // validate:371-379 — and the vendor must have a payable account, which the
    // server checks; the form only offers vendors, so that half is server-side.
    if (d.type === "PISR" && !d.vendorId) return { key: "vendorRequired" };
    // validate:380-383.
    if (d.type === "BPV") {
        if (!d.paymentAccountId) return { key: "paymentAccountRequired" };
        const pay = d.accounts?.[d.paymentAccountId];
        // Second layer behind SettlementAccountPicker — see `accounts` above.
        if (pay && !isPaymentAccountAllowed(pay)) return { key: "paymentAccountNotAllowed" };
    }

    for (let i = 0; i < d.lines.length; i++) {
        const l = d.lines[i];
        const line = i + 1;
        // validate:396 and VoucherLineInputDTO's @NotNull accountId.
        if (!l.accountId) return { key: "lineAccountRequired", line };
        // Second layer behind the picker's accountTypes filter — see `accounts`.
        const account = d.accounts?.[l.accountId];
        if (account && !isLineAccountAllowed(d.type, account)) {
            return { key: "lineAccountNotAllowed", line };
        }
        // validate:398-400 and VoucherLineInputDTO's @NotNull @Positive amount.
        if (!(l.amount > 0)) return { key: "lineAmountRequired", line };
        // BPV_VAT_REFUSAL.
        if (d.type === "BPV" && l.vatRate !== 0) return { key: "bpvNoVat", line };
        // VoucherService.LINE_PROPERTY_REFUSAL (finance-ops spec §1, S12/O8): an
        // income or expense line with no property drops out of every property
        // report, so it names one — on the line, on the header, or through a
        // property-bound leaf — or says it is shared. Needs the chart, like the
        // account checks above.
        if (account && (account.accountType === "INCOME" || account.accountType === "EXPENSE")
            && !l.propertyId && !d.headerPropertyId && !account.propertyId && !l.shared) {
            return { key: "linePropertyRequired", line };
        }

        /*
         * A BPV line that settles a vendor payable must settle THIS voucher's
         * vendor. Paying vendor A out of vendor B's payable moves B's balance and
         * leaves A's untouched — the journal balances, the payments list says one
         * thing and the vendor ledger another, and nothing afterwards says which
         * is wrong. A payable line with no vendor on the header is the same
         * mistake in a different hat.
         *
         * The comparison is on the ACCOUNT, exactly as
         * `VoucherService.requirePayableLinesMatchTheVendor` (:488-521) does it:
         * "is this line my vendor's payable account?", not "is my vendor the only
         * vendor who answers to it?". Two vendors may share one leaf, and a
         * voucher naming either of them is settling precisely the account its own
         * vendor is settled through — so both are allowed, which a per-owner
         * comparison would get wrong for one of them.
         *
         * BPV only, and explicitly so: `VoucherService.validate` guards its call
         * with `if (in.docType() == VoucherType.BPV)` and `requirePostable` calls
         * it from the BPV arm of its switch alone. A PISR line cannot structurally
         * hold a payable today — the picker is EXPENSE/ASSET and payables are
         * LIABILITY — but that is an invariant in a different file, and the two
         * should not be free to decouple silently.
         *
         * With no vendor list loaded `payableAccountIds` is absent and these two
         * checks do not fire; the server still has the last word.
         */
        if (d.type === "BPV" && d.payableAccountIds?.includes(l.accountId)) {
            if (!d.vendorId) return { key: "payableNeedsVendor", line };
            if (l.accountId !== d.vendorPayableAccountId) return { key: "otherVendorPayable", line };
        }
    }
    return null;
}

// ---- status ----

/** `VoucherService.requireDraft` (`:353-359`): only a DRAFT may be edited or deleted. */
export function canEditVoucher(status: VoucherStatus): boolean {
    return status === "DRAFT";
}

/** `VoucherService.amend` (`:220-223`): "Only a POSTED voucher can be amended". */
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

/**
 * 10MB, per the security ruling landing in `VoucherAttachmentService`: an
 * invoice scan, not a photo library. An oversize upload is a clean 400 there;
 * this constant is what stops it travelling first.
 */
export const ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024;

/**
 * PDF, PNG and JPEG — the three the server can verify by FILE SIGNATURE rather
 * than by the client-declared Content-Type, which is why HEIC and WEBP came off
 * the list. Used verbatim as the file input's `accept`.
 *
 * `accept` is a convenience, never the check: a user can always choose "all
 * files", so `attachmentRefusal` re-reads the type and the server reads the
 * bytes.
 */
export const ATTACHMENT_ACCEPT = "application/pdf,image/png,image/jpeg";

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
