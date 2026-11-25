"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { FileText, Plus } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { codedOf, serverText } from "@/components/finance/bankrec/serverText";
import { rentalIndexTabVisible, useRentalIndexEnabled } from "@/components/rentalIndex/useRentalIndexEnabled";
import { rentalIndexApi, rateStatus, type RentalIndexRate } from "@/lib/api/rentalIndex";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { formatCurrency, formatDate } from "@/lib/format";
import { cn } from "@/lib/utils";
import { RentalIndexAddDialog, type RentalIndexBuildingOption } from "./RentalIndexAddDialog";

type Props = { propertyId: string; buildings: RentalIndexBuildingOption[]; emirate?: string | null };

const BADGE: Record<string, string> = {
    ACTIVE: "bg-primary/10 text-primary",
    EXPIRED: "bg-muted/20 text-muted",
    REVOKED: "bg-error/10 text-error",
};

export function RentalIndexTab({ propertyId, buildings, emirate }: Props) {
    const enabled = useRentalIndexEnabled();
    const visible = rentalIndexTabVisible(enabled, emirate);
    const { data: session } = useSession();
    const canManage = hasPermission(session?.user?.role as UserRole | undefined, "canManageRentalIndex");
    const t = useTranslations("RentalIndex");
    const tc = useTranslations("Common");
    const [rates, setRates] = useState<RentalIndexRate[] | null>(null);
    const [loadFailed, setLoadFailed] = useState(false);
    const [dialog, setDialog] = useState<"pdf" | "manual" | null>(null);
    const [revoking, setRevoking] = useState<RentalIndexRate | null>(null);
    const [revokeBusy, setRevokeBusy] = useState(false);
    const [actionError, setActionError] = useState<string | null>(null);

    const load = useCallback(async () => {
        try { setRates(await rentalIndexApi.list(propertyId)); setLoadFailed(false); } catch { setLoadFailed(true); }
    }, [propertyId]);
    useEffect(() => { if (visible) void load(); }, [visible, load]);

    if (!visible) return null;

    const revoke = async () => {
        if (!revoking) return;
        setRevokeBusy(true); setActionError(null);
        try { await rentalIndexApi.revoke(revoking.id); setRevoking(null); await load(); }
        catch (e) { setActionError(serverText(tc, e) || codedOf(e).message || t("loadFailed")); setRevoking(null); }
        finally { setRevokeBusy(false); }
    };

    return (
        <div className="space-y-4" data-testid="rental-index-tab">
            <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                    <h2 className="text-lg font-bold text-foreground">{t("title")}</h2>
                    <p className="text-xs text-muted">{t("intro")}</p>
                </div>
                {canManage && (
                    <div className="flex gap-2">
                        <button type="button" onClick={() => setDialog("pdf")}
                                className="flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold bg-primary text-primary-foreground cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">
                            <Plus size={14} />{t("addFromDld")}
                        </button>
                        <button type="button" onClick={() => setDialog("manual")}
                                className="px-4 py-2 rounded-xl text-xs font-bold border border-border text-foreground hover:bg-background cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">
                            {t("enterByHand")}
                        </button>
                    </div>
                )}
            </div>
            {actionError && <p role="alert" className="text-xs font-semibold text-error">{actionError}</p>}
            {loadFailed && <p role="alert" className="text-xs font-semibold text-error">{t("loadFailed")}</p>}
            {rates && rates.length === 0 && <p className="text-sm text-muted">{t("empty")}</p>}
            {rates && rates.length > 0 && (
                <div className="bg-surface border border-border rounded-xl overflow-x-auto">
                    <table className="w-full text-xs">
                        <thead>
                            <tr className="text-start text-muted uppercase tracking-[0.1em]">
                                {["colType", "colBuilding", "colRange", "colAverage", "colSource", "colChecked", "colExpires", "colAddedBy", "colDocument", "colStatus"].map(k =>
                                    <th key={k} className="px-4 py-3 text-start font-semibold">{t(k)}</th>)}
                                {canManage && <th className="px-4 py-3" />}
                            </tr>
                        </thead>
                        <tbody>
                            {rates.map(r => {
                                const st = rateStatus(r);
                                return (
                                    <tr key={r.id} className={cn("border-t border-border", st !== "ACTIVE" && "opacity-60")}>
                                        <td className="px-4 py-3 font-semibold">{t.has(`unitType${r.unitType}`) ? t(`unitType${r.unitType}`) : r.unitType}</td>
                                        <td className="px-4 py-3">{r.buildingName ?? t("allBuildings")}</td>
                                        <td className="px-4 py-3" dir="ltr">{formatCurrency(r.indexMin)} – {formatCurrency(r.indexMax)}</td>
                                        <td className="px-4 py-3" dir="ltr">{formatCurrency(r.average)}</td>
                                        <td className="px-4 py-3">{t.has(`source${r.source}`) ? t(`source${r.source}`) : r.source}</td>
                                        <td className="px-4 py-3" dir="ltr">{formatDate(r.checkedOn)}</td>
                                        <td className="px-4 py-3" dir="ltr">{formatDate(r.expiresOn)}</td>
                                        <td className="px-4 py-3">{r.createdByName ?? ""}</td>
                                        <td className="px-4 py-3">
                                            {r.hasDocument && (
                                                <a href={rentalIndexApi.documentUrl(r.id)} target="_blank" rel="noopener"
                                                   className="inline-flex items-center gap-1 text-primary underline">
                                                    <FileText size={12} />{t("pdf")}
                                                </a>
                                            )}
                                        </td>
                                        <td className="px-4 py-3">
                                            <span className={cn("px-2 py-0.5 rounded-full text-[10px] font-bold", BADGE[st])}>
                                                {t(`status${st.charAt(0)}${st.slice(1).toLowerCase()}`)}
                                            </span>
                                        </td>
                                        {canManage && (
                                            <td className="px-4 py-3 text-end">
                                                {st !== "REVOKED" && (
                                                    <button type="button" onClick={() => setRevoking(r)}
                                                            className="text-error font-bold cursor-pointer hover:underline">{t("revoke")}</button>
                                                )}
                                            </td>
                                        )}
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
            )}
            {dialog && (
                <RentalIndexAddDialog
                    mode={dialog} propertyId={propertyId} buildings={buildings}
                    onClose={() => setDialog(null)}
                    onSaved={() => { setDialog(null); void load(); }}
                />
            )}
            <ConfirmDialog
                isOpen={!!revoking} onClose={() => setRevoking(null)} onConfirm={revoke}
                title={t("revokeTitle")} description={t("revokeBody")}
                confirmText={t("revokeConfirm")} cancelText={t("cancel")} isDestructive isLoading={revokeBusy}
            />
        </div>
    );
}
