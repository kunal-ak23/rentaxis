"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { FileText, Pencil, Plus, Receipt, ShieldCheck, Trash2 } from "lucide-react";
import { Link } from "@/i18n/routing";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { Pagination } from "@/components/ui/Pagination";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { voucherApi, type Voucher, type VoucherStatus, type VoucherType } from "@/lib/api/vouchers";
import { canEditVoucher } from "@/lib/voucherRules";
import { hasPermission, type UserRole } from "@/lib/rbac";

/**
 * Every purchase invoice and payment voucher, newest last (spec §11).
 *
 * The ordering is the server's: `VoucherRepository.search` ends
 * `order by v.createdAt asc`, so the list reads in the order the documents were
 * entered — which is how an accountant works through a day's paperwork — and
 * this page sends no sort of its own.
 *
 * Only a DRAFT row offers Edit and Delete, mirroring
 * `VoucherService.requireDraft`: a posted document is corrected by amendment on
 * its own page, never edited here. A posted row instead links to the journal it
 * wrote, because the number in the Doc No column IS that journal's entry number.
 */

const th = "text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-5 py-3 text-xs text-foreground";
const filter =
    "bg-surface border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer";

const STATUS_CLASS: Record<VoucherStatus, string> = {
    DRAFT: "bg-input text-muted border-border",
    POSTED: "bg-success/10 text-success border-success/30",
    REVERSED: "bg-warning/10 text-warning border-warning/30",
};

/** A voucher opens on the page that can render its own document type. */
function pathFor(v: Voucher): string {
    return v.docType === "BPV"
        ? `/dashboard/finance/vouchers/payment?id=${v.id}`
        : `/dashboard/finance/vouchers/purchase-invoice?id=${v.id}`;
}

