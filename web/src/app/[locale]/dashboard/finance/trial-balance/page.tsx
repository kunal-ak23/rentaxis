"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, Download, Filter, Info, Scale, ShieldCheck } from "lucide-react";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { downloadCsv, toCsv } from "@/lib/csv";
import { accountName, fmtAmount, ledgerApi, type TrialBalanceRow } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";

const TYPE_ORDER = ["ASSET", "LIABILITY", "INCOME", "EXPENSE", "EQUITY"] as const;
type TbType = (typeof TYPE_ORDER)[number];

/** Rounding noise, not an unbalanced book: half a fils either way is equal. */
const TOLERANCE = 0.005;

const th = "text-start px-4 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-4 py-2 text-xs";
const num = `${td} text-end tabular-nums`;
const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

const pad = (n: number) => String(n).padStart(2, "0");
const todayIso = () => {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

export default function TrialBalancePage() {
    const t = useTranslations("Ledger");
    const locale = useLocale();
    const tCommon = useTranslations("Common");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canAccessFinance");

    const initial = { asOf: todayIso(), propertyId: "" };
    const [draft, setDraft] = useState(initial);
    const [applied, setApplied] = useState(initial);
    const [rows, setRows] = useState<TrialBalanceRow[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    const properties = useNameLookup("properties");

    const load = useCallback(
        async (q: { asOf: string; propertyId: string }) => {
            setLoading(true);
            setLoadError(null);
            try {
                setRows(await ledgerApi.trialBalance({ asOf: q.asOf, propertyId: q.propertyId || undefined }));
            } catch (err) {
                setRows([]);
                setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
            } finally {
                setLoading(false);
            }
        },
        [tCommon],
    );

    useEffect(() => {
        if (!allowed) {
            setLoading(false);
            return;
        }
        load(applied);
    }, [allowed, applied, load]);

    const groups = useMemo(() => {
        // An account type the backend adds later must still show up rather than
        // vanish from the report, so anything outside TYPE_ORDER is appended.
        const seen = Array.from(new Set(rows.map(r => r.accountType)));
        const order = [
            ...TYPE_ORDER.filter(ty => seen.includes(ty)),
            ...seen.filter(ty => !TYPE_ORDER.includes(ty as TbType)),
        ];
        return order.map(type => {
            const items = rows.filter(r => r.accountType === type);
            return {
                type,
                items,
                debit: items.reduce((a, r) => a + r.debit, 0),
                credit: items.reduce((a, r) => a + r.credit, 0),
            };
        });
    }, [rows]);

    const totalDebit = rows.reduce((a, r) => a + r.debit, 0);
    const totalCredit = rows.reduce((a, r) => a + r.credit, 0);
    const diff = totalDebit - totalCredit;
    const balanced = Math.abs(diff) < TOLERANCE;

    const typeLabel = (type: string) =>
        TYPE_ORDER.includes(type as TbType) ? t(`accountTypes.${type as TbType}`) : type;

    const exportCsv = () => {
        const body: (string | number)[][] = [];
        for (const g of groups) {
            for (const r of g.items) body.push([r.code, accountName(r, locale), typeLabel(g.type), fmtAmount(r.debit), fmtAmount(r.credit)]);
            body.push(["", `${t("subTotal")} — ${typeLabel(g.type)}`, "", fmtAmount(g.debit), fmtAmount(g.credit)]);
        }
        body.push(["", t("grandTotal"), "", fmtAmount(totalDebit), fmtAmount(totalCredit)]);
        downloadCsv(
            `trial-balance-${applied.asOf}.csv`,
            toCsv([t("code"), t("name"), t("type"), t("debit"), t("credit")], body),
        );
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
                        <Scale size={20} className="text-primary" />
                        {t("trialBalance")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("trialBalanceDesc")}</p>
                </div>
                <button
                    type="button"
                    onClick={exportCsv}
                    disabled={rows.length === 0}
                    className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none"
                >
                    <Download size={13} />
                    {t("export")}
                </button>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={() => load(applied)} />}

            <div className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-6">
                <div className="flex flex-wrap items-end gap-4">
                    <div>
                        <label className={label} htmlFor="tb-as-of">{t("asOf")}</label>
                        <input
                            id="tb-as-of"
                            type="date"
                            className={field}
                            value={draft.asOf}
                            onChange={ev => setDraft({ ...draft, asOf: ev.target.value })}
                        />
                    </div>
                    <div>
                        <label className={label} htmlFor="tb-property">{t("propertyFilter")}</label>
                        <select
                            id="tb-property"
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
                        onClick={() => setApplied(draft)}
                        disabled={loading}
                        className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50 focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    >
                        <Filter size={13} />
                        {t("apply")}
                    </button>
                </div>
            </div>

            {/*
              * A property-filtered trial balance is not required to balance: the
              * tenant-level accounts (cash, VAT, rounding, discount, forfeited income,
              * opening-balance difference) carry no property dimension, so one half of
              * an entry is filtered out while the property-tagged half stays. Say so,
              * or the out-of-balance banner below reads as corrupted books.
              */}
            {!loading && rows.length > 0 && applied.propertyId && (
                <div className="mb-6 flex items-start gap-2 bg-input/60 border border-border text-muted rounded-xl px-5 py-3">
                    <Info size={16} className="shrink-0 mt-0.5" />
                    <span className="text-xs">{t("propertyFilterNote")}</span>
                </div>
            )}

            {!loading && rows.length > 0 && !balanced && (
                <div role="alert" className="mb-6 flex items-center gap-2 bg-error/10 border border-error/30 text-error rounded-xl px-5 py-3">
                    <AlertTriangle size={16} className="shrink-0" />
                    <span className="text-sm font-medium">{t("outOfBalance", { diff: fmtAmount(Math.abs(diff)) })}</span>
                </div>
            )}

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="bg-input rounded-xl h-16" />
                    ))}
                </div>
            ) : rows.length === 0 ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Scale size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{t("noRows")}</h3>
                </div>
            ) : (
                <div className="bg-surface border border-border rounded-xl overflow-x-auto shadow-sm">
                    <table className="w-full min-w-[720px]">
                        <thead>
                            <tr className="bg-input/50">
                                <th className={th}>{t("code")}</th>
                                <th className={th}>{t("name")}</th>
                                <th className={th}>{t("type")}</th>
                                <th className={`${th} text-end`}>{t("debit")}</th>
                                <th className={`${th} text-end`}>{t("credit")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {groups.map(g => (
                                <TypeGroup key={g.type} group={g} />
                            ))}
                            <tr className="bg-warning/10 font-bold border-t-2 border-border">
                                <td className={td} colSpan={3}>{t("grandTotal")}</td>
                                <td className={num}>{fmtAmount(totalDebit)}</td>
                                <td className={num}>{fmtAmount(totalCredit)}</td>
                            </tr>
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );

    function TypeGroup({ group }: { group: { type: string; items: TrialBalanceRow[]; debit: number; credit: number } }) {
        return (
            <>
                <tr>
                    <td colSpan={5} className="px-4 py-1.5 text-xs font-bold text-white" style={{ background: "#C8651B" }}>
                        {typeLabel(group.type)}
                    </td>
                </tr>
                {group.items.map(r => (
                    <tr key={r.accountId} className="border-t border-border hover:bg-input/30">
                        <td className={`${td} font-mono text-muted`}>{r.code}</td>
                        <td className={td}>{accountName(r, locale)}</td>
                        <td className={`${td} text-muted`}>{typeLabel(r.accountType)}</td>
                        <td className={num}>{fmtAmount(r.debit)}</td>
                        <td className={num}>{fmtAmount(r.credit)}</td>
                    </tr>
                ))}
                <tr className="bg-input/40 font-semibold border-t border-border">
                    <td className={td} colSpan={3}>{`${t("subTotal")} — ${typeLabel(group.type)}`}</td>
                    <td className={num}>{fmtAmount(group.debit)}</td>
                    <td className={num}>{fmtAmount(group.credit)}</td>
                </tr>
            </>
        );
    }
}
