"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { Layers, Loader2, Plus, ShieldCheck, Trash2 } from "lucide-react";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { payablesApi, type ApOpeningSummary } from "@/lib/api/payables";
import { hasPermission, type UserRole } from "@/lib/rbac";

const th = "px-3 py-2.5 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap text-start";
const td = "px-3 py-2 text-xs";
const field = "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";

type VendorOption = { id: string; nameEn: string; nameAr: string | null; active: boolean };

const blank = { vendorId: "", invoiceNumber: "", invoiceDate: "", dueDate: "", amount: "", propertyId: "" };

/**
 * Finance → Payables → Opening items (finance-ops spec §2): the supplier
 * invoices still open at cut-over. Their balance came in as an OB line on each
 * vendor's payable leaf; this grid says which invoices make it up, so payments
 * can be allocated to them, and checks Σ per vendor against that OB line.
 */
export default function ApOpeningItemsPage() {
    const t = useTranslations("Payables");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManagePayables");
    const properties = useNameLookup("properties", allowed);

    const [data, setData] = useState<ApOpeningSummary | null>(null);
    const [vendors, setVendors] = useState<VendorOption[]>([]);
    const [form, setForm] = useState(blank);
    const [busy, setBusy] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [formError, setFormError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoadError(null);
        try {
            setData(await payablesApi.openingItems.list());
        } catch (err) {
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        }
    }, [tCommon]);

    useEffect(() => {
        if (!allowed) return;
        load();
        fetch("/api/proxy/v1/vendors")
            .then(r => (r.ok ? r.json() : []))
            .then((rows: VendorOption[]) => setVendors(Array.isArray(rows) ? rows : []))
            .catch(() => {});
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

    const vendorName = (id: string) => {
        const v = vendors.find(x => x.id === id);
        return v ? (locale === "ar" && v.nameAr ? v.nameAr : v.nameEn) : "";
    };

    const add = async (e: React.FormEvent) => {
        e.preventDefault();
        setBusy(true);
        setFormError(null);
        try {
            await payablesApi.openingItems.create({
                vendorId: form.vendorId,
                invoiceNumber: form.invoiceNumber,
                invoiceDate: form.invoiceDate,
                dueDate: form.dueDate || null,
                amount: Number.parseFloat(form.amount),
                propertyId: form.propertyId || null,
            });
            setForm(f => ({ ...blank, vendorId: f.vendorId }));
            await load();
        } catch (err) {
            setFormError(err instanceof ApiError ? err.message : t("saveFailed"));
        } finally {
            setBusy(false);
        }
    };

    const remove = async (id: string) => {
        setBusy(true);
        setFormError(null);
        try {
            await payablesApi.openingItems.remove(id);
            await load();
        } catch (err) {
            setFormError(err instanceof ApiError ? err.message : t("saveFailed"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <div>
            <div className="mb-6">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                    <Layers size={20} className="text-primary" />
                    {t("openingItems")}
                </h1>
                <p className="text-xs text-muted font-medium">{t("openingItemsDesc")}</p>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <form onSubmit={add} className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-5 grid grid-cols-1 md:grid-cols-7 gap-3 items-end" data-testid="opening-item-form">
                <label className="md:col-span-2 text-[10px] font-semibold text-muted uppercase tracking-wider">
                    <span className="block mb-1">{t("vendor")}</span>
                    <select required className={field} data-testid="oi-vendor" value={form.vendorId} onChange={e => setForm({ ...form, vendorId: e.target.value })}>
                        <option value="">{t("chooseVendor")}</option>
                        {vendors.filter(v => v.active).map(v => <option key={v.id} value={v.id}>{locale === "ar" && v.nameAr ? v.nameAr : v.nameEn}</option>)}
                    </select>
                </label>
                <label className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                    <span className="block mb-1">{t("invoiceNumber")}</span>
                    <input required maxLength={60} className={field} data-testid="oi-invoice" value={form.invoiceNumber} onChange={e => setForm({ ...form, invoiceNumber: e.target.value })} />
                </label>
                <label className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                    <span className="block mb-1">{t("invoiceDate")}</span>
                    <input required type="date" className={field} data-testid="oi-date" value={form.invoiceDate} onChange={e => setForm({ ...form, invoiceDate: e.target.value })} />
                </label>
                <label className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                    <span className="block mb-1">{t("dueDateOptional")}</span>
                    <input type="date" className={field} data-testid="oi-due" value={form.dueDate} onChange={e => setForm({ ...form, dueDate: e.target.value })} />
                </label>
                <label className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                    <span className="block mb-1">{t("amount")}</span>
                    <input required inputMode="decimal" className={`${field} text-end tabular-nums`} data-testid="oi-amount" value={form.amount} onChange={e => setForm({ ...form, amount: e.target.value })} />
                </label>
                <label className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                    <span className="block mb-1">{t("property")}</span>
                    <select className={field} data-testid="oi-property" value={form.propertyId} onChange={e => setForm({ ...form, propertyId: e.target.value })}>
                        <option value="">{t("noProperty")}</option>
                        {properties.options.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}
                    </select>
                </label>
                <div className="md:col-span-7 flex items-center justify-between gap-3">
                    {formError ? <p role="alert" className="text-xs font-semibold text-error" data-testid="oi-error">{formError}</p> : <span />}
                    <button type="submit" disabled={busy} data-testid="oi-add"
                            className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50">
                        {busy ? <Loader2 size={13} className="animate-spin" /> : <Plus size={13} />}{t("addItem")}
                    </button>
                </div>
            </form>

            {data && data.vendors.length > 0 && (
                <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden mb-5">
                    <h2 className="px-4 py-3 text-xs font-bold border-b border-border">{t("obCheck")}</h2>
                    <div className="overflow-x-auto">
                        <table className="w-full" data-testid="ob-check">
                            <thead className="bg-input/60">
                                <tr>
                                    <th className={th}>{t("vendor")}</th>
                                    <th className={`${th} text-end`}>{t("itemsTotal")}</th>
                                    <th className={`${th} text-end`}>{t("openingBalance")}</th>
                                    <th className={`${th} text-end`}>{t("difference")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {data.vendors.map(v => (
                                    <tr key={v.vendorId}>
                                        <td className={td}>{vendorName(v.vendorId) || v.vendorName}</td>
                                        <td className={`${td} text-end`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(v.itemsTotal)}</bdi></td>
                                        <td className={`${td} text-end`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(v.openingBalance)}</bdi></td>
                                        <td className={`${td} text-end font-semibold ${Math.abs(v.difference) >= 0.005 ? "text-warning" : "text-success"}`}>
                                            <bdi dir="ltr" className="tabular-nums">{fmtAmount(v.difference)}</bdi>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            {data && data.items.length === 0 ? (
                <div className="text-center py-16 bg-background border border-dashed border-border rounded-xl text-sm text-muted">{t("noOpeningItems")}</div>
            ) : data ? (
                <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                    <div className="overflow-x-auto">
                        <table className="w-full min-w-[760px]" data-testid="opening-items">
                            <thead className="bg-input/60">
                                <tr>
                                    <th className={th}>{t("vendor")}</th>
                                    <th className={th}>{t("invoiceNumber")}</th>
                                    <th className={th}>{t("invoiceDate")}</th>
                                    <th className={th}>{t("dueDate")}</th>
                                    <th className={th}>{t("property")}</th>
                                    <th className={`${th} text-end`}>{t("amount")}</th>
                                    <th className={`${th} text-end`}>{t("paid")}</th>
                                    <th className={`${th} text-end`}>{t("open")}</th>
                                    <th className={th} />
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {data.items.map(i => (
                                    <tr key={i.id} data-testid={`opening-item-${i.invoiceNumber}`}>
                                        <td className={td}>{vendorName(i.vendorId) || i.vendorName}</td>
                                        <td className={td}>{i.invoiceNumber}</td>
                                        <td className={td}><bdi dir="ltr">{i.invoiceDate}</bdi></td>
                                        <td className={td}><bdi dir="ltr">{i.dueDate}</bdi></td>
                                        <td className={td}>{properties.name(i.propertyId) || "—"}</td>
                                        <td className={`${td} text-end`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(i.amount)}</bdi></td>
                                        <td className={`${td} text-end`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(i.allocated)}</bdi></td>
                                        <td className={`${td} text-end font-semibold`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(i.open)}</bdi></td>
                                        <td className={`${td} text-end`}>
                                            <button type="button" disabled={busy || i.allocated > 0} onClick={() => remove(i.id)}
                                                    aria-label={t("deleteItem")} title={i.allocated > 0 ? t("deleteBlocked") : t("deleteItem")}
                                                    className="p-1.5 text-muted hover:text-error disabled:opacity-30 cursor-pointer disabled:cursor-not-allowed">
                                                <Trash2 size={13} />
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            ) : null}
        </div>
    );
}
