"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import SideDrawer from "@/components/ui/SideDrawer";
import {
    ApiError, fetchPromoters, invitePromoter, updatePromoterAccess,
} from "@/lib/api/promotions";
import type {
    PromoBusinessDTO,
    PromoterAccessRequest,
    PromoterDTO,
    PromoterInviteRequest,
} from "@/types/promotion";
import type { PropertyOption } from "./AdEditor";
import { PromoterAccessForm } from "./PromoterAccessForm";

interface PromotersTabProps {
    businesses: PromoBusinessDTO[];
    properties: PropertyOption[];
}

type Dialog = { kind: "invite" } | { kind: "edit"; promoter: PromoterDTO };

/**
 * Promotions → Promoters: the outside advertisers of this organisation and what
 * each may see. Inviting creates the login (a set-password email goes out);
 * deactivating or deleting one stays on Users, so this tab has no such actions.
 */
export function PromotersTab({ businesses, properties }: PromotersTabProps) {
    const t = useTranslations("Promotions");

    const [rows, setRows] = useState<PromoterDTO[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    const [dialog, setDialog] = useState<Dialog | null>(null);
    const [busy, setBusy] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);

    // Guards a slow response from clobbering a newer one.
    const requestIdRef = useRef(0);

    const load = useCallback(async () => {
        const id = ++requestIdRef.current;
        setLoading(true);
        try {
            const data = await fetchPromoters();
            if (id !== requestIdRef.current) return;
            setRows(data);
            setError(null);
        } catch (e) {
            if (id !== requestIdRef.current) return;
            setError(e instanceof ApiError ? e.message : t("loadError"));
        } finally {
            if (id === requestIdRef.current) setLoading(false);
        }
    }, [t]);

    useEffect(() => { void load(); }, [load]);

    function close() {
        if (busy) return;
        setDialog(null);
        setFormError(null);
    }

    async function invite(body: PromoterInviteRequest) {
        setBusy(true);
        setFormError(null);
        try {
            await invitePromoter(body);
            setDialog(null);
            setNotice(t("inviteSent", { email: body.email }));
            await load();
        } catch (e) {
            // "That email already has a login" and the like arrive as 400s whose
            // (English) message is the only signal; show it where the admin is looking.
            setFormError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setBusy(false);
        }
    }

    async function saveAccess(userId: string, body: PromoterAccessRequest) {
        setBusy(true);
        setFormError(null);
        try {
            await updatePromoterAccess(userId, body);
            setDialog(null);
            setNotice(t("accessSaved"));
            await load();
        } catch (e) {
            setFormError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setBusy(false);
        }
    }

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between gap-3">
                <p className="max-w-2xl text-sm text-gray-600">{t("promotersIntro")}</p>
                <button type="button"
                    onClick={() => { setNotice(null); setFormError(null); setDialog({ kind: "invite" }); }}
                    className="shrink-0 rounded-lg bg-gray-900 px-4 py-2 text-white">
                    {t("invitePromoter")}
                </button>
            </div>

            {notice && (
                <p role="status" className="rounded-lg bg-green-50 px-3 py-2 text-sm text-green-800">{notice}</p>
            )}
            {error && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
            )}

            {loading ? (
                <div className="flex justify-center py-10"><Loader2 className="animate-spin" /></div>
            ) : rows.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-500">{t("promotersEmpty")}</p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-start text-gray-500">
                            <th className="py-2 text-start">{t("promoterName")}</th>
                            <th className="text-start">{t("promoterEmailLabel")}</th>
                            <th className="text-start">{t("status")}</th>
                            <th className="text-start">{t("promoterBusinesses")}</th>
                            <th className="text-start">{t("promoterProperties")}</th>
                            <th className="text-start">{t("promoterCanCreate")}</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map(row => (
                            <tr key={row.userId} className="border-b">
                                <td className="py-2">{row.name}</td>
                                <td dir="ltr" className="text-start">{row.email}</td>
                                <td>
                                    <span className={`rounded-full px-2 py-1 text-xs ${row.status === "INACTIVE"
                                        ? "bg-gray-100 text-gray-600" : "bg-green-50 text-green-700"}`}>
                                        {row.status === "INACTIVE" ? t("promoterInactive") : t("promoterActive")}
                                    </span>
                                </td>
                                <td>{row.businessIds.length}</td>
                                <td>{row.propertyIds.length}</td>
                                <td>
                                    {row.canCreateBusinesses ? (
                                        <span className="rounded-full bg-blue-50 px-2 py-1 text-xs text-blue-700">
                                            {t("canCreateBadge")}
                                        </span>
                                    ) : "—"}
                                </td>
                                <td className="text-end">
                                    <button type="button" className="underline"
                                        aria-label={`${t("editAccess")}: ${row.name}`}
                                        onClick={() => { setNotice(null); setFormError(null); setDialog({ kind: "edit", promoter: row }); }}>
                                        {t("editAccess")}
                                    </button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}

            <SideDrawer open={dialog !== null} onClose={close} testId="promoter-access-drawer"
                closeLabel={t("close")}
                title={dialog?.kind === "edit"
                    ? t("editAccessTitle", { name: dialog.promoter.name })
                    : t("inviteTitle")}>
                {dialog && (
                    <PromoterAccessForm
                        key={dialog.kind === "edit" ? dialog.promoter.userId : "invite"}
                        promoter={dialog.kind === "edit" ? dialog.promoter : null}
                        businesses={businesses} properties={properties}
                        busy={busy} error={formError}
                        onInvite={body => void invite(body)}
                        onSaveAccess={(id, body) => void saveAccess(id, body)}
                        onCancel={close} />
                )}
            </SideDrawer>
        </div>
    );
}
