"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Filter, Plus, Receipt, ShieldCheck } from "lucide-react";
import { Link } from "@/i18n/routing";
import { Pagination } from "@/components/ui/Pagination";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { journalStatusClass } from "@/components/finance/journalStatus";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount, ledgerApi, type JournalDocType, type JournalEntry, type Page } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";

const field =
    "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const th = "text-start px-5 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-5 py-3 text-xs text-foreground";

type Filters = { docType: JournalDocType | ""; from: string; to: string; propertyId: string };

const emptyFilters: Filters = { docType: "", from: "", to: "", propertyId: "" };

export default function JournalsPage() {
    const t = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canAccessFinance");
    const canPost = hasPermission(userRole, "canPostJournals");

    const properties = useNameLookup("properties", allowed);

    // `draft` is what the bar edits, `applied` is what the list shows — the same
    // split the ledger reports use, so typing a date does not re-query.
    const [draft, setDraft] = useState<Filters>(emptyFilters);
    const [applied, setApplied] = useState<Filters>(emptyFilters);
    const [page, setPage] = useState<Page<JournalEntry> | null>(null);
    const [pageIndex, setPageIndex] = useState(0);
    const [size, setSize] = useState(25);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [docTypes, setDocTypes] = useState<JournalDocType[]>([]);

    useEffect(() => {
        if (!allowed) return;
        ledgerApi.journals
            .docTypes()
            .then(setDocTypes)
            .catch(() => setDocTypes([]));
    }, [allowed]);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            setPage(
                await ledgerApi.journals.list({
                    docType: applied.docType || undefined,
                    from: applied.from || undefined,
                    to: applied.to || undefined,
                    propertyId: applied.propertyId || undefined,
                    page: pageIndex,
                    size,
                }),
            );
        } catch (err) {
            setPage(null);
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [applied, pageIndex, size, tCommon]);

    useEffect(() => {
        if (!allowed) {
            setLoading(false);
            return;
        }
        load();
    }, [allowed, load]);

    const apply = () => {
        setPageIndex(0);
        setApplied(draft);
    };

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{t("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("accessDeniedLedger")}</p>
                </div>
            </div>
        );
    }

    const rows = page?.content ?? [];

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-8">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Receipt size={20} className="text-primary" />
                        {t("journals")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("journalsDesc")}</p>
                </div>
                {canPost && (
                    <Link
                        href="/dashboard/finance/journals/new"
                        className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    >
                        <Plus size={13} />
                        {t("newJournal")}
                    </Link>
                )}
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <div className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-6">
                <div className="flex flex-wrap items-end gap-4">
                    <div>
                        <label className={label} htmlFor="jv-doc-type">{t("docType")}</label>
                        <select
                            id="jv-doc-type"
                            className={`${field} min-w-[10rem]`}
                            value={draft.docType}
                            onChange={ev => setDraft({ ...draft, docType: ev.target.value as JournalDocType | "" })}
                        >
                            <option value="">{t("allDocTypes")}</option>
                            {docTypes.map(dt => (
                                <option key={dt} value={dt}>{dt}</option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className={label} htmlFor="jv-from">{t("from")}</label>
                        <input id="jv-from" type="date" className={field} value={draft.from} onChange={ev => setDraft({ ...draft, from: ev.target.value })} />
                    </div>
                    <div>
                        <label className={label} htmlFor="jv-to">{t("to")}</label>
                        <input id="jv-to" type="date" className={field} value={draft.to} onChange={ev => setDraft({ ...draft, to: ev.target.value })} />
                    </div>
                    <div>
                        <label className={label} htmlFor="jv-property-filter">{t("propertyFilter")}</label>
                        <select
                            id="jv-property-filter"
                            className={`${field} min-w-[12rem]`}
                            value={draft.propertyId}
                            onChange={ev => setDraft({ ...draft, propertyId: ev.target.value })}
                        >
                            <option value="">{t("selectProperty")}</option>
                            {properties.options.map(p => (
                                <option key={p.id} value={p.id}>{p.label}</option>
                            ))}
                        </select>
                    </div>
                    <button
                        type="button"
                        onClick={apply}
                        disabled={loading}
                        className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50 focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    >
                        <Filter size={13} />
                        {t("apply")}
                    </button>
                </div>
            </div>

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4, 5].map(i => (
                        <div key={i} className="bg-input rounded-xl h-14" />
                    ))}
                </div>
            ) : rows.length === 0 ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Receipt size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{t("noRows")}</h3>
                </div>
            ) : (
                <div className="bg-surface border border-border rounded-xl shadow-sm p-2">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead className="border-b border-border">
                                <tr>
                                    <th className={th}>{t("docNo")}</th>
                                    <th className={th}>{t("docDate")}</th>
                                    <th className={th}>{t("docType")}</th>
                                    <th className={th}>{t("narration")}</th>
                                    <th className={th}>{t("propertyFilter")}</th>
                                    <th className={`${th} text-end`}>{t("total")}</th>
                                    <th className={th}>{t("status")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {rows.map(j => (
                                    <tr key={j.id} className="hover:bg-input/60 transition-colors">
                                        <td className={`${td} font-semibold`}>
                                            <Link
                                                href={`/dashboard/finance/journals/${j.id}`}
                                                className="text-primary hover:underline cursor-pointer"
                                            >
                                                {j.entryNumber}
                                            </Link>
                                        </td>
                                        <td className={`${td} tabular-nums`}>{j.entryDate}</td>
                                        <td className={td}>{j.docType}</td>
                                        <td className={`${td} text-muted max-w-[22rem] truncate`}>{j.narration}</td>
                                        <td className={`${td} text-muted`}>{properties.name(j.propertyId)}</td>
                                        <td className={`${td} text-end tabular-nums font-semibold`}>{fmtAmount(j.total)}</td>
                                        <td className={td}>
                                            <span className={`inline-block px-2 py-0.5 rounded-md border text-[10px] font-bold uppercase tracking-wider ${journalStatusClass(j.status)}`}>
                                                {j.status === "POSTED" ? t("posted") : t("reversed")}
                                            </span>
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
        </div>
    );
}
