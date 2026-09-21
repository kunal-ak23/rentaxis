"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle2, Download, Info, Layers, Loader2, ShieldCheck, Undo2, Upload } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { Pagination } from "@/components/ui/Pagination";
import { fmtIsoDate, todayIso } from "@/components/leases/leaseMath";
import { ApiError } from "@/lib/api/facilities";
import { Link } from "@/i18n/routing";
import { cutoverApi, type ImportBatch, type ImportBatchStatus } from "@/lib/api/cutover";
import {
    CONTRACT_IMPORT_ACCEPT, canDownloadImportTemplate, canReverseBatch, contractImportRefusal, isBatchFinal,
} from "@/lib/cutoverRules";
import { useImportJobPolling } from "@/hooks/useImportJobPolling";
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

/** Twenty-five problems is a screenful; the rest are a page away, never dropped. */
const ERRORS_PER_PAGE = 25;

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
    const [uploadError, setUploadError] = useState<string | null>(null);
    const [errorPage, setErrorPage] = useState(0);
    const importJob = useImportJobPolling();

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

    /**
     * A finished import wrote a new DRAFT batch, so the table has to be re-read —
     * the row it created is the whole point of the upload. Keyed on the batch id
     * so it runs once per import rather than once per poll.
     */
    const importedBatchId = importJob.job?.importBatchId ?? null;
    useEffect(() => {
        if (!importedBatchId) return;
        load();
    }, [importedBatchId, load]);

    const onWorkbook = (file: File) => {
        const refused = contractImportRefusal(file);
        if (refused) {
            // Checked here rather than after a 10MB upload that can only fail.
            setUploadError(t(refused));
            return;
        }
        setUploadError(null);
        setErrorPage(0);
        cutoverApi.contractImport
            .upload(file)
            .then(({ jobId }) => importJob.start(jobId))
            .catch(e => setUploadError(e instanceof ApiError ? e.message : tCommon("loadFailed")));
    };

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
                    <div className="flex flex-wrap items-center gap-3">
                        {/*
                         * A plain <a>, not next/link: this is a file download from
                         * the API proxy, not a route. next/link would client-side
                         * navigate to a path with no page behind it. No `download`
                         * attribute either — the controller sends
                         * `Content-Disposition: attachment; filename=contract-import-template.xlsx`,
                         * and `download` would override that with the URL's last
                         * segment, saving the file as "template".
                         *
                         * The CUT-OVER template, not the v1 one: different route,
                         * different role gate, and this is the workbook this page
                         * accepts.
                         */}
                        <a
                            href={cutoverApi.contractImport.templateUrl()}
                            data-testid="download-template"
                            className="border border-border px-4 py-2 rounded-lg text-xs font-bold flex items-center gap-2 cursor-pointer text-foreground"
                        >
                            <Download size={14} />
                            {t("downloadCutoverTemplate")}
                        </a>
                        <label className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-bold flex items-center gap-2 cursor-pointer">
                            {importJob.polling ? <Loader2 size={14} className="animate-spin" /> : <Upload size={14} />}
                            {t("uploadCutoverWorkbook")}
                            <input
                                type="file"
                                data-testid="upload-cutover"
                                aria-label={t("uploadCutoverWorkbook")}
                                className="hidden"
                                accept={CONTRACT_IMPORT_ACCEPT}
                                disabled={importJob.polling}
                                onChange={e => {
                                    const f = e.target.files?.[0];
                                    if (f) onWorkbook(f);
                                }}
                            />
                        </label>
                    </div>
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

            {uploadError && (
                <p role="alert" data-testid="import-upload-error" className="mb-4 text-xs font-semibold text-error">
                    {uploadError}
                </p>
            )}

            {importJob.error && (
                <p role="alert" data-testid="import-job-error" className="mb-4 text-xs font-semibold text-error">
                    {t("importJobLost")}
                </p>
            )}

            {importJob.timedOut && (
                <p data-testid="import-timed-out" className="mb-4 text-xs font-semibold text-warning">
                    {t("importTimedOut")}
                </p>
            )}

            {importJob.job && (
                <div
                    data-testid="import-job-status"
                    data-status={importJob.job.status}
                    className="mb-6 bg-surface border border-border rounded-xl shadow-sm p-5"
                >
                    {(importJob.job.status === "VALIDATING" || importJob.job.status === "PERSISTING") && (
                        <p className="text-xs font-semibold text-muted flex items-center gap-2">
                            <Loader2 size={14} className="animate-spin" />
                            {importJob.job.status === "VALIDATING" ? t("importRunning") : t("importPersisting")}
                        </p>
                    )}

                    {importJob.job.status === "COMPLETED" && (
                        <div className="space-y-2">
                            <p
                                data-testid="import-success"
                                className="text-xs font-semibold text-success flex items-center gap-2"
                            >
                                <CheckCircle2 size={14} className="shrink-0" />
                                {t("importCompleted")}
                            </p>
                            <p data-testid="import-counts" className="text-xs text-muted tabular-nums">
                                {t("importCounts", {
                                    properties: importJob.job.propertiesCreated,
                                    units: importJob.job.unitsCreated,
                                    renters: importJob.job.rentersCreated,
                                    leases: importJob.job.leasesCreated,
                                    cheques: importJob.job.chequesCreated,
                                })}
                            </p>
                            {importJob.job.importBatchId && (
                                <Link
                                    href={`/dashboard/finance/import-batches#${importJob.job.importBatchId}`}
                                    data-testid="import-view-batch"
                                    className="text-xs font-semibold text-primary hover:underline cursor-pointer"
                                >
                                    {t("viewImportedBatch")}
                                </Link>
                            )}
                        </div>
                    )}

                    {importJob.job.status === "FAILED" && (
                        <p
                            data-testid="import-failed"
                            className="text-xs font-semibold text-error flex items-center gap-2"
                        >
                            <AlertTriangle size={14} className="shrink-0" />
                            {t("importFailed")}
                        </p>
                    )}

                    {/*
                     * ContractImportPersistService writes the whole workbook in one
                     * transaction, so a validation failure leaves NOTHING behind.
                     * Said out loud, or the accountant goes hunting for
                     * half-imported properties that do not exist.
                     */}
                    {importJob.job.status === "VALIDATION_FAILED" && (
                        <p
                            data-testid="import-validation-failed"
                            className="text-xs font-semibold text-error flex items-start gap-2"
                        >
                            <AlertTriangle size={14} className="shrink-0 mt-0.5" />
                            {t("importValidationFailed")}
                        </p>
                    )}

                    {importJob.job.errors.length > 0 && (
                        <div className="mt-4">
                            <p data-testid="import-errors-title" className="text-xs font-bold text-foreground mb-2">
                                {t("importErrorsTitle", { n: importJob.job.errors.length })}
                            </p>
                            <div className="overflow-x-auto border border-border rounded-lg">
                                <table className="w-full" data-testid="import-errors-table">
                                    <thead className="bg-input/60 border-b border-border">
                                        <tr>
                                            <th className={th}>{t("sheet")}</th>
                                            <th className={`${th} text-end`}>{t("row")}</th>
                                            <th className={th}>{t("column")}</th>
                                            <th className={th}>{t("problem")}</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-border">
                                        {importJob.job.errors
                                            .slice(errorPage * ERRORS_PER_PAGE, errorPage * ERRORS_PER_PAGE + ERRORS_PER_PAGE)
                                            .map((err, i) => {
                                                const index = errorPage * ERRORS_PER_PAGE + i;
                                                return (
                                                    <tr key={index} data-testid={`import-error-${index}`}>
                                                        <td className={td}>{err.sheet}</td>
                                                        <td className={`${td} text-end tabular-nums`}>{err.row || "—"}</td>
                                                        <td className={`${td} font-mono text-muted`}>{err.field || "—"}</td>
                                                        <td className={td}>{err.message}</td>
                                                    </tr>
                                                );
                                            })}
                                    </tbody>
                                </table>
                            </div>
                            {importJob.job.errors.length > ERRORS_PER_PAGE && (
                                <Pagination
                                    currentPage={errorPage + 1}
                                    totalItems={importJob.job.errors.length}
                                    itemsPerPage={ERRORS_PER_PAGE}
                                    onPageChange={p => setErrorPage(p - 1)}
                                />
                            )}
                        </div>
                    )}

                    {!importJob.polling && (
                        <button
                            type="button"
                            data-testid="import-dismiss"
                            onClick={importJob.reset}
                            className="mt-4 text-xs font-semibold text-muted hover:text-foreground cursor-pointer"
                        >
                            {t("dismissImportResult")}
                        </button>
                    )}
                </div>
            )}

            {/* The implementer's notes, where they are needed: before the upload. */}
            {!importJob.job && (
                <div
                    data-testid="cutover-help"
                    className="mb-6 bg-input border border-border text-muted rounded-xl px-5 py-4 text-xs"
                >
                    <p className="font-bold text-foreground mb-2 flex items-center gap-2">
                        <Info size={14} className="shrink-0" />
                        {t("cutoverHelpTitle")}
                    </p>
                    <ul className="list-disc ms-5 space-y-1">
                        <li>{t("cutoverHelpNewProperties")}</li>
                        <li>{t("cutoverHelpAccounts")}</li>
                        <li>{t("cutoverHelpCreditAccount")}</li>
                        <li>{t("cutoverHelpEjari")}</li>
                    </ul>
                </div>
            )}

            {/*
             * Task 11 has not landed: there is no bulk-post route, so a DRAFT batch
             * has no post action. Said plainly rather than leaving a gap where a
             * button obviously belongs.
             */}
            <p data-testid="bulk-post-unavailable" className="mb-4 text-xs text-muted">
                {t("bulkPostNotAvailable")}
            </p>

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
                                        data-imported={b.id === importedBatchId ? "true" : "false"}
                                        className={
                                            b.id === importedBatchId
                                                ? "bg-success/5 ring-1 ring-inset ring-success/30"
                                                : "hover:bg-input/30 transition-colors"
                                        }
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