export default function VoucherListPage() {
    const t = useTranslations("Vouchers");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageVouchers");

    const [docType, setDocType] = useState<VoucherType | "">("");
    const [status, setStatus] = useState<VoucherStatus | "">("");
    const [propertyId, setPropertyId] = useState("");
    const [from, setFrom] = useState("");
    const [to, setTo] = useState("");
    const [pageIndex, setPageIndex] = useState(0);
    const [size, setSize] = useState(25);

    const [rows, setRows] = useState<Voucher[]>([]);
    const [total, setTotal] = useState(0);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [pendingDelete, setPendingDelete] = useState<Voucher | null>(null);
    const [deleting, setDeleting] = useState(false);

    const properties = useNameLookup("properties", allowed);

    const query = useMemo(
        () => ({ docType, status, propertyId: propertyId || undefined, from: from || undefined, to: to || undefined, page: pageIndex, size }),
        [docType, status, propertyId, from, to, pageIndex, size],
    );

    const load = useCallback(() => {
        setLoading(true);
        setLoadError(null);
        return voucherApi
            .list(query)
            .then(p => {
                setRows(p.content);
                setTotal(p.totalElements);
            })
            .catch(e => setLoadError(e instanceof ApiError ? e.message : tCommon("loadFailed")))
            .finally(() => setLoading(false));
    }, [query, tCommon]);

    useEffect(() => {
        // Nothing until NextAuth has answered: fetching with no role yet means
        // fetching AGAIN when it arrives, and a role that turns out to be a
        // PROPERTY_MANAGER means a 403 this page already knows it would get.
        if (!userRole) return;
        if (!allowed) {
            setLoading(false);
            return;
        }
        load();
    }, [userRole, allowed, load]);

    if (!userRole) {
        return <div data-testid="vouchers-loading" className="bg-input rounded-xl h-14 animate-pulse" />;
    }

    if (!allowed) {
        return (
            <div className="max-w-4xl" data-testid="voucher-access-denied">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("notAllowed")}</p>
                </div>
            </div>
        );
    }

    const confirmDelete = async () => {
        if (!pendingDelete) return;
        setDeleting(true);
        try {
            await voucherApi.remove(pendingDelete.id);
            setPendingDelete(null);
            await load();
        } catch (e) {
            setLoadError(e instanceof ApiError ? e.message : tCommon("loadFailed"));
            setPendingDelete(null);
        } finally {
            setDeleting(false);
        }
    };

    return (
        <div>
            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <div className="flex flex-wrap items-start justify-between gap-4 mb-8">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t("vouchers")}</h1>
                    <p className="text-sm text-muted">{t("vouchersDesc")}</p>
                </div>
                <div className="flex items-center gap-3">
                    <Link
                        href="/dashboard/finance/vouchers/purchase-invoice"
                        data-testid="new-purchase-invoice"
                        className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-bold flex items-center gap-2 cursor-pointer"
                    >
                        <Plus size={14} />
                        {t("newPurchaseInvoice")}
                    </Link>
                    <Link
                        href="/dashboard/finance/vouchers/payment"
                        data-testid="new-payment-voucher"
                        className="border border-border px-4 py-2 rounded-lg text-xs font-bold flex items-center gap-2 cursor-pointer text-foreground"
                    >
                        <Plus size={14} />
                        {t("newPaymentVoucher")}
                    </Link>
                </div>
            </div>

            <div className="flex flex-wrap items-center gap-3 mb-4">
                <select
                    data-testid="filter-doc-type"
                    aria-label={t("type")}
                    className={filter}
                    value={docType}
                    onChange={e => {
                        setDocType(e.target.value as VoucherType | "");
                        setPageIndex(0);
                    }}
                >
                    <option value="">{t("allTypes")}</option>
                    <option value="PISR">{t("purchaseInvoice")}</option>
                    <option value="BPV">{t("paymentVoucher")}</option>
                </select>
                <select
                    data-testid="filter-status"
                    aria-label={tLedger("status")}
                    className={filter}
                    value={status}
                    onChange={e => {
                        setStatus(e.target.value as VoucherStatus | "");
                        setPageIndex(0);
                    }}
                >
                    <option value="">{t("allStatuses")}</option>
                    <option value="DRAFT">{t("draft")}</option>
                    <option value="POSTED">{tLedger("posted")}</option>
                    <option value="REVERSED">{tLedger("reversed")}</option>
                </select>
                <select
                    data-testid="filter-property"
                    aria-label={tLedger("propertyFilter")}
                    className={filter}
                    value={propertyId}
                    onChange={e => {
                        setPropertyId(e.target.value);
                        setPageIndex(0);
                    }}
                >
                    <option value="">{t("allProperties")}</option>
                    {properties.options.map(p => (
                        <option key={p.id} value={p.id}>
                            {p.label}
                        </option>
                    ))}
                </select>
                <label className="flex items-center gap-2 text-xs text-muted">
                    {tLedger("from")}
                    <input
                        type="date"
                        data-testid="filter-from"
                        className={filter}
                        value={from}
                        onChange={e => {
                            setFrom(e.target.value);
                            setPageIndex(0);
                        }}
                    />
                </label>
                <label className="flex items-center gap-2 text-xs text-muted">
                    {tLedger("to")}
                    <input
                        type="date"
                        data-testid="filter-to"
                        className={filter}
                        value={to}
                        onChange={e => {
                            setTo(e.target.value);
                            setPageIndex(0);
                        }}
                    />
                </label>
            </div>

            {loading && (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="bg-input rounded-xl h-14" />
                    ))}
                </div>
            )}

            {!loading && rows.length === 0 && !loadError && (
                <div
                    data-testid="vouchers-empty"
                    className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center"
                >
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Receipt size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{t("noVouchers")}</h3>
                    <p className="text-xs text-muted font-medium">{t("createFirstVoucher")}</p>
                </div>
            )}

            {!loading && rows.length > 0 && (
                <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                    <div className="overflow-x-auto">
                        <table className="w-full" data-testid="vouchers-table">
                            <thead className="bg-input/60 border-b border-border">
                                <tr>
                                    <th className={th}>{tLedger("docDate")}</th>
                                    <th className={th}>{tLedger("docNo")}</th>
                                    <th className={th}>{t("type")}</th>
                                    <th className={th}>{t("vendor")}</th>
                                    <th className={th}>{tLedger("narration")}</th>
                                    <th className={`${th} text-end`}>{t("netTotal")}</th>
                                    <th className={`${th} text-end`}>{t("vatTotal")}</th>
                                    <th className={`${th} text-end`}>{t("grossTotal")}</th>
                                    <th className={th}>{tLedger("status")}</th>
                                    <th className={`${th} text-end`} />
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {rows.map(v => (
                                    <tr
                                        key={v.id}
                                        data-testid={`voucher-row-${v.id}`}
                                        className="hover:bg-input/30 transition-colors"
                                    >
                                        <td className={`${td} tabular-nums`}>{fmtIsoDate(v.docDate, locale)}</td>
                                        <td className={`${td} font-mono`} data-testid={`voucher-number-${v.id}`}>
                                            {v.voucherNumber ?? "—"}
                                        </td>
                                        <td className={td}>
                                            {v.docType === "BPV" ? t("paymentVoucher") : t("purchaseInvoice")}
                                        </td>
                                        <td className={td}>{v.vendorName ?? v.paymentAccountName ?? "—"}</td>
                                        <td className={`${td} text-muted`}>{v.narration ?? "—"}</td>
                                        <td className={`${td} text-end tabular-nums`}>{fmtAmount(v.netTotal)}</td>
                                        <td className={`${td} text-end tabular-nums text-muted`}>
                                            {fmtAmount(v.vatTotal)}
                                        </td>
                                        <td className={`${td} text-end tabular-nums font-semibold`}>
                                            {fmtAmount(v.grossTotal)}
                                        </td>
                                        <td className={td}>
                                            <span
                                                data-testid={`voucher-status-${v.id}`}
                                                data-status={v.status}
                                                className={`inline-block px-2 py-0.5 rounded-md border text-[10px] font-bold uppercase tracking-wider ${STATUS_CLASS[v.status]}`}
                                            >
                                                {v.status === "DRAFT"
                                                    ? t("draft")
                                                    : v.status === "POSTED"
                                                      ? tLedger("posted")
                                                      : tLedger("reversed")}
                                            </span>
                                        </td>
                                        <td className={`${td} text-end whitespace-nowrap`}>
                                            <div className="inline-flex items-center gap-3">
                                                {canEditVoucher(v.status) ? (
                                                    <>
                                                        <Link
                                                            href={pathFor(v)}
                                                            data-testid={`edit-voucher-${v.id}`}
                                                            aria-label={t("editDraft")}
                                                            className="text-primary hover:underline cursor-pointer inline-flex items-center gap-1"
                                                        >
                                                            <Pencil size={12} />
                                                            {t("editDraft")}
                                                        </Link>
                                                        <button
                                                            type="button"
                                                            data-testid={`delete-voucher-${v.id}`}
                                                            aria-label={t("deleteDraft")}
                                                            onClick={() => setPendingDelete(v)}
                                                            className="text-muted hover:text-error cursor-pointer inline-flex items-center gap-1"
                                                        >
                                                            <Trash2 size={12} />
                                                        </button>
                                                    </>
                                                ) : (
                                                    <>
                                                        <Link
                                                            href={pathFor(v)}
                                                            data-testid={`open-voucher-${v.id}`}
                                                            className="text-primary hover:underline cursor-pointer"
                                                        >
                                                            {t("openVoucher")}
                                                        </Link>
                                                        {v.journalId && (
                                                            <Link
                                                                href={`/dashboard/finance/journals/${v.journalId}`}
                                                                data-testid={`view-journal-${v.id}`}
                                                                className="text-primary hover:underline cursor-pointer inline-flex items-center gap-1"
                                                            >
                                                                <FileText size={12} />
                                                                {t("viewJournal")}
                                                            </Link>
                                                        )}
                                                    </>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            {!loading && total > 0 && (
                <Pagination
                    currentPage={pageIndex + 1}
                    totalItems={total}
                    itemsPerPage={size}
                    onPageChange={p => setPageIndex(p - 1)}
                    onItemsPerPageChange={n => {
                        setSize(n);
                        setPageIndex(0);
                    }}
                />
            )}

            <ConfirmDialog
                isOpen={!!pendingDelete}
                onClose={() => setPendingDelete(null)}
                onConfirm={confirmDelete}
                isLoading={deleting}
                isDestructive
                title={t("deleteDraft")}
                description={t("confirmDeleteDraft")}
                confirmText={t("deleteDraft")}
                cancelText={tLedger("cancel")}
                confirmTestId="confirm-delete"
            />
        </div>
    );
}
