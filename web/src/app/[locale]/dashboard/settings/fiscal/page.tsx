"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { CalendarClock, CheckCircle, Loader2, Lock, Save, ShieldCheck } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { ledgerApi, type FiscalSettings } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { BankLocksCard } from "@/components/finance/bankrec/BankLocksCard";

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

export default function FiscalSettingsPage() {
    const t = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageAccountSetup");

    const [settings, setSettings] = useState<FiscalSettings | null>(null);
    const [startMonth, setStartMonth] = useState(1);
    const [booksStartDate, setBooksStartDate] = useState("");
    const [lockThrough, setLockThrough] = useState("");
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);
    const [saveError, setSaveError] = useState<string | null>(null);
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [locking, setLocking] = useState(false);
    const [lockError, setLockError] = useState<string | null>(null);

    // Month names come from the browser's own calendar data rather than twelve
    // more catalog keys, so AR gets Arabic month names for free.
    const months = useMemo(() => {
        const fmt = new Intl.DateTimeFormat(locale, { month: "long" });
        return Array.from({ length: 12 }, (_, i) => ({
            value: i + 1,
            label: fmt.format(new Date(Date.UTC(2020, i, 1))),
        }));
    }, [locale]);

    const apply = (s: FiscalSettings) => {
        setSettings(s);
        setStartMonth(s.fiscalYearStartMonth);
        setBooksStartDate(s.booksStartDate ?? "");
    };

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            apply(await ledgerApi.fiscal.get());
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

    const save = async () => {
        setSaving(true);
        setSaved(false);
        setSaveError(null);
        try {
            apply(
                await ledgerApi.fiscal.update({
                    fiscalYearStartMonth: startMonth,
                    ...(booksStartDate ? { booksStartDate } : {}),
                }),
            );
            setSaved(true);
        } catch (err) {
            setSaveError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setSaving(false);
        }
    };

    const lock = async () => {
        setLocking(true);
        setLockError(null);
        try {
            apply(await ledgerApi.fiscal.lock(lockThrough));
            setConfirmOpen(false);
            setLockThrough("");
        } catch (err) {
            // The backend rejects a lock date earlier than the current one with a
            // 400; its message is the only thing that explains why.
            setLockError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setLocking(false);
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

    return (
        <div className="max-w-3xl">
            <div className="mb-8">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                    <CalendarClock size={20} className="text-primary" />
                    {t("fiscal")}
                </h1>
                <p className="text-xs text-muted font-medium">{t("fiscalDesc")}</p>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    <div className="bg-input rounded-xl h-40" />
                </div>
            ) : (
                <div className="bg-surface border border-border rounded-xl shadow-sm p-5">
                    <div className="grid gap-4 sm:grid-cols-2">
                        <div>
                            <label className={label} htmlFor="fiscal-start-month">{t("fiscalYearStartMonth")}</label>
                            <select
                                id="fiscal-start-month"
                                className={field}
                                value={startMonth}
                                onChange={ev => {
                                    setSaved(false);
                                    setStartMonth(Number(ev.target.value));
                                }}
                            >
                                {months.map(m => (
                                    <option key={m.value} value={m.value}>{m.label}</option>
                                ))}
                            </select>
                        </div>
                        <div>
                            <label className={label} htmlFor="fiscal-books-start">{t("booksStartDate")}</label>
                            <input
                                id="fiscal-books-start"
                                type="date"
                                className={field}
                                value={booksStartDate}
                                onChange={ev => {
                                    setSaved(false);
                                    setBooksStartDate(ev.target.value);
                                }}
                            />
                        </div>
                    </div>

                    {saveError && <p role="alert" className="mt-3 text-xs font-semibold text-error">{saveError}</p>}

                    <div className="flex items-center gap-3 mt-4">
                        <button
                            type="button"
                            onClick={save}
                            disabled={saving}
                            className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50 focus:ring-2 focus:ring-primary/20 focus:outline-none"
                        >
                            {saving ? <Loader2 size={13} className="animate-spin" /> : <Save size={13} />}
                            {t("save")}
                        </button>
                        {saved && (
                            <span className="flex items-center gap-1.5 text-xs font-semibold text-success">
                                <CheckCircle size={13} />
                                {t("saved")}
                            </span>
                        )}
                    </div>

                    <div className="border-t border-border mt-6 pt-5">
                        <h2 className="text-sm font-bold text-foreground mb-1">{t("lockPeriod")}</h2>
                        <p className="text-xs text-muted font-medium mb-4">{t("lockWarning")}</p>

                        <div className="grid gap-4 sm:grid-cols-2 items-end">
                            <div>
                                <div className={label}>{t("booksLockedThrough")}</div>
                                <div className="text-sm font-semibold text-foreground tabular-nums py-2">
                                    {settings?.booksLockedThrough ?? "—"}
                                </div>
                            </div>
                            <div>
                                <label className={label} htmlFor="fiscal-lock-through">{t("lockThrough")}</label>
                                <div className="flex items-center gap-3">
                                    <input
                                        id="fiscal-lock-through"
                                        type="date"
                                        className={field}
                                        value={lockThrough}
                                        onChange={ev => setLockThrough(ev.target.value)}
                                    />
                                    <button
                                        type="button"
                                        onClick={() => {
                                            setLockError(null);
                                            setConfirmOpen(true);
                                        }}
                                        disabled={!lockThrough}
                                        className="flex items-center gap-1.5 shrink-0 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                    >
                                        <Lock size={13} />
                                        {t("lockThrough")}
                                    </button>
                                </div>
                            </div>
                        </div>

                        {lockError && !confirmOpen && (
                            <p role="alert" className="mt-3 text-xs font-semibold text-error">{lockError}</p>
                        )}
                    </div>
                </div>
            )}

            <BankLocksCard />

            <ConfirmDialog
                isOpen={confirmOpen}
                onClose={() => setConfirmOpen(false)}
                onConfirm={lock}
                isLoading={locking}
                isDestructive
                title={t("lockPeriod")}
                description={t("lockWarning")}
                confirmText={t("lockThrough")}
                cancelText={t("cancel")}
            >
                <div>
                    <div className={label}>{t("lockThrough")}</div>
                    <div className="text-sm font-semibold text-foreground tabular-nums">{lockThrough || "—"}</div>
                </div>
                {lockError && <p role="alert" className="text-xs font-semibold text-error">{lockError}</p>}
            </ConfirmDialog>
        </div>
    );
}
