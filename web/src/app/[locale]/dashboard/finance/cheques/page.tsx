"use client";

import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Banknote, Filter, Receipt, ShieldCheck } from "lucide-react";
import { Link } from "@/i18n/routing";
import { Pagination } from "@/components/ui/Pagination";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { useNameLookup } from "@/components/finance/useNameLookup";
import ChequeStatusBadge from "@/components/cheques/ChequeStatusBadge";
import ChequeActionDialog from "@/components/cheques/ChequeActionDialog";
import BounceChequeDialog from "@/components/cheques/BounceChequeDialog";
import ReplaceChequeDialog from "@/components/cheques/ReplaceChequeDialog";
import ReceiveCashDialog from "@/components/cheques/ReceiveCashDialog";
import { registerActionsFor, type RegisterAction } from "@/components/cheques/registerActions";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { fmtAmount, type Page } from "@/lib/api/ledger";
import {
    ApiError,
    chequeApi,
    type AgingReport,
    type Cheque,
    type ChequeMode,
    type ChequeStatus,
    type ChequeSummary,
} from "@/lib/api/leasing";
import { hasPermission, type UserRole } from "@/lib/rbac";

const field =
    "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-4 py-3 text-xs text-foreground";

// Draft rows never enter the register — the server never returns them, and
// offering DRAFT as a filter would let the operator ask for something the
// endpoint can never answer.
const STATUS_OPTIONS: ChequeStatus[] = [
    "REGISTERED", "DEPOSITED", "CLEARED", "BOUNCED", "REPLACED", "CANCELLED", "RETURNED", "ONLINE_PENDING",
];
const MODE_OPTIONS: ChequeMode[] = ["PDC", "CASH", "TRANSFER", "ONLINE"];

type Filters = { status: ChequeStatus | ""; mode: ChequeMode | ""; propertyId: string; from: string; to: string; search: string };
const emptyFilters: Filters = { status: "", mode: "", propertyId: "", from: "", to: "", search: "" };

/** The dialog's own single-row action set — bounce, replace and receipt are handled separately. */
type SingleRowAction = Extract<RegisterAction, "deposit" | "receive" | "details" | "cancel" | "clear">;

