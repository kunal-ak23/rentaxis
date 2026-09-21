"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { Download, Layers, ShieldCheck, Undo2 } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { Pagination } from "@/components/ui/Pagination";
import { fmtIsoDate, todayIso } from "@/components/leases/leaseMath";
import { ApiError } from "@/lib/api/facilities";
import { cutoverApi, type ImportBatch, type ImportBatchStatus } from "@/lib/api/cutover";
import { canDownloadImportTemplate, canReverseBatch, isBatchFinal } from "@/lib/cutoverRules";
import { hasPermission, type UserRole } from "@/lib/rbac";

/**
 * The cut-over Import Batches screen (spec §10.3, §11): what has been imported,
 * and the one button that takes a whole run back off the books.
 *
 * **Three things the screen owes the person reading it.**
 *
 * Reverse appears on a POSTED batch and nowhere else, mirroring
 * `ImportBatchService.reverse` — a DRAFT has written no journals and a REVERSED
 * one has already been taken back. The confirmation says what will happen in
 * numbers, because "reverse the batch" is thirty-six journals and twelve
 * contracts, not one row.
 *
 * And REVERSED is stated as terminal, in the row and in the dialog. There is no
 * re-post endpoint and there is not meant to be one: a corrected spreadsheet
 * comes back as a NEW batch. A screen that left that unsaid would have people
 * hunting for a button that does not exist.
 *
 * **What is deliberately absent.** The brief described a bulk-post action; no
 * such endpoint exists in `ImportBatchController`, so there is no button for it.
 * The reversal date carries no period-lock gate either — `PostingService.reverse`
 * exempts batch journals from `assertOpen`, and a cut-over is loaded into periods
 * that are normally closed, so gating here would refuse what the server allows.
 * Both are recorded in `lib/cutoverRules.ts`.
 */

const th = "text-start px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-5 py-3 text-xs text-foreground";
const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const fieldLabel = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

const STATUS_CLASS: Record<ImportBatchStatus, string> = {
    DRAFT: "bg-input text-muted border-border",
    POSTED: "bg-success/10 text-success border-success/30",
    REVERSED: "bg-warning/10 text-warning border-warning/30",
};

