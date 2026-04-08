"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { useTranslations, useLocale } from "next-intl";
import { useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import {
  Heart, Bed, Bath, MapPin, Loader2, AlertCircle, RefreshCw, Image as ImageIcon,
} from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { Pagination } from "@/components/ui/Pagination";
import { formatCurrencyCompact } from "@/lib/format";
import { fetchWishlist, removeInterest } from "@/lib/api/listings";
import type { UnitListingSummaryDTO, ListingStatus } from "@/types/listing";

const ITEMS_PER_PAGE = 20;

// ─── Extended with bathrooms (API may return it) ──────────────────────────────
interface WishlistItem extends UnitListingSummaryDTO {
  bathrooms?: number | null;
  availableFrom?: string | null;
  interestStatus?: 'ACTIVE' | 'NOTIFIED' | 'WITHDRAWN';
}

// ─── Status chip ─────────────────────────────────────────────────────────────
function StatusBadge({ status, interestStatus, t }: {
  status: ListingStatus;
  interestStatus?: string;
  t: ReturnType<typeof useTranslations>;
}) {
  if (interestStatus === 'NOTIFIED') {
    return (
      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-purple-100 text-purple-700 border border-purple-200">
        {t('statusNotified')}
      </span>
    );
  }
  if (status === 'UPCOMING') {
    return (
      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-blue-100 text-blue-700 border border-blue-200">
        Upcoming
      </span>
    );
  }
  if (status === 'PUBLISHED') {
    return (
      <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold bg-green-100 text-green-700 border border-green-200">
        {t('availableNow')}
      </span>
    );
  }
  return null;
}

// ─── Swipeable card ───────────────────────────────────────────────────────────
function WishlistCard({
  item,
  onRemove,
  removing,
  t,
}: {
  item: WishlistItem;
  onRemove: (id: string) => void;
  removing: boolean;
  t: ReturnType<typeof useTranslations>;
}) {
  const [translateX, setTranslateX] = useState(0);
  const [revealed, setRevealed] = useState(false);
  const touchStartX = useRef(0);
  const touchStartY = useRef(0);
  const dragging = useRef(false);

  function onTouchStart(e: React.TouchEvent) {
    touchStartX.current = e.touches[0].clientX;
    touchStartY.current = e.touches[0].clientY;
    dragging.current = false;
  }

  function onTouchMove(e: React.TouchEvent) {
    const dx = e.touches[0].clientX - touchStartX.current;
    const dy = e.touches[0].clientY - touchStartY.current;
    if (Math.abs(dy) > Math.abs(dx)) return; // vertical scroll, ignore
    dragging.current = true;
    const clamped = Math.min(0, dx);
    setTranslateX(clamped);
  }

  function onTouchEnd() {
    if (!dragging.current) return;
    if (translateX < -60) {
      setTranslateX(-80);
      setRevealed(true);
    } else {
      setTranslateX(0);
      setRevealed(false);
    }
    dragging.current = false;
  }

  function closeSwipe() {
    setTranslateX(0);
    setRevealed(false);
  }

  const beds = item.bedrooms;

  return (
    <div className="relative overflow-hidden rounded-2xl border border-neutral-100 bg-white shadow-sm">
      {/* Remove button revealed by swipe */}
      <div className="absolute inset-y-0 right-0 flex items-center">
        <button
          onClick={() => onRemove(item.id)}
          className="h-full px-5 bg-red-500 text-white text-xs font-semibold flex items-center justify-center cursor-pointer transition-colors hover:bg-red-600"
        >
          {t('removeFromWishlistBtn')}
        </button>
      </div>

      {/* Card (swipeable) */}
      <div
        className="relative bg-white transition-transform duration-200"
        style={{ transform: `translateX(${translateX}px)` }}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
        onClick={revealed ? closeSwipe : undefined}
      >
        <div className="flex gap-4 p-4">
          {/* Photo */}
          <div className="relative w-24 h-20 rounded-xl overflow-hidden shrink-0 bg-neutral-200 flex items-center justify-center">
            {item.coverPhotoUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={item.coverPhotoUrl} alt={item.title} className="w-full h-full object-cover" />
            ) : (
              <ImageIcon size={20} className="text-neutral-400" />
            )}
          </div>

          {/* Info */}
          <div className="flex-1 min-w-0">
            <div className="flex items-start justify-between gap-2 mb-1">
              <h3 className="text-sm font-semibold text-neutral-900 line-clamp-1">{item.title}</h3>
              <StatusBadge status={item.status} interestStatus={item.interestStatus} t={t} />
            </div>
            {item.propertyName && (
              <p className="text-xs text-neutral-400 mb-2 truncate">{item.propertyName}</p>
            )}
            <div className="flex items-center gap-3 text-xs text-neutral-500 mb-2">
              {beds != null && (
                <span className="flex items-center gap-1">
                  <Bed size={11} />
                  {beds === 0 ? t('studio') : `${beds} ${beds === 1 ? t('bedrooms') : t('bedroomsPlural')}`}
                </span>
              )}
              {item.bathrooms != null && (
                <span className="flex items-center gap-1">
                  <Bath size={11} />
                  {item.bathrooms}
                </span>
              )}
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm font-bold text-neutral-900">
                {item.annualRent ? formatCurrencyCompact(item.annualRent) : '—'}
                {item.annualRent && <span className="text-xs font-normal text-neutral-400 ml-1">{t('perYear')}</span>}
              </span>
              <button
                onClick={() => onRemove(item.id)}
                disabled={removing}
                className="flex items-center gap-1 text-[10px] text-neutral-400 hover:text-red-500 transition-colors cursor-pointer disabled:opacity-50"
              >
                {removing ? <Loader2 size={10} className="animate-spin" /> : <Heart size={10} fill="currentColor" className="text-red-400" />}
                {t('wishlistRemove')}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Main page ────────────────────────────────────────────────────────────────
export default function WishlistPage() {
  const t = useTranslations('Marketplace');
  const locale = useLocale();
  const { data: session, status: sessionStatus } = useSession();
  const router = useRouter();

  const [items, setItems] = useState<WishlistItem[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [removingId, setRemovingId] = useState<string | null>(null);

  // Redirect unauthenticated users
  useEffect(() => {
    if (sessionStatus === 'unauthenticated') {
      router.push(`/${locale}/auth/login?returnTo=/${locale}/marketplace/wishlist`);
    }
  }, [sessionStatus, router, locale]);

  const loadWishlist = useCallback(async () => {
    const token = (session?.user as { accessToken?: string })?.accessToken;
    if (!token) return;
    setLoading(true);
    setError(null);
    try {
      const data = await fetchWishlist(token, currentPage - 1);
      setItems(data.content as WishlistItem[]);
      setTotalElements(data.totalElements);
    } catch {
      setError(t('errorLoad'));
    } finally {
      setLoading(false);
    }
  }, [session, currentPage, t]);

  useEffect(() => {
    if (sessionStatus === 'authenticated') {
      loadWishlist();
    }
  }, [loadWishlist, sessionStatus]);

  async function handleRemove(id: string) {
    const token = (session?.user as { accessToken?: string })?.accessToken;
    if (!token) return;
    setRemovingId(id);
    try {
      await removeInterest(id, token);
      setItems(prev => prev.filter(i => i.id !== id));
      setTotalElements(prev => prev - 1);
    } catch {}
    finally { setRemovingId(null); }
  }

  // Loading / redirecting
  if (sessionStatus === 'loading' || (sessionStatus === 'unauthenticated')) {
    return (
      <div className="min-h-screen bg-neutral-50 flex items-center justify-center">
        <Loader2 size={32} className="animate-spin text-neutral-400" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-neutral-50">
      {/* Header */}
      <div className="bg-white border-b border-neutral-100">
        <div className="max-w-2xl mx-auto px-4 py-5">
          <h1 className="text-xl font-bold text-neutral-900">{t('wishlistTitle')}</h1>
          <p className="text-xs text-neutral-400 mt-0.5">{t('wishlistSubtitle')}</p>
        </div>
      </div>

      <div className="max-w-2xl mx-auto px-4 py-6">
        {/* Error */}
        {error && (
          <div className="mb-4 flex items-center justify-between gap-3 bg-red-50 border border-red-200 text-red-700 rounded-2xl px-4 py-3">
            <div className="flex items-center gap-2 text-sm">
              <AlertCircle size={15} />
              {error}
            </div>
            <button
              onClick={() => { setError(null); loadWishlist(); }}
              className="flex items-center gap-1 text-xs font-semibold px-3 py-1 rounded-lg bg-red-100 hover:bg-red-200 transition-colors cursor-pointer"
            >
              <RefreshCw size={11} />
              {t('retry')}
            </button>
          </div>
        )}

        {/* Loading skeleton */}
        {loading && (
          <div className="space-y-3">
            {[...Array(4)].map((_, i) => (
              <div key={i} className="bg-white rounded-2xl border border-neutral-100 p-4 flex gap-4">
                <div className="w-24 h-20 rounded-xl bg-neutral-200 animate-pulse shrink-0" />
                <div className="flex-1 space-y-2 pt-1">
                  <div className="h-3 w-3/4 bg-neutral-200 rounded-full animate-pulse" />
                  <div className="h-3 w-1/2 bg-neutral-100 rounded-full animate-pulse" />
                  <div className="h-4 w-1/3 bg-neutral-200 rounded-full animate-pulse mt-3" />
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Empty state */}
        {!loading && items.length === 0 && (
          <div className="bg-white rounded-2xl border border-neutral-100 shadow-sm p-16 flex flex-col items-center gap-4">
            <div className="w-16 h-16 rounded-2xl bg-red-50 flex items-center justify-center">
              <Heart size={28} className="text-red-300" />
            </div>
            <div className="text-center">
              <h3 className="text-sm font-semibold text-neutral-800 mb-1">{t('wishlistEmpty')}</h3>
              <p className="text-xs text-neutral-400 mb-5">{t('wishlistEmptyDesc')}</p>
              <Link
                href="/marketplace"
                className="inline-flex items-center gap-2 bg-neutral-900 text-white px-5 py-2.5 rounded-xl text-xs font-semibold hover:bg-neutral-800 transition-colors cursor-pointer"
              >
                <MapPin size={13} />
                {t('browseListings')}
              </Link>
            </div>
          </div>
        )}

        {/* List */}
        {!loading && items.length > 0 && (
          <>
            {/* Swipe hint (mobile) */}
            <p className="text-xs text-neutral-400 mb-3 sm:hidden flex items-center gap-1">
              <span>←</span> {t('swipeToRemove')}
            </p>

            <div className="space-y-3">
              {items.map(item => (
                <WishlistCard
                  key={item.id}
                  item={item}
                  onRemove={handleRemove}
                  removing={removingId === item.id}
                  t={t}
                />
              ))}
            </div>

            <div className="mt-4 bg-white rounded-2xl border border-neutral-100 shadow-sm px-4">
              <Pagination
                currentPage={currentPage}
                totalItems={totalElements}
                itemsPerPage={ITEMS_PER_PAGE}
                onPageChange={setCurrentPage}
              />
            </div>
          </>
        )}
      </div>
    </div>
  );
}
