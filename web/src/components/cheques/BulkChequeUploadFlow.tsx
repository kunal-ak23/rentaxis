"use client";

import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import Image from "next/image";
import { Camera, Loader2, X, Check, AlertTriangle, Trash2, Pin, RotateCw, FileText } from "lucide-react";
import { useTranslations } from "next-intl";
import { formatCurrency, formatDate } from "@/lib/format";
import { autoMapChequesToRows } from "./autoMapChequesToRows";
import { useBulkChequeExtract, buildItemsFromFiles, type BulkExtractItem } from "./useBulkChequeExtract";
import DueDateDelta from "./DueDateDelta";
import type { Cheque } from "@/lib/api/leasing";
import { useUnsavedChangesWarning } from "@/hooks/useUnsavedChangesWarning";
import { CROP_UNRELIABLE, type ChequeMultiExtractionResponse, type DetectedChequeItem } from "@/types/cheque";

/**
 * Bulk-attach scanned cheque images onto a lease's own register rows.
 *
 * Accounting-v2 replaced payment schedules with the lease's cheque grid
 * (`GET /leases/{id}/cheques`), and `POST /leases/{id}/cheques/bulk-attach`
 * targets a cheque row by id (`chequeId`, aliased from the old `scheduleId`
 * on the wire — see `BulkAttachChequeItem`). Only a REGISTERED, PDC-mode row
 * has a cheque number to fill in; a DRAFT row belongs to the editable grid,
 * and CASH/TRANSFER/ONLINE rows have no physical instrument to scan.
 *
 * One uploaded file can hold several cheques (a photo of a few laid side by
 * side, or a PDF of scans): `POST /cheques/extract-many` answers with one item
 * per cheque, each with its own server-issued crop, and the file expands into
 * one review row per item. Auto-map and bulk-attach then work per row, so each
 * cheque gets its own image.
 */

type Props = {
  leaseId: string;
  rows: Cheque[]; // ALL cheques for the lease, server-fetched
  onSuccess: () => void; // called after successful bulk-attach
  onClose: () => void;
};

type RowState = {
  /** Row key: `${fileId}#${index}` — one row per cheque found in a file. */
  itemId: string;
  /** The uploaded file (extract item) this cheque was found in. */
  fileId: string;
  /** Index into that file's `response.items`. */
  detIndex: number;
  chequeNumber: string;
  bankName: string;
  payerName: string;
  chequeDate: string | null;
  amount: number | null;
  rowId: string | null;
  pinned: boolean;
  /**
   * A crop the server flagged (`crop_unreliable`) is the whole page, not one
   * cheque; the operator must confirm they checked it before it is attached.
   */
  cropConfirmed: boolean;
  /** The operator ticked "attach anyway" for a payee that matches none of the valid names. */
  payeeConfirmed: boolean;
};

type Step = 1 | 2 | 3;

/**
 * The date a scan of this row's cheque should carry: the cheque's own maturity
 * date. `postingDate` is the contract/posting date in accounting v2 — identical
 * on every row a wizard creates — so it is only a fallback for a row with no
 * cheque date yet.
 */
const rowChequeDate = (c: Cheque): string => c.chequeDate ?? c.postingDate;

/** Refusal codes the server sends for an upload; each has a translated message. */
const KNOWN_UPLOAD_ERRORS = new Set([
  "cheque_upload_file_required",
  "cheque_upload_image_too_large",
  "cheque_upload_file_too_large",
  "cheque_upload_unsupported_type",
  "cheque_upload_too_many_pages",
  "cheque_upload_too_many_cheques",
  "cheque_upload_pdf_unreadable",
  "cheque_upload_pdf_not_supported",
  "cheque_upload_busy",
]);