export default function ChequeRegisterPage() {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageCheques");
    const canCancel = hasPermission(userRole, "canCancelCheques");

    const properties = useNameLookup("properties", allowed);
    const searchParams = useSearchParams();

    const [draft, setDraft] = useState<Filters>(() => ({ ...emptyFilters, search: searchParams.get("search") ?? "" }));
    const [applied, setApplied] = useState<Filters>(draft);
    const [page, setPage] = useState<Page<Cheque> | null>(null);
    const [pageIndex, setPageIndex] = useState(0);
    const [size, setSize] = useState(25);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    const [summary, setSummary] = useState<ChequeSummary | null>(null);
    const [aging, setAging] = useState<AgingReport | null>(null);

    const [dialogAction, setDialogAction] = useState<{ action: SingleRowAction; cheque: Cheque } | null>(null);
    const [bounceTarget, setBounceTarget] = useState<Cheque | null>(null);
    const [replaceTarget, setReplaceTarget] = useState<Cheque | null>(null);
    const [cashReceiptOpen, setCashReceiptOpen] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            setPage(
                await chequeApi.list({
                    status: applied.status || undefined,
                    mode: applied.mode || undefined,
                    propertyId: applied.propertyId || undefined,
                    from: applied.from || undefined,
                    to: applied.to || undefined,
                    search: applied.search || undefined,
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

    const loadTiles = useCallback(async () => {
        try {
            const [s, a] = await Promise.all([
                chequeApi.summary(applied.propertyId || undefined),
                chequeApi.aging(applied.propertyId || undefined),
            ]);
            setSummary(s);
            setAging(a);
        } catch {
            setSummary(null);
            setAging(null);
        }
    }, [applied.propertyId]);

    useEffect(() => {
        if (!allowed) {
            setLoading(false);
            return;
        }
        load();
    }, [allowed, load]);

    useEffect(() => {
        if (!allowed) return;
        loadTiles();
    }, [allowed, loadTiles]);

    const apply = () => {
        setPageIndex(0);
        setApplied(draft);
    };

    const refresh = () => {
        load();
        loadTiles();
    };

    const openAction = (cheque: Cheque, action: RegisterAction) => {
        if (action === "receipt") {
            window.open(chequeApi.receiptUrl(cheque.id), "_blank", "noopener,noreferrer");
            return;
        }
        if (action === "bounce") {
            setBounceTarget(cheque);
            return;
        }
        if (action === "replace") {
            setReplaceTarget(cheque);
            return;
        }
        setDialogAction({ action: action as SingleRowAction, cheque });
    };

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
    const summaryTiles = summary
        ? [
              { key: "registered", label: t("summary.registered"), count: summary.registeredCount, amount: summary.registeredAmount },
              { key: "deposited", label: t("summary.deposited"), count: summary.depositedCount, amount: summary.depositedAmount },
              { key: "clearedThisMonth", label: t("summary.clearedThisMonth"), count: null, amount: summary.clearedThisMonthAmount },
              { key: "bounced", label: t("summary.bounced"), count: summary.bouncedCount, amount: summary.bouncedAmount },
              { key: "due", label: t("summary.due"), count: summary.dueCount, amount: summary.dueAmount },
              { key: "overdue", label: t("summary.overdue"), count: summary.overdueCount, amount: summary.overdueAmount },
          ]
        : [];

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Receipt size={20} className="text-primary" />
                        {t("register")}
                    </h1>
                </div>
                <div className="flex items-center gap-2">
                    <Link
                        href="/dashboard/finance/cheques/collection"
                        className="px-3 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40"
                    >
                        {t("collection")}
                    </Link>
                    <Link
                        href="/dashboard/finance/cheques/return-replace"
                        className="px-3 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40"
                    >
                        {t("returnReplace")}
                    </Link>
                    <Link
                        href="/dashboard/finance/cheques/post-dated"
                        className="px-3 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40"
                    >
                        {t("postDated")}
                    </Link>
                    <button
                        type="button"
                        data-testid="open-cash-receipt"
                        onClick={() => setCashReceiptOpen(true)}
                        className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer"
                    >
                        <Banknote size={13} />
                        {t("cashReceipt")}
                    </button>
                </div>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={refresh} />}

            {summary && (
                <div
                    data-testid="cheque-summary-tiles"
                    className="bg-surface border border-border rounded-xl px-5 py-4 mb-4 grid grid-cols-2 md:grid-cols-6 gap-2"
                >
                    {summaryTiles.map((tile, i) => (
                        <div
                            key={tile.key}
                            data-testid={`cheque-summary-${tile.key}`}
                            className={i < summaryTiles.length - 1 ? "border-e border-border pe-2" : ""}
                        >
                            <div className="text-[10.5px] text-muted uppercase tracking-wider font-semibold">{tile.label}</div>
                            <div className="font-mono text-[16px] font-bold text-foreground tabular-nums">{fmtAmount(tile.amount)}</div>
                            {tile.count != null && <div className="text-[10.5px] text-muted">{tile.count}</div>}
                        </div>
                    ))}
                </div>
            )}

            {aging && aging.totalCount > 0 && (
                <div data-testid="cheque-aging-strip" className="bg-surface border border-border rounded-xl px-5 py-3 mb-6 flex flex-wrap items-center gap-4">
                    {aging.buckets.map(b => (
                        <div key={b.label} data-testid={`cheque-aging-${b.label}`} className="text-[11px]">
                            <span className="text-muted">{b.label}: </span>
                            <span className="font-semibold">{b.count}</span>
                            <span className="text-muted"> ({fmtAmount(b.amount)})</span>
                        </div>
                    ))}
                </div>
            )}

            <div className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-6">
                <div className="flex flex-wrap items-end gap-4">
                    <div>
                        <label className={label} htmlFor="cq-search">{t("searchPlaceholder")}</label>
                        <input
                            id="cq-search"
                            data-testid="cheque-search"
                            className={`${field} min-w-[14rem]`}
                            placeholder={t("searchPlaceholder")}
                            value={draft.search}
                            onChange={ev => setDraft({ ...draft, search: ev.target.value })}
                        />
                    </div>
                    <div>
                        <label className={label} htmlFor="cq-status">{tLedger("status")}</label>
                        <select
                            id="cq-status"
                            data-testid="cheque-status-filter"
                            className={`${field} min-w-[10rem]`}
                            value={draft.status}
                            onChange={ev => setDraft({ ...draft, status: ev.target.value as ChequeStatus | "" })}
                        >
                            <option value="">{tl("allStatuses")}</option>
                            {STATUS_OPTIONS.map(s => (
                                <option key={s} value={s}>{t(`status.${s}`)}</option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className={label} htmlFor="cq-mode">{tl("chequeMode")}</label>
                        <select
                            id="cq-mode"
                            data-testid="cheque-mode-filter"
                            className={`${field} min-w-[9rem]`}
                            value={draft.mode}
                            onChange={ev => setDraft({ ...draft, mode: ev.target.value as ChequeMode | "" })}
                        >
                            <option value="">{t("allModes")}</option>
                            {MODE_OPTIONS.map(m => (
                                <option key={m} value={m}>{tl(`mode.${m}`)}</option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className={label} htmlFor="cq-property">{tLedger("propertyFilter")}</label>
                        <select
                            id="cq-property"
                            data-testid="cheque-property-filter"
                            className={`${field} min-w-[12rem]`}
                            value={draft.propertyId}
                            onChange={ev => setDraft({ ...draft, propertyId: ev.target.value })}
                        >
                            <option value="">{tLedger("selectProperty")}</option>
                            {properties.options.map(p => (
                                <option key={p.id} value={p.id}>{p.label}</option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className={label} htmlFor="cq-from">{tLedger("from")}</label>
                        <input id="cq-from" type="date" className={field} value={draft.from} onChange={ev => setDraft({ ...draft, from: ev.target.value })} />
                    </div>
                    <div>
                        <label className={label} htmlFor="cq-to">{tLedger("to")}</label>
                        <input id="cq-to" type="date" className={field} value={draft.to} onChange={ev => setDraft({ ...draft, to: ev.target.value })} />
                    </div>
                    <button
                        type="button"
                        data-testid="cheque-filter-apply"
                        onClick={apply}
                        disabled={loading}
                        className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50"
                    >
                        <Filter size={13} />
                        {tLedger("apply")}
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
                                    <th className={th}>{tl("chequeMode")}</th>
                                    <th className={th}>{tLedger("status")}</th>
                                    <th className={th}>{t("summary.due")}</th>
                                    <th className={th}>{tl("actions")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {rows.map(c => {
                                    const actions = registerActionsFor(c.status, c.mode, canCancel);
                                    return (
                                        <tr key={c.id} data-testid={`cheque-row-${c.id}`} className="hover:bg-input/60 transition-colors">
                                            <td className={`${td} font-semibold`}>{c.chequeNumber || `#${c.seqNo}`}</td>
                                            <td className={`${td} tabular-nums`}>{fmtIsoDate(c.chequeDate ?? c.postingDate, locale)}</td>
                                            <td className={td}>{c.renterName || "—"}</td>
                                            <td className={td}>{c.unitIdentifier || "—"}</td>
                                            <td className={`${td} text-muted`}>{c.propertyName || "—"}</td>
                                            <td className={`${td} text-end tabular-nums font-semibold`}>{fmtAmount(c.amount)}</td>
                                            <td className={td}>{tl(`mode.${c.mode}`)}</td>
                                            <td className={td}>
                                                <ChequeStatusBadge status={c.status} testId={`cheque-status-${c.id}`} />
                                            </td>
                                            <td className={td}>
                                                {c.overdue ? (
                                                    <span className="text-error font-semibold">{t("daysOverdue", { n: c.daysOverdue })}</span>
                                                ) : c.due ? (
                                                    <span className="text-warning font-semibold">{t("due")}</span>
                                                ) : (
                                                    "—"
                                                )}
                                            </td>
                                            <td className={td}>
                                                <div className="flex items-center gap-1.5 flex-wrap">
                                                    {actions.map(a => (
                                                        <button
                                                            key={a}
                                                            type="button"
                                                            data-testid={`cheque-row-action-${a}-${c.id}`}
                                                            onClick={() => openAction(c, a)}
                                                            className="px-2 py-1 rounded-md text-[10px] font-bold bg-input text-foreground hover:bg-border transition-colors cursor-pointer"
                                                        >
                                                            {t(a)}
                                                        </button>
                                                    ))}
                                                </div>
                                            </td>
                                        </tr>
                                    );
                                })}
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

            <ChequeActionDialog
                action={dialogAction?.action ?? null}
                cheque={dialogAction?.cheque ?? null}
                propertyId={dialogAction?.cheque.propertyId}
                onClose={() => setDialogAction(null)}
                onDone={() => {
                    setDialogAction(null);
                    refresh();
                }}
            />

            <BounceChequeDialog
                cheque={bounceTarget}
                propertyId={bounceTarget?.propertyId}
                onClose={() => setBounceTarget(null)}
                onDone={() => {
                    setBounceTarget(null);
                    refresh();
                }}
            />

            <ReplaceChequeDialog
                cheque={replaceTarget}
                propertyId={replaceTarget?.propertyId}
                onClose={() => setReplaceTarget(null)}
                onDone={() => {
                    setReplaceTarget(null);
                    refresh();
                }}
            />

            <ReceiveCashDialog
                open={cashReceiptOpen}
                initialLeaseId={searchParams.get("leaseId")}
                onClose={() => setCashReceiptOpen(false)}
                onDone={() => {
                    setCashReceiptOpen(false);
                    refresh();
                }}
            />
        </div>
    );
}
