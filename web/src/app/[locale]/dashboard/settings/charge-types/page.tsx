"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Loader2, ShieldCheck, Tags } from "lucide-react";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { chargeTypeApi, type ChargeRecognition, type ChargeType } from "@/lib/api/leasing";
import { hasPermission, type UserRole } from "@/lib/rbac";

const th = "px-3 py-2 text-start text-[10px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-2 text-xs text-foreground";
const RECOGNITIONS: ChargeRecognition[] = ["RENT_LIKE", "ONE_OFF", "PASS_THROUGH"];

/**
 * F14-18 / spec §4c: the charge-type catalogue, with how each fee is earned.
 *
 * Only a FEE type has a choice: earned over the term like rent (and copied on
 * renewal), one-off (income when charged, not copied), or a utility recovered at
 * cost (never income). Rent and deposits follow their own rules and show a dash.
 * A change applies to leases posted afterwards; nothing on the books is restated.
 */
export default function ChargeTypesPage() {
    const t = useTranslations("ChargeTypes");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageAccountSetup");

    const [rows, setRows] = useState<ChargeType[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [savingId, setSavingId] = useState<string | null>(null);
    const [rowError, setRowError] = useState<{ id: string; message: string } | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            setRows(await chargeTypeApi.list(false));
        } catch (err) {
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [tCommon]);

    useEffect(() => {
        if (allowed) load();
        else setLoading(false);
    }, [allowed, load]);

    const setRecognition = async (row: ChargeType, recognition: ChargeRecognition) => {
        setSavingId(row.id);
        setRowError(null);
        try {
            const saved = await chargeTypeApi.update(row.id, { ...row, recognition });
            setRows(prev => prev.map(r => (r.id === saved.id ? saved : r)));
        } catch (err) {
            setRowError({ id: row.id, message: err instanceof ApiError ? err.message : tCommon("loadFailed") });
        } finally {
            setSavingId(null);
        }
    };

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <p className="text-sm text-muted">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    return (
        <div className="max-w-5xl">
            <div className="mb-6">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                    <Tags size={20} className="text-primary" />
                    {t("title")}
                </h1>
                <p className="text-xs text-muted font-medium">{t("description")}</p>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            {loading ? (
                <div className="bg-input rounded-xl h-40 animate-pulse" />
            ) : (
                <div className="bg-surface border border-border rounded-xl shadow-sm overflow-x-auto">
                    <table className="w-full min-w-[640px]" data-testid="charge-types-table">
                        <thead className="bg-input/40">
                            <tr>
                                <th className={th}>{t("code")}</th>
                                <th className={th}>{t("name")}</th>
                                <th className={th}>{t("behaviour")}</th>
                                <th className={th}>{t("recognition")}</th>
                                <th className={th}>{t("renewal")}</th>
                                <th className={th}>{t("active")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {rows.map(row => {
                                const recognition = row.recognition ?? "RENT_LIKE";
                                const fee = row.behaviour === "FEE";
                                return (
                                    <tr key={row.id} className="border-t border-border" data-testid={`charge-type-${row.code}`}>
                                        <td className={`${td} font-mono text-[11px]`}>{row.code}</td>
                                        <td className={td}>{(locale === "ar" ? row.nameAr : null) || row.nameEn}</td>
                                        <td className={td}>{t(`behaviours.${row.behaviour}`)}</td>
                                        <td className={td}>
                                            {fee ? (
                                                <div className="flex items-center gap-2">
                                                    <select
                                                        aria-label={t("recognition")}
                                                        data-testid={`charge-type-recognition-${row.code}`}
                                                        className="bg-input border border-border rounded-lg px-2 py-1 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                                        value={recognition}
                                                        disabled={savingId === row.id}
                                                        onChange={ev => setRecognition(row, ev.target.value as ChargeRecognition)}
                                                    >
                                                        {RECOGNITIONS.map(r => (
                                                            <option key={r} value={r}>{t(`recognitions.${r}`)}</option>
                                                        ))}
                                                    </select>
                                                    {savingId === row.id && <Loader2 size={12} className="animate-spin text-muted" />}
                                                </div>
                                            ) : (
                                                <span className="text-muted">—</span>
                                            )}
                                            {rowError?.id === row.id && (
                                                <p role="alert" className="mt-1 text-[11px] font-semibold text-error">{rowError.message}</p>
                                            )}
                                        </td>
                                        <td className={`${td} text-muted`}>
                                            {row.behaviour === "DEPOSIT"
                                                ? t("renewalDeposit")
                                                : fee && recognition === "ONE_OFF" ? t("renewalSkipped") : t("renewalCopied")}
                                        </td>
                                        <td className={td}>{row.active ? t("yes") : t("no")}</td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
            )}
            <p className="mt-3 text-[11px] text-muted">{t("appliesToNewLeases")}</p>
        </div>
    );
}