/** One review row per cheque the file holds; a failed file still gets one row to fill in or remove. */
function rowsForFile(fileId: string, response: ChequeMultiExtractionResponse | null): RowState[] {
  const found = response?.items.length ? response.items : [null];
  return found.map<RowState>((det, i) => {
    const ex = det?.extracted;
    return {
      itemId: `${fileId}#${i}`,
      fileId,
      detIndex: i,
      chequeNumber: ex?.chequeNumber ?? "",
      bankName: ex?.bankName ?? "",
      payerName: ex?.payerName ?? "",
      chequeDate: ex?.chequeDate ?? null,
      amount: ex?.amount ?? null,
      rowId: null,
      pinned: false,
      cropConfirmed: false,
      payeeConfirmed: false,
    };
  });
}

function detectionOf(file: BulkExtractItem | undefined, row: RowState): DetectedChequeItem | null {
  return file?.response?.items[row.detIndex] ?? null;
}

export default function BulkChequeUploadFlow({ leaseId, rows, onSuccess, onClose }: Props) {
  const t = useTranslations("bulkChequeUpload");
  const inputRef = useRef<HTMLInputElement | null>(null);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const extract = useBulkChequeExtract();
  const [step, setStep] = useState<Step>(1);
  const [tableRows, setTableRows] = useState<RowState[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [rejectedCount, setRejectedCount] = useState(0);
  // The row whose crop is open full-size, next to its original page.
  const [previewKey, setPreviewKey] = useState<string | null>(null);

  // Only a REGISTERED, PDC row has a cheque number to attach a scan to.
  const eligibleRows = useMemo(
    () => rows.filter(c => c.status === "REGISTERED" && c.mode === "PDC").sort((a, b) => a.seqNo - b.seqNo),
    [rows],
  );

  // Suppress exhaustive-deps: cleanup runs only on unmount
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => () => extract.reset(), []);

  // Refs to give the a11y effect (mount-once) access to the latest
  // submitting/onClose without re-running and breaking focus restoration.
  const submittingRef = useRef(submitting);
  const onCloseRef = useRef(onClose);
  const previewOpenRef = useRef(false);
  useEffect(() => { previewOpenRef.current = previewKey !== null; }, [previewKey]);
  useEffect(() => { submittingRef.current = submitting; }, [submitting]);
  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);

  // a11y: Escape to close, focus restoration, Tab focus trap. Runs once
  // on mount — re-running on submit would clobber previouslyFocused and
  // cause a focus jump mid-submit.
  useEffect(() => {
    const previouslyFocused = (typeof document !== "undefined" ? document.activeElement : null) as HTMLElement | null;
    const focusables = () =>
      dialogRef.current
        ? Array.from(
            dialogRef.current.querySelectorAll<HTMLElement>(
              'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
            )
          )
        : [];

    // Focus the first focusable on open.
    queueMicrotask(() => focusables()[0]?.focus());

    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && previewOpenRef.current) {
        // An open crop preview closes first; the flow stays.
        e.preventDefault();
        setPreviewKey(null);
        return;
      }
      if (e.key === "Escape" && !submittingRef.current) {
        e.preventDefault();
        onCloseRef.current();
        return;
      }
      if (e.key === "Tab") {
        const items = focusables();
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        const active = document.activeElement as HTMLElement | null;
        // If focus has escaped the trap entirely (e.g. Approve button got
        // disabled mid-submit and was removed from focusables, but is still
        // activeElement), redirect into the dialog instead of letting the
        // browser advance past it.
        if (!active || !dialogRef.current?.contains(active) || !items.includes(active)) {
          e.preventDefault();
          (e.shiftKey ? last : first).focus();
          return;
        }
        if (e.shiftKey && active === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && active === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      previouslyFocused?.focus?.();
    };
    // Mount-once: latest submitting/onClose read via refs above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Shared with the opening-balance grid; see useUnsavedChangesWarning.
  useUnsavedChangesWarning(
    extract.items.some(it => it.status === "extracting" || it.status === "extracted"),
  );

  const onPick = (files: FileList | null) => {
    if (!files) return;
    const built = buildItemsFromFiles(Array.from(files));
    setRejectedCount(built.rejectedCount);
    extract.setItems(built.items);
  };

  const remap = (candidates: RowState[]) =>
    autoMapChequesToRows(
      candidates.map(r => ({ id: r.itemId, chequeDate: r.chequeDate, amount: r.amount, pinned: r.pinned, assignedRowId: r.rowId })),
      eligibleRows.map(c => ({ id: c.id, dueDate: rowChequeDate(c), amount: c.amount })),
    );

  const goExtract = async () => {
    setStep(2);
    const extractedItems = await extract.start(extract.items);
    // One row per cheque found: a file holding three cheques becomes three rows.
    const initialRows = extractedItems.flatMap(it => rowsForFile(it.id, it.response));
    const map = remap(initialRows);
    setTableRows(initialRows.map(r => ({ ...r, rowId: map.get(r.itemId) ?? null })));
    setStep(3);
  };

  const updateRow = (itemId: string, patch: Partial<RowState>) => {
    setTableRows(prev => {
      const next = prev.map(r => (r.itemId === itemId ? { ...r, ...patch } : r));
      // If this was a non-pin date change, re-run auto-map for non-pinned rows.
      if ("chequeDate" in patch && !next.find(r => r.itemId === itemId)?.pinned) {
        const map = remap(next);
        return next.map(r => (r.pinned ? r : { ...r, rowId: map.get(r.itemId) ?? null }));
      }
      return next;
    });
  };

  // Re-extract a failed file. Its one placeholder row becomes one row per
  // cheque found. The first new row keeps anything the operator typed while
  // the file was failed (each field is filled only while still blank, and a
  // pinned assignment stays); newly-read dates re-run the auto-map for every
  // unpinned row, exactly as a typed date would.
  const retryRow = async (itemId: string) => {
    const fileId = tableRows.find(r => r.itemId === itemId)?.fileId;
    if (!fileId) return;
    const response = await extract.retry(fileId);
    if (!response) return;
    setTableRows(prev => {
      const old = prev.filter(r => r.fileId === fileId);
      const typed = old[0];
      const fresh = rowsForFile(fileId, response).map((r, i) => {
        if (i !== 0 || !typed) return r;
        return {
          ...r,
          // A new read is a new payee check: any earlier confirmation was about the old one.
          payeeConfirmed: false,
          chequeNumber: typed.chequeNumber.trim() ? typed.chequeNumber : r.chequeNumber,
          bankName: typed.bankName.trim() ? typed.bankName : r.bankName,
          payerName: typed.payerName.trim() ? typed.payerName : r.payerName,
          chequeDate: typed.chequeDate ?? r.chequeDate,
          amount: typed.amount ?? r.amount,
          rowId: typed.rowId,
          pinned: typed.pinned,
        };
      });
      const at = prev.findIndex(r => r.fileId === fileId);
      const next = [...prev.slice(0, at), ...fresh, ...prev.slice(at).filter(r => r.fileId !== fileId)];
      const map = remap(next);
      return next.map(r => (r.pinned ? r : { ...r, rowId: map.get(r.itemId) ?? null }));
    });
  };

  const pickRow = (itemId: string, rowId: string | null) => {
    setTableRows(prev => prev.map(r => (r.itemId === itemId ? { ...r, rowId, pinned: rowId !== null } : r)));
  };

  const togglePin = (itemId: string) => {
    setTableRows(prev => prev.map(r => (r.itemId === itemId ? { ...r, pinned: !r.pinned } : r)));
  };

  // Removes one cheque's row; the file goes only when its last row does.
  const removeRow = (itemId: string) => {
    const fileId = tableRows.find(r => r.itemId === itemId)?.fileId;
    if (fileId && tableRows.filter(r => r.fileId === fileId).length === 1) {
      extract.removeItem(fileId);
    }
    setTableRows(prev => prev.filter(r => r.itemId !== itemId));
  };

  const counts = useMemo(() => {
    let needsDate = 0;
    let noSchedule = 0;
    let needsBank = 0;
    let duplicateNumber = 0;
    const numberSeen = new Map<string, number>();
    for (const r of tableRows) {
      if (!r.chequeDate) needsDate++;
      if (!r.rowId) noSchedule++;
      if (!r.bankName.trim()) needsBank++;
      if (r.chequeNumber.trim()) numberSeen.set(r.chequeNumber.trim(), (numberSeen.get(r.chequeNumber.trim()) ?? 0) + 1);
    }
    for (const v of numberSeen.values()) if (v > 1) duplicateNumber += v;
    // A row is "ready" only if it individually passes every check. The bucket
    // counts above OVERLAP (one empty row needs date AND bank AND a target row),
    // so `total - sum(buckets)` over-subtracts and can even go negative — count
    // the genuinely-complete rows directly instead.
    let ready = 0;
    for (const r of tableRows) {
      const num = r.chequeNumber.trim();
      if (r.chequeDate && r.rowId && r.bankName.trim() && num && (numberSeen.get(num) ?? 0) === 1) {
        ready++;
      }
    }
    return { needsDate, noSchedule, needsBank, duplicateNumber, ready };
  }, [tableRows]);

  // Owner ruling 2026-09-29: the server checked the read payee against the
  // organisation's valid names at extract time. A mismatch is approved only
  // once the operator confirms it; the server refuses it otherwise.
  // Per detected cheque: a photo holding three cheques has three payees.
  const payeeCheckOf = (r: RowState) => detectionOf(extract.items.find(it => it.id === r.fileId), r)?.payeeCheck ?? null;
  const unconfirmedPayee = tableRows.some(r => payeeCheckOf(r) === "MISMATCH" && !r.payeeConfirmed);

  const canApprove =
    tableRows.length > 0 &&
    !unconfirmedPayee &&
    counts.needsDate === 0 &&
    counts.noSchedule === 0 &&
    counts.needsBank === 0 &&
    counts.duplicateNumber === 0 &&
    tableRows.every(r => {
      if (!r.chequeNumber.trim()) return false;
      const det = detectionOf(extract.items.find(it => it.id === r.fileId), r);
      if (det?.flags.includes(CROP_UNRELIABLE) && !r.cropConfirmed) return false;
      return det?.image != null;
    });

  const approve = async () => {
    setSubmitting(true);
    setSubmitError(null);
    try {
      // Each row carries its own cheque's image: a crop when its file held several.
      const items = tableRows.map(r => {
        const det = detectionOf(extract.items.find(it => it.id === r.fileId), r);
        return {
          chequeId: r.rowId,
          chequeNumber: r.chequeNumber.trim(),
          chequeDate: r.chequeDate,
          bankName: r.bankName.trim(),
          payerName: r.payerName.trim() || null,
          imageUrl: det?.image.url,
          imageBlobPath: det?.image.blobPath,
          imageUploadedAt: det?.image.uploadedAt,
          payeeMismatchConfirmed: det?.payeeCheck === "MISMATCH" ? r.payeeConfirmed : undefined,
        };
      });
      const res = await fetch(`/api/proxy/v1/leases/${leaseId}/cheques/bulk-attach`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ items }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({})) as { rows?: { reason?: string }[] };
        if (Array.isArray(body.rows) && body.rows.some(r => r?.reason === "payee_mismatch_unconfirmed")) {
          // Review m4: the server refused a payee mismatch nobody confirmed (a
          // stale screen, or another tab changed the valid names).
          setSubmitError(t("payeeMismatchUnconfirmedError"));
        } else if (Array.isArray(body.rows)) {
          setSubmitError(t("rowConflictError"));
        } else {
          setSubmitError(t("genericError"));
        }
        return;
      }
      onSuccess();
    } catch {
      setSubmitError(t("networkError"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4">
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="bulk-cheque-upload-title"
        className="w-full max-w-6xl rounded-xl border border-border bg-background shadow-lg"
      >
        <div className="flex items-start justify-between border-b border-border px-5 py-4">
          <div>
            <p className="text-[11px] uppercase tracking-wider text-muted">{t("breadcrumb")}</p>
            <h3 id="bulk-cheque-upload-title" className="text-base font-semibold">{t("title")}</h3>
          </div>
          <button type="button" onClick={onClose} className="rounded p-1 text-muted hover:bg-input/40">
            <X size={16} />
          </button>
        </div>

        {/* webkitdirectory is a non-standard HTML attribute — cast via data attribute approach won't work,
            so we suppress the TS error on the non-standard prop */}
        <input
          ref={inputRef}
          type="file"
          // eslint-disable-next-line @typescript-eslint/ban-ts-comment
          // @ts-expect-error webkitdirectory is non-standard but widely supported
          webkitdirectory="true"
          multiple
          // Images only while server-side PDF input is off (rentaxis.cheques.pdf-upload.enabled).
          accept="image/*"
          className="hidden"
          onChange={(e) => onPick(e.target.files)}
        />

        <div className="px-5 py-4">
          {step === 1 && (
            <div className="grid gap-4">
              <button
                type="button"
                onClick={() => inputRef.current?.click()}
                className="flex min-h-[200px] flex-col items-center justify-center rounded-lg border border-dashed border-border bg-input/20 px-6 py-8 text-center hover:border-primary/60"
              >
                <Camera size={20} className="mb-2 text-muted" />
                <p className="text-sm font-medium">{t("pickFolder")}</p>
                <p className="mt-1 text-xs text-muted">{t("pickHint")}</p>
              </button>
              {extract.items.length > 0 && (
                <>
                  <p className="text-xs text-muted">
                    {t("selectedCount", { n: extract.items.length })}
                    {rejectedCount > 0 ? ` · ${t("rejectedCount", { n: rejectedCount })}` : ""}
                  </p>
                  <div className="grid grid-cols-4 gap-2 sm:grid-cols-6">
                    {extract.items.map(it => (
                      <div key={it.id} className="relative">
                        {it.file.type === "application/pdf" ? (
                          <div className="flex h-20 w-full flex-col items-center justify-center gap-1 rounded bg-input/40 px-1 text-[10px] text-muted">
                            <FileText size={16} />
                            <span className="w-full truncate text-center" dir="auto">{it.file.name}</span>
                          </div>
                        ) : (
                          <Image src={it.previewUrl} alt="" width={120} height={80} unoptimized className="h-20 w-full rounded object-cover" />
                        )}
                        <button
                          type="button"
                          aria-label={t("removeImage")}
                          onClick={() => extract.removeItem(it.id)}
                          className="absolute right-1 top-1 rounded-full bg-black/60 p-1 text-white"
                        >
                          <Trash2 size={10} />
                        </button>
                      </div>
                    ))}
                  </div>
                  <div className="flex justify-end">
                    <button
                      type="button"
                      onClick={() => void goExtract()}
                      className="rounded bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground"
                    >
                      {t("continueToExtract")}
                    </button>
                  </div>
                </>
              )}
            </div>
          )}

          {step === 2 && (
            <div className="flex flex-col items-center gap-3 py-10">
              <Loader2 size={20} className="animate-spin text-primary" />
              <p className="text-sm">{t("extractingCount", {
                done: extract.items.filter(it => it.status === "extracted" || it.status === "failed").length,
                total: extract.items.length
              })}</p>
            </div>
          )}

          {step === 3 && (
            <div className="space-y-3">
              <table className="w-full text-xs">
                <thead className="text-left text-muted">
                  <tr>
                    <th className="py-1">{t("colImage")}</th>
                    <th>{t("colChequeNumber")}</th>
                    <th>{t("colBank")}</th>
                    <th>{t("colPayer")}</th>
                    <th>{t("colChequeDate")}</th>
                    <th>{t("colAmount")}</th>
                    <th>{t("colInstallment")}</th>
                    <th>&#916;</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {tableRows.map(row => {
                    const item = extract.items.find(it => it.id === row.fileId);
                    if (!item) return null;
                    const det = detectionOf(item, row);
                    const thumb = det?.thumbnailUrl ?? (item.file.type === "application/pdf" ? null : item.previewUrl);
                    const flagged = det?.flags.includes(CROP_UNRELIABLE) ?? false;
                    const pageCount = item.response?.pages.length ?? 0;
                    const target = eligibleRows.find(s => s.id === row.rowId) ?? null;
                    const usedRowIds = new Set(tableRows.filter(r => r.itemId !== row.itemId && r.rowId).map(r => r.rowId));
                    const payeeCheck = det?.payeeCheck ?? null;
                    const payeeRead = det?.extracted?.payeeName ?? "";
                    return (
                      <Fragment key={row.itemId}>
                      <tr className="border-t border-border align-top">
                        <td className="py-1 pr-2">
                          <button
                            type="button"
                            onClick={() => setPreviewKey(row.itemId)}
                            aria-label={t("viewCrop")}
                            className="block rounded focus:outline-none focus:ring-2 focus:ring-primary"
                          >
                            {thumb ? (
                              <Image src={thumb} alt="" width={64} height={48} unoptimized className="h-12 w-16 rounded object-cover" data-testid="cheque-crop-thumb" />
                            ) : (
                              <span className="flex h-12 w-16 items-center justify-center rounded bg-input/40 text-muted">
                                <FileText size={14} />
                              </span>
                            )}
                          </button>
                          {pageCount > 1 && det && (
                            <p className="mt-1 text-[10px] text-muted">{t("pageLabel", { n: det.page })}</p>
                          )}
                          {flagged && (
                            <>
                              <p className="mt-1 max-w-[9rem] text-[10px] text-amber-800" role="note">
                                <AlertTriangle size={10} className="inline" /> {t("cropUnreliable")}
                              </p>
                              <label className="mt-1 flex max-w-[9rem] items-start gap-1 text-[10px]">
                                <input
                                  type="checkbox"
                                  checked={row.cropConfirmed}
                                  onChange={e => updateRow(row.itemId, { cropConfirmed: e.target.checked })}
                                  className="mt-0.5"
                                />
                                <span>{t("confirmCrop")}</span>
                              </label>
                            </>
                          )}
                          {item.status === "failed" && (
                            <p className="mt-1 text-[10px] text-red-700">
                              <AlertTriangle size={10} className="inline" />{" "}
                              {item.errorCode && KNOWN_UPLOAD_ERRORS.has(item.errorCode)
                                ? t(`uploadErrors.${item.errorCode}`)
                                : t("extractionFailed")}
                            </p>
                          )}
                          {(item.status === "failed" || item.status === "extracting") && (
                            <button
                              type="button"
                              onClick={() => void retryRow(row.itemId)}
                              disabled={item.status === "extracting"}
                              className="mt-1 inline-flex items-center gap-1 rounded border border-border px-1.5 py-0.5 text-[10px] font-medium hover:bg-input/40 disabled:opacity-50"
                            >
                              {item.status === "extracting" ? (
                                <>
                                  <Loader2 size={10} className="animate-spin" /> {t("retryingExtraction")}
                                </>
                              ) : (
                                <>
                                  <RotateCw size={10} /> {t("retryExtraction")}
                                </>
                              )}
                            </button>
                          )}
                        </td>
                        <td className="pr-2">
                          <input
                            value={row.chequeNumber}
                            onChange={e => updateRow(row.itemId, { chequeNumber: e.target.value })}
                            className="w-28 rounded border border-border px-1 py-0.5"
                          />
                        </td>
                        <td className="pr-2">
                          <input
                            value={row.bankName}
                            onChange={e => updateRow(row.itemId, { bankName: e.target.value })}
                            aria-invalid={!row.bankName.trim()}
                            className={
                              "w-32 rounded border px-1 py-0.5 " +
                              (row.bankName.trim() ? "border-border" : "border-red-500")
                            }
                          />
                        </td>
                        <td className="pr-2">
                          <input
                            value={row.payerName}
                            onChange={e => updateRow(row.itemId, { payerName: e.target.value })}
                            className="w-32 rounded border border-border px-1 py-0.5"
                          />
                        </td>
                        <td className="pr-2">
                          <input
                            type="date"
                            value={row.chequeDate ?? ""}
                            onChange={e => updateRow(row.itemId, { chequeDate: e.target.value || null })}
                            className="rounded border border-border px-1 py-0.5"
                          />
                        </td>
                        <td className="pr-2">
                          <span className="tabular-nums">{row.amount != null ? formatCurrency(row.amount) : "—"}</span>
                          {target && row.amount != null && Math.round(row.amount * 100) !== Math.round(Number(target.amount) * 100) && (
                            <span className="ml-2 inline-flex items-center gap-1 rounded-full border border-amber-300 bg-amber-50 px-2 py-0.5 text-[11px] text-amber-800">
                              {t("chequeMismatch", { cheque: formatCurrency(row.amount), installment: formatCurrency(Number(target.amount)) })}
                            </span>
                          )}
                        </td>
                        <td className="pr-2">
                          <select
                            value={row.rowId ?? ""}
                            onChange={e => pickRow(row.itemId, e.target.value || null)}
                            className="rounded border border-border px-1 py-0.5"
                          >
                            <option value="">{t("pickInstallment")}</option>
                            {eligibleRows
                              .filter(s => !usedRowIds.has(s.id) || s.id === row.rowId)
                              .map(s => (
                                <option key={s.id} value={s.id}>
                                  #{s.seqNo} · {formatDate(rowChequeDate(s))}
                                </option>
                              ))}
                          </select>
                        </td>
                        <td className="pr-2">
                          {target && row.chequeDate && (
                            <DueDateDelta dueDate={rowChequeDate(target)} chequeDate={row.chequeDate} />
                          )}
                        </td>
                        <td className="pr-2 text-right">
                          <button
                            type="button"
                            onClick={() => togglePin(row.itemId)}
                            aria-label={t("pinRow")}
                            className={"mr-1 rounded p-1 " + (row.pinned ? "text-primary" : "text-muted")}
                          >
                            <Pin size={12} />
                          </button>
                          <button
                            type="button"
                            onClick={() => removeRow(row.itemId)}
                            aria-label={t("removeRow")}
                            className="rounded p-1 text-muted hover:bg-input/40"
                          >
                            <Trash2 size={12} />
                          </button>
                        </td>
                      </tr>
                      {payeeCheck === "MISMATCH" && (
                        <tr>
                          <td colSpan={10} className="pb-2">
                            <div
                              data-testid="payee-mismatch"
                              role="alert"
                              className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded border border-red-300 bg-red-50 px-2 py-1 text-[11px] text-red-700"
                            >
                              <span className="font-semibold">
                                <AlertTriangle size={11} className="inline" /> {t("payeeMismatch")}:{" "}
                                <bdi>{payeeRead}</bdi>
                              </span>
                              <label className="inline-flex items-center gap-1.5 text-foreground">
                                <input
                                  type="checkbox"
                                  checked={row.payeeConfirmed}
                                  onChange={e => updateRow(row.itemId, { payeeConfirmed: e.target.checked })}
                                />
                                {t("payeeMismatchConfirm")}
                              </label>
                            </div>
                          </td>
                        </tr>
                      )}
                      {payeeCheck === "UNREADABLE" && (
                        <tr>
                          <td colSpan={10} className="pb-2">
                            <p data-testid="payee-unreadable" className="text-[11px] text-muted">
                              {t("payeeUnreadable")}
                            </p>
                          </td>
                        </tr>
                      )}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>

              <div className="flex items-center justify-between text-xs">
                <p className="text-muted">
                  {t("statusCounts", {
                    total: tableRows.length,
                    needsDate: counts.needsDate,
                    noSchedule: counts.noSchedule,
                    needsBank: counts.needsBank,
                    duplicate: counts.duplicateNumber,
                  })}
                </p>
                {submitError && <span className="text-red-700">{submitError}</span>}
                <button
                  type="button"
                  disabled={!canApprove || submitting}
                  onClick={() => void approve()}
                  className="rounded bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground disabled:opacity-50"
                >
                  {submitting ? <Loader2 size={12} className="inline animate-spin" /> : <Check size={12} className="inline" />}{" "}
                  {t("approveAll", { ready: counts.ready, total: tableRows.length })}
                </button>
              </div>
            </div>
          )}
        </div>
      {previewKey && (() => {
        const row = tableRows.find(r => r.itemId === previewKey);
        const file = row ? extract.items.find(it => it.id === row.fileId) : undefined;
        const det = row ? detectionOf(file, row) : null;
        if (!row || !file) return null;
        const pagePreview = file.response?.pages.find(p => p.page === (det?.page ?? 1))?.previewUrl
          ?? (file.file.type === "application/pdf" ? null : file.previewUrl);
        const crop = det?.thumbnailUrl ?? null;
        return (
          <div
            className="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 p-4"
            role="dialog"
            aria-modal="true"
            aria-label={t("viewCrop")}
            onClick={() => setPreviewKey(null)}
          >
            <div className="max-h-full w-full max-w-5xl overflow-auto rounded-lg bg-background p-4" onClick={e => e.stopPropagation()}>
              <div className="mb-3 flex items-center justify-between">
                <p className="text-sm font-semibold">{t("viewCrop")}</p>
                <button type="button" onClick={() => setPreviewKey(null)} aria-label={t("closePreview")} className="rounded p-1 text-muted hover:bg-input/40">
                  <X size={16} />
                </button>
              </div>
              {det?.flags.includes(CROP_UNRELIABLE) && (
                <p className="mb-3 text-xs text-amber-800" role="note">
                  <AlertTriangle size={12} className="inline" /> {t("cropUnreliable")}
                </p>
              )}
              <div className="grid gap-4 md:grid-cols-2">
                <figure>
                  <figcaption className="mb-1 text-xs text-muted">{t("cropLabel")}</figcaption>
                  {crop ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={crop} alt="" className="w-full rounded border border-border" />
                  ) : (
                    <p className="text-xs text-muted">{t("noPreview")}</p>
                  )}
                </figure>
                <figure>
                  <figcaption className="mb-1 text-xs text-muted">{t("originalLabel")}</figcaption>
                  {pagePreview ? (
                    // The box is in image coordinates, so it is placed with physical
                    // left/top whatever the page direction: the photo is never mirrored.
                    <div className="relative" dir="ltr">
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={pagePreview} alt="" className="block w-full rounded border border-border" />
                      {det?.box && (
                        <div
                          className="pointer-events-none absolute border-2 border-primary"
                          style={{
                            left: `${det.box.x * 100}%`,
                            top: `${det.box.y * 100}%`,
                            width: `${det.box.width * 100}%`,
                            height: `${det.box.height * 100}%`,
                          }}
                        />
                      )}
                    </div>
                  ) : (
                    <p className="text-xs text-muted">{t("noPreview")}</p>
                  )}
                </figure>
              </div>
            </div>
          </div>
        );
      })()}
      </div>
    </div>
  );
}
