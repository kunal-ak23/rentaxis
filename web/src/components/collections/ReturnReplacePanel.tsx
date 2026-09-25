"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { RefreshCcw, ShieldCheck } from "lucide-react";
import { Link } from "@/i18n/routing";
import { Pagination } from "@/components/ui/Pagination";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { useNameLookup } from "@/components/finance/useNameLookup";
import ReplaceChequeDialog from "@/components/cheques/ReplaceChequeDialog";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { fmtAmount, type Page } from "@/lib/api/ledger";
import { ApiError, chequeApi, type Cheque } from "@/lib/api/leasing";
import { hasPermission, type UserRole } from "@/lib/rbac";

const field =
    "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-4 py-3 text-xs text-foreground";

export default function ReturnReplacePanel(props: { embedded?: boolean; propertyId?: string }) {
    const embedded = props.embedded ?? false;
    const Heading = embedded ? "h2" : "h1";
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageCheques");

    const properties = useNameLookup("properties", allowed);

    const [ownPropertyId, setPropertyId] = useState("");
    // Inside the Collection hub the hub's property filter drives the query and
    // this panel's own picker is hidden.
    const propertyId = props.propertyId ?? ownPropertyId;
    const [page, setPage] = useState<Page<Cheque> | null>(null);
    const [pageIndex, setPageIndex] = useState(0);
    const [size, setSize] = useState(25);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [target, setTarget] = useState<Cheque | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            setPage(await chequeApi.list({ status: "BOUNCED", propertyId: propertyId || undefined, page: pageIndex, size }));
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

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6">
                <div>
                    <Heading className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <RefreshCcw size={20} className="text-primary" />
                        {t("returnReplace")}
                    </Heading>
                </div>
                {!embedded && (
                    <Link
                        href="/dashboard/finance/cheques"
                        className="px-3 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40"
                    >
                        {t("register")}
                    </Link>
                )}
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            {props.propertyId === undefined && (
                <div className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-6">
                    <label className={label} htmlFor="rr-property">{tLedger("propertyFilter")}</label>
                    <select
                        id="rr-property"
                        data-testid="return-replace-property-filter"
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
            )}

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3].map(i => <div key={i} className="bg-input rounded-xl h-14" />)}
                </div>
            ) : rows.length === 0 ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <RefreshCcw size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{t("noResults")}</h3>
                </div>
            ) : (
                <div className="bg-surface border border-border rounded-xl shadow-sm p-2">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead className="border-b border-border">
                                <tr>
                                    <th className={th}>{tl("chequeNo")}</th>
                                    <th className={th}>{tl("chequeDate")}</th>
                                    <th className={th}>{t("tenant")}</th>
                                    <th className={th}>{tl("unit")}</th>
                                    <th className={th}>{tLedger("propertyFilter")}</th>
                                    <th className={`${th} text-end`}>{tl("amount")}</th>
                                    <th className={th}>{t("failureReason")}</th>
                                    <th className={th}>{tl("actions")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {rows.map(c => (
                                    <tr key={c.id} data-testid={`cheque-row-${c.id}`} className="hover:bg-input/60 transition-colors">
                                        <td className={`${td} font-semibold`}>{c.chequeNumber || `#${c.seqNo}`}</td>
                                        <td className={`${td} tabular-nums`}>{fmtIsoDate(c.chequeDate ?? c.postingDate, locale)}</td>
                                        <td className={td}>{c.renterName || "—"}</td>
                                        <td className={td}>{c.unitIdentifier || "—"}</td>
                                        <td className={`${td} text-muted`}>{c.propertyName || "—"}</td>
                                        <td className={`${td} text-end tabular-nums font-semibold`}>{fmtAmount(c.amount)}</td>
                                        <td className={td}>{c.failureReason ? t(`failureReasons.${c.failureReason}`) : "—"}</td>
                                        <td className={td}>
                                            <button
                                                type="button"
                                                data-testid={`cheque-row-action-replace-${c.id}`}
                                                onClick={() => setTarget(c)}
                                                className="px-2 py-1 rounded-md text-[10px] font-bold bg-input text-foreground hover:bg-border transition-colors cursor-pointer"
                                            >
                                                {t("replace")}
                                            </button>
                                        </td>
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

            <ReplaceChequeDialog
                cheque={target}
                propertyId={target?.propertyId}
                onClose={() => setTarget(null)}
                onDone={() => {
                    setTarget(null);
                    load();
                }}
            />
        </div>
    );
}
