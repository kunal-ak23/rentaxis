"use client";

import { useState, useEffect, useCallback, use } from "react";
import { useSearchParams, useRouter, usePathname } from "next/navigation";
import {
  Bed, Bath, SlidersHorizontal, X, AlertCircle, RefreshCw,
  Loader2, Image as ImageIcon, LogIn, Home,
} from "lucide-react";
import { cn } from "@/lib/utils";
import type { PublicListingDTO } from "@/types/listing";

// ─── API helper ──────────────────────────────────────────────────────────────

async function fetchPublicListings(
  tenantSlug: string,
  params: Record<string, string | number | undefined>
): Promise<{ content: PublicListingDTO[]; totalElements: number; totalPages: number }> {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== '') q.set(k, String(v));
  });
  const res = await fetch(`/public/l/${tenantSlug}?${q.toString()}`);
  if (!res.ok) throw new Error('Failed to load listings');
  return res.json();
}

// ─── Listing card ─────────────────────────────────────────────────────────────

function ListingCard({ listing, tenantSlug }: { listing: PublicListingDTO; tenantSlug: string }) {
  const [imgErr, setImgErr] = useState(false);

  return (
    <a
      href={`/l/${tenantSlug}/${listing.slug}`}
      className="group block bg-white rounded-xl border border-neutral-100 shadow-sm hover:shadow-md transition-shadow overflow-hidden cursor-pointer"
    >
      {/* Cover photo */}
      <div className="relative aspect-[4/3] bg-neutral-100">
        {listing.coverPhotoUrl && !imgErr ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={listing.coverPhotoUrl}
            alt={listing.title ?? ''}
            className="w-full h-full object-cover group-hover:scale-[1.02] transition-transform duration-300"
            onError={() => setImgErr(true)}
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center">
            <ImageIcon size={32} className="text-neutral-300" />
          </div>
        )}
        {listing.rentRangeLabel && (
          <div className="absolute bottom-2 left-2 bg-black/60 backdrop-blur-sm text-white text-xs font-semibold px-2 py-1 rounded-lg">
            {listing.rentRangeLabel}/yr
          </div>
        )}
      </div>

      {/* Info */}
      <div className="p-4">
        <h3 className="font-semibold text-neutral-900 text-sm leading-snug line-clamp-2 group-hover:text-blue-600 transition-colors">
          {listing.title}
        </h3>
        <div className="flex items-center gap-3 mt-2 text-neutral-500 text-xs">
          {listing.bedrooms != null && (
            <span className="flex items-center gap-1">
              <Bed size={12} />
              {listing.bedrooms === 0 ? 'Studio' : `${listing.bedrooms} BR`}
            </span>
          )}
          {listing.bathrooms != null && (
            <span className="flex items-center gap-1">
              <Bath size={12} />
              {listing.bathrooms} BA
            </span>
          )}
        </div>
        {listing.availableLabel && (
          <p className="mt-2 text-[11px] text-emerald-600 font-medium">{listing.availableLabel}</p>
        )}
      </div>
    </a>
  );
}

// ─── Main page ─────────────────────────────────────────────────────────────────

