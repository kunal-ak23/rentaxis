"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Banknote, ShieldCheck } from "lucide-react";
import { Link } from "@/i18n/routing";
import { Pagination } from "@/components/ui/Pagination";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { useNameLookup } from "@/components/finance/useNameLookup";
import DepositBatchDialog from "@/components/cheques/DepositBatchDialog";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { fmtAmount, type Page } from "@/lib/api/ledger";
import { ApiError, chequeApi, type Cheque } from "@/lib/api/leasing";
import { hasPermission, type UserRole } from "@/lib/rbac";

const field =
    "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-4 py-3 text-xs text-foreground";

export default function ChequeCollectionPage() {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageCheques");

    const properties = useNameLookup("properties", allowed);

    const [propertyId, setPropertyId] = useState("");
    const [page, setPage] = useState<Page<Cheque> | null>(null);
    const [pageIndex, setPageIndex] = useState(0);
    const [size, setSize] = useState(25);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    // Keyed by cheque id so the running total survives a page change without
    // re-fetching every selected row.
    const [selected, setSelected] = useState<Map<string, number>>(new Map());
    const [dialogOpen, setDialogOpen] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            setPage(await chequeApi.toDeposit({ propertyId: propertyId || undefined, page: pageIndex, size }));
        } catch (err) {
            setPage(null);
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [propertyId, pageIndex, size, tCommon]);

    useEffect(() => {
        if (!allowed) {
            setLoading(false);
            return;
        }
        load();
    }, [allowed, load]);

    const selectedIds = useMemo(() => [...selected.keys()], [selected]);
    const selectedTotal = useMemo(() => [...selected.values()].reduce((s, a) => s + a, 0), [selected]);

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    const rows = page?.content ?? [];
    const allOnPageSelected = rows.length > 0 && rows.every(c => selected.has(c.id));

    const toggleOne = (c: Cheque) => {
        setSelected(prev => {
            const next = new Map(prev);
            if (next.has(c.id)) next.delete(c.id);
            else next.set(c.id, c.amount);
            return next;
        });
    };

    const toggleAllOnPage = () => {
        setSelected(prev => {
            const next = new Map(prev);
            if (allOnPageSelected) {
                for (const c of rows) next.delete(c.id);
            } else {
                for (const c of rows) next.set(c.id, c.amount);
            }
            return next;
        });
    };

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Banknote size={20} className="text-primary" />
                        {t("collection")}
                    </h1>
                </div>
                <Link
                    href="/dashboard/finance/cheques"
                    className="px-3 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40"
                >
                    {t("register")}
                </Link>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <div className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-6 flex flex-wrap items-end justify-between gap-4">
                <div>
                    <label className={label} htmlFor="cc-property">{tLedger("propertyFilter")}</label>
                    <select
                        id="cc-property"
                        data-testid="collection-property-filter"
                        className={`${field} min-w-[12rem]`}
                        value={propertyId}
                        onChange={ev => {
                            setPropertyId(ev.target.value);
                            setPageIndex(0);
                        }}
                    >
                        <option value="">{tLedger("selectProperty")}</option>
                        {properties.options.map(p => (
                            <option key={p.id} value={p.id}>{p.label}</option>
                        ))}
                    </select>
                </div>
                <div className="flex items-center gap-4">
                    <span className="text-xs text-muted" data-testid="collection-selected-total">
                        {t("selectedTotal")}: <strong className="text-foreground">{fmtAmount(selectedTotal)}</strong> ({selectedIds.length})
                    </span>
                    <button
                        type="button"
                        data-testid="collection-deposit-selected"
                        disabled={selectedIds.length === 0}
                        onClick={() => setDialogOpen(true)}
                        className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50"
                    >
                        {t("depositBatch")} ({selectedIds.length})
                    </button>
                </div>
            </div>

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3].map(i => <div key={i} className="bg-input rounded-xl h-14" />)}
                </div>
            ) : rows.length === 0 ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Banknote size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{t("noneToDeposit")}</h3>
                </div>
            ) : (
                <div className="bg-surface border border-border rounded-xl shadow-sm p-2">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead className="border-b border-border">
                                <tr>
                                    <th className={th}>
                                        <input
                                            type="checkbox"
                                            aria-label={t("selectAll")}
                                            data-testid="collection-select-all"
                                            checked={allOnPageSelected}
                                            onChange={toggleAllOnPage}
                                        />
                                    </th>
                                    <th className={th}>{tl("chequeNo")}</th>
                                    <th className={th}>{tl("chequeDate")}</th>
                                    <th className={th}>{t("tenant")}</th>
                                    <th className={th}>{tl("unit")}</th>
                                    <th className={th}>{tLedger("propertyFilter")}</th>
                                    <th className={`${th} text-end`}>{tl("amount")}</th>
                                    <th className={th}>{tl("chequeMode")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {rows.map(c => (
                                    <tr key={c.id} data-testid={`cheque-row-${c.id}`} className="hover:bg-input/60 transition-colors">
                                        <td className={td}>
                                            <input
                                                type="checkbox"
                                                aria-label={c.chequeNumber || `#${c.seqNo}`}
                                                data-testid={`collection-select-${c.id}`}
                                                checked={selected.has(c.id)}
                                                onChange={() => toggleOne(c)}
                                            />
                                        </td>
                                        <td className={`${td} font-semibold`}>{c.chequeNumber || `#${c.seqNo}`}</td>
                                        <td className={`${td} tabular-nums`}>{fmtIsoDate(c.chequeDate ?? c.postingDate, locale)}</td>
                                        <td className={td}>{c.renterName || "—"}</td>
                                        <td className={td}>{c.unitIdentifier || "—"}</td>
                                        <td className={`${td} text-muted`}>{c.propertyName || "—"}</td>
                                        <td className={`${td} text-end tabular-nums font-semibold`}>{fmtAmount(c.amount)}</td>
                                        <td className={td}>{tl(`mode.${c.mode}`)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>

                    {page && (
                        <div className="px-3">
                            <Pagination
                                currentPage={page.number + 1}
                                totalItems={page.totalElements}
                                itemsPerPage={page.size || size}
                                onPageChange={p => setPageIndex(p - 1)}
                                onItemsPerPageChange={n => {
                                    setSize(n);
                                    setPageIndex(0);
                                }}
                            />
                        </div>
                    )}
                </div>
            )}

            <DepositBatchDialog
                open={dialogOpen}
                chequeIds={selectedIds}
                total={selectedTotal}
                propertyId={propertyId || undefined}
                onClose={() => setDialogOpen(false)}
                onDone={() => {
                    setDialogOpen(false);
                    setSelected(new Map());
                    load();
                }}
            />
        </div>
    );
}
