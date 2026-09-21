"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { Link, useRouter } from "@/i18n/routing";
import { ArrowLeft, Ban, Loader2, ShieldCheck } from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { fmtAmount, ledgerApi } from "@/lib/api/ledger";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import ChequeReturnTable from "@/components/leases/ChequeReturnTable";
import { clampIso, fmtIsoDate, isoDayAfter, maxIso, todayIso } from "@/components/leases/leaseMath";
import {
    defaultDecisions,
    receivableAfterForSplit,
    splitIds,
    unclearedRows,
    type ChequeDecisions,
} from "@/components/leases/terminationMath";
import { ApiError, leaseApi, terminationApi, type LeaseDetail, type TerminationPreview } from "@/lib/api/leasing";

/**
 * Ending a contract on a date (spec §9.1).
 *
 * The page is a priced decision, not a form: every figure on it comes from
 * `GET /{id}/terminate/preview`, which is computed by the same code that
 * performs the termination, and the confirm posts **the date the preview was
 * drawn for** rather than whatever the picker currently reads. A screen that
 * posted the picker's value would let a user change the date, not wait for the
 * re-price, and confirm figures that were never true.
 *
 * Three gates, each mirroring a Java rule:
 *
 *  - status: `LeaseTerminationService.TERMINABLE` (:83) — ACTIVE or
 *    NOTICE_GIVEN, nothing else;
 *  - date: inside `[startDate, endDate]` and after `books_locked_through`
 *    (`validate`, :327-364);
 *  - role: previewing is SA/TA/ACCOUNTANT/PM, terminating is SA/TA/ACCOUNTANT
 *    (`LeaseController` :273-274 vs :287-288).
 */

const TERMINABLE: LeaseDetail["status"][] = ["ACTIVE", "NOTICE_GIVEN"];

function Figure({ label, value, hint, testId, tone }: {
    label: string;
    value: string;
    hint?: string;
    testId?: string;
    tone?: "default" | "warning";
}) {
    return (
        <div className="bg-surface border border-border rounded-xl px-4 py-3">
            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">{label}</p>
            <p
                data-testid={testId}
                className={cn(
                    "text-lg font-bold tabular-nums mt-1",
                    tone === "warning" ? "text-warning" : "text-foreground",
                )}
            >
                {value}
            </p>
            {hint && <p className="text-[10px] text-muted mt-1">{hint}</p>}
        </div>
    );
}

