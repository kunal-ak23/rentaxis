"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { BookOpen, Download, Loader2, ShieldCheck } from "lucide-react";
import LedgerFilters, { defaultLedgerRange, MAX_LEDGER_ACCOUNTS } from "@/components/finance/LedgerFilters";
import LedgerTable from "@/components/finance/LedgerTable";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { downloadCsv, toCsv } from "@/lib/csv";
import { accountName, fmtAmount, fmtBalance, ledgerApi, type AccountLedger, type LedgerQuery } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { Link } from "@/i18n/routing";

/**
 * `useSearchParams` opts the tree into client rendering, which `next build`
 * rejects unless it sits under a Suspense boundary — the same split the listings
 * page uses.
 */
export default function GeneralLedgerPage() {
    return (
        <Suspense fallback={<div className="flex items-center justify-center h-64"><Loader2 size={24} className="animate-spin text-muted" /></div>}>
            <GeneralLedger />
        </Suspense>
    );
}

function GeneralLedger() {
    const t = useTranslations("Ledger");
    const locale = useLocale();
    const tCommon = useTranslations("Common");
    const params = useSearchParams();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canAccessFinance");

    // A vendor ledger is one account rather than a filtered general ledger, so
    // the vendor id stays out of the query and picks a different endpoint.
    const vendorId = params.get("vendorId");
    const accountId = params.get("accountId");

    // A drill-down from the property P&L (finance-ops spec §1) arrives with the
    // cell's leaves, property and period, and asks for the P&L's own property
    // rule — coalesce(line property, account property) — so the ledger lists
    // every line the cell summed.
    const accountIdsParam = params.get("accountIds");
    const effectiveProperty = params.get("effectiveProperty") === "true";
    // The 20-account cap holds for a typed or bookmarked URL too (PR #365 R1).
    // A P&L drill-down (effectiveProperty) is exempt: it names exactly the
    // leaves its cell summed, and dropping any would make the ledger disagree
    // with the figure the user clicked.
    const urlIds = accountIdsParam ? [...new Set(accountIdsParam.split(",").filter(Boolean))] : [];
    const capped = !effectiveProperty && urlIds.length > MAX_LEDGER_ACCOUNTS;
    const range = defaultLedgerRange();
    const initial: LedgerQuery = {
        ...range,
        from: params.get("from") || range.from,
        to: params.get("to") || range.to,
        propertyId: params.get("propertyId") || undefined,
        effectiveProperty: effectiveProperty || undefined,
        accountIds: urlIds.length ? (capped ? urlIds.slice(0, MAX_LEDGER_ACCOUNTS) : urlIds)
            : accountId ? [accountId] : undefined,
    };
    // `draft` is what the filter bar edits; `applied` is what the report shows.
    // Splitting them is what makes Apply mean anything — editing a date must not
    // re-query on every keystroke.
    const [draft, setDraft] = useState<LedgerQuery>(initial);
    const [applied, setApplied] = useState<LedgerQuery>(initial);
    const [ledgers, setLedgers] = useState<AccountLedger[]>([]);
    const [loading, setLoading] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);

    const units = useNameLookup("units");
    const renters = useNameLookup("renters");
    const towers = useNameLookup("properties");

    // Loads on demand (client feedback 2026-09-25): at 1000s of buildings an
    // "every account" ledger is years of rows, so nothing is fetched until an
    // account is picked (or a vendor / drill-down link names them).
    const picked = !!vendorId || (applied.accountIds?.length ?? 0) > 0;

    const load = useCallback(
        async (q: LedgerQuery) => {
            if (!vendorId && !(q.accountIds?.length)) {
                setLedgers([]);
                return;
            }
            setLoading(true);
            setLoadError(null);
            try {
                const data = vendorId
                    ? [await ledgerApi.ledger.vendor(vendorId, { from: q.from, to: q.to })]
                    : await ledgerApi.ledger.general(q);
                setLedgers(data);
            } catch (err) {
                setLedgers([]);
                setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
            } finally {
                setLoading(false);
            }
        },
        [vendorId, tCommon],
    );

    useEffect(() => {
        if (!allowed) return;
        load(applied);
    }, [allowed, applied, load]);

    /** Apply the bar and keep the picks and period in the URL, so the view can be bookmarked. */
    const apply = () => {
        setApplied(draft);
        if (typeof window === "undefined") return;
        const q = new URLSearchParams(window.location.search);
        q.delete("accountId");
        const set = (k: string, v: string | undefined) => (v ? q.set(k, v) : q.delete(k));
        set("accountIds", draft.accountIds?.length ? draft.accountIds.join(",") : undefined);
        set("from", draft.from);
        set("to", draft.to);
        set("propertyId", draft.propertyId);
        const qs = q.toString();
        window.history.replaceState(window.history.state, "", window.location.pathname + (qs ? `?${qs}` : ""));
    };

    const exportCsv = () => {
        const rows: (string | number)[][] = [];
        for (const l of ledgers) {
            rows.push([l.accountCode, accountName(l, locale), applied.from ?? "", "", t("broughtForward"), "", "", fmtBalance(l.openingBalance), "", "", "", ""]);
            for (const r of l.rows) {
                rows.push([
                    l.accountCode,
                    accountName(l, locale),
                    r.entryDate,
                    r.entryNumber,
                    r.particular,
                    r.debit ? fmtAmount(r.debit) : "",
                    r.credit ? fmtAmount(r.credit) : "",
                    fmtBalance(r.balance),
                    units.name(r.unitId),
                    towers.name(r.propertyId),
                    renters.name(r.renterId),
                    r.narration,
                ]);
            }
            rows.push([
                l.accountCode, accountName(l, locale), "", "", t("subTotal"),
                fmtAmount(l.totalDebit), fmtAmount(l.totalCredit), fmtBalance(l.closingBalance), "", "", "", "",
            ]);
        }
        const headers = [
            t("code"), t("name"), t("docDate"), t("docNo"), t("particular"),
            t("debit"), t("credit"), t("balance"), t("unit"), t("tower"), t("tenant"), t("narration"),
        ];
        downloadCsv(`general-ledger-${applied.from ?? ""}-${applied.to ?? ""}.csv`, toCsv(headers, rows));
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

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-8">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <BookOpen size={20} className="text-primary" />
                        {vendorId ? t("vendorLedger") : t("generalLedger")}
                    </h1>
                    <p className="text-xs text-muted font-medium">
                        {t("generalLedgerDesc")}
                        {picked && applied.from && applied.to && (
                            <span className="ms-2 tabular-nums" data-testid="ledger-period">{t("periodLabel", { from: applied.from, to: applied.to })}</span>
                        )}
                    </p>
                </div>
                <button
                    type="button"
                    onClick={exportCsv}
                    disabled={ledgers.length === 0}
                    className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none"
                >
                    <Download size={13} />
                    {t("export")}
                </button>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={() => load(applied)} />}
            {capped && (
                <p role="status" data-testid="ledger-url-capped" className="mb-4 text-xs text-warning">
                    {t("urlAccountsCapped", { count: urlIds.length, max: MAX_LEDGER_ACCOUNTS })}
                </p>
            )}

            <LedgerFilters
                value={draft}
                onChange={setDraft}
                onApply={apply}
                busy={loading}
                showAccounts={!vendorId}
                showProperty={!vendorId}
                maxAccounts={MAX_LEDGER_ACCOUNTS}
            />

            {!picked ? (
                <div data-testid="ledger-pick-prompt" className="text-center py-20 bg-background border border-dashed border-border rounded-xl flex flex-col items-center gap-2">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-4">
                        <BookOpen size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground">{t("pickAccountsPrompt", { max: MAX_LEDGER_ACCOUNTS })}</h3>
                    <p className="text-xs text-muted max-w-md">{t("pickAccountsHint")}</p>
                    <Link href="/dashboard/finance/trial-balance" className="text-xs font-semibold text-primary hover:underline">{t("openTrialBalance")}</Link>
                </div>
            ) : loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="bg-input rounded-xl h-16" />
                    ))}
                </div>
            ) : ledgers.length === 0 ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <BookOpen size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{t("noRows")}</h3>
                </div>
            ) : (
                <LedgerTable ledgers={ledgers} broughtForward />
            )}
        </div>
    );
}
