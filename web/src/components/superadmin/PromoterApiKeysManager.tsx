"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { KeyRound, Search, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
import { Toast, useToast } from "@/components/ui/Toast";
import { AccessDeniedState, LoadFailedState } from "@/components/ui/PageStates";
import { isAbortError } from "@/lib/api/abort";
import { useLatestRequest } from "@/hooks/useLatestRequest";

const BASE = "/api/proxy/admin/promoter-api-keys";
const STATUSES = ["active", "expiring", "revoked", "expired"] as const;
type KeyStatus = (typeof STATUSES)[number];

type AdminKeyRow = {
    id: string; tenantId: string; orgName: string;
    promoterUserId: string; promoterName: string; promoterEmail: string;
    name: string; keyPrefix: string;
    createdAt: string; lastUsedAt: string | null; expiresAt: string | null;
    revokedAt: string | null; revokedReason: string | null;
    status: KeyStatus;
};

type Load = { kind: "loading" } | { kind: "ok" } | { kind: "forbidden" } | { kind: "failed" };

const STATUS_STYLE: Record<KeyStatus, string> = {
    active: "bg-success/10 text-success",
    expiring: "bg-warning/10 text-warning",
    revoked: "bg-error/10 text-error",
    expired: "bg-muted/10 text-muted",
};

const canRevoke = (r: AdminKeyRow) => r.status === "active" || r.status === "expiring";

