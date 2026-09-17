"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { BookOpen, CheckCircle, Loader2, Save, ShieldCheck } from "lucide-react";
import { ledgerApi, type RoleMapping, type TemplateRow, type AccountRole } from "@/lib/api/ledger";
import { ApiError } from "@/lib/api/facilities";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { hasPermission, type UserRole } from "@/lib/rbac";
import AccountPicker from "@/components/finance/AccountPicker";

const roleLabel = (role: AccountRole) => role.replaceAll("_", " ");

/**
 * Two halves of the same setup: the template says which accounts every new
 * property gets, the defaults say which tenant-wide account a role falls back
 * to when a property has no mapping of its own.
 */
export default function AccountTemplatePage() {
    const t = useTranslations("Ledger");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageAccountSetup");

    const [template, setTemplate] = useState<TemplateRow[]>([]);
    const [defaults, setDefaults] = useState<RoleMapping[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [templateError, setTemplateError] = useState<string | null>(null);
    const [defaultsError, setDefaultsError] = useState<string | null>(null);
    const [savingTemplate, setSavingTemplate] = useState(false);
    const [savingRole, setSavingRole] = useState<AccountRole | null>(null);
    const [savedRole, setSavedRole] = useState<AccountRole | null>(null);
    // Picked-but-not-yet-saved default per role, so choosing an account in the
    // dropdown doesn't write to the tenant's books on its own.
    const [pendingDefault, setPendingDefault] = useState<Record<string, string>>({});
    const [templateSaved, setTemplateSaved] = useState(false);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            const [rows, mappings] = await Promise.all([ledgerApi.template.get(), ledgerApi.defaults.get()]);
            setTemplate(rows);
            setDefaults(mappings);
        } catch (err) {
            setLoadError(err instanceof ApiError ? err.message : "Failed to load the account setup");
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        if (allowed) load();
        else setLoading(false);
    }, [allowed, load]);

    const patchRow = (role: AccountRole, patch: Partial<TemplateRow>) => {
        setTemplateSaved(false);
        setTemplate(prev => prev.map(r => (r.role === role ? { ...r, ...patch } : r)));
    };

    const saveTemplate = async () => {
        setSavingTemplate(true);
        setTemplateError(null);
        setTemplateSaved(false);
        try {
            setTemplate(await ledgerApi.template.save(template));
            setTemplateSaved(true);
        } catch (err) {
            setTemplateError(err instanceof ApiError ? err.message : "Failed to save the template");
        } finally {
            setSavingTemplate(false);
        }
    };

    const saveDefault = async (role: AccountRole, accountId: string | null) => {
        if (!accountId) return;
        setSavingRole(role);
        setDefaultsError(null);
        setSavedRole(null);
        try {
            const saved = await ledgerApi.defaults.set(role, accountId);
            setDefaults(prev => prev.map(r => (r.role === role ? saved : r)));
            setPendingDefault(prev => {
                const next = { ...prev };
                delete next[role];
                return next;
            });
            setSavedRole(role);
        } catch (err) {
            setDefaultsError(err instanceof ApiError ? err.message : "Failed to save the default account");
        } finally {
            setSavingRole(null);
        }
    };

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{t("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    const th = "text-left px-5 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider";

    return (
        <div className="max-w-5xl">
            <div className="mb-8">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                    <BookOpen size={20} className="text-primary" />
                    {t("accountTemplate")}
                </h1>
                <p className="text-xs text-muted font-medium">{t("accountTemplateDesc")}</p>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            {loading ? (
                <div className="space-y-6">
                    <div className="bg-input rounded-xl h-64 animate-pulse" />
                    <div className="bg-input rounded-xl h-48 animate-pulse" />
                </div>
            ) : (
                <div className="space-y-8">
                    {/* ── Property account template ── */}
                    <section className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                        <div className="flex items-center justify-between gap-4 px-5 py-4 border-b border-border">
                            <div>
                                <h2 className="text-sm font-semibold text-foreground">{t("accountTemplate")}</h2>
                                <p className="text-[11px] text-muted mt-0.5">{t("patternHint")}</p>
                            </div>
                            <div className="flex items-center gap-3">
                                {templateSaved && (
                                    <span className="flex items-center gap-1 text-[11px] font-semibold text-success">
                                        <CheckCircle size={13} />
                                        {t("saved")}
                                    </span>
                                )}
                                <button
                                    type="button"
                                    onClick={saveTemplate}
                                    disabled={savingTemplate || template.length === 0}
                                    className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50 focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                >
                                    {savingTemplate ? <Loader2 size={13} className="animate-spin" /> : <Save size={13} />}
                                    {t("save")}
                                </button>
                            </div>
                        </div>

                        {templateError && (
                            <div role="alert" className="mx-5 mt-4 bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg p-3">
                                {templateError}
                            </div>
                        )}

                        <table className="w-full">
                            <thead>
                                <tr className="bg-input/50">
                                    <th className={th}>{t("role")}</th>
                                    <th className={th}>{t("namePattern")}</th>
                                    <th className={th}>{t("parentGroup")}</th>
                                    <th className={th}>{t("enabled")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {template.map(row => (
                                    <tr key={row.role} className="hover:bg-input/30">
                                        <td className="px-5 py-3 text-xs font-medium whitespace-nowrap">{roleLabel(row.role)}</td>
                                        <td className="px-5 py-3">
                                            <input
                                                aria-label={`${t("namePattern")} ${roleLabel(row.role)}`}
                                                className="w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                                value={row.namePattern}
                                                onChange={ev => patchRow(row.role, { namePattern: ev.target.value })}
                                            />
                                        </td>
                                        <td className="px-5 py-3 min-w-[16rem]">
                                            <AccountPicker
                                                leafOnly={false}
                                                groupOnly
                                                placeholder={t("parentGroup")}
                                                value={row.parentAccountId || null}
                                                onChange={id => patchRow(row.role, { parentAccountId: id })}
                                            />
                                        </td>
                                        <td className="px-5 py-3">
                                            <label className="flex items-center gap-2 cursor-pointer">
                                                <input
                                                    type="checkbox"
                                                    aria-label={`${t("enabled")} ${roleLabel(row.role)}`}
                                                    className="rounded border-border"
                                                    checked={row.enabled}
                                                    onChange={ev => patchRow(row.role, { enabled: ev.target.checked })}
                                                />
                                            </label>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </section>

                    {/* ── Tenant-wide default accounts ── */}
                    <section className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                        <div className="px-5 py-4 border-b border-border">
                            <h2 className="text-sm font-semibold text-foreground">{t("defaultAccounts")}</h2>
                            <p className="text-[11px] text-muted mt-0.5">{t("defaultAccountsDesc")}</p>
                        </div>

                        {defaultsError && (
                            <div role="alert" className="mx-5 mt-4 bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg p-3">
                                {defaultsError}
                            </div>
                        )}

                        <table className="w-full">
                            <thead>
                                <tr className="bg-input/50">
                                    <th className={th}>{t("role")}</th>
                                    <th className={th}>{t("account")}</th>
                                    <th className={th} />
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {defaults.map(row => (
                                    <tr key={row.role} className="hover:bg-input/30">
                                        <td className="px-5 py-3 text-xs font-medium whitespace-nowrap">{roleLabel(row.role)}</td>
                                        <td className="px-5 py-3 min-w-[18rem]">
                                            <AccountPicker
                                                placeholder={t("account")}
                                                value={pendingDefault[row.role] ?? row.accountId}
                                                onChange={id => {
                                                    setSavedRole(null);
                                                    setPendingDefault(prev => ({ ...prev, [row.role]: id }));
                                                }}
                                            />
                                            {!row.accountId && !pendingDefault[row.role] && (
                                                <p className="text-[10px] text-warning mt-1">{t("unmapped")}</p>
                                            )}
                                        </td>
                                        <td className="px-5 py-3 text-right whitespace-nowrap">
                                            {savedRole === row.role ? (
                                                <span className="flex items-center justify-end gap-1 text-[11px] font-semibold text-success">
                                                    <CheckCircle size={13} />
                                                    {t("saved")}
                                                </span>
                                            ) : (
                                                <button
                                                    type="button"
                                                    onClick={() => saveDefault(row.role, pendingDefault[row.role] ?? null)}
                                                    disabled={savingRole === row.role || !pendingDefault[row.role]}
                                                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-input text-foreground border border-border text-[11px] font-bold cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                                >
                                                    {savingRole === row.role ? <Loader2 size={12} className="animate-spin" /> : <Save size={12} />}
                                                    {t("save")}
                                                </button>
                                            )}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </section>
                </div>
            )}
        </div>
    );
}
