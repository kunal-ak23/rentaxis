"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { ArrowLeft, BookOpen, FileText, Loader2, ShieldCheck } from "lucide-react";
import { Link } from "@/i18n/routing";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { payablesApi, type Advance, type OpenItem } from "@/lib/api/payables";
import { hasPermission, type UserRole } from "@/lib/rbac";

const th = "px-3 py-2.5 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap text-start";
const td = "px-3 py-2 text-xs";
const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const button = "flex items-center gap-1.5 px-3 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all cursor-pointer";

const STATUS_CLASS: Record<string, string> = {
    OPEN: "bg-warning/10 text-warning border-warning/30",
    PART_PAID: "bg-primary/10 text-primary border-primary/30",
    PAID: "bg-success/10 text-success border-success/30",
};

type VendorDetail = { id: string; nameEn: string; nameAr: string | null; trn: string | null; paymentTermsDays: number | null; active: boolean };

const pad = (n: number) => String(n).padStart(2, "0");
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

/**
 * A vendor's supplier-AP page (finance-ops spec §2): every invoice with what is
 * still owed on it, payments not yet allocated (advances) with an Apply action,
 * and the statement of account as a PDF in English or Arabic.
 */
export default function VendorAccountPage() {
    const t = useTranslations("Payables");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const params = useParams<{ id: string }>();
    const vendorId = params.id;
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManagePayables");

    const [tab, setTab] = useState<"items" | "advances" | "statement">("items");
    const [vendor, setVendor] = useState<VendorDetail | null>(null);
    const [items, setItems] = useState<OpenItem[]>([]);
    const [advances, setAdvances] = useState<Advance[]>([]);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [apply, setApply] = useState<{ paymentId: string; target: string; amount: string } | null>(null);
    const today = new Date();
    const [from, setFrom] = useState(iso(new Date(today.getFullYear(), 0, 1)));
    const [to, setTo] = useState(iso(today));

    const load = useCallback(async () => {
        setLoadError(null);
        try {
            const [v, i, a] = await Promise.all([
                fetch(`/api/proxy/v1/vendors/${vendorId}`).then(r => {
                    if (!r.ok) throw new ApiError(r.status, tCommon("loadFailed"));
                    return r.json() as Promise<VendorDetail>;
                }),
                payablesApi.vendorItems(vendorId),
                payablesApi.advances(vendorId),
            ]);
            setVendor(v);
            setItems(i);
            setAdvances(a);
        } catch (err) {
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        }
    }, [vendorId, tCommon]);

    useEffect(() => {
        if (allowed) load();
    }, [allowed, load]);

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <p className="text-sm text-muted">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    const openItems = items.filter(i => i.open > 0);
    const totalOpen = openItems.reduce((s, i) => s + i.open, 0);
    const totalAdvance = advances.reduce((s, a) => s + a.unallocated, 0);

    const submitApply = async () => {
        if (!apply) return;
        const [kind, id] = apply.target.split(":");
        setBusy(true);
        setActionError(null);
        try {
            await payablesApi.allocate({
                paymentId: apply.paymentId,
                ...(kind === "PISR" ? { invoiceId: id } : { openingItemId: id }),
                amount: Number.parseFloat(apply.amount),
            });
            setApply(null);
            await load();
        } catch (err) {
            setActionError(err instanceof ApiError ? err.message : t("saveFailed"));
        } finally {
            setBusy(false);
        }
    };

    const tabClass = (k: typeof tab) =>
        `px-4 py-2 text-xs font-semibold border-b-2 cursor-pointer ${tab === k ? "border-primary text-foreground" : "border-transparent text-muted"}`;

    return (
        <div className="max-w-6xl">
            <Link href="/dashboard/finance/vendors" className="inline-flex items-center gap-1.5 text-xs font-semibold text-muted hover:text-foreground mb-4">
                <ArrowLeft size={13} className="rtl:rotate-180" />{t("vendors")}
            </Link>
            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1" data-testid="vendor-name">
                        {vendor ? (locale === "ar" && vendor.nameAr ? vendor.nameAr : vendor.nameEn) : "…"}
                    </h1>
                    {vendor && (
                        <p className="text-xs text-muted">
                            {t("trn")}: {vendor.trn ? <bdi dir="ltr">{vendor.trn}</bdi> : <span className="text-warning">{t("noTrn")}</span>}
                            {" · "}{t("terms", { days: vendor.paymentTermsDays ?? 30 })}
                        </p>
                    )}
                </div>
                <div className="flex gap-6 text-xs">
                    <div><span className="text-muted">{t("openTotal")}</span> <bdi dir="ltr" className="font-bold tabular-nums" data-testid="vendor-open">{fmtAmount(totalOpen)}</bdi></div>
                    <div><span className="text-muted">{t("advances")}</span> <bdi dir="ltr" className="font-bold tabular-nums" data-testid="vendor-advance">{fmtAmount(totalAdvance)}</bdi></div>
                    <Link href={`/dashboard/finance/general-ledger?vendorId=${vendorId}`} className="flex items-center gap-1 text-primary font-semibold hover:underline">
                        <BookOpen size={13} />{t("ledger")}
                    </Link>
                </div>
            </div>

            <div role="tablist" className="flex gap-1 border-b border-border mb-4">
                <button role="tab" aria-selected={tab === "items"} className={tabClass("items")} onClick={() => setTab("items")} data-testid="tab-items">{t("openItemsTab")}</button>
                <button role="tab" aria-selected={tab === "advances"} className={tabClass("advances")} onClick={() => setTab("advances")} data-testid="tab-advances">{t("advancesTab")}</button>
                <button role="tab" aria-selected={tab === "statement"} className={tabClass("statement")} onClick={() => setTab("statement")} data-testid="tab-statement">{t("statementTab")}</button>
            </div>

            {actionError && <p role="alert" className="text-xs font-semibold text-error mb-3" data-testid="vendor-action-error">{actionError}</p>}

            {tab === "items" && (
                <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                    {items.length === 0 ? (
                        <p className="px-4 py-10 text-center text-sm text-muted">{t("noInvoices")}</p>
                    ) : (
                        <div className="overflow-x-auto">
                            <table className="w-full min-w-[820px]" data-testid="vendor-items">
                                <thead className="bg-input/60">
                                    <tr>
                                        <th className={th}>{t("invoiceNumber")}</th>
                                        <th className={th}>{t("voucher")}</th>
                                        <th className={th}>{t("invoiceDate")}</th>
                                        <th className={th}>{t("dueDate")}</th>
                                        <th className={`${th} text-end`}>{t("daysOverdue")}</th>
                                        <th className={`${th} text-end`}>{t("amount")}</th>
                                        <th className={`${th} text-end`}>{t("paid")}</th>
                                        <th className={`${th} text-end`}>{t("open")}</th>
                                        <th className={th}>{t("status")}</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {items.map(i => (
                                        <tr key={i.kind + i.id} data-testid={`vendor-item-${i.invoiceNumber}`}>
                                            <td className={td}>{i.invoiceNumber}</td>
                                            <td className={`${td} font-mono`}>
                                                {i.kind === "PISR" ? (
                                                    <Link href={`/dashboard/finance/vouchers/purchase-invoice?id=${i.id}`} className="text-primary hover:underline">{i.docNumber}</Link>
                                                ) : t("openingItem")}
                                            </td>
                                            <td className={td}><bdi dir="ltr">{i.invoiceDate}</bdi></td>
                                            <td className={td}><bdi dir="ltr">{i.dueDate}</bdi></td>
                                            <td className={`${td} text-end tabular-nums`}>{i.open > 0 && i.daysOverdue > 0 ? <bdi dir="ltr" className="text-error">{i.daysOverdue}</bdi> : "—"}</td>
                                            <td className={`${td} text-end`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(i.gross)}</bdi></td>
                                            <td className={`${td} text-end`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(i.allocated)}</bdi></td>
                                            <td className={`${td} text-end font-semibold`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(i.open)}</bdi></td>
                                            <td className={td}>
                                                <span className={`inline-block px-2 py-0.5 rounded-md border text-[10px] font-bold uppercase ${STATUS_CLASS[i.status]}`} data-status={i.status}>
                                                    {t(`status.${i.status}`)}
                                                </span>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>
            )}

            {tab === "advances" && (
                <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                    {advances.length === 0 ? (
                        <p className="px-4 py-10 text-center text-sm text-muted">{t("noAdvances")}</p>
                    ) : (
                        <div className="overflow-x-auto">
                            <table className="w-full min-w-[720px]" data-testid="vendor-advances">
                                <thead className="bg-input/60">
                                    <tr>
                                        <th className={th}>{t("voucher")}</th>
                                        <th className={th}>{t("date")}</th>
                                        <th className={th}>{t("reference")}</th>
                                        <th className={`${th} text-end`}>{t("paid")}</th>
                                        <th className={`${th} text-end`}>{t("unallocated")}</th>
                                        <th className={th} />
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {advances.map(a => (
                                        <tr key={a.paymentId} data-testid={`advance-${a.voucherNumber}`}>
                                            <td className={`${td} font-mono`}>
                                                <Link href={`/dashboard/finance/vouchers/payment?id=${a.paymentId}`} className="text-primary hover:underline">{a.voucherNumber}</Link>
                                            </td>
                                            <td className={td}><bdi dir="ltr">{a.docDate}</bdi></td>
                                            <td className={td}>{a.reference ?? "—"}</td>
                                            <td className={`${td} text-end`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(a.paid)}</bdi></td>
                                            <td className={`${td} text-end font-semibold`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(a.unallocated)}</bdi></td>
                                            <td className={`${td} text-end`}>
                                                {apply?.paymentId === a.paymentId ? (
                                                    <div className="flex flex-wrap items-center justify-end gap-2">
                                                        <select aria-label={t("applyTo")} className={field} data-testid="apply-target" value={apply.target}
                                                                onChange={e => {
                                                                    const target = e.target.value;
                                                                    const item = openItems.find(i => `${i.kind}:${i.id}` === target);
                                                                    setApply({ ...apply, target, amount: item ? Math.min(item.open, a.unallocated).toFixed(2) : apply.amount });
                                                                }}>
                                                            <option value="">{t("applyTo")}</option>
                                                            {openItems.map(i => (
                                                                <option key={i.kind + i.id} value={`${i.kind}:${i.id}`}>{i.invoiceNumber} · {fmtAmount(i.open)}</option>
                                                            ))}
                                                        </select>
                                                        <input aria-label={t("amount")} inputMode="decimal" className={`${field} w-28 text-end`} data-testid="apply-amount"
                                                               value={apply.amount} onChange={e => setApply({ ...apply, amount: e.target.value })} />
                                                        <button type="button" disabled={busy || !apply.target || !(Number.parseFloat(apply.amount) > 0)} onClick={submitApply}
                                                                className="px-3 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50" data-testid="apply-confirm">
                                                            {busy ? <Loader2 size={12} className="animate-spin" /> : t("apply")}
                                                        </button>
                                                        <button type="button" onClick={() => setApply(null)} className="text-xs text-muted cursor-pointer">{t("cancel")}</button>
                                                    </div>
                                                ) : (
                                                    <button type="button" disabled={openItems.length === 0}
                                                            onClick={() => setApply({ paymentId: a.paymentId, target: "", amount: "" })}
                                                            className="text-xs font-semibold text-primary cursor-pointer disabled:opacity-40" data-testid={`apply-${a.voucherNumber}`}>
                                                        {t("applyAdvance")}
                                                    </button>
                                                )}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>
            )}

            {tab === "statement" && (
                <div className="bg-surface border border-border rounded-xl shadow-sm p-5 space-y-4">
                    <p className="text-xs text-muted">{t("statementDesc")}</p>
                    <div className="flex flex-wrap items-end gap-3">
                        <label className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                            <span className="block mb-1">{t("from")}</span>
                            <input type="date" className={field} value={from} onChange={e => setFrom(e.target.value)} data-testid="soa-from" />
                        </label>
                        <label className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                            <span className="block mb-1">{t("to")}</span>
                            <input type="date" className={field} value={to} onChange={e => setTo(e.target.value)} data-testid="soa-to" />
                        </label>
                        <a className={button} href={payablesApi.statementPdfUrl(vendorId, from, to, "en")} target="_blank" rel="noopener noreferrer" data-testid="soa-pdf-en">
                            <FileText size={13} />{t("pdfEn")}
                        </a>
                        <a className={button} href={payablesApi.statementPdfUrl(vendorId, from, to, "ar")} target="_blank" rel="noopener noreferrer" data-testid="soa-pdf-ar">
                            <FileText size={13} />{t("pdfAr")}
                        </a>
                    </div>
                </div>
            )}
        </div>
    );
}
