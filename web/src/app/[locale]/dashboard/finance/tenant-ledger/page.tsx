"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { ShieldCheck, Users } from "lucide-react";
import LedgerFilters, { defaultLedgerRange } from "@/components/finance/LedgerFilters";
import LedgerTable from "@/components/finance/LedgerTable";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { ledgerApi, type AccountLedger, type LedgerQuery } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";

/**
 * One tenant's postings across every account they touch — the statement a
 * landlord hands a tenant who disputes a balance.
 */
export default function TenantLedgerPage() {
    const t = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canAccessFinance");

    const initial: LedgerQuery = defaultLedgerRange();
    const [draft, setDraft] = useState<LedgerQuery>(initial);
    const [applied, setApplied] = useState<LedgerQuery>(initial);
    const [ledgers, setLedgers] = useState<AccountLedger[]>([]);
    // Nothing is fetched until a tenant is chosen, so the first paint is the
    // prompt rather than an empty report that looks like "no data".
    const [loading, setLoading] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);

    const renters = useNameLookup("renters");
    const tenantName = renters.name(applied.renterId);

    const load = useCallback(
        async (q: LedgerQuery) => {
            if (!q.renterId) {
                setLedgers([]);
                return;
            }
            setLoading(true);
            setLoadError(null);
            try {
                setLedgers(await ledgerApi.ledger.renter(q.renterId, { from: q.from, to: q.to }));
            } catch (err) {
                setLedgers([]);
                setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
            } finally {
                setLoading(false);
            }
        },
        [tCommon],
    );

    useEffect(() => {
        if (allowed) load(applied);
    }, [allowed, applied, load]);

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
            <div className="mb-8">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                    <Users size={20} className="text-primary" />
                    {t("tenantLedger")}
                </h1>
                <p className="text-xs text-muted font-medium">{t("tenantLedgerDesc")}</p>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={() => load(applied)} />}

            <LedgerFilters value={draft} onChange={setDraft} onApply={() => setApplied(draft)} busy={loading} showRenter />

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="bg-input rounded-xl h-16" />
                    ))}
                </div>
            ) : ledgers.length === 0 ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Users size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">
                        {applied.renterId ? t("noRows") : t("selectRenterFirst")}
                    </h3>
                </div>
            ) : (
                <LedgerTable ledgers={ledgers} showTenantColumns subBand={tenantName || undefined} />
            )}
        </div>
    );
}
