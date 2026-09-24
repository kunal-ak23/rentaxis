"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
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
import ClearBatchDialog from "@/components/cheques/ClearBatchDialog";
import UnappliedPaymentsTile from "@/components/cheques/UnappliedPaymentsTile";
import { registerActionsFor, type RegisterAction } from "@/components/cheques/registerActions";
import { chequeLabel } from "@/components/cheques/chequeLabel";
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
import {
    filtersFromQuery,
    queryWithFilters,
    REGISTER_MODES,
    REGISTER_STATUSES,
    type RegisterFilters,
} from "@/components/cheques/registerFilters";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { bankRecApi, type ChequeEvidence } from "@/lib/api/bankRec";

const field =
    "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-4 py-3 text-xs text-foreground";

// Draft rows never enter the register — the server never returns them, and
// offering DRAFT as a filter would let the operator ask for something the
// endpoint can never answer.
const STATUS_OPTIONS: ChequeStatus[] = REGISTER_STATUSES;
const MODE_OPTIONS: ChequeMode[] = REGISTER_MODES;

type Filters = RegisterFilters;

/** The dialog's own single-row action set — bounce, replace and receipt are handled separately. */
type SingleRowAction = Extract<RegisterAction, "deposit" | "receive" | "details" | "cancel" | "clear" | "releaseOnline">;

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

    // #85: the register opens on the filters in its URL (`?status=BOUNCED`), so a
    // link from the dashboard or a notification lands on the rows it promised.
    const [draft, setDraft] = useState<Filters>(() => filtersFromQuery(searchParams));
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

    // Batch clearing (#57): offered only while the register is filtered to
    // DEPOSITED, so every tickable row is one the server will accept. Keyed by
    // id so each tick carries its amount into the total. Every reload prunes
    // the selection to the deposited rows it returned (see `load`): a row
    // cleared or bounced from its own menu, or by a colleague, leaves the
    // list, and a tick the operator can no longer see or untick must not stay
    // in the count, the total or the batch the server would refuse whole.
    const batchMode = applied.status === "DEPOSITED";
    const [selected, setSelected] = useState<Map<string, number>>(new Map());
    const [clearBatchOpen, setClearBatchOpen] = useState(false);
    const selectedIds = useMemo(() => [...selected.keys()], [selected]);
    const selectedTotal = useMemo(() => [...selected.values()].reduce((sum, a) => sum + a, 0), [selected]);

    // The Bank column (finance-ops spec §3): statement evidence for the cleared
    // rows on this page. Evidence only — clearing by hand stays as it is.
    const [evidence, setEvidence] = useState<Map<string, ChequeEvidence>>(new Map());
    useEffect(() => {
        const cleared = (page?.content ?? []).filter(c => c.status === "CLEARED").map(c => c.id);
        let alive = true;
        bankRecApi.chequeEvidence(cleared)
            .then(list => alive && setEvidence(new Map((Array.isArray(list) ? list : []).map(e => [e.chequeId, e]))))
            .catch(() => alive && setEvidence(new Map()));
        return () => {
            alive = false;
        };
    }, [page]);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            const result = await chequeApi.list({
                status: applied.status || undefined,
                mode: applied.mode || undefined,
                propertyId: applied.propertyId || undefined,
                from: applied.from || undefined,
                to: applied.to || undefined,
                search: applied.search || undefined,
                page: pageIndex,
                size,
            });
            setPage(result);
            const visible = new Set(result.content.filter(c => c.status === "DEPOSITED").map(c => c.id));
            setSelected(prev => {
                if ([...prev.keys()].every(id => visible.has(id))) return prev;
                const next = new Map<string, number>();
                for (const [id, amount] of prev) if (visible.has(id)) next.set(id, amount);
                return next;
            });
        } catch (err) {
            setPage(null);
            setSelected(prev => (prev.size === 0 ? prev : new Map()));
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
        setSelected(new Map());
        // …and writes them back, so the filtered register is a URL that can be
        // copied, reloaded or sent. replaceState, not a navigation: nothing needs
        // to re-render beyond what setApplied already does.
        if (typeof window !== "undefined") {
            const next = window.location.pathname + queryWithFilters(window.location.search, draft);
            window.history.replaceState(window.history.state, "", next);
        }
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

            <UnappliedPaymentsTile visible={canCancel} />

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
                    {batchMode && (
                        <div className="flex flex-wrap items-center justify-end gap-4 px-3 py-2 border-b border-border">
                            <span className="text-xs text-muted" data-testid="clear-batch-selected-total">
                                {t("selectedTotal")}: <strong className="text-foreground">{fmtAmount(selectedTotal)}</strong> ({selectedIds.length})
                            </span>
                            <button
                                type="button"
                                data-testid="clear-batch-open"
                                disabled={selectedIds.length === 0}
                                onClick={() => setClearBatchOpen(true)}
                                className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50"
                            >
                                {t("clearBatch")} ({selectedIds.length})
                            </button>
                        </div>
                    )}
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead className="border-b border-border">
                                <tr>
                                    {batchMode && (
                                        <th className={th}>
                                            <input
                                                type="checkbox"
                                                aria-label={t("selectAll")}
                                                data-testid="clear-batch-select-all"
                                                checked={allOnPageSelected}
                                                onChange={toggleAllOnPage}
                                            />
                                        </th>
                                    )}
                                    <th className={th}>{tl("chequeNo")}</th>
                                    <th className={th}>{tl("chequeDate")}</th>
                                    <th className={th}>{t("tenant")}</th>
                                    <th className={th}>{tl("unit")}</th>
                                    <th className={th}>{tLedger("propertyFilter")}</th>
                                    <th className={`${th} text-end`}>{tl("amount")}</th>
                                    <th className={th}>{tl("chequeMode")}</th>
                                    <th className={th}>{tLedger("status")}</th>
                                    <th className={th}>{t("bank")}</th>
                                    <th className={th}>{t("summary.due")}</th>
                                    <th className={th}>{tl("actions")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {rows.map(c => {
                                    const actions = registerActionsFor(c.status, c.mode, canCancel, undefined, c.ledgerSettled);
                                    return (
                                        <tr key={c.id} data-testid={`cheque-row-${c.id}`} className="hover:bg-input/60 transition-colors">
                                            {batchMode && (
                                                <td className={td}>
                                                    <input
                                                        type="checkbox"
                                                        aria-label={chequeLabel(c)}
                                                        data-testid={`clear-batch-select-${c.id}`}
                                                        checked={selected.has(c.id)}
                                                        disabled={c.status !== "DEPOSITED"}
                                                        onChange={() => toggleOne(c)}
                                                    />
                                                </td>
                                            )}
                                            {/*
                                              * See `chequeLabel` (shared with ChequeActionDialog's title, #43):
                                              * a numberless PDC row is a cheque still awaiting its number, so
                                              * `#seqNo` is a fair placeholder; a CASH/TRANSFER/ONLINE row has no
                                              * cheque number by nature, so show a dash — the Mode and narration
                                              * columns already say what the row is.
                                              */}
                                            <td className={`${td} font-semibold`}>
                                                {chequeLabel(c)}
                                            </td>
                                            <td className={`${td} tabular-nums`}>{fmtIsoDate(c.chequeDate ?? c.postingDate, locale)}</td>
                                            <td className={td}>{c.renterName || "—"}</td>
                                            <td className={td}>{c.unitIdentifier || "—"}</td>
                                            <td className={`${td} text-muted`}>{c.propertyName || "—"}</td>
                                            <td className={`${td} text-end tabular-nums font-semibold`}>{fmtAmount(c.amount)}</td>
                                            <td className={td}>{tl(`mode.${c.mode}`)}</td>
                                            <td className={td}>
                                                <ChequeStatusBadge status={c.status} testId={`cheque-status-${c.id}`} />
                                                {c.receiptNumber && (
                                                    <span className="block text-[10px] text-muted tabular-nums" data-testid={`cheque-receipt-${c.id}`}>
                                                        {t("receiptNumber", { number: c.receiptNumber })}
                                                    </span>
                                                )}
                                                {c.ledgerSettled && (
                                                    <span className="block text-[10px] text-muted" data-testid={`cheque-ledger-settled-${c.id}`}>
                                                        {t("ledgerSettled")}
                                                    </span>
                                                )}
                                            </td>
                                            <td className={td} data-testid={`cheque-bank-${c.id}`}>
                                                {c.status !== "CLEARED" ? "—" : (() => {
                                                    const e = evidence.get(c.id);
                                                    if (!e) return <span className="text-muted">—</span>;
                                                    return e.state === "CONFIRMED" ? (
                                                        <span className="text-success">{t("bank_CONFIRMED", { date: fmtIsoDate(e.statementDate ?? "", locale) })}</span>
                                                    ) : e.state === "NOT_ON_STATEMENT" ? (
                                                        <span className="text-warning">{t("bank_NOT_ON_STATEMENT")}</span>
                                                    ) : e.state === "CASH" ? <span title={t("bank_CASH")}>—</span> : t(`bank_${e.state}`);
                                                })()}
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

            <ClearBatchDialog
                open={clearBatchOpen}
                chequeIds={selectedIds}
                total={selectedTotal}
                onClose={() => setClearBatchOpen(false)}
                onDone={() => {
                    setClearBatchOpen(false);
                    setSelected(new Map());
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
