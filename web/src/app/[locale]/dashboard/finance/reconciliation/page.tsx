"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle2, Info, Scale, ShieldCheck } from "lucide-react";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { Pagination } from "@/components/ui/Pagination";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { ApiError } from "@/lib/api/facilities";
import { fmtBalance } from "@/lib/api/ledger";
import { cutoverApi, type ReconciliationRow } from "@/lib/api/cutover";
import { hasUnfilledDerivedAccounts, isReconciled } from "@/lib/cutoverRules";
import { isZeroAmount, sumAmounts } from "@/lib/money";
import { hasPermission, type UserRole } from "@/lib/rbac";

/**
 * Our balance against the PACT trial balance, account by account (spec §10.3).
 *
 * Read-only by design: nothing here writes. The two figures come from different
 * places — ours from the ledger, PACT's from the uploaded snapshot — and the
 * report's job is to show where they disagree, not to reconcile them for you.
 *
 * **The honesty banner is the most important thing on the page.** Until the
 * cut-over contracts are imported AND bulk-posted, every account the import
 * derives (rent receivable, deposits, PDCs, advance rent…) reads 0.00 on our side
 * against PACT's real figure. A report that showed those as eighty-thousand-dirham
 * discrepancies without a word of explanation would say the books are broken when
 * the truth is that a step has not run yet. So when a derived account is empty on
 * our side and not on PACT's, the page says why.
 *
 * Both balances are signed debit-positive — the same convention as the trial
 * balance — so a credit-balance account reads negative on both sides and
 * `difference` still means `ours − PACT`. `fmtBalance` renders that as "Dr"/"Cr"
 * rather than a bare minus sign.
 */

const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-4 py-2 text-xs text-foreground";

