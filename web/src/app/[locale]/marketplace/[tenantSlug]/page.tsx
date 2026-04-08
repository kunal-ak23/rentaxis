"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { useTranslations, useLocale } from "next-intl";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import { useSession } from "next-auth/react";
import {
  Heart, MapPin, Bed, Bath, Maximize2, List, Map as MapIcon,
  SlidersHorizontal, X, AlertCircle, RefreshCw, Loader2, Image as ImageIcon,
} from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { Pagination } from "@/components/ui/Pagination";
import { formatCurrencyCompact } from "@/lib/format";
import {
  fetchMarketplaceListings,
  addInterest,
  removeInterest,
} from "@/lib/api/listings";
import type { UnitListingSummaryDTO, ListingStatus, Furnishing } from "@/types/listing";

// ─── Haversine distance ──────────────────────────────────────────────────────
function haversineKm(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6371;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ─── Extended summary with optional lat/lng ──────────────────────────────────
interface ListingWithDistance extends UnitListingSummaryDTO {
  lat?: number | null;
  lng?: number | null;
  distanceKm?: number;
  bathrooms?: number | null;
}

// ─── Status chip ─────────────────────────────────────────────────────────────
function StatusChip({ status, availableFrom, t }: {
  status: ListingStatus;
  availableFrom?: string | null;
  t: ReturnType<typeof useTranslations>;
}) {
  if (status === 'UPCOMING' && availableFrom) {
    const date = new Date(availableFrom).toLocaleDateString('en-AE', { month: 'short', year: 'numeric' });
    return (
      <span className="absolute top-2 left-2 inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-blue-600 text-white shadow">
        {t('availableFrom', { date })}
      </span>
    );
  }
  return null;
}

// ─── Listing card ─────────────────────────────────────────────────────────────
function ListingCard({
  listing,
  tenantSlug,
  wishlisted,
  onWishlistToggle,
  showDistance,
  t,
}: {
  listing: ListingWithDistance;
  tenantSlug: string;
  wishlisted: boolean;
  onWishlistToggle: (id: string) => void;
  showDistance: boolean;
  t: ReturnType<typeof useTranslations>;
}) {
  const beds = listing.bedrooms;
  const baths = listing.bathrooms;

  function bedsLabel() {
    if (beds === null || beds === undefined) return null;
    if (beds === 0) return t('studio');
    return `${beds} ${beds === 1 ? t('bedrooms') : t('bedroomsPlural')}`;
  }

  return (
    <div className="group bg-white rounded-2xl overflow-hidden shadow-sm hover:shadow-lg transition-all duration-300 border border-neutral-100 flex flex-col">
      {/* Photo */}
      <div className="relative aspect-[16/9] overflow-hidden bg-gradient-to-br from-neutral-200 to-neutral-300 flex items-center justify-center">
        {listing.coverPhotoUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={listing.coverPhotoUrl}
            alt={listing.title}
            className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
          />
        ) : (
          <ImageIcon size={36} className="text-neutral-400" />
        )}

        {/* Status chip */}
        {listing.status === 'UPCOMING' && (
          <StatusChip status={listing.status} availableFrom={null} t={t} />
        )}

        {/* Distance chip */}
        {showDistance && listing.distanceKm !== undefined && (
          <span className="absolute bottom-2 left-2 inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold bg-black/60 text-white backdrop-blur-sm">
            <MapPin size={9} />
            {t('distanceKm', { km: listing.distanceKm.toFixed(1) })}
          </span>
        )}

        {/* Wishlist button */}
        <button
          onClick={(e) => { e.preventDefault(); e.stopPropagation(); onWishlistToggle(listing.id); }}
          className={cn(
            "absolute top-2 right-2 w-8 h-8 rounded-full flex items-center justify-center shadow transition-all duration-200 cursor-pointer",
            wishlisted
              ? "bg-red-500 text-white"
              : "bg-white/90 text-neutral-500 hover:bg-white hover:text-red-500"
          )}
          aria-label={wishlisted ? t('wishlistRemove') : t('wishlistAdd')}
        >
          <Heart size={14} fill={wishlisted ? 'currentColor' : 'none'} />
        </button>
      </div>

      {/* Body */}
      <Link href={`/marketplace/${tenantSlug}/${listing.slug}`} className="flex flex-col flex-1 p-4 gap-2 hover:no-underline">
        <p className="text-xs text-neutral-400 truncate">{listing.propertyName ?? ''}</p>
        <h3 className="text-sm font-semibold text-neutral-900 line-clamp-2 leading-snug">{listing.title}</h3>

        {/* Facts row */}
        <div className="flex items-center gap-3 text-xs text-neutral-500 mt-auto">
          {bedsLabel() && (
            <span className="flex items-center gap-1">
              <Bed size={12} className="shrink-0" />
              {bedsLabel()}
            </span>
          )}
          {baths != null && (
            <span className="flex items-center gap-1">
              <Bath size={12} className="shrink-0" />
              {baths} {baths === 1 ? t('bathrooms') : t('bathroomsPlural')}
            </span>
          )}
        </div>

        {/* Rent */}
        <div className="flex items-baseline gap-1 mt-1">
          <span className="text-base font-bold text-neutral-900">
            {listing.annualRent ? formatCurrencyCompact(listing.annualRent) : '—'}
          </span>
          {listing.annualRent && (
            <span className="text-xs text-neutral-400">{t('perYear')}</span>
          )}
        </div>
      </Link>
    </div>
  );
}

