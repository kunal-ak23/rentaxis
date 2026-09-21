"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { CheckCircle, FileText, Loader2, Paperclip, Plus, Trash2, Upload } from "lucide-react";
import { Link } from "@/i18n/routing";
import AccountPicker from "@/components/finance/AccountPicker";
import SettlementAccountPicker from "@/components/finance/SettlementAccountPicker";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount, ledgerApi } from "@/lib/api/ledger";
import {
    grossTotalOf, netTotalOf, vatOf, vatTotalOf, voucherApi,
    type EditableVoucherType, type VoucherAttachment, type VoucherDetail,
    type VoucherInput, type VoucherLineInput, type VoucherStatus,
} from "@/lib/api/vouchers";
import {
    ALLOWED_VAT_RATES, ATTACHMENT_ACCEPT, attachmentRefusal, canAmendVoucher,
    canEditVoucher, canManageAttachments, draftRefusal, isDateLocked, lineAccountTypes,
    vatAllowedOn, type DraftRefusal,
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
 * which names the Java it mirrors. Post is disabled — with the reason on screen,
 * not hidden in a tooltip — whenever `draftRefusal` or the period lock says the
 * server would refuse. A posted voucher is read-only with an Amend button; a
 * reversed one is read-only with nothing.
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
};

type VendorRow = {
    id: string;
    nameEn: string;
    nameAr: string | null;
    active: boolean;
    payableAccount: { id: string; code: string; name: string } | null;
};

let lineKeySeq = 0;
const newLine = (): DraftLine => ({
    key: `l${++lineKeySeq}`,
    accountId: null,
    description: "",
    amount: "",
    vatRate: "0",
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

const STATUS_CLASS: Record<VoucherStatus, string> = {
    DRAFT: "bg-input text-muted border-border",
    POSTED: "bg-success/10 text-success border-success/30",
    REVERSED: "bg-warning/10 text-warning border-warning/30",
};

export default function VoucherForm({
    type,
    voucherId,
    onPosted,
}: {
    type: EditableVoucherType;
    /** An existing voucher to open: a draft to finish, or a posted one to read and amend. */
    voucherId?: string;
    onPosted?: (v: VoucherDetail) => void;
}) {
    const t = useTranslations("Vouchers");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");

    const withVat = vatAllowedOn(type);
    const properties = useNameLookup("properties");

    const [docDate, setDocDate] = useState(todayIso);
    const [vendorId, setVendorId] = useState("");
    const [vendors, setVendors] = useState<VendorRow[]>([]);
    const [invoiceNumber, setInvoiceNumber] = useState("");
    const [narration, setNarration] = useState("");
    const [propertyId, setPropertyId] = useState("");
    const [paymentAccountId, setPaymentAccountId] = useState<string | null>(null);
    const [chequeNumber, setChequeNumber] = useState("");
    const [chequeDate, setChequeDate] = useState("");
    const [lines, setLines] = useState<DraftLine[]>(() => [newLine()]);
    const [attachments, setAttachments] = useState<VoucherAttachment[]>([]);

    const [savedId, setSavedId] = useState<string | undefined>(voucherId);
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
        fetch("/api/proxy/v1/vendors")
            .then(r => (r.ok ? r.json() : []))
            .then((rows: VendorRow[]) => alive && setVendors(Array.isArray(rows) ? rows : []))
            .catch(() => {});
        ledgerApi.fiscal
            .get()
            .then(f => alive && setBooksLockedThrough(f.booksLockedThrough))
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
        setLines(
            v.lines.length
                ? v.lines.map(l => ({
                      key: `s${l.lineNo}`,
                      accountId: l.accountId,
                      description: l.description ?? "",
                      amount: String(l.amount),
                      vatRate: String(l.vatRate ?? 0),
                  }))
                : [newLine()],
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

    // ---- derived ----

    /** `accountId -> vendorId` for every vendor payable in the chart. */
    const payableOwners = useMemo(() => {
        const map: Record<string, string> = {};
        for (const v of vendors) if (v.payableAccount) map[v.payableAccount.id] = v.id;
        return map;
    }, [vendors]);

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

    const editable = canEditVoucher(status);
    const locked = isDateLocked(docDate, booksLockedThrough);

    const refusal: DraftRefusal | null = useMemo(
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
                payableOwners: vendors.length ? payableOwners : undefined,
            }),
        [type, vendorId, paymentAccountId, lines, numericLines, payableOwners, vendors.length],
    );

    /**
     * The single sentence under the buttons naming what the server would refuse.
     * On screen rather than in a `title`, because a disabled button with a hidden
     * reason is the same dead end as a 400 — it just arrives earlier.
     */
    const blocker = refusal
        ? t(refusal)
        : locked
          ? t("periodLocked", { date: booksLockedThrough ?? "" })
          : null;

    const canPost = editable && !blocker && !busy;

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
                propertyId: propertyId || null,
            })),
        }),
        [type, docDate, vendorId, invoiceNumber, narration, propertyId, paymentAccountId,
         chequeNumber, chequeDate, lines, numericLines, withVat],
    );

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
            onPosted?.({ ...({} as VoucherDetail), id: savedId ?? "", status: "DRAFT" } as VoucherDetail);
        });

    const amend = () =>
        run(async () => {
            if (!savedId) return;
            const fresh = await voucherApi.amend(savedId, {
                reversalDate: amendDate,
                reason: amendReason,
                replacement: body(),
            });
            applyDetail(fresh);
            setPostedNumber(fresh.voucherNumber);
            setConfirm(null);
            onPosted?.(fresh);
        });

    const uploadAttachment = (file: File) => {
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

    // ---- render ----

    const columns = 4 + (withVat ? 2 : 0) + 1;

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
                            onClick={() => setLines(ls => [...ls, newLine()])}
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

                {blocker && editable && (
                    <p data-testid="voucher-blocker" className="text-xs font-medium text-warning me-auto">
                        {blocker}
                    </p>
                )}

                {editable && savedId && (
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

                {editable && (
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

                {editable && (
                    <button
                        type="button"
                        data-testid="post-voucher"
                        disabled={!canPost}
                        onClick={() => setConfirm("post")}
                        className="px-5 py-2.5 rounded-lg text-xs font-bold bg-primary text-primary-foreground cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {busy ? t("posting") : t("post")}
                    </button>
                )}

                {/* A posted voucher is corrected by reversal, never edited: journal
                    entries are immutable, so an "edit" would put the document and
                    the ledger permanently out of step (VoucherService.amend). */}
                {canAmendVoucher(status) && (
                    <button
                        type="button"
                        data-testid="amend-voucher"
                        disabled={busy}
                        onClick={() => {
                            setAmendDate(todayIso());
                            setAmendReason("");
                            setConfirm("amend");
                        }}
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
                title={t("amend")}
                description={t("confirmAmend", { number: voucherNumber ?? "" })}
                confirmText={t("amend")}
                cancelText={tLedger("cancel")}
                confirmTestId="confirm-amend"
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
                {vendorId && <p className="sr-only">{vendorName(vendorId)}</p>}
            </ConfirmDialog>
        </div>
    );
}