export default function ReconciliationPage() {
    const t = useTranslations("Cutover");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageOpeningBalances");

    const [rows, setRows] = useState<ReconciliationRow[]>([]);
    const [asOf, setAsOf] = useState<string | null>(null);
    const [differencesOnly, setDifferencesOnly] = useState(false);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(50);

    /**
     * Deliberately does NOT flip `loading` on: `loading` starts true and is
     * cleared once, in the `finally` below. Turning it on here would be a
     * synchronous setState inside the mount effect — a cascading render, and
     * react-hooks/set-state-in-effect says so. The retry path, which does want
     * the spinner back, turns it on at its own call site.
     */
    const load = useCallback(() => {
        // The as-at date belongs to the opening-balance grid (booksStartDate - 1),
        // so it is read from there rather than invented here. A failure to fetch
        // it must not blank the report, which is the useful half.
        return Promise.all([
            cutoverApi.reconciliation(),
            cutoverApi.openingBalances.grid().catch(() => null),
        ])
            .then(([report, grid]) => {
                setRows(report);
                setAsOf(grid?.asOf ?? null);
                // Cleared on success rather than before the request: nothing in
                // this effect may set state synchronously, and a banner that
                // vanishes the instant Retry is pressed says nothing useful.
                setLoadError(null);
            })
            .catch(e => setLoadError(e instanceof ApiError ? e.message : tCommon("loadFailed")))
            .finally(() => setLoading(false));
    }, [tCommon]);

    useEffect(() => {
        // Nothing until NextAuth has answered, and nothing for a role the
        // controller refuses — that 403 is one this page already knows about.
        // `loading` is left alone on the denied path: the access-denied panel
        // returns above before anything reads it.
        if (!userRole || !allowed) return;
        load();
    }, [userRole, allowed, load]);

    /**
     * By account code, which is the order a trial balance is read in — and a
     * PLAIN string sort, matching the server's own
     * `findAllByOrderByAccountCodeAsc`. A numeric-aware collation would put
     * "9001" before "110100" and quietly disagree with every other screen that
     * lists these codes.
     */
    const sorted = useMemo(() => [...rows].sort((a, b) => (a.code < b.code ? -1 : a.code > b.code ? 1 : 0)), [rows]);
    const differing = useMemo(() => sorted.filter(r => !isZeroAmount(r.difference)), [sorted]);
    const shown = differencesOnly ? differing : sorted;
    const visible = shown.slice(page * size, page * size + size);

    /** Totals over every row, not just the visible page, and added in fils. */
    const totals = useMemo(
        () => ({
            derived: sumAmounts(sorted.map(r => r.derivedBalance)),
            pact: sumAmounts(sorted.map(r => r.pactBalance)),
            difference: sumAmounts(sorted.map(r => r.difference)),
        }),
        [sorted],
    );

    if (!userRole) {
        return <div data-testid="rec-loading" className="bg-input rounded-xl h-14 animate-pulse" />;
    }

    if (!allowed) {
        return (
            <div className="max-w-4xl" data-testid="rec-access-denied">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("notAllowedOpeningBalances")}</p>
                </div>
            </div>
        );
    }

    return (
        <div>
            {loadError && (
                <LoadErrorBanner
                    message={loadError}
                    onRetry={() => {
                        setLoading(true);
                        load();
                    }}
                />
            )}

            <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t("reconciliation")}</h1>
                    <p className="text-sm text-muted" data-testid="rec-as-of">
                        {t("reconciliationDesc", { date: asOf ? fmtIsoDate(asOf, locale) : "…" })}
                    </p>
                </div>
                <div className="flex items-center gap-4">
                    {rows.length > 0 && (
                        <span data-testid="rec-out-of-balance" className="text-xs font-semibold text-muted">
                            {t("outOfBalance", { n: differing.length })}
                        </span>
                    )}
                    <label className="flex items-center gap-2 text-xs font-medium text-foreground cursor-pointer">
                        <input
                            type="checkbox"
                            data-testid="rec-differences-only"
                            checked={differencesOnly}
                            onChange={e => {
                                setDifferencesOnly(e.target.checked);
                                setPage(0);
                            }}
                            className="cursor-pointer"
                        />
                        {t("differencesOnly")}
                    </label>
                </div>
            </div>

            {/*
             * The banner this page exists for. A derived account empty on our side
             * and not on PACT's means the contract import has not been posted yet.
             */}
            {!loading && hasUnfilledDerivedAccounts(rows) && (
                <div
                    data-testid="rec-derived-notice"
                    className="mb-4 bg-input border border-border text-muted rounded-xl px-5 py-3 text-xs flex items-start gap-2"
                >
                    <Info size={14} className="shrink-0 mt-0.5" />
                    <span>{t("derivedNotYetPosted")}</span>
                </div>
            )}

            {!loading && rows.length > 0 && isReconciled(rows) && (
                <div
                    role="status"
                    data-testid="rec-reconciled"
                    className="mb-4 bg-success/10 border border-success/30 text-success rounded-xl px-5 py-3 text-xs font-medium flex items-center gap-2"
                >
                    <CheckCircle2 size={14} className="shrink-0" />
                    {t("reconciled")}
                </div>
            )}

            {loading && (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="bg-input rounded-xl h-12" />
                    ))}
                </div>
            )}

            {!loading && rows.length === 0 && !loadError && (
                <div
                    data-testid="rec-empty"
                    className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center"
                >
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Scale size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{tLedger("noRows")}</h3>
                    <p className="text-xs text-muted font-medium max-w-md">{t("trialBalanceFormat")}</p>
                </div>
            )}

            {!loading && rows.length > 0 && (
                <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                    <div className="overflow-x-auto">
                        <table className="w-full" data-testid="rec-table">
                            <thead className="bg-input/60 border-b border-border">
                                <tr>
                                    <th className={th}>{tLedger("code")}</th>
                                    <th className={th}>{tLedger("account")}</th>
                                    <th className={`${th} text-end`}>{t("derivedBalance")}</th>
                                    <th className={`${th} text-end`}>{t("pactBalance")}</th>
                                    <th className={`${th} text-end`}>{t("difference")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {visible.map(r => {
                                    const differs = !isZeroAmount(r.difference);
                                    return (
                                        <tr
                                            key={r.code}
                                            data-testid={`rec-row-${r.code}`}
                                            className={differs ? "bg-warning/5" : "hover:bg-input/30"}
                                        >
                                            <td className={`${td} font-mono text-muted`}>{r.code}</td>
                                            <td className={td}>
                                                {r.name}
                                                {r.accountId === null && (
                                                    <span className="ms-2 text-[10px] text-warning inline-flex items-center gap-1">
                                                        <AlertTriangle size={10} />
                                                        {t("notInOurChart")}
                                                    </span>
                                                )}
                                                {r.derived && (
                                                    <span className="ms-2 text-[10px] text-muted">{t("derived")}</span>
                                                )}
                                            </td>
                                            <td
                                                data-testid={`rec-derived-${r.code}`}
                                                className={`${td} text-end tabular-nums`}
                                            >
                                                {fmtBalance(r.derivedBalance)}
                                            </td>
                                            <td
                                                data-testid={`rec-pact-${r.code}`}
                                                className={`${td} text-end tabular-nums`}
                                            >
                                                {fmtBalance(r.pactBalance)}
                                            </td>
                                            <td
                                                data-testid={`rec-difference-${r.code}`}
                                                data-differs={differs ? "true" : "false"}
                                                className={`${td} text-end tabular-nums ${differs ? "font-bold text-warning" : "text-muted"}`}
                                            >
                                                {fmtBalance(r.difference)}
                                            </td>
                                        </tr>
                                    );
                                })}
                                {visible.length === 0 && (
                                    <tr>
                                        <td colSpan={5} className="px-4 py-10 text-center text-xs text-muted">
                                            {tLedger("noRows")}
                                        </td>
                                    </tr>
                                )}
                            </tbody>
                            <tfoot className="bg-input/60 border-t border-border">
                                <tr>
                                    {/* Over every row, not just this page — a total of
                                        what you happen to be looking at is not a total. */}
                                    <td className={`${td} font-bold`} colSpan={2}>
                                        {t("reconciliationTotals")}
                                    </td>
                                    <td
                                        data-testid="rec-total-derived"
                                        className={`${td} text-end tabular-nums font-bold`}
                                    >
                                        {fmtBalance(totals.derived)}
                                    </td>
                                    <td data-testid="rec-total-pact" className={`${td} text-end tabular-nums font-bold`}>
                                        {fmtBalance(totals.pact)}
                                    </td>
                                    <td
                                        data-testid="rec-total-difference"
                                        className={`${td} text-end tabular-nums font-bold`}
                                    >
                                        {fmtBalance(totals.difference)}
                                    </td>
                                </tr>
                            </tfoot>
                        </table>
                    </div>
                </div>
            )}

            {!loading && shown.length > 0 && (
                <Pagination
                    currentPage={page + 1}
                    totalItems={shown.length}
                    itemsPerPage={size}
                    onPageChange={p => setPage(p - 1)}
                    onItemsPerPageChange={n => {
                        setSize(n);
                        setPage(0);
                    }}
                />
            )}
        </div>
    );
}