export default function PublicListingsPage({
  params,
}: {
  params: Promise<{ tenantSlug: string }>;
}) {
  const { tenantSlug } = use(params);
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const [listings, setListings] = useState<PublicListingDTO[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filtersOpen, setFiltersOpen] = useState(false);

  const page = Number(searchParams.get('page') || '0');
  const minBedrooms = searchParams.get('minBedrooms') || '';
  const furnishing = searchParams.get('furnishing') || '';
  const minRent = searchParams.get('minRent') || '';
  const maxRent = searchParams.get('maxRent') || '';
  const availableNow = searchParams.get('availableNow') === 'true';

  const activeFilters = [minBedrooms, furnishing, minRent, maxRent, availableNow ? '1' : '']
    .filter(Boolean).length;

  function updateParam(key: string, value: string) {
    const p = new URLSearchParams(searchParams.toString());
    if (value) p.set(key, value); else p.delete(key);
    p.delete('page');
    router.replace(`${pathname}?${p.toString()}`);
  }

  function clearFilters() {
    router.replace(pathname);
  }

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchPublicListings(tenantSlug, {
        page,
        size: 12,
        minBedrooms: minBedrooms || undefined,
        furnishing: furnishing || undefined,
        minRent: minRent || undefined,
        maxRent: maxRent || undefined,
        availableNow: availableNow ? 'true' : undefined,
        sort: 'createdAt,asc',
      });
      setListings(data.content);
      setTotalElements(data.totalElements);
      setTotalPages(data.totalPages);
    } catch {
      setError('Failed to load listings. Please try again.');
    } finally {
      setLoading(false);
    }
  }, [tenantSlug, page, minBedrooms, furnishing, minRent, maxRent, availableNow]);

  useEffect(() => { load(); }, [load]);

  return (
    <div className="min-h-screen bg-neutral-50">
      {/* Header */}
      <header className="bg-white border-b border-neutral-100 sticky top-0 z-30">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between gap-4">
          <div className="flex items-center gap-2 text-neutral-900 font-semibold text-sm">
            <Home size={16} className="text-blue-600" />
            Available Properties
          </div>
          <a
            href="/auth/login"
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-semibold hover:bg-blue-700 transition-colors"
          >
            <LogIn size={13} />
            Sign in to save
          </a>
        </div>
      </header>

      <div className="max-w-6xl mx-auto px-4 py-6">
        {/* Filter bar */}
        <div className="flex items-center gap-2 mb-6 flex-wrap">
          <button
            onClick={() => setFiltersOpen(v => !v)}
            className={cn(
              "inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border text-xs font-medium transition-colors cursor-pointer",
              filtersOpen || activeFilters > 0
                ? "bg-blue-50 border-blue-300 text-blue-700"
                : "bg-white border-neutral-200 text-neutral-600 hover:bg-neutral-50"
            )}
          >
            <SlidersHorizontal size={13} />
            Filters
            {activeFilters > 0 && (
              <span className="bg-blue-600 text-white rounded-full px-1.5 text-[10px] font-bold">
                {activeFilters}
              </span>
            )}
          </button>

          {/* Bedroom quick filters */}
          {['0', '1', '2', '3', '4'].map(b => (
            <button
              key={b}
              onClick={() => updateParam('minBedrooms', minBedrooms === b ? '' : b)}
              className={cn(
                "px-3 py-2 rounded-lg border text-xs font-medium transition-colors cursor-pointer",
                minBedrooms === b
                  ? "bg-blue-600 text-white border-blue-600"
                  : "bg-white border-neutral-200 text-neutral-600 hover:bg-neutral-50"
              )}
            >
              {b === '0' ? 'Studio' : b === '4' ? '4+ BR' : `${b} BR`}
            </button>
          ))}

          {activeFilters > 0 && (
            <button
              onClick={clearFilters}
              className="inline-flex items-center gap-1 px-2 py-2 rounded-lg text-xs text-neutral-500 hover:text-neutral-800 transition-colors cursor-pointer"
            >
              <X size={12} /> Clear
            </button>
          )}
        </div>

        {/* Expanded filter panel */}
        {filtersOpen && (
          <div className="bg-white border border-neutral-100 rounded-xl p-4 mb-6 grid grid-cols-2 sm:grid-cols-4 gap-4">
            <div>
              <label className="text-[11px] font-medium text-neutral-500 mb-1 block uppercase tracking-wide">
                Min Rent (AED/yr)
              </label>
              <input
                type="number"
                placeholder="e.g. 50000"
                value={minRent}
                onChange={e => updateParam('minRent', e.target.value)}
                className="w-full border border-neutral-200 rounded-lg px-2.5 py-1.5 text-xs focus:outline-none focus:ring-2 focus:ring-blue-200"
              />
            </div>
            <div>
              <label className="text-[11px] font-medium text-neutral-500 mb-1 block uppercase tracking-wide">
                Max Rent (AED/yr)
              </label>
              <input
                type="number"
                placeholder="e.g. 150000"
                value={maxRent}
                onChange={e => updateParam('maxRent', e.target.value)}
                className="w-full border border-neutral-200 rounded-lg px-2.5 py-1.5 text-xs focus:outline-none focus:ring-2 focus:ring-blue-200"
              />
            </div>
            <div>
              <label className="text-[11px] font-medium text-neutral-500 mb-1 block uppercase tracking-wide">
                Furnishing
              </label>
              <select
                value={furnishing}
                onChange={e => updateParam('furnishing', e.target.value)}
                className="w-full border border-neutral-200 rounded-lg px-2.5 py-1.5 text-xs focus:outline-none focus:ring-2 focus:ring-blue-200 bg-white"
              >
                <option value="">Any</option>
                <option value="UNFURNISHED">Unfurnished</option>
                <option value="SEMI_FURNISHED">Semi-furnished</option>
                <option value="FULLY_FURNISHED">Fully furnished</option>
              </select>
            </div>
            <div className="flex items-end">
              <label className="flex items-center gap-2 cursor-pointer text-xs text-neutral-700 font-medium">
                <input
                  type="checkbox"
                  checked={availableNow}
                  onChange={e => updateParam('availableNow', e.target.checked ? 'true' : '')}
                  className="rounded"
                />
                Available now
              </label>
            </div>
          </div>
        )}

        {/* Results */}
        {loading ? (
          <div className="flex items-center justify-center py-20 text-neutral-400">
            <Loader2 size={24} className="animate-spin mr-2" />
            Loading listings…
          </div>
        ) : error ? (
          <div className="flex flex-col items-center gap-3 py-20 text-neutral-500">
            <AlertCircle size={28} className="text-red-400" />
            <p className="text-sm">{error}</p>
            <button
              onClick={load}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg bg-neutral-100 text-xs font-medium hover:bg-neutral-200 cursor-pointer transition-colors"
            >
              <RefreshCw size={13} /> Retry
            </button>
          </div>
        ) : listings.length === 0 ? (
          <div className="flex flex-col items-center gap-2 py-20 text-neutral-400">
            <Home size={32} className="text-neutral-300" />
            <p className="text-sm">No listings match your filters.</p>
            {activeFilters > 0 && (
              <button onClick={clearFilters} className="text-blue-600 text-xs underline cursor-pointer">
                Clear filters
              </button>
            )}
          </div>
        ) : (
          <>
            <p className="text-xs text-neutral-400 mb-4">
              {totalElements} {totalElements === 1 ? 'property' : 'properties'} available
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
              {listings.map(l => (
                <ListingCard key={l.slug} listing={l} tenantSlug={tenantSlug} />
              ))}
            </div>

            {/* Pagination */}
            {totalPages > 1 && (
              <div className="flex items-center justify-center gap-2 mt-8">
                <button
                  disabled={page === 0}
                  onClick={() => updateParam('page', String(page - 1))}
                  className="px-3 py-1.5 rounded-lg border border-neutral-200 text-xs font-medium disabled:opacity-40 cursor-pointer hover:bg-neutral-50 transition-colors"
                >
                  Previous
                </button>
                <span className="text-xs text-neutral-500">
                  Page {page + 1} of {totalPages}
                </span>
                <button
                  disabled={page + 1 >= totalPages}
                  onClick={() => updateParam('page', String(page + 1))}
                  className="px-3 py-1.5 rounded-lg border border-neutral-200 text-xs font-medium disabled:opacity-40 cursor-pointer hover:bg-neutral-50 transition-colors"
                >
                  Next
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
