"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { CheckCircle, FileText, Loader2, Paperclip, Plus, Trash2, Upload } from "lucide-react";
import { Link } from "@/i18n/routing";
import AccountPicker, { loadAccounts } from "@/components/finance/AccountPicker";
import SettlementAccountPicker from "@/components/finance/SettlementAccountPicker";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount, ledgerApi, type Account } from "@/lib/api/ledger";
import {
    grossTotalOf, netTotalOf, vatOf, vatTotalOf, voucherApi,
    type EditableVoucherType, type VoucherAttachment, type VoucherDetail,
    type VoucherInput, type VoucherLineInput, type VoucherStatus,
} from "@/lib/api/vouchers";
import {
    ALLOWED_VAT_RATES, ATTACHMENT_ACCEPT, attachmentRefusal, canAmendVoucher,
    canEditVoucher, canManageAttachments, draftRefusal, isDateLocked, lineAccountTypes,
    vatAllowedOn, type DraftRefusalResult,
} from "@/lib/voucherRules";

/**
 * One form for both voucher documents (spec §10.1, §11): a Purchase/Service
 * Invoice (PISR) and a Bank/Cash Payment Voucher (BPV). They share a header, a
 * line grid, a live totals footer and a paperwork panel, and differ in exactly
 * the places the server treats them differently — which is the argument for one
 * component rather than two that drift.
 *
 * **VAT.** PISR lines carry a rate; the preview beside each line and the footer
 * total come from `vatOf`/`vatTotalOf`, which mirror `VoucherMath` to the fil:
 * HALF_UP per line, then summed. A BPV renders no VAT column at all, because
 * `VoucherService.BPV_VAT_REFUSAL` refuses any rate on a payment line, and a
 * field whose only legal value is zero is not a field.
 *
 * **What it refuses to offer.** Every gate here comes from `lib/voucherRules.ts`,
 * which names the Java it mirrors. Post is disabled — with the reason on screen
 * and announced through `aria-describedby`, not hidden in a tooltip — whenever
 * `draftRefusal` or the period lock says the server would refuse.
 *
 * **Amend mode.** A posted voucher opens read-only, but Amend does not merely
 * ask for a date and a reason: it puts the document back into an editable state
 * with the draft-time gates and the live VAT preview intact, because
 * `VoucherService.amend` exists to "post a fresh voucher carrying the CORRECTED
 * figures". Without that, the replacement submitted would be byte-for-byte the
 * original and the screen would report success — the worst of both. Post
 * amendment stays disabled until something has actually changed, Cancel
 * amendment restores the posted values, and the reversal date is checked against
 * the period lock inside the dialog that collects it. A reversed voucher is
 * read-only with nothing.
 *
 * **One load.** The voucher, the vendors, the properties and the fiscal
 * settings are fetched once on mount. The plan 3 walkthrough lost an
 * accountant's typed deductions to a second load landing on a live form
 * (commit a2bbd01f); `loadedFor` below makes that impossible here.
 */

type DraftLine = {
    /** Stable across reorder and removal — index would re-key the row being deleted. */
    key: string;
    accountId: string | null;
    description: string;
    /** Kept as typed text so a half-entered "1." is not rewritten under the cursor. */
    amount: string;
    vatRate: string;
    /**
     * The line's OWN dimensions, not the header's.
     *
     * `VoucherLineInputDTO` carries both, and `VoucherService.apply` writes them
     * onto the journal line — they are what put a maintenance invoice on one
     * building's ledger rather than across the portfolio. A one-invoice,
     * two-property bill is ordinary, so these belong per row. Empty string means
     * "not set"; the server then falls back to the header
     * (`apply`: `li.propertyId() == null ? in.propertyId() : li.propertyId()`).
     */
    propertyId: string;
    unitId: string;
};

type VendorRow = {
    id: string;
    nameEn: string;
    nameAr: string | null;
    active: boolean;
    payableAccount: { id: string; code: string; name: string } | null;
};

type UnitRow = { id: string; unitNumber: string; property: { id: string } | null };

let lineKeySeq = 0;
/** A new row starts on the header's property, which is the common case for a single-site invoice. */
const newLine = (propertyId = ""): DraftLine => ({
    key: `l${++lineKeySeq}`,
    accountId: null,
    description: "",
    amount: "",
    vatRate: "0",
    propertyId,
    unitId: "",
});

