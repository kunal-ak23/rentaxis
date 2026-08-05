"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import { X, Loader2, CalendarCheck, Users } from "lucide-react";
import { cn } from "@/lib/utils";
import { fetchBooking, approveBooking, rejectBooking, releaseBooking, ApiError } from "@/lib/api/facilities";
import type { BookingDetailDTO, BookingRequestDTO, BookingRequestStatus } from "@/types/facility";

export const BOOKING_STATUS_CLASSES: Record<BookingRequestStatus, string> = {
  PENDING: "bg-warning/10 text-warning border border-warning/20",
  APPROVED: "bg-success/10 text-success border border-success/20",
  REJECTED: "bg-error/10 text-error border border-error/20",
  CANCELLED: "bg-input text-muted border border-border",
  RELEASED: "bg-info/10 text-info border border-info/20",
};

interface BookingDetailDrawerProps {
  bookingId: string;
  onClose: () => void;
  /** Called after any successful approve/reject/release with the updated row so the table can patch it in place. */
  onChanged: (updated: BookingRequestDTO) => void;
}

export function BookingDetailDrawer({ bookingId, onClose, onChanged }: BookingDetailDrawerProps) {
  const t = useTranslations("Bookings");
  const [detail, setDetail] = useState<BookingDetailDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [adminNote, setAdminNote] = useState("");
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDetail(await fetchBooking(bookingId));
    } catch {
      setError(t("loadError"));
    } finally {
      setLoading(false);
    }
  }, [bookingId, t]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onClose]);

  const act = async (action: "approve" | "reject" | "release") => {
    setWorking(true);
    setError(null);
    try {
      let updated: BookingRequestDTO;
      if (action === "approve") updated = await approveBooking(bookingId, { adminNote: adminNote || undefined });
      else if (action === "reject") updated = await rejectBooking(bookingId, { adminNote: adminNote || undefined });
      else updated = await releaseBooking(bookingId);
      // Use the DTO the action response already carries — no extra round-trip.
      setDetail(prev => (prev ? { ...prev, request: updated } : prev));
      onChanged(updated);
    } catch (err) {
      if (err instanceof ApiError) setError(err.message);
      else setError(t("actionError"));
    } finally {
      setWorking(false);
    }
  };

  const req = detail?.request;
  const statusLabel = (s: BookingRequestStatus) => t(`status${s}`);

  return (
    <>
      {/* Overlay */}
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[100]"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Drawer (logical end side — RTL-safe) */}
      <div
        className="fixed inset-y-0 end-0 w-full max-w-md bg-surface shadow-2xl z-[101] flex flex-col border-s border-border"
        role="dialog"
        aria-modal="true"
        aria-label={t("detailTitle")}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center">
              <CalendarCheck size={16} className="text-primary" />
            </div>
            <h2 className="text-sm font-bold text-foreground">{t("detailTitle")}</h2>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-muted hover:text-foreground rounded-lg transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
            aria-label="Close"
          >
            <X size={18} />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto">
          {loading ? (
            <div className="flex flex-col items-center justify-center h-48 gap-3">
              <Loader2 size={24} className="animate-spin text-muted" />
              <p className="text-xs text-muted">{t("loading")}</p>
            </div>
          ) : req ? (
            <div className="px-6 py-4 space-y-5">
              {/* Resource + status */}
              <div className="flex items-start justify-between gap-2">
                <div>
                  <p className="text-sm font-bold text-foreground">{req.resourceName}</p>
                  <p className="text-[11px] text-muted font-semibold uppercase tracking-widest">
                    {t(`type${req.resourceType}`)}
                  </p>
                </div>
                <span className={cn(
                  "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold shrink-0",
                  BOOKING_STATUS_CLASSES[req.status]
                )}>
                  {statusLabel(req.status)}
                </span>
              </div>

              {/* Renter details */}
              <div className="bg-input/40 rounded-xl border border-border p-4 space-y-1">
                <p className="text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-2">{t("renterContact")}</p>
                <p className="text-sm font-semibold text-foreground">{req.renterName ?? "—"}</p>
                {req.renterEmail && (
                  <a href={`mailto:${req.renterEmail}`} className="text-xs text-primary hover:underline block truncate">
                    {req.renterEmail}
                  </a>
                )}
                {req.renterPhone && (
                  <a href={`tel:${req.renterPhone}`} className="text-xs text-foreground hover:text-primary transition-colors block">
                    {req.renterPhone}
                  </a>
                )}
                {req.unitNumber && (
                  <p className="text-xs text-muted">{t("colUnit")}: {req.unitNumber}</p>
                )}
              </div>

              {/* Request details */}
              <div className="space-y-2 text-xs">
                <p className="text-muted">
                  <span className="font-semibold">{t("requestedOn")}:</span>{" "}
                  {new Date(req.createdAt).toLocaleDateString()}
                </p>
                {req.preferredDate && (
                  <p className="text-muted">
                    <span className="font-semibold">{t("preferredDate")}:</span>{" "}
                    {new Date(req.preferredDate).toLocaleDateString()}
                  </p>
                )}
                {req.note && (
                  <p className="text-muted italic">
                    <span className="font-semibold not-italic">{t("renterNote")}:</span> &ldquo;{req.note}&rdquo;
                  </p>
                )}
                {req.adminNote && (
                  <p className="text-muted">
                    <span className="font-semibold">{t("adminNote")}:</span> {req.adminNote}
                  </p>
                )}
                {req.decidedAt && (
                  <p className="text-muted">
                    <span className="font-semibold">{t("decidedAt")}:</span>{" "}
                    {new Date(req.decidedAt).toLocaleDateString()}
                  </p>
                )}
              </div>

              {/* Other applicants */}
              <div>
                <p className="text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-2 flex items-center gap-1.5">
                  <Users size={12} /> {t("otherRequests")}
                </p>
                {detail && detail.otherRequests.length > 0 ? (
                  <div className="divide-y divide-border border border-border rounded-xl overflow-hidden">
                    {detail.otherRequests.map(o => (
                      <div key={o.id} className="px-4 py-3 flex items-center justify-between gap-2 bg-input/20">
                        <div className="min-w-0">
                          <p className="text-xs font-semibold text-foreground truncate">{o.renterName ?? "—"}</p>
                          <p className="text-[10px] text-muted">
                            {o.unitNumber ? `${t("colUnit")} ${o.unitNumber} · ` : ""}
                            {new Date(o.createdAt).toLocaleDateString()}
                          </p>
                        </div>
                        <span className={cn(
                          "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold shrink-0",
                          BOOKING_STATUS_CLASSES[o.status]
                        )}>
                          {statusLabel(o.status)}
                        </span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-xs text-muted italic">{t("noOtherRequests")}</p>
                )}
              </div>
            </div>
          ) : null}
        </div>

        {/* Footer actions */}
        {req && (req.status === "PENDING" || (req.status === "APPROVED" && req.resourceType === "PARKING_SPOT")) && (
          <div className="shrink-0 px-6 py-4 border-t border-border bg-input/30 space-y-3">
            {error && <p className="text-xs font-semibold text-error">{error}</p>}
            {req.status === "PENDING" && (
              <>
                <div>
                  <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1">
                    {t("adminNoteLabel")}
                  </label>
                  <textarea
                    rows={2}
                    maxLength={2000}
                    className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 resize-none"
                    value={adminNote}
                    onChange={e => setAdminNote(e.target.value)}
                  />
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => act("approve")}
                    disabled={working}
                    className="flex-1 px-4 py-2.5 bg-success/10 text-success hover:bg-success/20 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-success/30 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {working ? t("working") : t("approve")}
                  </button>
                  <button
                    onClick={() => act("reject")}
                    disabled={working}
                    className="flex-1 px-4 py-2.5 bg-error/10 text-error hover:bg-error/20 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-error/30 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {working ? t("working") : t("reject")}
                  </button>
                </div>
              </>
            )}
            {req.status === "APPROVED" && req.resourceType === "PARKING_SPOT" && (
              <button
                onClick={() => act("release")}
                disabled={working}
                className="w-full px-4 py-2.5 bg-info/10 text-info hover:bg-info/20 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-info/30 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {working ? t("working") : t("release")}
              </button>
            )}
          </div>
        )}
        {!loading && !req && error && (
          <div className="px-6 py-4 text-xs font-semibold text-error">{error}</div>
        )}
      </div>
    </>
  );
}