// ─── Filter sidebar ───────────────────────────────────────────────────────────
function FilterPanel({
  open,
  onClose,
  bedrooms,
  minRent,
  maxRent,
  furnishing,
  availableNow,
  onApply,
  t,
}: {
  open: boolean;
  onClose: () => void;
  bedrooms: string;
  minRent: string;
  maxRent: string;
  furnishing: string[];
  availableNow: boolean;
  onApply: (filters: { bedrooms: string; minRent: string; maxRent: string; furnishing: string[]; availableNow: boolean }) => void;
  t: ReturnType<typeof useTranslations>;
}) {
  const [localBeds, setLocalBeds] = useState(bedrooms);
  const [localMin, setLocalMin] = useState(minRent);
  const [localMax, setLocalMax] = useState(maxRent);
  const [localFurnishing, setLocalFurnishing] = useState<string[]>(furnishing);
  const [localAvailableNow, setLocalAvailableNow] = useState(availableNow);

  useEffect(() => {
    setLocalBeds(bedrooms); setLocalMin(minRent); setLocalMax(maxRent);
    setLocalFurnishing(furnishing); setLocalAvailableNow(availableNow);
  }, [bedrooms, minRent, maxRent, furnishing, availableNow]);

  const bedOptions = ['', '1', '2', '3', '4', '5'];
  const furnishingOptions: Furnishing[] = ['UNFURNISHED', 'SEMI_FURNISHED', 'FULLY_FURNISHED'];
  const furnishingLabels: Record<Furnishing, string> = {
    UNFURNISHED: t('furnishingUnfurnished'),
    SEMI_FURNISHED: t('furnishingSemi'),
    FULLY_FURNISHED: t('furnishingFully'),
  };

  function toggleFurnishing(val: string) {
    setLocalFurnishing(prev => prev.includes(val) ? prev.filter(f => f !== val) : [...prev, val]);
  }

  const content = (
    <div className="flex flex-col gap-5 h-full">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-neutral-900">{t('filterTitle')}</h2>
        <button onClick={onClose} className="p-1 rounded-lg hover:bg-neutral-100 transition-colors cursor-pointer">
          <X size={16} className="text-neutral-500" />
        </button>
      </div>

      {/* Bedrooms */}
      <div>
        <p className="text-xs font-medium text-neutral-600 mb-2">{t('filterBedrooms')}</p>
        <div className="flex flex-wrap gap-2">
          {bedOptions.map(b => (
            <button
              key={b || 'any'}
              onClick={() => setLocalBeds(b)}
              className={cn(
                "px-3 py-1.5 rounded-full text-xs font-medium border transition-all cursor-pointer",
                localBeds === b
                  ? "bg-neutral-900 text-white border-neutral-900"
                  : "bg-white text-neutral-600 border-neutral-200 hover:border-neutral-400"
              )}
            >
              {b === '' ? t('bedsAny') : b === '1' ? t('beds1') : b === '2' ? t('beds2') : b === '3' ? t('beds3') : b === '4' ? t('beds4') : t('beds5')}
            </button>
          ))}
        </div>
      </div>

      {/* Rent range */}
      <div>
        <p className="text-xs font-medium text-neutral-600 mb-2">{t('filterMinRent')}</p>
        <input
          type="number"
          value={localMin}
          onChange={e => setLocalMin(e.target.value)}
          placeholder="0"
          className="w-full border border-neutral-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-neutral-900/20 focus:border-neutral-900 focus:outline-none"
        />
        <p className="text-xs font-medium text-neutral-600 mb-2 mt-3">{t('filterMaxRent')}</p>
        <input
          type="number"
          value={localMax}
          onChange={e => setLocalMax(e.target.value)}
          placeholder="∞"
          className="w-full border border-neutral-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-neutral-900/20 focus:border-neutral-900 focus:outline-none"
        />
      </div>

      {/* Furnishing */}
      <div>
        <p className="text-xs font-medium text-neutral-600 mb-2">{t('filterFurnishing')}</p>
        <div className="flex flex-col gap-2">
          {furnishingOptions.map(f => (
            <label key={f} className="flex items-center gap-2 cursor-pointer text-sm text-neutral-700">
              <input
                type="checkbox"
                checked={localFurnishing.includes(f)}
                onChange={() => toggleFurnishing(f)}
                className="w-4 h-4 rounded border-neutral-300 accent-neutral-900 cursor-pointer"
              />
              {furnishingLabels[f]}
            </label>
          ))}
        </div>
      </div>

      {/* Available now */}
      <label className="flex items-center gap-2 cursor-pointer text-sm text-neutral-700">
        <input
          type="checkbox"
          checked={localAvailableNow}
          onChange={e => setLocalAvailableNow(e.target.checked)}
          className="w-4 h-4 rounded border-neutral-300 accent-neutral-900 cursor-pointer"
        />
        {t('filterAvailableNow')}
      </label>

      {/* Actions */}
      <div className="flex gap-2 mt-auto pt-4 border-t border-neutral-100">
        <button
          onClick={() => { setLocalBeds(''); setLocalMin(''); setLocalMax(''); setLocalFurnishing([]); setLocalAvailableNow(false); }}
          className="flex-1 py-2 rounded-xl border border-neutral-200 text-sm font-medium text-neutral-600 hover:bg-neutral-50 transition-colors cursor-pointer"
        >
          {t('clearFilters')}
        </button>
        <button
          onClick={() => onApply({ bedrooms: localBeds, minRent: localMin, maxRent: localMax, furnishing: localFurnishing, availableNow: localAvailableNow })}
          className="flex-1 py-2 rounded-xl bg-neutral-900 text-white text-sm font-semibold hover:bg-neutral-800 transition-colors cursor-pointer"
        >
          {t('applyFilters')}
        </button>
      </div>
    </div>
  );

  return (
    <>
      {/* Desktop sidebar */}
      <aside className="hidden lg:block w-72 shrink-0 bg-white rounded-2xl border border-neutral-100 shadow-sm p-5 h-fit sticky top-6">
        {content}
      </aside>

      {/* Mobile bottom sheet */}
      {open && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" onClick={onClose} />
          <div className="absolute bottom-0 left-0 right-0 bg-white rounded-t-3xl p-5 shadow-xl max-h-[85vh] overflow-y-auto">
            {content}
          </div>
        </div>
      )}
    </>
  );
}