export default function PromoterApiKeysManager() {
    const t = useTranslations("SuperadminApiKeys");
    const tNav = useTranslations("Navigation");
    const locale = useLocale();
    const begin = useLatestRequest();
    const { toast, showToast } = useToast();

    const [rows, setRows] = useState<AdminKeyRow[]>([]);
    const [total, setTotal] = useState(0);
    const [load, setLoad] = useState<Load>({ kind: "loading" });
    const [search, setSearch] = useState("");
    const [q, setQ] = useState("");
    const [status, setStatus] = useState("");
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(25);
    const [reloadTick, setReloadTick] = useState(0);

    const [target, setTarget] = useState<AdminKeyRow | null>(null);
    const [reason, setReason] = useState("");
    const [busy, setBusy] = useState(false);
    const [revokeFailed, setRevokeFailed] = useState(false);

    // Debounce the search box into `q`; a new query starts again at page 1.
    useEffect(() => {
        const h = setTimeout(() => {
            setQ(prev => {
                const next = search.trim();
                if (prev !== next) setPage(1);
                return next;
            });
        }, 300);
        return () => clearTimeout(h);
    }, [search]);

    useEffect(() => {
        const { signal, isCurrent } = begin();
        const params = new URLSearchParams();
        if (q) params.set("q", q);
        if (status) params.set("status", status);
        params.set("page", String(page - 1));
        params.set("size", String(size));
        setLoad({ kind: "loading" });
        (async () => {
            try {
                const res = await fetch(`${BASE}?${params}`, { signal });
                if (!isCurrent()) return;
                if (res.status === 403) { setLoad({ kind: "forbidden" }); return; }
                if (!res.ok) { setLoad({ kind: "failed" }); return; }
                const body = await res.json();
                if (!isCurrent()) return;
                setRows(Array.isArray(body.content) ? body.content : []);
                setTotal(typeof body.totalElements === "number" ? body.totalElements : 0);
                setLoad({ kind: "ok" });
            } catch (err) {
                if (isAbortError(err) || !isCurrent()) return;
                setLoad({ kind: "failed" });
            }
        })();
    }, [q, status, page, size, reloadTick, begin]);

    const fmt = useCallback((iso: string | null) =>
        iso ? new Date(iso).toLocaleDateString(locale, { year: "numeric", month: "short", day: "numeric" }) : "—", [locale]);

    const closeDialog = () => { if (!busy) { setTarget(null); setReason(""); setRevokeFailed(false); } };

    const revoke = async () => {
        if (!target || busy) return;
        const trimmed = reason.trim();
        if (!trimmed) return;
        setBusy(true);
        setRevokeFailed(false);
        try {
            const res = await fetch(`${BASE}/${target.id}/revoke`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ reason: trimmed }),
            });
            if (!res.ok) { setRevokeFailed(true); return; }
            const updated = (await res.json()) as AdminKeyRow;
            setRows(prev => prev.map(r => (r.id === updated.id ? updated : r)));
            setTarget(null);
            setReason("");
            showToast(t("revoked"));
        } catch {
            setRevokeFailed(true);
        } finally {
            setBusy(false);
        }
    };

    if (load.kind === "forbidden") return <AccessDeniedState />;
    if (load.kind === "failed") return <LoadFailedState onRetry={() => setReloadTick(n => n + 1)} message={t("loadFailed")} />;

    return (
        <div className="max-w-7xl mx-auto space-y-6">
            <Toast toast={toast} />
            <div>
                <h1 className="text-2xl font-bold text-foreground flex items-center gap-2">
                    <KeyRound size={22} aria-hidden="true" />
                    {tNav("apiKeys")}
                </h1>
                <p className="text-sm text-muted mt-1">{t("subtitle")}</p>
            </div>

            <div className="flex flex-wrap items-center gap-3">
                <div className="relative flex-1 min-w-[220px] max-w-md">
                    <Search size={16} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted" aria-hidden="true" />
                    <input
                        data-testid="api-keys-search"
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                        placeholder={t("searchPlaceholder")}
                        aria-label={t("searchPlaceholder")}
                        className="w-full ps-9 pe-3 py-2 text-sm bg-surface border border-border rounded-lg focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    />
                </div>
                <select
                    data-testid="api-keys-status-filter"
                    value={status}
                    onChange={e => { setStatus(e.target.value); setPage(1); }}
                    aria-label={t("filterStatus")}
                    className="bg-surface border border-border rounded-lg px-3 py-2 text-sm cursor-pointer"
                >
                    <option value="">{t("allStatuses")}</option>
                    {STATUSES.map(s => <option key={s} value={s}>{t(`status.${s}`)}</option>)}
                </select>
            </div>

            <div className="bg-surface rounded-xl border border-border shadow-sm overflow-x-auto">
                {load.kind === "loading" ? (
                    <div className="flex justify-center py-16 text-muted"><Loader2 className="animate-spin" size={24} aria-label={t("loading")} /></div>
                ) : rows.length === 0 ? (
                    <div className="py-16 text-center text-sm text-muted" data-testid="api-keys-empty">{t("empty")}</div>
                ) : (
                    <table className="w-full text-sm text-start">
                        <thead>
                            <tr className="border-b border-border text-xs text-muted">
                                {(["org", "promoter", "keyName", "prefix", "created", "lastUsed", "statusCol"] as const).map(c => (
                                    <th key={c} className="px-4 py-3 text-start font-semibold">{t(`col.${c}`)}</th>
                                ))}
                                <th className="px-4 py-3" />
                            </tr>
                        </thead>
                        <tbody>
                            {rows.map(r => (
                                <tr key={r.id} className="border-b border-border last:border-0">
                                    <td className="px-4 py-3 font-medium text-foreground">{r.orgName}</td>
                                    <td className="px-4 py-3">
                                        <div className="text-foreground">{r.promoterName}</div>
                                        <div className="text-xs text-muted" dir="ltr">{r.promoterEmail}</div>
                                    </td>
                                    <td className="px-4 py-3">{r.name}</td>
                                    <td className="px-4 py-3 font-mono text-xs" dir="ltr">{r.keyPrefix}</td>
                                    <td className="px-4 py-3 whitespace-nowrap">{fmt(r.createdAt)}</td>
                                    <td className="px-4 py-3 whitespace-nowrap">{r.lastUsedAt ? fmt(r.lastUsedAt) : t("never")}</td>
                                    <td className="px-4 py-3">
                                        <span data-testid="api-key-status" className={cn("inline-block rounded-full px-2.5 py-0.5 text-xs font-semibold", STATUS_STYLE[r.status])}>
                                            {t(`status.${r.status}`)}
                                        </span>
                                    </td>
                                    <td className="px-4 py-3 text-end">
                                        {canRevoke(r) && (
                                            <button
                                                type="button"
                                                data-testid="api-key-revoke"
                                                onClick={() => { setTarget(r); setReason(""); setRevokeFailed(false); }}
                                                className="cursor-pointer text-xs font-semibold text-error hover:underline"
                                            >
                                                {t("revoke")}
                                            </button>
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>

            <Pagination
                currentPage={page}
                totalItems={total}
                itemsPerPage={size}
                onPageChange={setPage}
                onItemsPerPageChange={n => { setSize(n); setPage(1); }}
            />

            <ConfirmDialog
                isOpen={target !== null}
                onClose={closeDialog}
                onConfirm={revoke}
                title={t("revokeTitle")}
                description={t("revokeDescription")}
                confirmText={t("revoke")}
                cancelText={t("cancel")}
                isDestructive
                isLoading={busy}
                confirmTestId="api-key-revoke-confirm"
                confirmDisabled={reason.trim() === ""}
            >
                {target && (
                    <div data-testid="api-key-revoke-dialog" className="space-y-3 text-start">
                        <dl className="text-sm space-y-1 rounded-lg bg-background border border-border p-3">
                            <div className="flex gap-2"><dt className="text-muted">{t("col.org")}</dt><dd className="font-medium">{target.orgName}</dd></div>
                            <div className="flex gap-2"><dt className="text-muted">{t("col.promoter")}</dt><dd className="font-medium">{target.promoterName} <span dir="ltr" className="text-muted">({target.promoterEmail})</span></dd></div>
                            <div className="flex gap-2"><dt className="text-muted">{t("col.prefix")}</dt><dd className="font-mono" dir="ltr">{target.keyPrefix}</dd></div>
                        </dl>
                        <label className="block text-sm font-medium">
                            {t("reasonLabel")}
                            <textarea
                                data-testid="api-key-revoke-reason"
                                value={reason}
                                onChange={e => setReason(e.target.value)}
                                maxLength={500}
                                rows={3}
                                className="mt-1 w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-primary/20 focus:outline-none"
                            />
                        </label>
                        {revokeFailed && (
                            <p role="alert" data-testid="api-key-revoke-error" className="text-sm text-error">{t("revokeFailed")}</p>
                        )}
                    </div>
                )}
            </ConfirmDialog>
        </div>
    );
}
