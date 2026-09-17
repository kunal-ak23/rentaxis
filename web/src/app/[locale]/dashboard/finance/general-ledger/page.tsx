"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { BookOpen, Download, Loader2, ShieldCheck } from "lucide-react";
import LedgerFilters, { defaultLedgerRange } from "@/components/finance/LedgerFilters";
import LedgerTable from "@/components/finance/LedgerTable";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { downloadCsv, toCsv } from "@/lib/csv";
import { fmtAmount, fmtBalance, ledgerApi, type AccountLedger, type LedgerQuery } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";

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
    const tCommon = useTranslations("Common");
    const params = useSearchParams();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canAccessFinance");

    // A vendor ledger is one account rather than a filtered general ledger, so
    // the vendor id stays out of the query and picks a different endpoint.
    const vendorId = params.get("vendorId");
    const accountId = params.get("accountId");

    const initial: LedgerQuery = {
        ...defaultLedgerRange(),
        accountIds: accountId ? [accountId] : undefined,
    };
    // `draft` is what the filter bar edits; `applied` is what the report shows.
    // Splitting them is what makes Apply mean anything — editing a date must not
    // re-query on every keystroke.
    const [draft, setDraft] = useState<LedgerQuery>(initial);
    const [applied, setApplied] = useState<LedgerQuery>(initial);
    const [ledgers, setLedgers] = useState<AccountLedger[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    const units = useNameLookup("units");
    const renters = useNameLookup("renters");

    const load = useCallback(
        async (q: LedgerQuery) => {
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
        if (!allowed) {
            setLoading(false);
            return;
        }
        load(applied);
    }, [allowed, applied, load]);

    const exportCsv = () => {
        const rows: (string | number)[][] = [];
        for (const l of ledgers) {
            if (l.openingBalance !== 0) {
                rows.push([l.accountCode, l.accountName, "", "", t("openingBalance"), "", "", fmtBalance(l.openingBalance), "", "", ""]);
            }
            for (const r of l.rows) {
                rows.push([
                    l.accountCode,
                    l.accountName,
                    r.entryDate,
                    r.entryNumber,
                    r.particular,
                    r.debit ? fmtAmount(r.debit) : "",
                    r.credit ? fmtAmount(r.credit) : "",
                    fmtBalance(r.balance),
                    units.name(r.unitId),
                    renters.name(r.renterId),
                    r.narration,
                ]);
            }
            rows.push([
                l.accountCode, l.accountName, "", "", t("subTotal"),
                fmtAmount(l.totalDebit), fmtAmount(l.totalCredit), fmtBalance(l.closingBalance), "", "", "",
            ]);
        }
        const headers = [
            t("code"), t("name"), t("docDate"), t("docNo"), t("particular"),
            t("debit"), t("credit"), t("balance"), t("unit"), t("tenant"), t("narration"),
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
                    <p className="text-xs text-muted font-medium">{t("generalLedgerDesc")}</p>
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

            <LedgerFilters
                value={draft}
                onChange={setDraft}
                onApply={() => setApplied(draft)}
                busy={loading}
                showAccounts={!vendorId}
                showProperty={!vendorId}
            />

            {loading ? (
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
                <LedgerTable ledgers={ledgers} />
            )}
        </div>
    );
}
