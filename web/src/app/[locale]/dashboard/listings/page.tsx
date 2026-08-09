"use client";

import { useState, useEffect, useCallback, Suspense } from "react";
import { useTranslations } from "next-intl";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { useSession } from "next-auth/react";
import {
  Plus, Search, List, LayoutGrid, Pencil, Trash2, Users,
  AlertCircle, RefreshCw, Loader2, Image as ImageIcon,
  Eye, EyeOff, ChevronUp, ChevronDown
} from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
import { formatCurrencyCompact } from "@/lib/format";
import {
  fetchListings,
  publishListing,
  unlistListing,
  deleteListing,
} from "@/lib/api/listings";
import type { UnitListingSummaryDTO, ListingStatus } from "@/types/listing";
import { InterestsDrawer } from "./_components/InterestsDrawer";

type SortDir = 'asc' | 'desc';

function getStatusBadgeClass(status: ListingStatus) {
  switch (status) {
    case 'PUBLISHED': return 'bg-success/10 text-success border border-success/20';
    case 'DRAFT':     return 'bg-input text-muted border border-border';
    case 'UNLISTED':  return 'bg-warning/10 text-warning border border-warning/20';
    case 'UPCOMING':  return 'bg-blue-50 text-blue-600 border border-blue-200';
    default:          return 'bg-input text-muted border border-border';
  }
}

function getStatusLabel(status: ListingStatus, t: ReturnType<typeof useTranslations>) {
  switch (status) {
    case 'PUBLISHED': return t('statusPublished');
    case 'DRAFT':     return t('statusDraft');
    case 'UNLISTED':  return t('statusUnlisted');
    case 'UPCOMING':  return t('statusUpcoming');
    default:          return status;
  }
}

function BedroomsLabel({ beds, t }: { beds: number | null; t: ReturnType<typeof useTranslations> }) {
  if (beds === null) return <span className="text-muted">—</span>;
  if (beds === 0) return <span>{t('studio')}</span>;
  return <span>{beds}</span>;
}

export default function ListingsPage() {
  return (
    <Suspense fallback={<div className="flex items-center justify-center h-64"><Loader2 size={24} className="animate-spin text-muted" /></div>}>
      <ListingsContent />
    </Suspense>
  );
}