export default function TerminateLeasePage() {
    const params = useParams();
    const router = useRouter();
    const locale = useLocale();
    const leaseId = params.id as string;

    const t = useTranslations("Termination");
    const tLeasing = useTranslations("Leasing");
    const tLedger = useTranslations("Ledger");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    const canPreview = hasPermission(userRole, "canPreviewTermination");
    const canTerminate = hasPermission(userRole, "canTerminateLeases");

    const [lease, setLease] = useState<LeaseDetail | null>(null);
    const [lockedThrough, setLockedThrough] = useState<string | null>(null);
    const [date, setDate] = useState<string | null>(null);
    const [preview, setPreview] = useState<TerminationPreview | null>(null);
    const [decisions, setDecisions] = useState<ChequeDecisions>({});
    const [notes, setNotes] = useState("");
    const [loading, setLoading] = useState(true);
    const [pricing, setPricing] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [previewError, setPreviewError] = useState<string | null>(null);
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [submitError, setSubmitError] = useState<string | null>(null);

    const loadLease = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            const [detail, fiscal] = await Promise.all([
                leaseApi.get(leaseId),
                // A failed fiscal read must not block the page: the server
                // refuses a locked date anyway, this only narrows the picker.
                ledgerApi.fiscal.get().catch(() => null),
            ]);
            setLease(detail);
            setLockedThrough(fiscal?.booksLockedThrough ?? null);
        } catch (e) {
            setLoadError(e instanceof ApiError ? e.message : t("previewFailed"));
        } finally {
            setLoading(false);
        }
    }, [leaseId, t]);

    useEffect(() => {
        if (!canPreview && userRole) {
            setLoading(false);
            return;
        }
        loadLease();
    }, [canPreview, userRole, loadLease]);

    const terminable = !!lease && TERMINABLE.includes(lease.status);

    /** `validate`'s bounds, as the picker's own. */
    const minDate = lease ? maxIso(lease.startDate, isoDayAfter(lockedThrough)) : undefined;
    const maxDate = lease?.endDate;

    // Today, pulled inside the term — the date a move-out is nearly always on.
    useEffect(() => {
        if (!lease || !terminable || date !== null) return;
        setDate(clampIso(todayIso(), minDate, maxDate));
    }, [lease, terminable, date, minDate, maxDate]);

    const price = useCallback(
        async (on: string) => {
            setPricing(true);
            setPreviewError(null);
            try {
                const p = await terminationApi.preview(leaseId, on);
                setPreview(p);
                setDecisions(defaultDecisions(p));
            } catch (e) {
                setPreview(null);
                setPreviewError(e instanceof ApiError ? e.message : t("previewFailed"));
            } finally {
                setPricing(false);
            }
        },
        [leaseId, t],
    );

    useEffect(() => {
        if (!terminable || !date) return;
        price(date);
    }, [terminable, date, price]);

    const rows = useMemo(() => (preview ? unclearedRows(preview) : []), [preview]);
    const receivableAfter = preview ? receivableAfterForSplit(preview, decisions) : 0;
    /** Absent on an older server, and zero on every residential tenancy. */
    const unearnedVat = preview?.unearnedVat ?? 0;
    const returnedCount = rows.filter(c => decisions[c.id] === "RETURN").length;

    const submit = async () => {
        if (!preview) return;
        setSubmitting(true);
        setSubmitError(null);
        try {
            const { returnChequeIds, keepChequeIds } = splitIds(preview, decisions);
            await terminationApi.terminate(leaseId, {
                // The preview's own date, not the picker's: these are the
                // figures the user is confirming.
                terminationDate: preview.terminationDate,
                returnChequeIds,
                keepChequeIds,
                notes: notes.trim() || null,
            });
            setConfirmOpen(false);
            // Straight on to the deposit: §9.2 is drawn from the receivable
            // §9.1 just left behind.
            router.push(`/dashboard/leases/${leaseId}/settlement`);
        } catch (e) {
            setSubmitError(e instanceof ApiError ? e.message : t("terminateFailed"));
        } finally {
            setSubmitting(false);
        }
    };

    if (userRole && !canPreview) {
        return (
            <div className="max-w-3xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted" data-testid="terminate-access-denied">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    if (loading) {
        return (
            <div className="flex justify-center py-24">
                <Loader2 size={20} className="animate-spin text-muted" />
            </div>
        );
    }

    return (
        <div className="max-w-5xl space-y-6">
            <div className="flex items-center gap-3">
                <Link
                    href={`/dashboard/leases/${leaseId}`}
                    className="p-2 rounded-lg hover:bg-input transition-colors text-muted hover:text-foreground"
                    data-testid="terminate-back"
                    aria-label={t("backToLease")}
                >
                    <ArrowLeft size={18} />
                </Link>
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-0.5">{t("title")}</h1>
                    <p className="text-xs text-muted">{t("desc")}</p>
                </div>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={loadLease} />}

            {lease && !terminable && (
                <div
                    role="alert"
                    data-testid="terminate-not-terminable"
                    className="bg-warning/10 border border-warning/30 text-warning rounded-xl px-5 py-3 text-sm"
                >
                    {/* The label, never the Java enum — in Arabic the raw token
                        was the only Latin text in the sentence. */}
                    {t("notTerminable", { status: tLeasing(`leaseStatus.${lease.status}`) })}
                </div>
            )}

            {lease && terminable && (
                <>
                    <div className="bg-surface border border-border rounded-xl px-5 py-4 flex flex-wrap items-end gap-4">
                        <label className="flex flex-col gap-1">
                            <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                                {t("terminationDate")}
                            </span>
                            <input
                                type="date"
                                data-testid="terminate-date"
                                value={date ?? ""}
                                min={minDate}
                                max={maxDate}
                                onChange={e => setDate(e.target.value)}
                                className="border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                            />
                            <span className="text-[10px] text-muted">
                                {t("dateHint", {
                                    start: fmtIsoDate(lease.startDate, locale),
                                    end: fmtIsoDate(lease.endDate, locale),
                                })}
                            </span>
                        </label>
                        {lockedThrough && (
                            <p className="text-[10px] text-muted">
                                {tLedger("booksLockedThrough")}: {fmtIsoDate(lockedThrough, locale)}
                            </p>
                        )}
                        {pricing && <Loader2 size={14} className="animate-spin text-muted mb-2" />}
                    </div>

                    {previewError && (
                        <div
                            role="alert"
                            data-testid="terminate-preview-error"
                            className="bg-error/10 border border-error/30 text-error rounded-xl px-5 py-3 text-sm"
                        >
                            {previewError}
                        </div>
                    )}

                    {preview && (
                        <>
                            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                                <Figure
                                    label={t("earnedThroughDate")}
                                    value={fmtAmount(preview.earnedRentThroughDate)}
                                    testId="terminate-earned"
                                />
                                <Figure
                                    label={t("recognisedSoFar")}
                                    value={fmtAmount(preview.recognisedSoFar)}
                                    testId="terminate-recognised"
                                />
                                <Figure
                                    label={t("unearnedRent")}
                                    value={fmtAmount(preview.unearnedRent)}
                                    testId="terminate-unearned"
                                />
                                {/*
                                  Only when there is any. It is zero on every
                                  residential tenancy, and a permanent 0.00 card
                                  beside four real figures reads as an omission
                                  rather than as a fact. `receivableAfter`
                                  already nets it off, so this is a disclosure,
                                  not a term of the arithmetic.
                                */}
                                {unearnedVat > 0 && (
                                    <Figure
                                        label={t("unearnedVat")}
                                        value={fmtAmount(unearnedVat)}
                                        hint={t("unearnedVatHint")}
                                        testId="terminate-unearned-vat"
                                    />
                                )}
                                <Figure
                                    label={t("receivableAfter")}
                                    value={fmtAmount(receivableAfter)}
                                    hint={t("receivableAfterHint")}
                                    testId="terminate-receivable-after"
                                    tone={receivableAfter > 0 ? "warning" : "default"}
                                />
                            </div>

                            <ChequeReturnTable
                                rows={rows}
                                bounced={preview.bouncedOutstanding}
                                decisions={decisions}
                                disabled={!canTerminate}
                                onChange={(id, decision) => setDecisions(prev => ({ ...prev, [id]: decision }))}
                            />

                            {canTerminate ? (
                                <div className="space-y-3">
                                    <label className="flex flex-col gap-1">
                                        <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                                            {t("notes")}
                                        </span>
                                        <textarea
                                            data-testid="terminate-notes"
                                            value={notes}
                                            rows={3}
                                            placeholder={t("notesPlaceholder")}
                                            onChange={e => setNotes(e.target.value)}
                                            className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none"
                                        />
                                    </label>

                                    {submitError && (
                                        <p className="text-xs text-error text-start" data-testid="terminate-error">
                                            {submitError}
                                        </p>
                                    )}

                                    <div className="flex justify-end">
                                        <button
                                            type="button"
                                            data-testid="terminate-submit"
                                            disabled={pricing || submitting}
                                            onClick={() => setConfirmOpen(true)}
                                            className="flex items-center gap-2 bg-error text-white px-4 py-2 rounded-lg text-xs font-semibold hover:bg-error/90 transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                                        >
                                            <Ban size={14} /> {t("terminate")}
                                        </button>
                                    </div>
                                </div>
                            ) : (
                                <p
                                    data-testid="terminate-preview-only"
                                    className="rounded-xl bg-info/10 border border-info/30 px-4 py-2.5 text-xs text-info"
                                >
                                    {t("previewOnly")}
                                </p>
                            )}
                        </>
                    )}
                </>
            )}

            <ConfirmDialog
                isOpen={confirmOpen}
                onClose={() => setConfirmOpen(false)}
                onConfirm={submit}
                isDestructive
                isLoading={submitting}
                title={t("confirmTitle", { date: fmtIsoDate(preview?.terminationDate, locale) })}
                description={t("confirmBody", { returned: returnedCount })}
                confirmText={t("terminate")}
                cancelText={tLedger("cancel")}
                confirmTestId="terminate-confirm"
            />
        </div>
    );
}