// ─── Main page ────────────────────────────────────────────────────────────────
export default function MarketplacePage({ params }: { params: { tenantSlug: string } }) {
  const t = useTranslations('Marketplace');
  const locale = useLocale();
  const { tenantSlug } = params;
  const { data: session } = useSession();
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const [listings, setListings] = useState<ListingWithDistance[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(1);
  const itemsPerPage = 12;

  const [viewMode, setViewMode] = useState<'list' | 'map'>('list');
  const [filterOpen, setFilterOpen] = useState(false);
  const [wishlistedIds, setWishlistedIds] = useState<Set<string>>(new Set());

  // Geolocation
  const [userPos, setUserPos] = useState<{ lat: number; lng: number } | null>(null);
  const geolocationAttempted = useRef(false);

  // URL-driven filter state
  const bedroomsParam = searchParams.get('bedrooms') || '';
  const minRentParam = searchParams.get('minRent') || '';
  const maxRentParam = searchParams.get('maxRent') || '';
  const furnishingParam = searchParams.get('furnishing') || '';
  const availableNowParam = searchParams.get('availableNow') === 'true';
  const sortParam = searchParams.get('sort') || 'createdAt,asc';

  // Attempt geolocation once on mount (silent)
  useEffect(() => {
    if (geolocationAttempted.current) return;
    geolocationAttempted.current = true;
    if (typeof navigator !== 'undefined' && navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (pos) => setUserPos({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
        () => { /* denied — stay null */ },
        { timeout: 5000 }
      );
    }
  }, []);

  const loadListings = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const token = (session?.user as { accessToken?: string })?.accessToken;
      const data = await fetchMarketplaceListings(tenantSlug, {
        page: currentPage - 1,
        size: itemsPerPage,
        bedrooms: bedroomsParam ? Number(bedroomsParam) : undefined,
        minRent: minRentParam ? Number(minRentParam) : undefined,
        maxRent: maxRentParam ? Number(maxRentParam) : undefined,
        furnishing: furnishingParam || undefined,
        availableNow: availableNowParam || undefined,
        sort: sortParam,
      }, token);

      // Attach distances if geolocation available
      const enriched: ListingWithDistance[] = data.content.map(l => {
        const ext = l as ListingWithDistance;
        if (userPos && ext.lat != null && ext.lng != null) {
          ext.distanceKm = haversineKm(userPos.lat, userPos.lng, ext.lat, ext.lng);
        }
        return ext;
      });

      setListings(enriched);
      setTotalElements(data.totalElements);
    } catch {
      setError(t('errorLoad'));
    } finally {
      setLoading(false);
    }
  }, [currentPage, bedroomsParam, minRentParam, maxRentParam, furnishingParam, availableNowParam, sortParam, tenantSlug, session, userPos, t]);

  useEffect(() => { loadListings(); }, [loadListings]);

  function updateParams(updates: Record<string, string>) {
    const p = new URLSearchParams(searchParams.toString());
    Object.entries(updates).forEach(([k, v]) => {
      if (v) p.set(k, v); else p.delete(k);
    });
    router.replace(`${pathname}?${p.toString()}`);
    setCurrentPage(1);
  }

  function handleApplyFilters(filters: { bedrooms: string; minRent: string; maxRent: string; furnishing: string[]; availableNow: boolean }) {
    updateParams({
      bedrooms: filters.bedrooms,
      minRent: filters.minRent,
      maxRent: filters.maxRent,
      furnishing: filters.furnishing.join(','),
      availableNow: filters.availableNow ? 'true' : '',
    });
    setFilterOpen(false);
  }

  function handleWishlistToggle(id: string) {
    if (!session) {
      router.push(`/${locale}/auth/login?returnTo=${encodeURIComponent(pathname)}`);
      return;
    }
    const token = (session.user as { accessToken?: string })?.accessToken ?? '';
    if (wishlistedIds.has(id)) {
      removeInterest(id, token).catch(() => {});
      setWishlistedIds(prev => { const n = new Set(prev); n.delete(id); return n; });
    } else {
      addInterest(id, undefined, token).catch(() => {});
      setWishlistedIds(prev => new Set([...prev, id]));
    }
  }

  const sortOptions = [
    { value: 'createdAt,asc', label: t('sortNewest') },
    { value: 'annualRent,asc', label: t('sortRentAsc') },
    { value: 'annualRent,desc', label: t('sortRentDesc') },
    ...(userPos ? [{ value: 'distance,asc', label: t('sortNearest') }] : []),
  ];

  const showDistance = !!userPos;

  return (
    <div className="min-h-screen bg-neutral-50">
      {/* Top bar */}
      <div className="bg-white border-b border-neutral-100 sticky top-0 z-30">
        <div className="max-w-screen-xl mx-auto px-4 py-3 flex items-center justify-between gap-3">
          <div>
            <h1 className="text-base font-bold text-neutral-900">{t('title')}</h1>
            {totalElements > 0 && !loading && (
              <p className="text-xs text-neutral-400">{totalElements} properties</p>
            )}
          </div>

          <div className="flex items-center gap-2">
            {/* Sort */}
            <select
              value={sortParam}
              onChange={e => updateParams({ sort: e.target.value })}
              className="hidden sm:block bg-white border border-neutral-200 rounded-xl px-3 py-1.5 text-xs text-neutral-700 focus:ring-2 focus:ring-neutral-900/20 focus:outline-none cursor-pointer"
            >
              {sortOptions.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>

            {/* Filter button (mobile) */}
            <button
              onClick={() => setFilterOpen(true)}
              className="lg:hidden flex items-center gap-1.5 px-3 py-1.5 rounded-xl border border-neutral-200 bg-white text-xs font-medium text-neutral-700 hover:bg-neutral-50 transition-colors cursor-pointer"
            >
              <SlidersHorizontal size={13} />
              {t('filterTitle')}
            </button>

            {/* View toggle */}
            <div className="flex items-center bg-neutral-100 rounded-xl p-0.5">
              <button
                onClick={() => setViewMode('list')}
                className={cn(
                  "px-3 py-1.5 rounded-lg text-xs font-medium transition-all cursor-pointer flex items-center gap-1",
                  viewMode === 'list' ? "bg-white text-neutral-900 shadow-sm" : "text-neutral-500 hover:text-neutral-700"
                )}
              >
                <List size={12} />
                {t('viewList')}
              </button>
              <button
                onClick={() => setViewMode('map')}
                className={cn(
                  "px-3 py-1.5 rounded-lg text-xs font-medium transition-all cursor-pointer flex items-center gap-1",
                  viewMode === 'map' ? "bg-white text-neutral-900 shadow-sm" : "text-neutral-500 hover:text-neutral-700"
                )}
              >
                <MapIcon size={12} />
                {t('viewMap')}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Main content */}
      <div className="max-w-screen-xl mx-auto px-4 py-6 flex gap-6">
        {/* Sidebar filter (desktop) */}
        <FilterPanel
          open={filterOpen}
          onClose={() => setFilterOpen(false)}
          bedrooms={bedroomsParam}
          minRent={minRentParam}
          maxRent={maxRentParam}
          furnishing={furnishingParam ? furnishingParam.split(',') : []}
          availableNow={availableNowParam}
          onApply={handleApplyFilters}
          t={t}
        />

        {/* Listings area */}
        <div className="flex-1 min-w-0">
          {/* Error */}
          {error && (
            <div className="mb-4 flex items-center justify-between gap-3 bg-red-50 border border-red-200 text-red-700 rounded-2xl px-4 py-3">
              <div className="flex items-center gap-2 text-sm">
                <AlertCircle size={15} />
                {error}
              </div>
              <button
                onClick={() => { setError(null); loadListings(); }}
                className="flex items-center gap-1 text-xs font-semibold px-3 py-1 rounded-lg bg-red-100 hover:bg-red-200 transition-colors cursor-pointer"
              >
                <RefreshCw size={11} />
                {t('retry')}
              </button>
            </div>
          )}

          {/* Map placeholder */}
          {viewMode === 'map' && (
            <div className="bg-white rounded-2xl border border-neutral-100 shadow-sm h-96 flex flex-col items-center justify-center gap-3 text-neutral-400">
              <MapPin size={40} className="text-neutral-300" />
              <p className="text-base font-semibold text-neutral-600">{t('mapComingSoon')}</p>
              <p className="text-sm text-neutral-400">{t('mapComingSoonDesc')}</p>
            </div>
          )}

          {/* Loading skeleton */}
          {loading && viewMode === 'list' && (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
              {[...Array(6)].map((_, i) => (
                <div key={i} className="bg-white rounded-2xl overflow-hidden border border-neutral-100">
                  <div className="aspect-[16/9] bg-neutral-200 animate-pulse" />
                  <div className="p-4 space-y-3">
                    <div className="h-3 w-3/4 bg-neutral-200 rounded-full animate-pulse" />
                    <div className="h-3 w-1/2 bg-neutral-100 rounded-full animate-pulse" />
                    <div className="h-4 w-1/3 bg-neutral-200 rounded-full animate-pulse mt-2" />
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Empty state */}
          {!loading && viewMode === 'list' && listings.length === 0 && (
            <div className="bg-white rounded-2xl border border-neutral-100 shadow-sm p-16 flex flex-col items-center gap-4">
              <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center">
                <ImageIcon size={28} className="text-neutral-400" />
              </div>
              <div className="text-center">
                <h3 className="text-sm font-semibold text-neutral-800 mb-1">{t('noListingsTitle')}</h3>
                <p className="text-xs text-neutral-400">{t('noListingsDesc')}</p>
              </div>
            </div>
          )}

          {/* Grid */}
          {!loading && viewMode === 'list' && listings.length > 0 && (
            <>
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
                {listings.map(listing => (
                  <ListingCard
                    key={listing.id}
                    listing={listing}
                    tenantSlug={tenantSlug}
                    wishlisted={wishlistedIds.has(listing.id)}
                    onWishlistToggle={handleWishlistToggle}
                    showDistance={showDistance}
                    t={t}
                  />
                ))}
              </div>
              <div className="mt-6 bg-white rounded-2xl border border-neutral-100 shadow-sm px-4">
                <Pagination
                  currentPage={currentPage}
                  totalItems={totalElements}
                  itemsPerPage={itemsPerPage}
                  onPageChange={setCurrentPage}
                />
              </div>
            </>
          )}
        </div>
      </div>

    </div>
  );
}