function ListingsContent() {
  const t = useTranslations('Listings');
  const { data: session } = useSession();
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const [listings, setListings] = useState<UnitListingSummaryDTO[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  // View / filter state from URL params
  const [viewMode, setViewMode] = useState<'table' | 'grid'>('table');
  const [currentPage, setCurrentPage] = useState(1);
  const [itemsPerPage, setItemsPerPage] = useState(25);
  const [sortDir, setSortDir] = useState<SortDir>('asc');

  // Filter state from URL
  const statusFilter = searchParams.get('status') || '';
  const searchQuery = searchParams.get('search') || '';

  // Confirm dialog
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirmConfig, setConfirmConfig] = useState<{
    title: string; description: string; confirmText: string;
    isDestructive: boolean; onConfirm: () => void;
  }>({ title: '', description: '', confirmText: '', isDestructive: false, onConfirm: () => {} });

  // Interests drawer
  const [interestsListingId, setInterestsListingId] = useState<string | null>(null);
  const [interestsTitle, setInterestsTitle] = useState<string>('');

  const token = (session?.user as { accessToken?: string })?.accessToken;

  const loadListings = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchListings({
        page: currentPage - 1,
        size: itemsPerPage,
        status: statusFilter || undefined,
        search: searchQuery || undefined,
        sort: `createdAt,${sortDir}`,
      }, token);
      setListings(data.content);
      setTotalElements(data.totalElements);
    } catch {
      setError(t('errorLoad'));
    } finally {
      setLoading(false);
    }
  }, [currentPage, itemsPerPage, statusFilter, searchQuery, sortDir, token, t]);

  useEffect(() => { loadListings(); }, [loadListings]);

  function updateUrlParam(key: string, value: string) {
    const params = new URLSearchParams(searchParams.toString());
    if (value) params.set(key, value);
    else params.delete(key);
    router.replace(`${pathname}?${params.toString()}`);
    setCurrentPage(1);
  }

  function handlePublish(listing: UnitListingSummaryDTO) {
    setConfirmConfig({
      title: t('confirmPublishTitle'),
      description: t('confirmPublishDesc'),
      confirmText: t('confirmPublish'),
      isDestructive: false,
      onConfirm: async () => {
        setConfirmOpen(false);
        setActionLoading(`publish-${listing.id}`);
        try { await publishListing(listing.id, token); await loadListings(); } catch {}
        finally { setActionLoading(null); }
      },
    });
    setConfirmOpen(true);
  }

  function handleUnlist(listing: UnitListingSummaryDTO) {
    setConfirmConfig({
      title: t('confirmUnlistTitle'),
      description: t('confirmUnlistDesc'),
      confirmText: t('confirmUnlist'),
      isDestructive: false,
      onConfirm: async () => {
        setConfirmOpen(false);
        setActionLoading(`unlist-${listing.id}`);
        try { await unlistListing(listing.id, token); await loadListings(); } catch {}
        finally { setActionLoading(null); }
      },
    });
    setConfirmOpen(true);
  }

  function handleDelete(listing: UnitListingSummaryDTO) {
    setConfirmConfig({
      title: t('confirmDeleteTitle'),
      description: t('confirmDeleteDesc'),
      confirmText: t('confirmDelete'),
      isDestructive: true,
      onConfirm: async () => {
        setConfirmOpen(false);
        setActionLoading(`delete-${listing.id}`);
        try { await deleteListing(listing.id, token); await loadListings(); } catch {}
        finally { setActionLoading(null); }
      },
    });
    setConfirmOpen(true);
  }

  function openInterests(listing: UnitListingSummaryDTO) {
    setInterestsListingId(listing.id);
    setInterestsTitle(listing.title);
  }

  // --- Skeleton ---
  if (loading && listings.length === 0) {
    return (
      <div>
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
          <div>
            <div className="h-6 w-32 bg-input rounded-lg animate-pulse mb-2" />
            <div className="h-4 w-64 bg-background rounded-lg animate-pulse" />
          </div>
          <div className="h-9 w-36 bg-input rounded-lg animate-pulse" />
        </div>
        <div className="bg-surface rounded-xl border border-border overflow-hidden">
          <div className="h-10 bg-input/50 animate-pulse" />
          {[...Array(6)].map((_, i) => (
            <div key={i} className="flex gap-4 px-4 py-3 border-b border-border animate-pulse">
              <div className="h-10 w-10 rounded-lg bg-input shrink-0" />
              <div className="flex-1 space-y-2">
                <div className="h-3 w-48 bg-input rounded" />
                <div className="h-3 w-32 bg-background rounded" />
              </div>
              <div className="h-5 w-20 bg-input rounded-full" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  // --- Render row actions ---
  function renderActions(listing: UnitListingSummaryDTO) {
    const isPublishable = listing.status === 'DRAFT' || listing.status === 'UNLISTED' || listing.status === 'UPCOMING';
    const isUnlistable  = listing.status === 'PUBLISHED';
    return (
      <div className="flex items-center justify-center gap-1">
        <Link
          href={`/dashboard/listings/${listing.id}`}
          className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-input text-foreground hover:bg-input/80 transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
          aria-label={t('actionEdit')}
        >
          <Pencil size={11} />
          {t('actionEdit')}
        </Link>
        {isPublishable && (
          <button
            onClick={() => handlePublish(listing)}
            disabled={actionLoading === `publish-${listing.id}`}
            className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-success/10 text-success hover:bg-success/20 transition-colors cursor-pointer disabled:opacity-50"
            aria-label={t('actionPublish')}
          >
            {actionLoading === `publish-${listing.id}` ? <Loader2 size={11} className="animate-spin" /> : <Eye size={11} />}
            {t('actionPublish')}
          </button>
        )}
        {isUnlistable && (
          <button
            onClick={() => handleUnlist(listing)}
            disabled={actionLoading === `unlist-${listing.id}`}
            className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-warning/10 text-warning hover:bg-warning/20 transition-colors cursor-pointer disabled:opacity-50"
            aria-label={t('actionUnlist')}
          >
            {actionLoading === `unlist-${listing.id}` ? <Loader2 size={11} className="animate-spin" /> : <EyeOff size={11} />}
            {t('actionUnlist')}
          </button>
        )}
        <button
          onClick={() => handleDelete(listing)}
          disabled={actionLoading === `delete-${listing.id}`}
          className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-medium bg-error/10 text-error hover:bg-error/20 transition-colors cursor-pointer disabled:opacity-50"
          aria-label={t('actionDelete')}
        >
          {actionLoading === `delete-${listing.id}` ? <Loader2 size={11} className="animate-spin" /> : <Trash2 size={11} />}
        </button>
      </div>
    );
  }

  return (
    <div>
      {/* Error Banner */}
      {error && (
        <div className="mb-6 flex items-center justify-between gap-3 bg-error/10 border border-error/30 text-error rounded-xl px-5 py-3">
          <div className="flex items-center gap-2">
            <AlertCircle size={16} />
            <span className="text-sm font-medium">{error}</span>
          </div>
          <button
            onClick={() => { setError(null); loadListings(); }}
            className="cursor-pointer flex items-center gap-1.5 text-xs font-semibold bg-error/10 hover:bg-error/20 px-3 py-1.5 rounded-lg transition-colors"
          >
            <RefreshCw size={12} />
            {t('retry')}
          </button>
        </div>
      )}

      {/* Header */}
      <div className="flex flex-col gap-4 mb-8">
        <div>
          <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t('title')}</h1>
          <p className="text-xs text-muted font-medium">{t('subtitle')}</p>
        </div>

        <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
          {/* Search + status filter */}
          <div className="flex items-center gap-2 flex-wrap">
            <div className="relative">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
              <input
                type="text"
                placeholder={t('search')}
                defaultValue={searchQuery}
                onChange={(e) => updateUrlParam('search', e.target.value)}
                className="pl-9 pr-4 py-2 bg-surface border border-border rounded-lg text-sm text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none w-60 transition-all"
              />
            </div>
            <select
              value={statusFilter}
              onChange={(e) => updateUrlParam('status', e.target.value)}
              className="bg-surface border border-border rounded-lg px-3 py-2 text-sm text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
            >
              <option value="">{t('filterStatus')}</option>
              <option value="DRAFT">{t('statusDraft')}</option>
              <option value="PUBLISHED">{t('statusPublished')}</option>
              <option value="UNLISTED">{t('statusUnlisted')}</option>
              <option value="UPCOMING">{t('statusUpcoming')}</option>
            </select>
          </div>

          {/* Right controls */}
          <div className="flex items-center gap-3">
            {/* View toggle */}
            <div className="flex items-center bg-input rounded-lg p-0.5 border border-border">
              <button
                onClick={() => setViewMode('table')}
                className={cn(
                  "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                  viewMode === 'table' ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                )}
              >
                <List size={13} />
                {t('table')}
              </button>
              <button
                onClick={() => setViewMode('grid')}
                className={cn(
                  "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                  viewMode === 'grid' ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                )}
              >
                <LayoutGrid size={13} />
                {t('grid')}
              </button>
            </div>

            <Link
              href="/dashboard/listings/new"
              className="cursor-pointer flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:opacity-90 transition-all duration-200 active:scale-95 focus:ring-2 focus:ring-primary/30 focus:outline-none"
            >
              <Plus size={14} />
              {t('newListing')}
            </Link>
          </div>
        </div>
      </div>

      {/* Empty state */}
      {!loading && listings.length === 0 && (
        <div className="bg-surface rounded-xl border border-border p-16 flex flex-col items-center gap-4">
          <div className="w-16 h-16 rounded-2xl bg-input flex items-center justify-center">
            <ImageIcon size={28} className="text-muted" />
          </div>
          <div className="text-center">
            <h3 className="text-sm font-semibold text-foreground mb-1">{t('noListingsTitle')}</h3>
            <p className="text-xs text-muted mb-5">{t('noListingsDesc')}</p>
            <Link
              href="/dashboard/listings/new"
              className="inline-flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-lg text-xs font-semibold hover:opacity-90 transition-all duration-200 cursor-pointer"
            >
              <Plus size={14} />
              {t('createFirstListing')}
            </Link>
          </div>
        </div>
      )}

      {/* TABLE VIEW */}
      {!loading && listings.length > 0 && viewMode === 'table' && (
        <div className="bg-surface rounded-xl border border-border overflow-hidden">
          <table className="w-full">
            <thead>
              <tr className="bg-input/50">
                <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider w-12"></th>
                <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t('colTitle')}</th>
                <th className="text-left px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider hidden md:table-cell">{t('colProperty')}</th>
                <th className="text-center px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider hidden lg:table-cell">{t('colBedrooms')}</th>
                <th className="text-right px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider hidden lg:table-cell">{t('colRent')}</th>
                <th className="text-center px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t('colStatus')}</th>
                <th className="text-center px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider hidden md:table-cell">{t('colInterests')}</th>
                <th
                  className="text-right px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider hidden lg:table-cell cursor-pointer select-none group"
                  onClick={() => setSortDir(d => d === 'asc' ? 'desc' : 'asc')}
                >
                  <span className="inline-flex items-center gap-1 group-hover:text-foreground transition-colors">
                    {t('colCreated')}
                    {sortDir === 'asc' ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                  </span>
                </th>
                <th className="text-center px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider">{t('colActions')}</th>
              </tr>
            </thead>
            <tbody>
              {listings.map(listing => (
                <tr key={listing.id} className="border-b border-border hover:bg-input/30 transition-colors">
                  {/* Cover photo */}
                  <td className="px-4 py-3">
                    {listing.coverPhotoUrl ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={listing.coverPhotoUrl}
                        alt={listing.title}
                        className="w-10 h-10 rounded-lg object-cover border border-border"
                      />
                    ) : (
                      <div className="w-10 h-10 rounded-lg bg-input flex items-center justify-center border border-border">
                        <ImageIcon size={14} className="text-muted" />
                      </div>
                    )}
                  </td>
                  {/* Title */}
                  <td className="px-4 py-3">
                    <Link
                      href={`/dashboard/listings/${listing.id}`}
                      className="text-xs font-medium text-foreground hover:text-primary transition-colors"
                    >
                      {listing.title}
                    </Link>
                  </td>
                  {/* Property */}
                  <td className="px-4 py-3 text-xs text-muted hidden md:table-cell">
                    {listing.propertyName ?? '—'}
                  </td>
                  {/* Bedrooms */}
                  <td className="px-4 py-3 text-xs text-center hidden lg:table-cell">
                    <BedroomsLabel beds={listing.bedrooms} t={t} />
                  </td>
                  {/* Annual Rent */}
                  <td className="px-4 py-3 text-xs font-medium text-foreground text-right tabular-nums hidden lg:table-cell">
                    {listing.annualRent ? formatCurrencyCompact(listing.annualRent) : '—'}
                  </td>
                  {/* Status */}
                  <td className="px-4 py-3 text-center">
                    <span className={cn("inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold", getStatusBadgeClass(listing.status))}>
                      {getStatusLabel(listing.status, t)}
                    </span>
                  </td>
                  {/* Interests */}
                  <td className="px-4 py-3 text-center hidden md:table-cell">
                    {listing.interestsCount > 0 ? (
                      <button
                        onClick={() => openInterests(listing)}
                        className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:text-primary/80 cursor-pointer transition-colors"
                      >
                        <Users size={12} />
                        {listing.interestsCount}
                      </button>
                    ) : (
                      <span className="text-xs text-muted">0</span>
                    )}
                  </td>
                  {/* Created — matches the column's createdAt sort key */}
                  <td className="px-4 py-3 text-xs text-muted text-right hidden lg:table-cell tabular-nums">
                    {new Date(listing.createdAt).toLocaleDateString()}
                  </td>
                  {/* Actions */}
                  <td className="px-4 py-3">
                    {renderActions(listing)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="px-4">
            <Pagination
              currentPage={currentPage}
              totalItems={totalElements}
              itemsPerPage={itemsPerPage}
              onPageChange={setCurrentPage}
              onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
            />
          </div>
        </div>
      )}

      {/* GRID VIEW */}
      {!loading && listings.length > 0 && viewMode === 'grid' && (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5">
            {listings.map(listing => (
              <div key={listing.id} className="bg-surface rounded-xl border border-border overflow-hidden flex flex-col hover:shadow-md transition-shadow">
                {/* Cover */}
                <div className="relative aspect-[4/3] bg-input flex items-center justify-center overflow-hidden">
                  {listing.coverPhotoUrl ? (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={listing.coverPhotoUrl}
                      alt={listing.title}
                      className="w-full h-full object-cover"
                    />
                  ) : (
                    <ImageIcon size={32} className="text-muted/40" />
                  )}
                  <div className="absolute top-2 right-2">
                    <span className={cn("inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold shadow-sm", getStatusBadgeClass(listing.status))}>
                      {getStatusLabel(listing.status, t)}
                    </span>
                  </div>
                </div>
                {/* Info */}
                <div className="p-4 flex flex-col flex-1 gap-2">
                  <Link
                    href={`/dashboard/listings/${listing.id}`}
                    className="text-sm font-semibold text-foreground hover:text-primary transition-colors line-clamp-2 leading-tight"
                  >
                    {listing.title}
                  </Link>
                  {listing.propertyName && (
                    <p className="text-[11px] text-muted truncate">{listing.propertyName}</p>
                  )}
                  <div className="flex items-center justify-between mt-auto pt-2">
                    <span className="text-xs font-bold text-foreground tabular-nums">
                      {listing.annualRent ? formatCurrencyCompact(listing.annualRent) : '—'}
                    </span>
                    {listing.interestsCount > 0 && (
                      <button
                        onClick={() => openInterests(listing)}
                        className="inline-flex items-center gap-1 text-[10px] font-medium text-primary hover:text-primary/80 cursor-pointer transition-colors"
                      >
                        <Users size={11} />
                        {listing.interestsCount} {t('interests')}
                      </button>
                    )}
                  </div>
                  {/* Card actions */}
                  <div className="pt-2 border-t border-border flex gap-2">
                    <Link
                      href={`/dashboard/listings/${listing.id}`}
                      className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-[11px] font-semibold bg-input text-foreground hover:bg-input/80 transition-colors cursor-pointer"
                    >
                      <Pencil size={12} />
                      {t('actionEdit')}
                    </Link>
                    {listing.status !== 'PUBLISHED' && (
                      <button
                        onClick={() => handlePublish(listing)}
                        disabled={actionLoading === `publish-${listing.id}`}
                        className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-[11px] font-semibold bg-success/10 text-success hover:bg-success/20 transition-colors cursor-pointer disabled:opacity-50"
                      >
                        <Eye size={12} />
                        {t('actionPublish')}
                      </button>
                    )}
                    {listing.status === 'PUBLISHED' && (
                      <button
                        onClick={() => handleUnlist(listing)}
                        disabled={actionLoading === `unlist-${listing.id}`}
                        className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-[11px] font-semibold bg-warning/10 text-warning hover:bg-warning/20 transition-colors cursor-pointer disabled:opacity-50"
                      >
                        <EyeOff size={12} />
                        {t('actionUnlist')}
                      </button>
                    )}
                    <button
                      onClick={() => handleDelete(listing)}
                      disabled={actionLoading === `delete-${listing.id}`}
                      className="p-2 rounded-lg text-[11px] font-semibold bg-error/10 text-error hover:bg-error/20 transition-colors cursor-pointer disabled:opacity-50"
                      aria-label={t('actionDelete')}
                    >
                      <Trash2 size={12} />
                    </button>
                  </div>
                </div>
              </div>
            ))}
          </div>

          <div className="mt-4">
            <Pagination
              currentPage={currentPage}
              totalItems={totalElements}
              itemsPerPage={itemsPerPage}
              onPageChange={setCurrentPage}
              onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
            />
          </div>
        </>
      )}

      {/* Confirm Dialog */}
      <ConfirmDialog
        isOpen={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={confirmConfig.onConfirm}
        title={confirmConfig.title}
        description={confirmConfig.description}
        confirmText={confirmConfig.confirmText}
        cancelText={t('cancel')}
        isDestructive={confirmConfig.isDestructive}
      />

      {/* Interests Drawer */}
      {interestsListingId && (
        <InterestsDrawer
          listingId={interestsListingId}
          listingTitle={interestsTitle}
          onClose={() => setInterestsListingId(null)}
        />
      )}
    </div>
  );
}
