"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { X, Download, Loader2, ChevronLeft, ChevronRight, Users } from "lucide-react";
import { cn } from "@/lib/utils";
import { fetchInterests } from "@/lib/api/listings";
import type { InterestDTO, InterestStatus } from "@/types/listing";

interface InterestsDrawerProps {
  listingId: string;
  listingTitle: string;
  onClose: () => void;
}

function getInterestStatusClass(status: InterestStatus) {
  switch (status) {
    case 'ACTIVE':    return 'bg-success/10 text-success border border-success/20';
    case 'NOTIFIED':  return 'bg-blue-50 text-blue-600 border border-blue-200';
    case 'WITHDRAWN': return 'bg-input text-muted border border-border';
    default:          return 'bg-input text-muted border border-border';
  }
}

function getInitials(name: string | null): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  if (parts.length >= 2) return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  return name.slice(0, 2).toUpperCase();
}

export function InterestsDrawer({ listingId, listingTitle, onClose }: InterestsDrawerProps) {
  const t = useTranslations('Listings');
  const { data: session } = useSession();
  const token = (session?.user as { accessToken?: string })?.accessToken;
  const [interests, setInterests] = useState<InterestDTO[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [currentPage, setCurrentPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);

  const load = useCallback(async (page: number) => {
    setLoading(true);
    try {
      const data = await fetchInterests(listingId, token, page);
      setInterests(data.content);
      setTotalElements(data.totalElements);
      setTotalPages(data.totalPages);
      setCurrentPage(data.number);
    } catch {
      // silent
    } finally {
      setLoading(false);
    }
  }, [listingId, token]);

  useEffect(() => { load(0); }, [load]);

  // Close on Escape
  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  async function exportCsv() {
    setExporting(true);
    try {
      const all = await fetchInterests(listingId, token, 0, 1000);
      const headers = [
        t('interestName'), t('interestEmail'), t('interestPhone'),
        t('interestNote'), t('interestDate'), t('interestStatus'),
      ];
      const rows = all.content.map(i => [
        i.renterName ?? '',
        i.renterEmail ?? '',
        i.renterPhone ?? '',
        i.note ?? '',
        new Date(i.createdAt).toLocaleDateString(),
        i.status,
      ]);
      const csv = [headers, ...rows]
        .map(row => row.map(v => `"${String(v).replace(/"/g, '""')}"`).join(','))
        .join('\n');
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `interests-${listingId}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      // silent — user can retry
    } finally {
      setExporting(false);
    }
  }

  function getStatusLabel(status: InterestStatus) {
    switch (status) {
      case 'ACTIVE':    return t('interestStatusActive');
      case 'NOTIFIED':  return t('interestStatusNotified');
      case 'WITHDRAWN': return t('interestStatusWithdrawn');
      default:          return status;
    }
  }

  return (
    <>
      {/* Overlay */}
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[100]"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Drawer */}
      <div
        className="fixed inset-y-0 right-0 w-full max-w-md bg-surface shadow-2xl z-[101] flex flex-col border-l border-border"
        role="dialog"
        aria-modal="true"
        aria-label={t('interestsDrawerTitle')}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center">
              <Users size={16} className="text-primary" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-foreground">{t('interestsDrawerTitle')}</h2>
              <p className="text-[11px] text-muted truncate max-w-[220px]">{listingTitle}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {totalElements > 0 && (
              <button
                onClick={exportCsv}
                disabled={exporting}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-input text-foreground hover:bg-input/80 transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:opacity-60"
              >
                {exporting ? <Loader2 size={13} className="animate-spin" /> : <Download size={13} />}
                {exporting ? t('loadingText') : t('exportCsv')}
              </button>
            )}
            <button
              onClick={onClose}
              className="p-2 text-muted hover:text-foreground rounded-lg transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
              aria-label="Close"
            >
              <X size={18} />
            </button>
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto">
          {loading ? (
            <div className="flex flex-col items-center justify-center h-48 gap-3">
              <Loader2 size={24} className="animate-spin text-muted" />
              <p className="text-xs text-muted">{t('loadingText')}</p>
            </div>
          ) : interests.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-64 gap-3">
              <div className="w-14 h-14 rounded-2xl bg-input flex items-center justify-center">
                <Users size={24} className="text-muted/50" />
              </div>
              <p className="text-sm text-muted font-medium">{t('noInterestsYet')}</p>
            </div>
          ) : (
            <div className="divide-y divide-border">
              {interests.map(interest => (
                <div key={interest.id} className="px-6 py-4 hover:bg-input/30 transition-colors">
                  <div className="flex items-start gap-3">
                    {/* Avatar */}
                    <div className="w-9 h-9 rounded-full bg-primary/10 text-primary flex items-center justify-center text-xs font-bold shrink-0 select-none">
                      {getInitials(interest.renterName)}
                    </div>
                    {/* Info */}
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between gap-2 mb-1">
                        <span className="text-sm font-semibold text-foreground truncate">
                          {interest.renterName ?? '—'}
                        </span>
                        <span className={cn("inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold shrink-0", getInterestStatusClass(interest.status))}>
                          {getStatusLabel(interest.status)}
                        </span>
                      </div>
                      {interest.renterEmail && (
                        <a
                          href={`mailto:${interest.renterEmail}`}
                          className="text-xs text-primary hover:underline block truncate"
                        >
                          {interest.renterEmail}
                        </a>
                      )}
                      {interest.renterPhone && (
                        <a
                          href={`tel:${interest.renterPhone}`}
                          className="text-xs text-foreground hover:text-primary transition-colors block"
                        >
                          {interest.renterPhone}
                        </a>
                      )}
                      {interest.note && (
                        <p className="text-xs text-muted mt-1 italic line-clamp-2">&ldquo;{interest.note}&rdquo;</p>
                      )}
                      <p className="text-[10px] text-muted mt-1.5">
                        {new Date(interest.createdAt).toLocaleDateString()}
                      </p>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Footer pagination */}
        {totalPages > 1 && (
          <div className="shrink-0 px-6 py-3 border-t border-border flex items-center justify-between bg-input/30">
            <span className="text-xs text-muted">
              {totalElements} {t('interests')}
            </span>
            <div className="flex items-center gap-1">
              <button
                onClick={() => load(currentPage - 1)}
                disabled={currentPage === 0 || loading}
                className="p-1.5 rounded-md text-muted hover:bg-input hover:text-foreground disabled:opacity-30 disabled:cursor-not-allowed transition-colors cursor-pointer"
                aria-label="Previous page"
              >
                <ChevronLeft size={14} />
              </button>
              <span className="text-xs text-muted px-2">
                {currentPage + 1} / {totalPages}
              </span>
              <button
                onClick={() => load(currentPage + 1)}
                disabled={currentPage >= totalPages - 1 || loading}
                className="p-1.5 rounded-md text-muted hover:bg-input hover:text-foreground disabled:opacity-30 disabled:cursor-not-allowed transition-colors cursor-pointer"
                aria-label="Next page"
              >
                <ChevronRight size={14} />
              </button>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
