"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2, Sparkles } from "lucide-react";
import { ledgerApi, type RoleMapping, type AccountRole } from "@/lib/api/ledger";
import { ApiError } from "@/lib/api/facilities";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import AccountPicker, { invalidateAccounts } from "./AccountPicker";

/**
 * Per-property role → account mapping. A row is either mapped to a
 * property-specific account, inherited from the tenant-wide default
 * (`inherited`), or unmapped — postings for an unmapped role fail, so the
 * unmapped state is called out rather than shown as an empty cell.
 */
export default function PropertyAccountsTab({ propertyId }: { propertyId: string }) {
    const t = useTranslations("Ledger");
    const [rows, setRows] = useState<RoleMapping[]>([]);
    const [loading, setLoading] = useState(true);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [editing, setEditing] = useState<AccountRole | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            setRows(await ledgerApi.propertyAccounts.get(propertyId));
        } catch (e) {
            setError(e instanceof ApiError ? e.message : "Failed to load ledger accounts");
        } finally {
            setLoading(false);
        }
    }, [propertyId]);

    useEffect(() => {
        load();
    }, [load]);

    const generate = async () => {
        setBusy(true);
        setError(null);
        try {
            setRows(await ledgerApi.propertyAccounts.generate(propertyId));
            // New accounts were just created — every picker's cached list is stale.
            invalidateAccounts();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : "Failed to generate accounts");
        } finally {
            setBusy(false);
        }
    };

    const set = async (role: AccountRole, accountId: string) => {
        setBusy(true);
        setError(null);
        try {
            await ledgerApi.propertyAccounts.set(propertyId, role, accountId);
            setEditing(null);
            await load();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : "Failed to change the account");
        } finally {
            setBusy(false);
        }
    };

    const clear = async (role: AccountRole) => {
        setBusy(true);
        setError(null);
        try {
            await ledgerApi.propertyAccounts.clear(propertyId, role);
            await load();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : "Failed to reset the account");
        } finally {
            setBusy(false);
        }
    };

    return (
        <div className="space-y-4">
            <div className="flex items-start justify-between gap-4">
                <div>
                    <h3 className="text-sm font-semibold text-foreground">{t("propertyAccounts")}</h3>
                    <p className="text-xs text-muted">{t("propertyAccountsDesc")}</p>
                </div>
                <button
                    type="button"
                    onClick={generate}
                    disabled={busy}
                    className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-medium cursor-pointer disabled:opacity-50 focus:ring-2 focus:ring-primary/20 focus:outline-none"
                >
                    {busy ? <Loader2 size={13} className="animate-spin" /> : <Sparkles size={13} />}
                    {t("generateMissing")}
                </button>
            </div>

            {error && <LoadErrorBanner message={error} onRetry={load} />}

            {loading ? (
                <div className="bg-input rounded-xl h-40 animate-pulse" />
            ) : (
                <div className="bg-surface border border-border rounded-xl overflow-hidden">
                    <table className="w-full">
                        <thead>
                            <tr className="bg-input/50">
                                {[t("role"), t("account"), t("status"), ""].map((h, i) => (
                                    <th
                                        key={i}
                                        className="text-left px-5 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider"
                                    >
                                        {h}
                                    </th>
                                ))}
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {rows.map(r => (
                                <tr key={r.role} data-testid="property-account-row" data-role={r.role} className="hover:bg-input/30">
                                    <td className="px-5 py-3 text-xs font-medium">{r.role.replaceAll("_", " ")}</td>
                                    <td className="px-5 py-3 text-xs">
                                        {editing === r.role ? (
                                            <div className="max-w-md">
                                                <AccountPicker
                                                    autoFocus
                                                    value={r.accountId}
                                                    propertyId={propertyId}
                                                    placeholder={t("account")}
                                                    onChange={id => set(r.role, id)}
                                                />
                                                <p className="text-[10px] text-muted mt-1">{t("remapWarning")}</p>
                                            </div>
                                        ) : r.accountId ? (
                                            <>
                                                <span className="font-mono text-muted mr-2">{r.accountCode}</span>
                                                {r.accountName}
                                            </>
                                        ) : (
                                            <span className="text-warning">{t("unmapped")}</span>
                                        )}
                                    </td>
                                    <td className="px-5 py-3 text-xs">
                                        {r.inherited ? (
                                            <span className="bg-input text-muted border border-border px-2 py-0.5 rounded-lg text-[10px] font-bold">
                                                {t("inherited")}
                                            </span>
                                        ) : null}
                                    </td>
                                    <td className="px-5 py-3 text-xs text-right whitespace-nowrap">
                                        <button
                                            type="button"
                                            className="text-primary hover:underline cursor-pointer mr-3"
                                            onClick={() => setEditing(editing === r.role ? null : r.role)}
                                        >
                                            {t("changeAccount")}
                                        </button>
                                        {r.accountId && !r.inherited && (
                                            <button
                                                type="button"
                                                className="text-muted hover:underline cursor-pointer"
                                                disabled={busy}
                                                onClick={() => clear(r.role)}
                                            >
                                                {t("useDefault")}
                                            </button>
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
}
