"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Image from "next/image";
import { Camera, Loader2, X, Check, AlertTriangle, Trash2, Pin } from "lucide-react";
import { useTranslations } from "next-intl";
import { formatCurrency, formatDate } from "@/lib/format";
import { autoMapChequesToSchedules } from "./autoMapChequesToSchedules";
import { useBulkChequeExtract, buildItemsFromFiles, type BulkExtractItem } from "./useBulkChequeExtract";
import DueDateDelta from "@/components/payments/DueDateDelta";

type Schedule = {
  id: string;
  installmentNumber: number;
  dueDate: string;
  amount: string | number;
  status: string; // "PENDING" only enters the dropdown
};

type Props = {
  leaseId: string;
  schedules: Schedule[]; // ALL schedules for the lease, server-fetched
  onSuccess: () => void; // called after successful bulk-attach
  onClose: () => void;
};

type RowState = {
  itemId: string;
  chequeNumber: string;
  bankName: string;
  payerName: string;
  chequeDate: string | null;
  amount: number | null;
  scheduleId: string | null;
  pinned: boolean;
};

type Step = 1 | 2 | 3;

export default function BulkChequeUploadFlow({ leaseId, schedules, onSuccess, onClose }: Props) {
  const t = useTranslations("bulkChequeUpload");
  const inputRef = useRef<HTMLInputElement | null>(null);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const extract = useBulkChequeExtract();
  const [step, setStep] = useState<Step>(1);
  const [rows, setRows] = useState<RowState[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [rejectedCount, setRejectedCount] = useState(0);

  const pendingSchedules = useMemo(
    () => schedules.filter(s => s.status === "PENDING").sort((a, b) => a.installmentNumber - b.installmentNumber),
    [schedules]
  );

  // Suppress exhaustive-deps: cleanup runs only on unmount
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => () => extract.reset(), []);

  // Refs to give the a11y effect (mount-once) access to the latest
  // submitting/onClose without re-running and breaking focus restoration.
  const submittingRef = useRef(submitting);
  const onCloseRef = useRef(onClose);
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

  useEffect(() => {
    const hasPending = extract.items.some(it => it.status === "extracting" || it.status === "extracted");
    if (!hasPending) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      // Modern browsers ignore custom messages but show a generic prompt when preventDefault is called.
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [extract.items]);

  const onPick = (files: FileList | null) => {
    if (!files) return;
    const built = buildItemsFromFiles(Array.from(files));
    setRejectedCount(built.rejectedCount);
    extract.setItems(built.items);
  };

  const goExtract = async () => {
    setStep(2);
    const extractedItems = await extract.start(extract.items);
    // Build initial rows from extracted items.
    const initialRows = extractedItems.map<RowState>(it => {
      const ex = it.response?.extracted;
      return {
        itemId: it.id,
        chequeNumber: ex?.chequeNumber ?? "",
        bankName: ex?.bankName ?? "",
        payerName: ex?.payerName ?? "",
        chequeDate: ex?.chequeDate ?? null,
        amount: ex?.amount ?? null,
        scheduleId: null,
        pinned: false,
      };
    });
    const map = autoMapChequesToSchedules(
      initialRows.map(r => ({ id: r.itemId, chequeDate: r.chequeDate, pinned: r.pinned, assignedScheduleId: r.scheduleId })),
      pendingSchedules.map(s => ({ id: s.id, dueDate: s.dueDate }))
    );
    setRows(initialRows.map(r => ({ ...r, scheduleId: map.get(r.itemId) ?? null })));
    setStep(3);
  };

  const updateRow = (itemId: string, patch: Partial<RowState>) => {
    setRows(prev => {
      const next = prev.map(r => (r.itemId === itemId ? { ...r, ...patch } : r));
      // If this was a non-pin date change, re-run auto-map for non-pinned rows.
      if ("chequeDate" in patch && !next.find(r => r.itemId === itemId)?.pinned) {
        const map = autoMapChequesToSchedules(
          next.map(r => ({ id: r.itemId, chequeDate: r.chequeDate, pinned: r.pinned, assignedScheduleId: r.scheduleId })),
          pendingSchedules.map(s => ({ id: s.id, dueDate: s.dueDate }))
        );
        return next.map(r => (r.pinned ? r : { ...r, scheduleId: map.get(r.itemId) ?? null }));
      }
      return next;
    });
  };

  const pickSchedule = (itemId: string, scheduleId: string | null) => {
    setRows(prev => prev.map(r => (r.itemId === itemId ? { ...r, scheduleId, pinned: scheduleId !== null } : r)));
  };

  const togglePin = (itemId: string) => {
    setRows(prev => prev.map(r => (r.itemId === itemId ? { ...r, pinned: !r.pinned } : r)));
  };

  const removeRow = (itemId: string) => {
    extract.removeItem(itemId);
    setRows(prev => prev.filter(r => r.itemId !== itemId));
  };

  const counts = useMemo(() => {
    let needsDate = 0;
    let noSchedule = 0;
    let needsBank = 0;
    let duplicateNumber = 0;
    const numberSeen = new Map<string, number>();
    for (const r of rows) {
      if (!r.chequeDate) needsDate++;
      if (!r.scheduleId) noSchedule++;
      if (!r.bankName.trim()) needsBank++;
      if (r.chequeNumber.trim()) numberSeen.set(r.chequeNumber.trim(), (numberSeen.get(r.chequeNumber.trim()) ?? 0) + 1);
    }
    for (const v of numberSeen.values()) if (v > 1) duplicateNumber += v;
    const ready = rows.length - needsDate - noSchedule - needsBank - duplicateNumber;
    return { needsDate, noSchedule, needsBank, duplicateNumber, ready };
  }, [rows]);

  const canApprove =
    rows.length > 0 &&
    counts.needsDate === 0 &&
    counts.noSchedule === 0 &&
    counts.needsBank === 0 &&
    counts.duplicateNumber === 0 &&
    rows.every(r => {
      if (!r.chequeNumber.trim()) return false;
      const item = extract.items.find(it => it.id === r.itemId);
      return item?.response?.image != null;
    });

  const approve = async () => {
    setSubmitting(true);
    setSubmitError(null);
    try {
      const items = rows.map(r => {
        const ex = extract.items.find(it => it.id === r.itemId);
        return {
          scheduleId: r.scheduleId,
          chequeNumber: r.chequeNumber.trim(),
          chequeDate: r.chequeDate,
          bankName: r.bankName.trim(),
          payerName: r.payerName.trim() || null,
          imageUrl: ex?.response?.image.url,
          imageBlobPath: ex?.response?.image.blobPath,
          imageUploadedAt: ex?.response?.image.uploadedAt,
        };
      });
      const res = await fetch(`/api/proxy/v1/leases/${leaseId}/cheques/bulk-attach`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ items }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({})) as { rows?: unknown[] };
        if (Array.isArray(body.rows)) {
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
                        <Image src={it.previewUrl} alt="" width={120} height={80} unoptimized className="h-20 w-full rounded object-cover" />
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
                  {rows.map(row => {
                    const item = extract.items.find(it => it.id === row.itemId);
                    if (!item) return null;
                    const sched = pendingSchedules.find(s => s.id === row.scheduleId) ?? null;
                    const usedSchedIds = new Set(rows.filter(r => r.itemId !== row.itemId && r.scheduleId).map(r => r.scheduleId));
                    return (
                      <tr key={row.itemId} className="border-t border-border align-top">
                        <td className="py-1 pr-2">
                          <a href={item.response?.image.url ?? "#"} target="_blank" rel="noreferrer">
                            <Image src={item.previewUrl} alt="" width={64} height={48} unoptimized className="h-12 w-16 rounded object-cover" />
                          </a>
                          {item.status === "failed" && (
                            <p className="mt-1 text-[10px] text-red-700">
                              <AlertTriangle size={10} className="inline" /> {t("extractionFailed")}
                            </p>
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
                          {sched && row.amount != null && Math.round(row.amount * 100) !== Math.round(Number(sched.amount) * 100) && (
                            <span className="ml-2 inline-flex items-center gap-1 rounded-full border border-amber-300 bg-amber-50 px-2 py-0.5 text-[11px] text-amber-800">
                              {t("chequeMismatch", { cheque: formatCurrency(row.amount), installment: formatCurrency(Number(sched.amount)) })}
                            </span>
                          )}
                        </td>
                        <td className="pr-2">
                          <select
                            value={row.scheduleId ?? ""}
                            onChange={e => pickSchedule(row.itemId, e.target.value || null)}
                            className="rounded border border-border px-1 py-0.5"
                          >
                            <option value="">{t("pickInstallment")}</option>
                            {pendingSchedules
                              .filter(s => !usedSchedIds.has(s.id) || s.id === row.scheduleId)
                              .map(s => (
                                <option key={s.id} value={s.id}>
                                  #{s.installmentNumber} · {formatDate(s.dueDate)}
                                </option>
                              ))}
                          </select>
                        </td>
                        <td className="pr-2">
                          {sched && row.chequeDate && (
                            <DueDateDelta dueDate={sched.dueDate} chequeDate={row.chequeDate} />
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
                    );
                  })}
                </tbody>
              </table>

              <div className="flex items-center justify-between text-xs">
                <p className="text-muted">
                  {t("statusCounts", {
                    total: rows.length,
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
                  {t("approveAll", { ready: counts.ready, total: rows.length })}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