const pad = (n: number) => String(n).padStart(2, "0");
function todayIso(): string {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** "" and "abc" are 0, so an empty line reads as incomplete rather than as NaN. */
function num(s: string): number {
    const n = Number.parseFloat(s);
    return Number.isFinite(n) ? n : 0;
}

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200 disabled:opacity-60 disabled:cursor-not-allowed";
const fieldLabel = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-4 py-2 text-xs";

/** One stable id so the disabled Post button can point `aria-describedby` at its reason. */
const BLOCKER_ID = "voucher-blocker-reason";

const STATUS_CLASS: Record<VoucherStatus, string> = {
    DRAFT: "bg-input text-muted border-border",
    POSTED: "bg-success/10 text-success border-success/30",
    REVERSED: "bg-warning/10 text-warning border-warning/30",
};

export default function VoucherForm({
    type,
    voucherId,
    onPosted,
    onDeleted,
}: {
    type: EditableVoucherType;
    /** An existing voucher to open: a draft to finish, or a posted one to read and amend. */
    voucherId?: string;
    onPosted?: (v: VoucherDetail) => void;
    /**
     * Its own callback rather than `onPosted(null as VoucherDetail)`: a deleted
     * draft is not a posted voucher, and force-casting an empty object through
     * `VoucherDetail` hands any future consumer garbage with no type error.
     */
    onDeleted?: () => void;
}) {
    const t = useTranslations("Vouchers");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");

    const withVat = vatAllowedOn(type);
    const properties = useNameLookup("properties");

    const [docDate, setDocDate] = useState(todayIso);
    const [vendorId, setVendorId] = useState("");
    const [vendors, setVendors] = useState<VendorRow[]>([]);
    const [units, setUnits] = useState<UnitRow[]>([]);
    const [invoiceNumber, setInvoiceNumber] = useState("");
    const [narration, setNarration] = useState("");
    const [propertyId, setPropertyId] = useState("");
    const [paymentAccountId, setPaymentAccountId] = useState<string | null>(null);
    const [chequeNumber, setChequeNumber] = useState("");
    const [chequeDate, setChequeDate] = useState("");
    const [lines, setLines] = useState<DraftLine[]>(() => [newLine()]);
    const [attachments, setAttachments] = useState<VoucherAttachment[]>([]);

    const [savedId, setSavedId] = useState<string | undefined>(voucherId);
    const [amendedFromId, setAmendedFromId] = useState<string | null>(null);
    const [amendedFromNumber, setAmendedFromNumber] = useState<string | null>(null);
    const [status, setStatus] = useState<VoucherStatus>("DRAFT");
    const [voucherNumber, setVoucherNumber] = useState<string | null>(null);
    const [journalId, setJournalId] = useState<string | null>(null);
    const [postedNumber, setPostedNumber] = useState<string | null>(null);

    const [booksLockedThrough, setBooksLockedThrough] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [formError, setFormError] = useState<string | null>(null);
    const [attachmentError, setAttachmentError] = useState<string | null>(null);
    const [confirm, setConfirm] = useState<"post" | "delete" | "amend" | null>(null);
    const [amendDate, setAmendDate] = useState(todayIso);
    const [amendReason, setAmendReason] = useState("");
    /** True from the moment Amend is clicked until it is posted or cancelled. */
    const [amending, setAmending] = useState(false);
    /** The posted document as loaded, so Cancel amendment can put it back verbatim. */
    const [posted, setPosted] = useState<VoucherDetail | null>(null);
    /** The chart by id — the second layer behind the pickers' own filters. */
    const [accounts, setAccounts] = useState<Record<string, Account>>({});

    /**
     * The id this form has already loaded. A ref, not state: it must be written
     * before the effect body can run a second time, and a re-render triggered by
     * anything else (a keystroke, the vendor list landing) must never re-enter
     * the fetch and overwrite lines the accountant is in the middle of typing.
     */
    const loadedFor = useRef<string | null>(null);

    // ---- one load, on mount ----

    useEffect(() => {
        let alive = true;
        // Vendors carry their payable account, which the payable-line rules need;
        // the vendors page reads the same unpaginated list.
        //
        // A failed load used to be swallowed into `[]` here (`r.ok ? r.json() :
        // []`), which is indistinguishable from "this tenant has no vendors" — an
        // ACCOUNTANT hitting the S1 403 (VendorController used to be SA/TA only)
        // saw an empty, unexplained dropdown and could not tell whether to type a
        // vendor name or give up. Surface it like every other load on this form.
        fetch("/api/proxy/v1/vendors")
            .then(r => {
                if (!r.ok) throw new ApiError(r.status, tCommon("loadFailed"));
                return r.json();
            })
            .then((rows: VendorRow[]) => alive && setVendors(Array.isArray(rows) ? rows : []))
            .catch(e => alive && setLoadError(e instanceof ApiError ? e.message : tCommon("loadFailed")));
        // One unpaginated GET, kept with its property so a line's unit list can be
        // filtered without a request per row (UnitController#getAllUnits).
        fetch("/api/proxy/v1/units")
            .then(r => (r.ok ? r.json() : []))
            .then((rows: UnitRow[]) => alive && setUnits(Array.isArray(rows) ? rows : []))
            .catch(() => {});
        ledgerApi.fiscal
            .get()
            .then(f => alive && setBooksLockedThrough(f.booksLockedThrough))
            .catch(() => {});
        // Shares AccountPicker's module-level cache, so this costs no extra GET.
        loadAccounts()
            .then(rows => alive && setAccounts(Object.fromEntries(rows.map(a => [a.id, a]))))
            .catch(() => {});
        return () => {
            alive = false;
        };
    }, []);

    const applyDetail = useCallback((v: VoucherDetail) => {
        setDocDate(v.docDate);
        setVendorId(v.vendorId ?? "");
        setInvoiceNumber(v.invoiceNumber ?? "");
        setNarration(v.narration ?? "");
        setPropertyId(v.propertyId ?? "");
        setPaymentAccountId(v.paymentAccountId);
        setChequeNumber(v.chequeNumber ?? "");
        setChequeDate(v.chequeDate ?? "");
        setAttachments(v.attachments ?? []);
        setStatus(v.status);
        setVoucherNumber(v.voucherNumber);
        setJournalId(v.journalId);
        setSavedId(v.id);
        setAmendedFromId(v.amendedFromId ?? null);
        setPosted(v);
        setLines(
            v.lines.length
                ? v.lines.map(l => ({
                      key: `s${l.lineNo}`,
                      accountId: l.accountId,
                      description: l.description ?? "",
                      amount: String(l.amount),
                      vatRate: String(l.vatRate ?? 0),
                      propertyId: l.propertyId ?? "",
                      unitId: l.unitId ?? "",
                  }))
                : [newLine(v.propertyId ?? "")],
        );
    }, []);

    useEffect(() => {
        if (!voucherId || loadedFor.current === voucherId) return;
        loadedFor.current = voucherId;
        voucherApi
            .get(voucherId)
            .then(applyDetail)
            .catch(e => setLoadError(e instanceof ApiError ? e.message : tCommon("loadFailed")));
    }, [voucherId, applyDetail, tCommon]);

    /**
     * The amended original's document number. It carries only its id on the
     * replacement, and the link has to read as a document number — the same
     * reason and the same shape as the journal page's reversal-pair lookup. A
     * failed fetch falls back to a short id rather than blanking the link.
     */
    useEffect(() => {
        if (!amendedFromId) return;
        let alive = true;
        voucherApi
            .get(amendedFromId)
            .then(v => alive && setAmendedFromNumber(v.voucherNumber ?? amendedFromId.slice(0, 8)))
            .catch(() => alive && setAmendedFromNumber(amendedFromId.slice(0, 8)));
        return () => {
            alive = false;
        };
    }, [amendedFromId]);

    // ---- derived ----

    /**
     * Every account that is some vendor's payable leaf — a SET, not an
     * `accountId -> vendorId` map. Two vendors may share one leaf (no unique
     * constraint on `vendors.payable_account_id`), and a map could only remember
     * the last of them, so it refused one of the two a payment the server accepts.
     */
    const payableAccountIds = useMemo(
        () => [...new Set(vendors.map(v => v.payableAccount?.id).filter((id): id is string => !!id))],
        [vendors],
    );

    /** The selected vendor's own payable leaf — the account the rule compares against. */
    const vendorPayableAccountId = useMemo(
        () => vendors.find(v => v.id === vendorId)?.payableAccount?.id ?? null,
        [vendors, vendorId],
    );

    const numericLines = useMemo(
        () => lines.map(l => ({ amount: num(l.amount), vatRate: withVat ? num(l.vatRate) : 0 })),
        [lines, withVat],
    );

    const totals = useMemo(
        () => ({
            net: netTotalOf(numericLines),
            vat: vatTotalOf(numericLines),
            gross: grossTotalOf(numericLines),
        }),
        [numericLines],
    );

    // Amend mode re-opens the fields of a POSTED document; everything downstream
    // (the gates, the VAT preview, Add line) keys off this one flag, exactly as
    // it does for a draft.
    const editable = canEditVoucher(status) || amending;
    const locked = isDateLocked(docDate, booksLockedThrough);
    const amendDateLocked = isDateLocked(amendDate, booksLockedThrough);

    const refusal: DraftRefusalResult | null = useMemo(
        () =>
            draftRefusal({
                type,
                vendorId,
                paymentAccountId,
                lines: lines.map((l, i) => ({
                    accountId: l.accountId ?? "",
                    amount: numericLines[i].amount,
                    vatRate: numericLines[i].vatRate,
                })),
                payableAccountIds: vendors.length ? payableAccountIds : undefined,
                vendorPayableAccountId,
                accounts: Object.keys(accounts).length ? accounts : undefined,
            }),
        [type, vendorId, paymentAccountId, lines, numericLines, payableAccountIds,
         vendorPayableAccountId, vendors.length, accounts],
    );

    // ---- requests ----

    const body = useCallback(
        (): VoucherInput => ({
            docType: type,
            docDate,
            // A BPV may name a vendor too — it is how the server knows whose
            // payable a settlement line belongs to — so this is not PISR-only.
            vendorId: vendorId || null,
            invoiceNumber: type === "PISR" ? invoiceNumber || null : null,
            narration: narration || null,
            propertyId: propertyId || null,
            paymentAccountId: type === "BPV" ? paymentAccountId : null,
            chequeNumber: type === "BPV" ? chequeNumber || null : null,
            chequeDate: type === "BPV" ? chequeDate || null : null,
            lines: lines.map<VoucherLineInput>((l, i) => ({
                accountId: l.accountId as string,
                description: l.description || null,
                amount: numericLines[i].amount,
                // Never a rate on a payment line, whatever is in state.
                vatRate: withVat ? numericLines[i].vatRate : 0,
                // The LINE's dimensions. Sending the header's here was the bug: it
                // collapsed a two-property invoice onto one ledger and dropped
                // every unit. Null lets `VoucherService.apply` fall back to the
                // header, which is what an unset row should mean.
                propertyId: l.propertyId || null,
                unitId: l.unitId || null,
            })),
        }),
        [type, docDate, vendorId, invoiceNumber, narration, propertyId, paymentAccountId,
         chequeNumber, chequeDate, lines, numericLines, withVat],
    );

    /**
     * Has the amendment changed anything? Compared on the request body rather
     * than on the individual fields, so it answers the only question that
     * matters: would the replacement differ from what is already posted?
     */
    const dirty = useMemo(() => {
        if (!posted) return true;
        const current = body();
        const original: VoucherInput = {
            docType: type,
            docDate: posted.docDate,
            vendorId: posted.vendorId ?? null,
            invoiceNumber: type === "PISR" ? posted.invoiceNumber ?? null : null,
            narration: posted.narration ?? null,
            propertyId: posted.propertyId ?? null,
            paymentAccountId: type === "BPV" ? posted.paymentAccountId : null,
            chequeNumber: type === "BPV" ? posted.chequeNumber ?? null : null,
            chequeDate: type === "BPV" ? posted.chequeDate ?? null : null,
            lines: posted.lines.map<VoucherLineInput>(l => ({
                accountId: l.accountId,
                description: l.description || null,
                amount: l.amount,
                vatRate: withVat ? l.vatRate ?? 0 : 0,
                propertyId: l.propertyId ?? null,
                unitId: l.unitId ?? null,
            })),
        };
        return JSON.stringify(current) !== JSON.stringify(original);
    }, [posted, body, type, withVat]);

    /**
     * The single sentence under the buttons naming what the server would refuse.
     * On screen and wired to the button through `aria-describedby`, because a
     * disabled button with a hidden reason is the same dead end as a 400 — it
     * just arrives earlier, silently.
     */
    const blocker = refusal
        ? t(refusal.key, { line: refusal.line ?? 1 })
        : locked
          ? t("periodLocked", { date: booksLockedThrough ?? "" })
          : amending && !dirty
            ? t("amendNoChanges")
            : null;

    const canPost = editable && !blocker && !busy;

    const run = async (fn: () => Promise<void>) => {
        setBusy(true);
        setFormError(null);
        try {
            await fn();
        } catch (e) {
            setFormError(e instanceof ApiError ? e.message : String(e));
        } finally {
            setBusy(false);
        }
    };

    /** Create or update, returning the id the attachments and the post hang off. */
    const persist = async (): Promise<VoucherDetail> => {
        const saved = savedId ? await voucherApi.update(savedId, body()) : await voucherApi.create(body());
        setSavedId(saved.id);
        return saved;
    };

    const saveDraft = () =>
        run(async () => {
            applyDetail(await persist());
        });

    const postVoucher = () =>
        run(async () => {
            const saved = await persist();
            const posted = await voucherApi.post(saved.id);
            applyDetail(posted);
            setPostedNumber(posted.voucherNumber);
            setConfirm(null);
            onPosted?.(posted);
        });

    const deleteDraft = () =>
        run(async () => {
            if (savedId) await voucherApi.remove(savedId);
            setConfirm(null);
            onDeleted?.();
        });

    /** Enter amend mode. The fields re-open; nothing is sent until Post amendment. */
    const startAmend = () => {
        setAmendDate(todayIso());
        setAmendReason("");
        setFormError(null);
        setPostedNumber(null);
        setAmending(true);
    };

    /** Leave amend mode, putting the posted document back exactly as it was. */
    const cancelAmend = () => {
        setAmending(false);
        setFormError(null);
        if (posted) applyDetail(posted);
    };

    const amend = () =>
        run(async () => {
            if (!savedId) return;
            // One transaction on the server: the original's journal is reversed and
            // the replacement is posted, so a reversal cannot survive a failed
            // replacement. What comes back is the NEW voucher, already POSTED.
            const fresh = await voucherApi.amend(savedId, {
                reversalDate: amendDate,
                reason: amendReason,
                replacement: body(),
            });
            setAmending(false);
            applyDetail(fresh);
            setPostedNumber(fresh.voucherNumber);
            setConfirm(null);
            // Deliberately NOT onPosted: that navigates to the list, and an
            // amendment's whole result is the replacement — its new number, and
            // the link back to the original now marked REVERSED. The accountant
            // stays on it.
        });

    const uploadAttachment = (file: File) => {
        // Nothing while a save is in flight. An attachment needs a voucher to
        // hang off, so this saves a draft first — and during a save that is
        // ALREADY running `savedId` is still null, so the `persist()` below
        // fires a SECOND `POST /vouchers`: two drafts of the same invoice, one
        // of them carrying the attachment and neither of them named. The input
        // is `disabled` on `busy` too; this is the rule, that is the affordance.
        if (busy) return;
        const refused = attachmentRefusal(file, attachments.length);
        if (refused) {
            // Checked here rather than after a 25MB round trip that can only fail.
            setAttachmentError(t(refused));
            return;
        }
        setAttachmentError(null);
        return run(async () => {
            // An attachment needs a voucher to hang off, so a brand-new document
            // is saved as a draft first.
            const id = savedId ?? (await persist()).id;
            const a = await voucherApi.attachments.upload(id, file.name, file);
            setAttachments(prev => [...prev, a]);
        });
    };

    const setLine = (i: number, patch: Partial<DraftLine>) =>
        setLines(ls => ls.map((x, j) => (j === i ? { ...x, ...patch } : x)));

    const vendorName = (id: string) => vendors.find(v => v.id === id)?.nameEn ?? "";

    /** The units of one property, name-sorted. Empty until a property is chosen. */
    const unitsFor = (propId: string) =>
        propId
            ? units
                  .filter(u => u.property?.id === propId)
                  .sort((a, b) => a.unitNumber.localeCompare(b.unitNumber))
            : [];

    // ---- render ----

    // account, description, property, unit, amount, line total, actions (+ VAT rate and VAT amount)
    const columns = 6 + (withVat ? 2 : 0) + 1;

    return (
        <div className="space-y-6" data-testid="voucher-form" data-voucher-type={type}>
            {loadError && <LoadErrorBanner message={loadError} onRetry={() => location.reload()} />}

            {postedNumber && (
                <div
                    role="status"
                    data-testid="voucher-posted"
                    className="flex items-center gap-2 bg-success/10 border border-success/30 text-success rounded-xl px-5 py-3"
                >
                    <CheckCircle size={16} className="shrink-0" />
                    <span className="text-sm font-medium">{t("voucherPosted", { number: postedNumber })}</span>
                </div>
            )}

            {amending && (
                <div
                    data-testid="amend-banner"
                    className="bg-warning/10 border border-warning/30 text-warning rounded-xl px-5 py-3"
                >
                    <p className="text-sm font-bold">{t("amendMode", { number: voucherNumber ?? "" })}</p>
                    <p className="text-xs mt-1">{t("amendModeHint")}</p>
                </div>
            )}

            {/* Header */}
            <div className="bg-surface border border-border rounded-xl shadow-sm p-5">
                <div className="flex items-center justify-between gap-3 mb-4">
                    <span
                        data-testid="voucher-status"
                        data-status={status}
                        className={`inline-block px-2 py-0.5 rounded-md border text-[10px] font-bold uppercase tracking-wider ${STATUS_CLASS[status]}`}
                    >
                        {status === "DRAFT" ? t("draft") : status === "POSTED" ? tLedger("posted") : tLedger("reversed")}
                    </span>
                    {voucherNumber ? (
                        <span className="text-xs font-mono font-bold text-foreground" data-testid="voucher-number">
                            {voucherNumber}
                        </span>
                    ) : (
                        // The number IS the journal's entry number, taken at posting
                        // time — showing a provisional one would be a promise the
                        // ledger has not made.
                        <span className="text-[10px] text-muted">{t("voucherNumberPending")}</span>
                    )}
                </div>

                <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    <div>
                        <label className={fieldLabel} htmlFor="voucher-doc-date">
                            {tLedger("docDate")}
                        </label>
                        <input
                            id="voucher-doc-date"
                            data-testid="doc-date"
                            type="date"
                            className={field}
                            disabled={!editable}
                            value={docDate}
                            onChange={e => setDocDate(e.target.value)}
                        />
                    </div>

                    {/*
                     * The vendor is required on a purchase invoice and optional on a
                     * payment voucher, where it names whose payable a settlement line
                     * belongs to. Both render it, so the payable rules have something
                     * to check against.
                     */}
                    <div>
                        <label className={fieldLabel} htmlFor="voucher-vendor">
                            {t("vendor")}
                        </label>
                        <select
                            id="voucher-vendor"
                            data-testid="vendor"
                            className={field}
                            disabled={!editable}
                            value={vendorId}
                            onChange={e => setVendorId(e.target.value)}
                        >
                            <option value="">{type === "PISR" ? t("selectVendor") : t("noVendor")}</option>
                            {vendors
                                .filter(v => v.active || v.id === vendorId)
                                .map(v => (
                                    <option key={v.id} value={v.id}>
                                        {v.nameEn}
                                    </option>
                                ))}
                        </select>
                    </div>

                    {type === "PISR" ? (
                        <div>
                            <label className={fieldLabel} htmlFor="voucher-invoice-number">
                                {t("invoiceNumber")}
                            </label>
                            <input
                                id="voucher-invoice-number"
                                data-testid="invoice-number"
                                className={field}
                                disabled={!editable}
                                value={invoiceNumber}
                                onChange={e => setInvoiceNumber(e.target.value)}
                            />
                        </div>
                    ) : (
                        <>
                            <div>
                                <span className={fieldLabel}>{t("paymentAccount")}</span>
                                {/*
                                 * SettlementAccountPicker, not a bare AccountPicker:
                                 * it is the one definition of "an account cleared
                                 * funds may leave from" (ChequeService.
                                 * isSettlementAccount), which VoucherService
                                 * re-asserts for this very field.
                                 */}
                                <SettlementAccountPicker
                                    value={paymentAccountId}
                                    onChange={setPaymentAccountId}
                                    propertyId={propertyId || null}
                                    placeholder={t("selectPaymentAccount")}
                                    disabled={!editable}
                                />
                            </div>
                            <div>
                                <label className={fieldLabel} htmlFor="voucher-cheque-number">
                                    {t("chequeNumber")}
                                </label>
                                <input
                                    id="voucher-cheque-number"
                                    data-testid="cheque-number"
                                    className={field}
                                    disabled={!editable}
                                    value={chequeNumber}
                                    onChange={e => setChequeNumber(e.target.value)}
                                />
                            </div>
                            <div>
                                <label className={fieldLabel} htmlFor="voucher-cheque-date">
                                    {t("chequeDate")}
                                </label>
                                <input
                                    id="voucher-cheque-date"
                                    data-testid="cheque-date"
                                    type="date"
                                    className={field}
                                    disabled={!editable}
                                    value={chequeDate}
                                    onChange={e => setChequeDate(e.target.value)}
                                />
                            </div>
                        </>
                    )}

                    <div>
                        <label className={fieldLabel} htmlFor="voucher-property">
                            {t("property")}
                        </label>
                        <select
                            id="voucher-property"
                            data-testid="property"
                            className={field}
                            disabled={!editable}
                            value={propertyId}
                            onChange={e => setPropertyId(e.target.value)}
                        >
                            <option value="">{t("allProperties")}</option>
                            {properties.options.map(p => (
                                <option key={p.id} value={p.id}>
                                    {p.label}
                                </option>
                            ))}
                        </select>
                    </div>

                    <div className="md:col-span-2">
                        <label className={fieldLabel} htmlFor="voucher-narration">
                            {tLedger("narration")}
                        </label>
                        <input
                            id="voucher-narration"
                            data-testid="narration"
                            className={field}
                            disabled={!editable}
                            value={narration}
                            onChange={e => setNarration(e.target.value)}
                        />
                    </div>
                </div>
            </div>

            {/* Lines */}
            <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full" data-testid="voucher-lines">
                        <thead className="bg-input/60 border-b border-border">
                            <tr>
                                <th className={`${th} min-w-[220px]`}>{tLedger("account")}</th>
                                <th className={th}>{t("description")}</th>
                                <th className={th}>{t("property")}</th>
                                <th className={th}>{tLedger("unit")}</th>
                                <th className={`${th} text-end`}>{t("amount")}</th>
                                {withVat && <th className={`${th} text-end`}>{t("vatRate")}</th>}
                                {withVat && <th className={`${th} text-end`}>{t("vatAmount")}</th>}
                                <th className={`${th} text-end`}>{t("lineTotal")}</th>
                                <th className={th} />
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {lines.map((l, i) => {
                                const amount = numericLines[i].amount;
                                const vat = withVat ? vatOf(amount, numericLines[i].vatRate) : 0;
                                return (
                                    <tr key={l.key} data-testid={`voucher-line-${i}`}>
                                        <td className={td}>
                                            <AccountPicker
                                                value={l.accountId}
                                                onChange={id => setLine(i, { accountId: id })}
                                                // PISR: an EXPENSE or ASSET leaf and
                                                // nothing else (VoucherService.validate).
                                                // BPV: any leaf — a payable, an expense,
                                                // a salary.
                                                accountTypes={lineAccountTypes(type)}
                                                propertyId={propertyId || null}
                                                placeholder={tLedger("account")}
                                                disabled={!editable}
                                            />
                                        </td>
                                        <td className={td}>
                                            <input
                                                data-testid={`line-description-${i}`}
                                                aria-label={t("description")}
                                                className={field}
                                                disabled={!editable}
                                                value={l.description}
                                                onChange={e => setLine(i, { description: e.target.value })}
                                            />
                                        </td>
                                        <td className={td}>
                                            <select
                                                data-testid={`line-property-${i}`}
                                                aria-label={t("property")}
                                                className={`${field} w-40`}
                                                disabled={!editable}
                                                value={l.propertyId}
                                                // Changing the property drops the unit with it:
                                                // a unit belongs to exactly one property, so a
                                                // kept one would post a line whose unit is not
                                                // in its own building.
                                                onChange={e => setLine(i, { propertyId: e.target.value, unitId: "" })}
                                            >
                                                <option value="">{t("allProperties")}</option>
                                                {properties.options.map(pr => (
                                                    <option key={pr.id} value={pr.id}>
                                                        {pr.label}
                                                    </option>
                                                ))}
                                            </select>
                                        </td>
                                        <td className={td}>
                                            <select
                                                data-testid={`line-unit-${i}`}
                                                aria-label={tLedger("unit")}
                                                className={`${field} w-32`}
                                                // A unit without a property to scope it would be a
                                                // list of every unit in the portfolio.
                                                disabled={!editable || !l.propertyId}
                                                value={l.unitId}
                                                onChange={e => setLine(i, { unitId: e.target.value })}
                                            >
                                                <option value="">{t("wholeProperty")}</option>
                                                {unitsFor(l.propertyId).map(u => (
                                                    <option key={u.id} value={u.id}>
                                                        {u.unitNumber}
                                                    </option>
                                                ))}
                                            </select>
                                        </td>
                                        <td className={`${td} text-end`}>
                                            <input
                                                data-testid={`line-amount-${i}`}
                                                aria-label={t("amount")}
                                                inputMode="decimal"
                                                className={`${field} w-32 text-end tabular-nums`}
                                                disabled={!editable}
                                                value={l.amount}
                                                onChange={e => setLine(i, { amount: e.target.value })}
                                            />
                                        </td>
                                        {withVat && (
                                            <td className={`${td} text-end`}>
                                                <select
                                                    data-testid={`line-vat-rate-${i}`}
                                                    aria-label={t("vatRate")}
                                                    className={`${field} w-20`}
                                                    disabled={!editable}
                                                    value={l.vatRate}
                                                    onChange={e => setLine(i, { vatRate: e.target.value })}
                                                >
                                                    {ALLOWED_VAT_RATES.map(r => (
                                                        <option key={r} value={String(r)}>
                                                            {r}%
                                                        </option>
                                                    ))}
                                                </select>
                                            </td>
                                        )}
                                        {withVat && (
                                            <td
                                                data-testid={`line-vat-amount-${i}`}
                                                className={`${td} text-end tabular-nums text-muted`}
                                            >
                                                {fmtAmount(vat)}
                                            </td>
                                        )}
                                        <td
                                            data-testid={`line-total-${i}`}
                                            className={`${td} text-end tabular-nums font-semibold`}
                                        >
                                            {fmtAmount(amount + vat)}
                                        </td>
                                        <td className={`${td} text-end`}>
                                            <button
                                                type="button"
                                                data-testid={`remove-line-${i}`}
                                                aria-label={tLedger("removeLine")}
                                                disabled={!editable || lines.length === 1}
                                                onClick={() => setLines(ls => ls.filter((_, j) => j !== i))}
                                                className="p-2 text-muted hover:text-error disabled:opacity-30 disabled:cursor-not-allowed cursor-pointer"
                                            >
                                                <Trash2 size={14} />
                                            </button>
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                        <tfoot className="bg-input/60 border-t border-border">
                            <tr>
                                <td className={`${td} font-bold`} colSpan={columns - 2}>
                                    {t("netTotal")}
                                </td>
                                <td data-testid="net-total" className={`${td} text-end tabular-nums font-bold`}>
                                    {fmtAmount(totals.net)}
                                </td>
                                <td />
                            </tr>
                            {withVat && (
                                <tr>
                                    <td className={`${td} font-bold`} colSpan={columns - 2}>
                                        {t("vatTotal")}
                                    </td>
                                    <td data-testid="vat-total" className={`${td} text-end tabular-nums font-bold`}>
                                        {fmtAmount(totals.vat)}
                                    </td>
                                    <td />
                                </tr>
                            )}
                            <tr>
                                <td className={`${td} font-bold`} colSpan={columns - 2}>
                                    {t("grossTotal")}
                                </td>
                                <td data-testid="gross-total" className={`${td} text-end tabular-nums font-bold`}>
                                    {fmtAmount(totals.gross)}
                                </td>
                                <td />
                            </tr>
                        </tfoot>
                    </table>
                </div>
                {editable && (
                    <div className="px-4 py-3 border-t border-border">
                        <button
                            type="button"
                            data-testid="add-line"
                            onClick={() => setLines(ls => [...ls, newLine(propertyId)])}
                            className="flex items-center gap-2 text-xs font-semibold text-primary cursor-pointer"
                        >
                            <Plus size={14} />
                            {tLedger("addLine")}
                        </button>
                    </div>
                )}
            </div>

            {/* Attachments */}
            <div className="bg-surface border border-border rounded-xl shadow-sm p-5" data-testid="attachments-panel">
                <div className="flex items-center justify-between gap-3 mb-3">
                    <h3 className="text-xs font-bold text-foreground flex items-center gap-2">
                        <Paperclip size={14} />
                        {t("attachments")}
                    </h3>
                    {canManageAttachments(status) ? (
                        <label className="flex items-center gap-2 text-xs font-semibold text-primary cursor-pointer">
                            <Upload size={14} />
                            {t("addAttachment")}
                            <input
                                type="file"
                                data-testid="attachment-input"
                                aria-label={t("addAttachment")}
                                className="hidden"
                                accept={ATTACHMENT_ACCEPT}
                                /* Closed while anything is in flight; see
                                   `uploadAttachment` for what it prevents. */
                                disabled={busy}
                                onChange={e => {
                                    const f = e.target.files?.[0];
                                    if (f) uploadAttachment(f);
                                }}
                            />
                        </label>
                    ) : (
                        <span className="text-[10px] text-muted" data-testid="attachments-frozen">
                            {t("attachmentsFrozen")}
                        </span>
                    )}
                </div>
                <p className="text-[10px] text-muted mb-2">{t("attachmentTypes")}</p>
                {attachmentError && (
                    <p role="alert" data-testid="attachment-error" className="text-xs font-semibold text-error mb-2">
                        {attachmentError}
                    </p>
                )}
                <ul className="space-y-1">
                    {attachments.map(a => (
                        <li
                            key={a.id}
                            data-testid={`attachment-row-${a.id}`}
                            className="flex items-center justify-between gap-3 text-xs"
                        >
                            <a
                                href={voucherApi.attachments.downloadUrl(a.id)}
                                className="text-primary hover:underline cursor-pointer"
                            >
                                {a.name}
                            </a>
                            {canManageAttachments(status) && (
                                <button
                                    type="button"
                                    data-testid={`remove-attachment-${a.id}`}
                                    onClick={() =>
                                        run(async () => {
                                            await voucherApi.attachments.remove(a.id);
                                            setAttachments(prev => prev.filter(x => x.id !== a.id));
                                        })
                                    }
                                    className="text-muted hover:text-error cursor-pointer font-semibold"
                                >
                                    {t("removeAttachment")}
                                </button>
                            )}
                        </li>
                    ))}
                </ul>
            </div>

            {formError && (
                <p role="alert" data-testid="voucher-error" className="text-xs font-semibold text-error">
                    {formError}
                </p>
            )}

            {/* Actions */}
            <div className="flex flex-wrap items-center justify-end gap-3">
                {journalId && (
                    <Link
                        href={`/dashboard/finance/journals/${journalId}`}
                        data-testid="view-journal"
                        className="flex items-center gap-1.5 text-xs font-semibold text-primary hover:underline cursor-pointer me-auto"
                    >
                        <FileText size={13} />
                        {t("viewJournal")}
                    </Link>
                )}

                {amendedFromId && (
                    <Link
                        href={`/dashboard/finance/vouchers/${type === "BPV" ? "payment" : "purchase-invoice"}?id=${amendedFromId}`}
                        data-testid="amended-from"
                        className="text-xs font-semibold text-primary hover:underline cursor-pointer me-auto"
                    >
                        {t("amendedFrom", { number: amendedFromNumber ?? "…" })}
                    </Link>
                )}

                {blocker && editable && (
                    <p id={BLOCKER_ID} data-testid="voucher-blocker" className="text-xs font-medium text-warning me-auto">
                        {blocker}
                    </p>
                )}

                {canEditVoucher(status) && savedId && (
                    <button
                        type="button"
                        data-testid="delete-draft"
                        disabled={busy}
                        onClick={() => setConfirm("delete")}
                        className="px-5 py-2.5 rounded-lg text-xs font-semibold border border-border text-error cursor-pointer disabled:opacity-50"
                    >
                        {t("deleteDraft")}
                    </button>
                )}

                {canEditVoucher(status) && (
                    <button
                        type="button"
                        data-testid="save-draft"
                        disabled={busy}
                        onClick={saveDraft}
                        className="px-5 py-2.5 rounded-lg text-xs font-semibold border border-border text-foreground cursor-pointer disabled:opacity-50"
                    >
                        {busy ? <Loader2 size={14} className="animate-spin" /> : t("saveDraft")}
                    </button>
                )}

                {canEditVoucher(status) && (
                    <button
                        type="button"
                        data-testid="post-voucher"
                        disabled={!canPost}
                        aria-describedby={blocker ? BLOCKER_ID : undefined}
                        onClick={() => setConfirm("post")}
                        className="px-5 py-2.5 rounded-lg text-xs font-bold bg-primary text-primary-foreground cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {busy ? t("posting") : t("post")}
                    </button>
                )}

                {amending && (
                    <>
                        <button
                            type="button"
                            data-testid="cancel-amendment"
                            disabled={busy}
                            onClick={cancelAmend}
                            className="px-5 py-2.5 rounded-lg text-xs font-semibold border border-border text-foreground cursor-pointer disabled:opacity-50"
                        >
                            {t("cancelAmendment")}
                        </button>
                        <button
                            type="button"
                            data-testid="post-amendment"
                            disabled={!canPost}
                            aria-describedby={blocker ? BLOCKER_ID : undefined}
                            onClick={() => setConfirm("amend")}
                            className="px-5 py-2.5 rounded-lg text-xs font-bold bg-primary text-primary-foreground cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {busy ? t("posting") : t("postAmendment")}
                        </button>
                    </>
                )}

                {/* A posted voucher is corrected by reversal, never edited: journal
                    entries are immutable, so an "edit" would put the document and
                    the ledger permanently out of step (VoucherService.amend). */}
                {canAmendVoucher(status) && !amending && (
                    <button
                        type="button"
                        data-testid="amend-voucher"
                        disabled={busy}
                        onClick={startAmend}
                        className="px-5 py-2.5 rounded-lg text-xs font-bold bg-primary text-primary-foreground cursor-pointer disabled:opacity-50"
                    >
                        {t("amend")}
                    </button>
                )}
            </div>

            <ConfirmDialog
                isOpen={confirm === "post"}
                onClose={() => setConfirm(null)}
                onConfirm={postVoucher}
                isLoading={busy}
                title={t("post")}
                description={t("confirmPost", { date: docDate })}
                confirmText={t("post")}
                cancelText={tLedger("cancel")}
                confirmTestId="confirm-post"
            />

            <ConfirmDialog
                isOpen={confirm === "delete"}
                onClose={() => setConfirm(null)}
                onConfirm={deleteDraft}
                isLoading={busy}
                isDestructive
                title={t("deleteDraft")}
                description={t("confirmDeleteDraft")}
                confirmText={t("deleteDraft")}
                cancelText={tLedger("cancel")}
                confirmTestId="confirm-delete"
            />

            <ConfirmDialog
                isOpen={confirm === "amend"}
                onClose={() => setConfirm(null)}
                onConfirm={amend}
                isLoading={busy}
                title={t("postAmendment")}
                description={t("confirmAmend", { number: voucherNumber ?? "" })}
                confirmText={t("postAmendment")}
                cancelText={tLedger("cancel")}
                confirmTestId="confirm-amend"
                // VoucherService.amend calls fiscal.assertOpen(reversalDate) before
                // it writes anything, so the refusal belongs beside the field that
                // causes it rather than after the round trip.
                confirmDisabled={amendDateLocked}
            >
                <p className="text-xs text-muted">{t("amendHint")}</p>
                <div>
                    <label className={fieldLabel} htmlFor="voucher-amend-date">
                        {tLedger("reverseDate")}
                    </label>
                    <input
                        id="voucher-amend-date"
                        data-testid="amend-date"
                        type="date"
                        className={field}
                        value={amendDate}
                        onChange={e => setAmendDate(e.target.value)}
                    />
                </div>
                <div>
                    <label className={fieldLabel} htmlFor="voucher-amend-reason">
                        {t("amendReason")}
                    </label>
                    <input
                        id="voucher-amend-reason"
                        data-testid="amend-reason"
                        className={field}
                        value={amendReason}
                        onChange={e => setAmendReason(e.target.value)}
                    />
                </div>
                {amendDateLocked && (
                    <p role="alert" data-testid="amend-blocker" className="text-xs font-semibold text-warning">
                        {t("amendReversalLocked", { date: booksLockedThrough ?? "" })}
                    </p>
                )}
                {vendorId && <p className="sr-only">{vendorName(vendorId)}</p>}
            </ConfirmDialog>
        </div>
    );
}
