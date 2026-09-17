"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { ArrowLeft, Loader2, Plus, ShieldCheck, Trash2 } from "lucide-react";
import Link from "next/link";
import AccountPicker from "@/components/finance/AccountPicker";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount, ledgerApi, type ManualJournalBody } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";

const pad = (n: number) => String(n).padStart(2, "0");
const today = () => {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const th = "text-start px-3 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider";

type DraftLine = {
    /** Stable across inserts/removals so React does not re-key the whole grid. */
    key: number;
    accountId: string | null;
    debit: string;
    credit: string;
    narration: string;
};

const blankLine = (key: number): DraftLine => ({ key, accountId: null, debit: "", credit: "", narration: "" });

/**
 * Manual journal voucher.
 *
 * Navigation goes through `next/navigation` and `next/link` with the locale
 * prefix written out, rather than the wrappers in `@/i18n/routing`: next-intl's
 * client navigation module is ESM inside node_modules that Vitest cannot resolve
 * from a test file, so importing it here would make this page untestable. Every
 * other page keeps the wrappers.
 */
export default function NewJournalPage() {
    const t = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const router = useRouter();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canPostJournals");

    const properties = useNameLookup("properties", allowed);

    const [entryDate, setEntryDate] = useState(today);
    const [narration, setNarration] = useState("");
    const [propertyId, setPropertyId] = useState("");
    // Double entry needs at least two sides, so the grid opens with the two rows
    // the smallest possible voucher uses.
    const [lines, setLines] = useState<DraftLine[]>([blankLine(0), blankLine(1)]);
    const [nextKey, setNextKey] = useState(2);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const patch = (key: number, next: Partial<DraftLine>) =>
        setLines(prev => prev.map(l => (l.key === key ? { ...l, ...next } : l)));

    const addLine = () => {
        setLines(prev => [...prev, blankLine(nextKey)]);
        setNextKey(k => k + 1);
    };

    const removeLine = (key: number) => setLines(prev => (prev.length <= 2 ? prev : prev.filter(l => l.key !== key)));

    const totals = useMemo(
        () =>
            lines.reduce(
                (a, l) => ({ dr: a.dr + (Number(l.debit) || 0), cr: a.cr + (Number(l.credit) || 0) }),
                { dr: 0, cr: 0 },
            ),
        [lines],
    );
    const diff = totals.dr - totals.cr;
    const outOfBalance = Math.abs(diff) >= 0.005;
    const balanced = !outOfBalance && totals.dr > 0;
    // Exactly one side per line: `!==` on two booleans is XOR.
    const complete = lines.every(l => l.accountId && (Number(l.debit) > 0) !== (Number(l.credit) > 0));
    const canPost = balanced && complete && lines.length >= 2 && !submitting;

    const submit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        if (!canPost) return;
        setSubmitting(true);
        setError(null);
        try {
            const body: ManualJournalBody = {
                entryDate,
                narration,
                ...(propertyId ? { propertyId } : {}),
                lines: lines.map(l => ({
                    accountId: l.accountId as string,
                    debit: Number(l.debit) || 0,
                    credit: Number(l.credit) || 0,
                    ...(l.narration ? { narration: l.narration } : {}),
                })),
            };
            const entry = await ledgerApi.journals.postManual(body);
            // The detail page owns the "posted" banner: it already has the entry
            // number, and the confirmation survives the navigation this way.
            router.push(`/${locale}/dashboard/finance/journals/${entry.id}?posted=1`);
        } catch (err) {
            setError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
            setSubmitting(false);
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
        <div className="max-w-5xl">
            <Link
                href={`/${locale}/dashboard/finance/journals`}
                className="inline-flex items-center gap-1.5 text-xs font-semibold text-muted hover:text-foreground mb-4 cursor-pointer"
            >
                <ArrowLeft size={13} className="rtl:rotate-180" />
                {t("journals")}
            </Link>

            <h1 className="text-xl font-bold text-foreground tracking-tight mb-8">{t("newJournal")}</h1>

            {error && (
                <div role="alert" className="mb-6 flex items-center gap-2 bg-error/10 border border-error/30 text-error rounded-xl px-5 py-3">
                    <span className="text-sm font-medium">{error}</span>
                </div>
            )}

            <form onSubmit={submit}>
                <div className="bg-surface border border-border rounded-xl shadow-sm p-5 mb-5 grid gap-4 sm:grid-cols-3">
                    <div>
                        <label className={label} htmlFor="jv-date">{t("docDate")}</label>
                        <input
                            id="jv-date"
                            type="date"
                            className={field}
                            value={entryDate}
                            onChange={ev => setEntryDate(ev.target.value)}
                            required
                        />
                    </div>
                    <div>
                        <label className={label} htmlFor="jv-property">{t("propertyFilter")}</label>
                        <select
                            id="jv-property"
                            className={field}
                            value={propertyId}
                            onChange={ev => setPropertyId(ev.target.value)}
                        >
                            <option value="">{t("selectProperty")}</option>
                            {properties.options.map(p => (
                                <option key={p.id} value={p.id}>{p.label}</option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className={label} htmlFor="jv-narration">{t("narration")}</label>
                        <input
                            id="jv-narration"
                            type="text"
                            className={field}
                            value={narration}
                            onChange={ev => setNarration(ev.target.value)}
                        />
                    </div>
                </div>

                <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden mb-5">
                    <table className="w-full">
                        <thead className="bg-input/60 border-b border-border">
                            <tr>
                                <th className={`${th} w-[34%]`}>{t("account")}</th>
                                <th className={`${th} text-end w-[15%]`}>{t("debit")}</th>
                                <th className={`${th} text-end w-[15%]`}>{t("credit")}</th>
                                <th className={th}>{t("narration")}</th>
                                <th className="w-10" />
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {lines.map(l => (
                                <tr key={l.key}>
                                    <td className="px-3 py-2 align-top">
                                        <AccountPicker
                                            value={l.accountId}
                                            onChange={id => patch(l.key, { accountId: id })}
                                            propertyId={propertyId || null}
                                            placeholder={t("account")}
                                        />
                                    </td>
                                    <td className="px-3 py-2 align-top">
                                        <input
                                            type="number"
                                            step="0.01"
                                            min="0"
                                            aria-label={t("debit")}
                                            className={`${field} text-end tabular-nums`}
                                            value={l.debit}
                                            onChange={ev => patch(l.key, { debit: ev.target.value })}
                                        />
                                    </td>
                                    <td className="px-3 py-2 align-top">
                                        <input
                                            type="number"
                                            step="0.01"
                                            min="0"
                                            aria-label={t("credit")}
                                            className={`${field} text-end tabular-nums`}
                                            value={l.credit}
                                            onChange={ev => patch(l.key, { credit: ev.target.value })}
                                        />
                                    </td>
                                    <td className="px-3 py-2 align-top">
                                        <input
                                            type="text"
                                            aria-label={t("narration")}
                                            className={field}
                                            value={l.narration}
                                            onChange={ev => patch(l.key, { narration: ev.target.value })}
                                        />
                                    </td>
                                    <td className="px-3 py-2 align-top">
                                        <button
                                            type="button"
                                            onClick={() => removeLine(l.key)}
                                            disabled={lines.length <= 2}
                                            aria-label={t("removeLine")}
                                            className="p-1.5 rounded-lg text-muted hover:text-error hover:bg-error/10 cursor-pointer disabled:opacity-30 disabled:cursor-not-allowed focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                        >
                                            <Trash2 size={14} />
                                        </button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                        <tfoot className="bg-input/60 border-t border-border">
                            <tr>
                                <td className="px-3 py-2.5">
                                    <button
                                        type="button"
                                        onClick={addLine}
                                        className="inline-flex items-center gap-1.5 text-xs font-bold text-primary cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-lg px-1 py-0.5"
                                    >
                                        <Plus size={13} />
                                        {t("addLine")}
                                    </button>
                                </td>
                                <td className="px-3 py-2.5 text-end text-xs font-bold tabular-nums">{fmtAmount(totals.dr)}</td>
                                <td className="px-3 py-2.5 text-end text-xs font-bold tabular-nums">{fmtAmount(totals.cr)}</td>
                                <td className="px-3 py-2.5 text-xs text-muted" colSpan={2}>
                                    {t("totalDebit")} / {t("totalCredit")}
                                    {outOfBalance && <span className="ms-2 text-error font-semibold tabular-nums">{fmtAmount(Math.abs(diff))}</span>}
                                </td>
                            </tr>
                        </tfoot>
                    </table>
                </div>

                <div className="flex items-center justify-between gap-4">
                    <p className={`text-xs font-semibold ${outOfBalance ? "text-error" : "text-transparent"}`}>
                        {outOfBalance ? t("unbalanced") : ""}
                    </p>
                    <button
                        type="submit"
                        disabled={!canPost}
                        className="flex items-center gap-2 px-5 py-2.5 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    >
                        {submitting && <Loader2 size={14} className="animate-spin" />}
                        {t("postJournal")}
                    </button>
                </div>
            </form>
        </div>
    );
}
