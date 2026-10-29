"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { Link } from "@/i18n/routing";
import { Toast, useToast } from "@/components/ui/Toast";
import {
    ApiError, createPromoterApiKey, fetchPromoterApiKeys, revokePromoterApiKey, rotatePromoterApiKey,
} from "@/lib/api/promoter";
import type { ApiKeyDTO, ApiKeyStatus } from "@/types/promotion";
import { ApiKeyRevealDialog } from "./ApiKeyRevealDialog";
import { ApiKeyRevokeDialog } from "./ApiKeyRevokeDialog";

const MAX_ACTIVE = 2;

const STATUS_STYLE: Record<ApiKeyStatus, string> = {
    active: "bg-green-100 text-green-800",
    expiring: "bg-amber-100 text-amber-800",
    revoked: "bg-gray-200 text-gray-700",
    expired: "bg-gray-200 text-gray-700",
};

/**
 * The promoter's API keys: create, rotate, revoke. A secret exists only in
 * `issued` (state) while its reveal dialog is open; it is never persisted,
 * put in a URL or shown in a toast.
 */
export function PromoterDeveloperTab() {
    const t = useTranslations("Promoter");
    const locale = useLocale();
    const [keys, setKeys] = useState<ApiKeyDTO[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [creating, setCreating] = useState(false);
    const [name, setName] = useState("");
    const [busy, setBusy] = useState(false);
    const [issued, setIssued] = useState<{ secret: string; rotated: boolean } | null>(null);
    const [revoking, setRevoking] = useState<ApiKeyDTO | null>(null);
    const { toast, showToast } = useToast();

    const load = useCallback(async () => {
        try {
            setKeys(await fetchPromoterApiKeys());
            setError(null);
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("devLoadError"));
        }
    }, [t]);

    useEffect(() => { void load(); }, [load]);

    const fmt = (iso: string) => new Date(iso).toLocaleDateString(locale, { dateStyle: "medium" });
    const activeCount = (keys ?? []).filter(k => k.status === "active").length;
    const atLimit = activeCount >= MAX_ACTIVE;

    async function create() {
        setBusy(true);
        try {
            const res = await createPromoterApiKey(name.trim());
            setIssued({ secret: res.secret, rotated: false });
            setCreating(false);
            setName("");
            setError(null);
            await load();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("devActionError"));
        } finally {
            setBusy(false);
        }
    }

    async function rotate(k: ApiKeyDTO) {
        setBusy(true);
        try {
            const res = await rotatePromoterApiKey(k.id);
            setIssued({ secret: res.secret, rotated: true });
            setError(null);
            await load();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("devActionError"));
        } finally {
            setBusy(false);
        }
    }

    async function revoke(k: ApiKeyDTO, reason: string) {
        setBusy(true);
        try {
            await revokePromoterApiKey(k.id, reason || undefined);
            setRevoking(null);
            setError(null);
            showToast(t("devStatusRevoked"));
            await load();
        } catch (e) {
            setRevoking(null);
            setError(e instanceof ApiError ? e.message : t("devActionError"));
        } finally {
            setBusy(false);
        }
    }

    function revokedNote(k: ApiKeyDTO) {
        if (k.status !== "revoked") return null;
        if (k.revokedByAdmin) return t("devRevokedByAdmin", { reason: k.revokedReason ?? "—" });
        return k.revokedReason ? t("devRevokedReason", { reason: k.revokedReason }) : t("devRevokedByYou");
    }

    return (
        <div className="space-y-4">
            <div className="flex items-start justify-between gap-3">
                <div className="space-y-1">
                    <p className="text-sm text-gray-600">{t("devIntro")}</p>
                    <Link href="/dashboard/promoter/developer" className="text-sm underline">
                        {t("devDocsLink")}
                    </Link>
                </div>
                <div className="shrink-0 text-end">
                    <button type="button" disabled={atLimit || keys === null}
                        onClick={() => { setError(null); setCreating(true); }}
                        className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-40">
                        {t("devCreateKey")}
                    </button>
                    {atLimit && <p className="mt-1 max-w-xs text-xs text-gray-500">{t("devCreateLimitHint")}</p>}
                </div>
            </div>

            {error && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
            )}

            {creating && (
                <form className="flex items-end gap-2 rounded-lg border p-3"
                    onSubmit={e => { e.preventDefault(); void create(); }}>
                    <label className="block flex-1 text-sm">
                        <span className="mb-1 block">{t("devKeyNameLabel")}</span>
                        <input type="text" value={name} maxLength={80} autoFocus
                            placeholder={t("devKeyNamePlaceholder")}
                            onChange={e => setName(e.target.value)}
                            className="w-full rounded-lg border px-3 py-2" />
                    </label>
                    <button type="button" onClick={() => { setCreating(false); setName(""); }}
                        className="rounded-lg border px-4 py-2">{t("devCancel")}</button>
                    <button type="submit" disabled={busy || !name.trim()}
                        className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-40">
                        {t("devCreateSubmit")}
                    </button>
                </form>
            )}

            {keys === null ? (
                !error && <div className="flex justify-center py-10"><Loader2 className="animate-spin" /></div>
            ) : keys.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-500">{t("devEmpty")}</p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-gray-500">
                            <th className="py-2 text-start">{t("devColName")}</th>
                            <th className="text-start">{t("devColKey")}</th>
                            <th className="text-start">{t("devColCreated")}</th>
                            <th className="text-start">{t("devColLastUsed")}</th>
                            <th className="text-start">{t("devColStatus")}</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {keys.map(k => (
                            <tr key={k.id} className="border-b align-top">
                                <td className="py-2">{k.name}</td>
                                <td dir="ltr" className="text-start font-mono">{k.keyPrefix}…</td>
                                <td>{fmt(k.createdAt)}</td>
                                <td>{k.lastUsedAt ? fmt(k.lastUsedAt) : t("devNever")}</td>
                                <td>
                                    <span className={`rounded-full px-2 py-0.5 text-xs ${STATUS_STYLE[k.status]}`}>
                                        {t(`devStatus${k.status[0].toUpperCase()}${k.status.slice(1)}`)}
                                    </span>
                                    {k.status === "expiring" && k.expiresAt && (
                                        <p className="mt-1 text-xs text-gray-500">{t("devExpiresOn", { date: fmt(k.expiresAt) })}</p>
                                    )}
                                    {revokedNote(k) && <p className="mt-1 text-xs text-gray-600">{revokedNote(k)}</p>}
                                </td>
                                <td className="text-end">
                                    {k.status === "active" && (
                                        <button type="button" disabled={busy} className="me-3 underline disabled:opacity-40"
                                            onClick={() => void rotate(k)}>{t("devRotate")}</button>
                                    )}
                                    {(k.status === "active" || k.status === "expiring") && (
                                        <button type="button" disabled={busy}
                                            className="text-red-600 underline disabled:opacity-40"
                                            onClick={() => setRevoking(k)}>{t("devRevoke")}</button>
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}

            {issued && (
                <ApiKeyRevealDialog secret={issued.secret} rotated={issued.rotated}
                    onClose={() => setIssued(null)} />
            )}
            {revoking && (
                <ApiKeyRevokeDialog keyName={revoking.name} busy={busy}
                    onConfirm={reason => void revoke(revoking, reason)}
                    onCancel={() => setRevoking(null)} />
            )}

            <Toast toast={toast} />
        </div>
    );
}