export default function ImportBatchesPage() {
    const t = useTranslations("Cutover");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageImportBatches");

    const [rows, setRows] = useState<ImportBatch[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [banner, setBanner] = useState<string | null>(null);
    const [pending, setPending] = useState<ImportBatch | null>(null);
    const [reverseDate, setReverseDate] = useState(todayIso);
    const [reason, setReason] = useState("");
    const [reversing, setReversing] = useState(false);
    const [reverseError, setReverseError] = useState<string | null>(null);
    const [page, setPage] = useState(0);
    const [size, setSize] = useState(25);

    const load = useCallback(() => {
        setLoading(true);
        setLoadError(null);
        // No sort sent: ImportBatchService.list is findAllByOrderByCreatedAtAsc,
        // so the batches already read oldest-first — the order they happened in,
        // which is the order a cut-over is reasoned about.
        return cutoverApi.batches
            .list()
            .then(setRows)
            .catch(e => setLoadError(e instanceof ApiError ? e.message : tCommon("loadFailed")))
            .finally(() => setLoading(false));
    }, [tCommon]);

    useEffect(() => {
        // Nothing until NextAuth has answered: fetching with no role yet means
        // fetching again when it arrives, and a PROPERTY_MANAGER means a 403 this
        // page already knows it would get.
        if (!userRole) return;
        if (!allowed) {
            setLoading(false);
            return;
        }
        load();
    }, [userRole, allowed, load]);

    if (!userRole) {
        return <div data-testid="import-batches-loading" className="bg-input rounded-xl h-14 animate-pulse" />;
    }

    if (!allowed) {
        return (
            <div className="max-w-4xl" data-testid="import-batches-access-denied">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("notAllowed")}</p>
                </div>
            </div>
        );
    }

    const openReverse = (b: ImportBatch) => {
        setPending(b);
        setReverseDate(todayIso());
        setReason("");
        setReverseError(null);
        setBanner(null);
    };

    const confirmReverse = async () => {
        if (!pending) return;
        setReversing(true);
        setReverseError(null);
        try {
            await cutoverApi.batches.reverse(pending.id, { date: reverseDate, reason });
            setPending(null);
            setBanner(t("batchReversed"));
            // Reloaded rather than patched in place: the status the row shows
            // should be the one the server holds, not this screen's guess at it.
            await load();
        } catch (e) {
            setReverseError(e instanceof ApiError ? e.message : tCommon("loadFailed"));
        } finally {
            setReversing(false);
        }
    };

    const visible = rows.slice(page * size, page * size + size);

    return (
        <div>
            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <div className="flex flex-wrap items-start justify-between gap-4 mb-8">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t("importBatches")}</h1>
                    <p className="text-sm text-muted max-w-2xl">{t("importBatchesDesc")}</p>
                </div>
                {/*
                 * One role narrower than the page: PortfolioImportController#template
                 * is SA/TA and would 403 an accountant on click.
                 */}
                {canDownloadImportTemplate(userRole) && (
                    /*
                     * A plain <a>, not next/link: this is a file download from the
                     * API proxy, not a route. next/link would client-side navigate
                     * to a path with no page behind it. No `download` attribute
                     * either — PortfolioImportController sends
                     * `Content-Disposition: attachment; filename=portfolio-import-template.xlsx`,
                     * and `download` would override that with the URL's last
                     * segment, saving the file as "template".
                     */
                    // eslint-disable-next-line @next/next/no-html-link-for-pages
                    <a
                        href="/api/proxy/v1/import/portfolio/template"
                        data-testid="download-template"
                        className="border border-border px-4 py-2 rounded-lg text-xs font-bold flex items-center gap-2 cursor-pointer text-foreground"
                    >
                        <Download size={14} />
                        {t("downloadTemplate")}
                    </a>
                )}
            </div>

            {banner && (
                <div
                    role="status"
                    data-testid="batch-reversed-banner"
                    className="mb-4 bg-success/10 border border-success/30 text-success rounded-xl px-5 py-3 text-xs font-medium"
                >
                    {banner}
                </div>
            )}

            {loading && (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3].map(i => (
                        <div key={i} className="bg-input rounded-xl h-14" />
                    ))}
                </div>
            )}

            {!loading && rows.length === 0 && !loadError && (
                <div
                    data-testid="batches-empty"
                    className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center"
                >
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <Layers size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{t("noBatches")}</h3>
                    <p className="text-xs text-muted font-medium max-w-md">{t("noBatchesDesc")}</p>
                </div>
            )}

            {!loading && rows.length > 0 && (
                <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                    <div className="overflow-x-auto">
                        <table className="w-full" data-testid="batches-table">
                            <thead className="bg-input/60 border-b border-border">
                                <tr>
                                    <th className={th}>{t("batchLabel")}</th>
                                    <th className={th}>{t("kind")}</th>
                                    <th className={th}>{t("imported")}</th>
                                    <th className={`${th} text-end`}>{t("leasesImported")}</th>
                                    <th className={`${th} text-end`}>{t("journalsPosted")}</th>
                                    <th className={th}>{tLedger("status")}</th>
                                    <th className={`${th} text-end`} />
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {visible.map(b => (
                                    <tr
                                        key={b.id}
                                        data-testid={`batch-row-${b.id}`}
                                        className="hover:bg-input/30 transition-colors"
                                    >
                                        <td className={`${td} font-medium`}>{b.label ?? b.id.slice(0, 8)}</td>
                                        <td className={`${td} text-muted`}>{t(b.kind)}</td>
                                        <td className={`${td} tabular-nums`}>{fmtIsoDate(b.createdAt, locale)}</td>
                                        <td className={`${td} text-end tabular-nums`}>{b.leasesImported}</td>
                                        <td className={`${td} text-end tabular-nums`}>{b.journalsPosted}</td>
                                        <td className={td}>
                                            <span
                                                data-testid={`batch-status-${b.id}`}
                                                data-status={b.status}
                                                className={`inline-block px-2 py-0.5 rounded-md border text-[10px] font-bold uppercase tracking-wider ${STATUS_CLASS[b.status]}`}
                                            >
                                                {b.status === "DRAFT"
                                                    ? t("draft")
                                                    : b.status === "POSTED"
                                                      ? tLedger("posted")
                                                      : tLedger("reversed")}
                                            </span>
                                        </td>
                                        <td className={`${td} text-end whitespace-nowrap`}>
                                            {canReverseBatch(b.status) ? (
                                                <button
                                                    type="button"
                                                    data-testid={`reverse-batch-${b.id}`}
                                                    onClick={() => openReverse(b)}
                                                    className="text-error hover:underline cursor-pointer inline-flex items-center gap-1.5 font-semibold"
                                                >
                                                    <Undo2 size={12} />
                                                    {t("reverseBatch")}
                                                </button>
                                            ) : isBatchFinal(b.status) ? (
                                                // Said out loud: there is no re-post, by design.
                                                <span data-testid={`batch-final-${b.id}`} className="text-muted">
                                                    {t("reversedBatchFinal")}
                                                </span>
                                            ) : (
                                                <span className="text-muted">{t("draftNothingToReverse")}</span>
                                            )}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            {!loading && rows.length > 0 && (
                <Pagination
                    currentPage={page + 1}
                    totalItems={rows.length}
                    itemsPerPage={size}
                    onPageChange={p => setPage(p - 1)}
                    onItemsPerPageChange={n => {
                        setSize(n);
                        setPage(0);
                    }}
                />
            )}

            <ConfirmDialog
                isOpen={!!pending}
                onClose={() => setPending(null)}
                onConfirm={confirmReverse}
                isLoading={reversing}
                isDestructive
                title={t("reverseBatch")}
                description={t("confirmReverseBatch", {
                    journals: pending?.journalsPosted ?? 0,
                    leases: pending?.leasesImported ?? 0,
                })}
                confirmText={t("reverseBatch")}
                cancelText={tLedger("cancel")}
                confirmTestId="confirm-reverse-batch"
                // ReverseBatchDTO.date is @NotNull and ImportBatchService.reverse
                // refuses a null one, so an emptied field must not travel.
                confirmDisabled={!reverseDate}
            >
                <p className="text-xs text-muted">{t("reverseBatchHint")}</p>
                <div>
                    <label className={fieldLabel} htmlFor="batch-reverse-date">
                        {tLedger("reverseDate")}
                    </label>
                    {/*
                     * No period-lock gate, deliberately: PostingService.reverse
                     * exempts a journal carrying an importBatchId from assertOpen,
                     * because a cut-over is loaded into periods that are normally
                     * closed and a batch that could not be undone afterwards would
                     * be a one-way door.
                     */}
                    <input
                        id="batch-reverse-date"
                        data-testid="batch-reverse-date"
                        type="date"
                        className={field}
                        value={reverseDate}
                        onChange={e => setReverseDate(e.target.value)}
                    />
                </div>
                <div>
                    <label className={fieldLabel} htmlFor="batch-reverse-reason">
                        {tLedger("reverseReason")}
                    </label>
                    <input
                        id="batch-reverse-reason"
                        data-testid="batch-reverse-reason"
                        className={field}
                        value={reason}
                        onChange={e => setReason(e.target.value)}
                    />
                </div>
                {!reverseDate && (
                    <p data-testid="batch-reverse-blocker" className="text-xs font-semibold text-warning">
                        {t("reverseDateRequired")}
                    </p>
                )}
                {reverseError && (
                    <p role="alert" data-testid="batch-reverse-error" className="text-xs font-semibold text-error">
                        {reverseError}
                    </p>
                )}
            </ConfirmDialog>
        </div>
    );
}
