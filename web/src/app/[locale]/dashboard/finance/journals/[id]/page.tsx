"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { ArrowLeft, CheckCircle, Loader2, Receipt, RotateCcw, ShieldCheck } from "lucide-react";
import { Link, useRouter } from "@/i18n/routing";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { journalStatusClass } from "@/components/finance/journalStatus";
import { ApiError } from "@/lib/api/facilities";
import { accountName, fmtAmount, ledgerApi, type JournalEntry } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";

const pad = (n: number) => String(n).padStart(2, "0");
const today = () => {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

const th = "text-start px-4 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-4 py-2.5 text-xs text-foreground";
const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const fieldLabel = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

/** `useSearchParams` needs a Suspense boundary for `next build` to prerender the route. */
export default function JournalDetailPage() {
    return (
        <Suspense fallback={<div className="flex items-center justify-center h-64"><Loader2 size={24} className="animate-spin text-muted" /></div>}>
            <JournalDetail />
        </Suspense>
    );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <div>
            <div className={fieldLabel}>{label}</div>
            <div className="text-sm font-semibold text-foreground">{children}</div>
        </div>
    );
}

function JournalDetail() {
    const t = useTranslations("Ledger");
    const locale = useLocale();
    const tCommon = useTranslations("Common");
    const tVouchers = useTranslations("Vouchers");
    const params = useParams<{ id: string }>();
    const search = useSearchParams();
    const router = useRouter();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canAccessFinance");
    const canPost = hasPermission(userRole, "canPostJournals");

    const id = params?.id ?? "";
    const units = useNameLookup("units", allowed);
    const renters = useNameLookup("renters", allowed);
    const properties = useNameLookup("properties", allowed);

    const [entry, setEntry] = useState<JournalEntry | null>(null);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [reverseDate, setReverseDate] = useState(today);
    const [reason, setReason] = useState("");
    const [reversing, setReversing] = useState(false);
    const [reverseError, setReverseError] = useState<string | null>(null);
    // The entry carries only the ids of its reversal pair, and the links have to
    // read as document numbers, so the one or two related entries are fetched.
    const [relatedNumbers, setRelatedNumbers] = useState<Record<string, string>>({});
    // Set by the new-JV form's redirect, so the confirmation survives the navigation.
    const justPosted = search.get("posted") === "1";

    const load = useCallback(async () => {
        if (!id) return;
        setLoading(true);
        setLoadError(null);
        try {
            setEntry(await ledgerApi.journals.get(id));
        } catch (err) {
            setEntry(null);
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [id, tCommon]);

    useEffect(() => {
        if (!allowed) {
            setLoading(false);
            return;
        }
        load();
    }, [allowed, load]);

    useEffect(() => {
        const ids = [entry?.reversalOfId, entry?.reversedById].filter((x): x is string => !!x);
        if (ids.length === 0) return;
        let alive = true;
        Promise.all(
            ids.map(rid =>
                ledgerApi.journals
                    .get(rid)
                    .then(e => [rid, e.entryNumber] as const)
                    // A missing or forbidden sibling must not blank the page; the
                    // link still works, it just carries the id.
                    .catch(() => [rid, rid.slice(0, 8)] as const),
            ),
        ).then(pairs => {
            if (alive) setRelatedNumbers(Object.fromEntries(pairs));
        });
        return () => {
            alive = false;
        };
    }, [entry?.reversalOfId, entry?.reversedById]);

    const reverse = async () => {
        if (!entry) return;
        setReversing(true);
        setReverseError(null);
        try {
            const reversal = await ledgerApi.journals.reverse(entry.id, { date: reverseDate, reason });
            setConfirmOpen(false);
            router.push(`/dashboard/finance/journals/${reversal.id}`);
        } catch (err) {
            setReverseError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setReversing(false);
        }
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

    const totalDebit = entry?.lines.reduce((s, l) => s + l.debit, 0) ?? 0;
    const totalCredit = entry?.lines.reduce((s, l) => s + l.credit, 0) ?? 0;
    /**
     * Reverse belongs to a MANUAL journal and to nothing else.
     *
     * `POST /journals/{id}/reverse` refuses an entry that belongs to a document
     * — a voucher, a lease, a cheque, a recognition period, a settlement —
     * because reversing the journal behind a document would leave the document
     * POSTED and its ledger empty, with neither screen saying so. Each of those
     * is corrected where it was created: a voucher through Amend (reverse and
     * re-post as one transaction), a contract through termination, a cheque
     * through cancel.
     *
     * A null `sourceType` is an entry written before the column existed — not
     * manual, and not safe to assume manual.
     */
    const canReverse =
        !!entry
        && entry.status === "POSTED"
        && entry.reversalOfId === null
        && entry.sourceType === "MANUAL"
        && canPost;

    /** The document page that owns a voucher-sourced entry, if it is one. */
    const sourceVoucherHref =
        entry?.sourceType === "VOUCHER" && entry.sourceId
            ? `/dashboard/finance/vouchers/${entry.docType === "BPV" ? "payment" : "purchase-invoice"}?id=${entry.sourceId}`
            : null;

    return (
        <div className="max-w-6xl">
            <Link
                href="/dashboard/finance/journals"
                className="inline-flex items-center gap-1.5 text-xs font-semibold text-muted hover:text-foreground mb-4 cursor-pointer"
            >
                <ArrowLeft size={13} className="rtl:rotate-180" />
                {t("journals")}
            </Link>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    <div className="bg-input rounded-xl h-32" />
                    <div className="bg-input rounded-xl h-48" />
                </div>
            ) : !entry ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Receipt size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{t("noRows")}</h3>
                </div>
            ) : (
                <>
                    {justPosted && (
                        <div role="status" className="mb-6 flex items-center gap-2 bg-success/10 border border-success/30 text-success rounded-xl px-5 py-3">
                            <CheckCircle size={16} className="shrink-0" />
                            <span className="text-sm font-medium">{t("journalPosted", { number: entry.entryNumber })}</span>
                        </div>
                    )}

                    <div className="flex items-start justify-between gap-4 mb-6">
                        <h1 className="text-xl font-bold text-foreground tracking-tight flex items-center gap-2">
                            <Receipt size={20} className="text-primary" />
                            {t("journalDetail")} {entry.entryNumber}
                        </h1>
                        {canReverse && (
                            <button
                                type="button"
                                data-testid="reverse-journal"
                                onClick={() => {
                                    setReverseError(null);
                                    setReverseDate(today());
                                    setReason("");
                                    setConfirmOpen(true);
                                }}
                                className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold cursor-pointer hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none"
                            >
                                <RotateCcw size={13} />
                                {t("reverse")}
                            </button>
                        )}
                    </div>

                    <div className="bg-surface border border-border rounded-xl shadow-sm p-5 mb-6 grid gap-5 sm:grid-cols-3 lg:grid-cols-4">
                        <Field label={t("docNo")}>{entry.entryNumber}</Field>
                        <Field label={t("docDate")}><span className="tabular-nums">{entry.entryDate}</span></Field>
                        <Field label={t("docType")}>{entry.docType}</Field>
                        <Field label={t("status")}>
                            <span className={`inline-block px-2 py-0.5 rounded-md border text-[10px] font-bold uppercase tracking-wider ${journalStatusClass(entry.status)}`}>
                                {entry.status === "POSTED" ? t("posted") : t("reversed")}
                            </span>
                        </Field>
                        <Field label={t("propertyFilter")}>{properties.name(entry.propertyId) || "—"}</Field>
                        <div className="sm:col-span-2 lg:col-span-3">
                            <div className={fieldLabel}>{t("narration")}</div>
                            <div className="text-sm font-medium text-foreground">{entry.narration || "—"}</div>
                        </div>
                    </div>

                    {(entry.sourceType === "LEASE" && entry.sourceId) || sourceVoucherHref || entry.reversalOfId || entry.reversedById ? (
                        <div className="flex flex-wrap items-center gap-4 mb-6 text-xs font-semibold">
                            {entry.sourceType === "LEASE" && entry.sourceId && (
                                <Link href={`/dashboard/leases/${entry.sourceId}`} className="text-primary hover:underline cursor-pointer">
                                    {t("sourceLease")}
                                </Link>
                            )}
                            {/* Where this entry can actually be corrected, now that
                                Reverse no longer offers to do it from here. */}
                            {sourceVoucherHref && (
                                <Link href={sourceVoucherHref} data-testid="source-voucher" className="text-primary hover:underline cursor-pointer">
                                    {tVouchers("sourceVoucher")}
                                </Link>
                            )}
                            {entry.reversalOfId && (
                                <Link href={`/dashboard/finance/journals/${entry.reversalOfId}`} className="text-primary hover:underline cursor-pointer">
                                    {t("reversalOf", { number: relatedNumbers[entry.reversalOfId] ?? "…" })}
                                </Link>
                            )}
                            {entry.reversedById && (
                                <Link href={`/dashboard/finance/journals/${entry.reversedById}`} className="text-primary hover:underline cursor-pointer">
                                    {t("reversedBy", { number: relatedNumbers[entry.reversedById] ?? "…" })}
                                </Link>
                            )}
                        </div>
                    ) : null}

                    <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                        <div className="overflow-x-auto">
                            <table className="w-full">
                                <thead className="bg-input/60 border-b border-border">
                                    <tr>
                                        <th className={`${th} w-12`}>{t("line")}</th>
                                        <th className={th}>{t("account")}</th>
                                        <th className={`${th} text-end`}>{t("debit")}</th>
                                        <th className={`${th} text-end`}>{t("credit")}</th>
                                        <th className={th}>{t("narration")}</th>
                                        <th className={th}>{t("unit")}</th>
                                        <th className={th}>{t("tenant")}</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {entry.lines.map(l => (
                                        <tr key={l.lineNo}>
                                            <td className={`${td} text-muted tabular-nums`}>{l.lineNo}</td>
                                            <td className={td}>
                                                <span className="font-mono text-muted me-2">{l.accountCode}</span>
                                                {accountName(l, locale)}
                                            </td>
                                            <td className={`${td} text-end tabular-nums`}>{l.debit ? fmtAmount(l.debit) : ""}</td>
                                            <td className={`${td} text-end tabular-nums`}>{l.credit ? fmtAmount(l.credit) : ""}</td>
                                            <td className={`${td} text-muted`}>{l.narration}</td>
                                            <td className={`${td} text-muted`}>{units.name(l.unitId)}</td>
                                            <td className={`${td} text-muted`}>{renters.name(l.renterId)}</td>
                                        </tr>
                                    ))}
                                </tbody>
                                <tfoot className="bg-input/60 border-t border-border">
                                    <tr>
                                        {/* The entry total, not a sub-total: this tfoot sums every line. */}
                                        <td className={`${td} font-bold`} colSpan={2}>{t("total")}</td>
                                        <td className={`${td} text-end tabular-nums font-bold`}>{fmtAmount(totalDebit)}</td>
                                        <td className={`${td} text-end tabular-nums font-bold`}>{fmtAmount(totalCredit)}</td>
                                        <td colSpan={3} />
                                    </tr>
                                </tfoot>
                            </table>
                        </div>
                    </div>

                    <ConfirmDialog
                        isOpen={confirmOpen}
                        onClose={() => setConfirmOpen(false)}
                        onConfirm={reverse}
                        isLoading={reversing}
                        title={t("reverse")}
                        description={t("confirmReverse", { number: entry.entryNumber })}
                        confirmText={t("reverse")}
                        cancelText={t("cancel")}
                    >
                        <div>
                            <label className={fieldLabel} htmlFor="jv-reverse-date">{t("reverseDate")}</label>
                            <input
                                id="jv-reverse-date"
                                type="date"
                                className={field}
                                value={reverseDate}
                                onChange={ev => setReverseDate(ev.target.value)}
                            />
                        </div>
                        <div>
                            <label className={fieldLabel} htmlFor="jv-reverse-reason">{t("reverseReason")}</label>
                            <input
                                id="jv-reverse-reason"
                                type="text"
                                className={field}
                                value={reason}
                                onChange={ev => setReason(ev.target.value)}
                            />
                        </div>
                        {reverseError && (
                            <p role="alert" className="text-xs font-semibold text-error">{reverseError}</p>
                        )}
                    </ConfirmDialog>
                </>
            )}
        </div>
    );
}
