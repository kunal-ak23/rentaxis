"use client";

import { useState, useEffect, useCallback, use } from "react";
import { useTranslations, useLocale } from "next-intl";
import { useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import {
  Bed, Bath, Maximize2, ArrowLeft, Heart, MapPin, ChevronLeft, ChevronRight,
  X, Waves, Dumbbell, Droplets, Building, ArrowUpDown, Car, Star, Leaf,
  ShieldCheck, Tv, Wind, Wifi, Package, Sofa, Sun, Train, ShoppingBag,
  School, TreePine, Loader2, AlertCircle,
} from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { formatCurrencyCompact, formatCurrency } from "@/lib/format";
import {
  fetchMarketplaceListing,
  fetchWishlist,
  addInterest,
  removeInterest,
} from "@/lib/api/listings";
import type { UnitListingDTO, ListingAmenity, UnitListingMediaDTO } from "@/types/listing";

// ─── Amenity icon map ─────────────────────────────────────────────────────────
const amenityIconMap: Record<ListingAmenity, React.ElementType> = {
  POOL: Waves,
  GYM: Dumbbell,
  SAUNA: Droplets,
  STEAM_ROOM: Droplets,
  JACUZZI: Droplets,
  KIDS_PLAY_AREA: Star,
  KIDS_POOL: Waves,
  BBQ_AREA: Leaf,
  GARDEN: TreePine,
  ROOFTOP_LOUNGE: Sun,
  SECURITY_24_7: ShieldCheck,
  CCTV: ShieldCheck,
  CONCIERGE: Star,
  INTERCOM: Wifi,
  COVERED_PARKING: Car,
  VISITOR_PARKING: Car,
  EV_CHARGING: Car,
  ELEVATOR: ArrowUpDown,
  CENTRAL_AC: Wind,
  DISTRICT_COOLING: Wind,
  MAIDS_ROOM: Building,
  STUDY_ROOM: Package,
  STORAGE_ROOM: Package,
  LAUNDRY_ROOM: Package,
  BUILT_IN_WARDROBES: Sofa,
  BALCONY: Building,
  PRIVATE_GARDEN: TreePine,
  MAID_SERVICE: Star,
  PET_FRIENDLY: Heart,
  SMART_HOME: Tv,
  SOLAR_POWER: Sun,
  NEAR_METRO: Train,
  NEAR_SCHOOL: School,
  NEAR_MALL: ShoppingBag,
  SEA_VIEW: Waves,
  OTHER: Star,
};


// ─── Lightbox ─────────────────────────────────────────────────────────────────
function Lightbox({
  media,
  index,
  onClose,
  onPrev,
  onNext,
  t,
}: {
  media: UnitListingMediaDTO[];
  index: number;
  onClose: () => void;
  onPrev: () => void;
  onNext: () => void;
  t: ReturnType<typeof useTranslations>;
}) {
  const current = media[index];
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
      if (e.key === 'ArrowLeft') onPrev();
      if (e.key === 'ArrowRight') onNext();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, onPrev, onNext]);

  return (
    <div className="fixed inset-0 z-50 bg-black/95 flex items-center justify-center" onClick={onClose}>
      <div className="relative max-w-5xl w-full px-4" onClick={e => e.stopPropagation()}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={current.url}
          alt={current.caption ?? ''}
          className="max-h-[80vh] w-full object-contain rounded-xl"
        />
        {current.caption && (
          <p className="text-center text-white/60 text-sm mt-2">{current.caption}</p>
        )}
        <p className="text-center text-white/40 text-xs mt-1">{index + 1} / {media.length}</p>
      </div>

      <button onClick={onClose} className="absolute top-4 right-4 w-10 h-10 rounded-full bg-white/10 hover:bg-white/20 flex items-center justify-center text-white transition-colors cursor-pointer" aria-label={t('lightboxClose')}>
        <X size={18} />
      </button>
      {index > 0 && (
        <button onClick={(e) => { e.stopPropagation(); onPrev(); }} className="absolute left-4 top-1/2 -translate-y-1/2 w-10 h-10 rounded-full bg-white/10 hover:bg-white/20 flex items-center justify-center text-white transition-colors cursor-pointer" aria-label={t('lightboxPrev')}>
          <ChevronLeft size={20} />
        </button>
      )}
      {index < media.length - 1 && (
        <button onClick={(e) => { e.stopPropagation(); onNext(); }} className="absolute right-4 top-1/2 -translate-y-1/2 w-10 h-10 rounded-full bg-white/10 hover:bg-white/20 flex items-center justify-center text-white transition-colors cursor-pointer" aria-label={t('lightboxNext')}>
          <ChevronRight size={20} />
        </button>
      )}
    </div>
  );
}

// ─── Haversine ────────────────────────────────────────────────────────────────
function haversineKm(lat1: number, lng1: number, lat2: number, lng2: number): number {
  const R = 6371;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// ─── Main page ────────────────────────────────────────────────────────────────
export default function ListingDetailPage({ params }: { params: Promise<{ tenantSlug: string; slug: string }> }) {
  const t = useTranslations('Marketplace');
  const locale = useLocale();
  const { tenantSlug, slug } = use(params);
  const { data: session } = useSession();
  const router = useRouter();

  const [listing, setListing] = useState<UnitListingDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
  const [descExpanded, setDescExpanded] = useState(false);
  const [wishlisted, setWishlisted] = useState(false);
  const [wishlistLoading, setWishlistLoading] = useState(false);
  const [interestNote, setInterestNote] = useState('');
  const [userPos, setUserPos] = useState<{ lat: number; lng: number } | null>(null);

  // Geolocation
  useEffect(() => {
    if (typeof navigator !== 'undefined' && navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (p) => setUserPos({ lat: p.coords.latitude, lng: p.coords.longitude }),
        () => {},
        { timeout: 5000 }
      );
    }
  }, []);

  const loadListing = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const token = (session?.user as { accessToken?: string })?.accessToken;
      const data = await fetchMarketplaceListing(tenantSlug, slug, token);
      setListing(data);
      // If authenticated, check whether this listing is already wishlisted
      if (session) {
        try {
          const wishlist = await fetchWishlist('');
          setWishlisted(wishlist.some(item => item.id === data.id));
        } catch {}
      }
    } catch {
      setError(t('errorLoad'));
    } finally {
      setLoading(false);
    }
  }, [tenantSlug, slug, session, t]);

  useEffect(() => { loadListing(); }, [loadListing]);

  async function handleWishlistToggle() {
    if (!session) {
      router.push(`/${locale}/auth/login?returnTo=/${locale}/marketplace/${tenantSlug}/${slug}`);
      return;
    }
    const token = (session.user as { accessToken?: string })?.accessToken ?? '';
    if (!listing) return;
    setWishlistLoading(true);
    try {
      if (wishlisted) {
        await removeInterest(listing.id, token);
        setWishlisted(false);
      } else {
        await addInterest(listing.id, interestNote || undefined, token);
        setWishlisted(true);
      }
    } catch {}
    finally { setWishlistLoading(false); }
  }

  if (loading) {
    return (
      <div className="min-h-screen bg-neutral-50 flex items-center justify-center">
        <Loader2 size={32} className="animate-spin text-neutral-400" />
      </div>
    );
  }

  if (error || !listing) {
    return (
      <div className="min-h-screen bg-neutral-50 flex flex-col items-center justify-center gap-4">
        <AlertCircle size={40} className="text-red-400" />
        <p className="text-neutral-600">{error ?? t('errorLoad')}</p>
        <button onClick={loadListing} className="px-4 py-2 rounded-xl bg-neutral-900 text-white text-sm cursor-pointer">{t('retry')}</button>
      </div>
    );
  }

  const photos = listing.media.filter(m => m.mediaType === 'PHOTO' || m.isCover);
  const floorPlans = listing.media.filter(m => m.mediaType === 'FLOOR_PLAN');
  const videos = listing.media.filter(m => m.mediaType === 'VIDEO_URL');
  const tours = listing.media.filter(m => m.mediaType === 'TOUR_360_URL');

  const titleLocale = locale === 'ar' ? (listing.titleAr || listing.titleEn) : listing.titleEn;
  const descLocale = locale === 'ar' ? (listing.descriptionAr || listing.descriptionEn) : listing.descriptionEn;
  const descLong = (descLocale ?? '').length > 300;
  const descDisplay = descLong && !descExpanded ? (descLocale ?? '').slice(0, 300) + '…' : (descLocale ?? '');

  const distance = userPos && listing.lat && listing.lng
    ? haversineKm(userPos.lat, userPos.lng, listing.lat, listing.lng)
    : null;

  const isUpcoming = listing.status === 'UPCOMING';
  const wishlistLabel = isUpcoming
    ? (listing.availableFrom
      ? t('notifyWhenAvailable', { date: new Date(listing.availableFrom).toLocaleDateString('en-AE', { month: 'short', year: 'numeric' }) })
      : t('notifyMe'))
    : wishlisted ? t('removeFromWishlist') : t('addToWishlist');

  return (
    <div className="min-h-screen bg-neutral-50 pb-32 lg:pb-0">
      {/* Back nav */}
      <div className="bg-white border-b border-neutral-100 sticky top-0 z-20">
        <div className="max-w-screen-xl mx-auto px-4 py-3 flex items-center gap-3">
          <Link href={`/marketplace/${tenantSlug}`} className="flex items-center gap-1.5 text-sm text-neutral-500 hover:text-neutral-900 transition-colors cursor-pointer">
            <ArrowLeft size={15} />
            {t('backToListings')}
          </Link>
        </div>
      </div>

      <div className="max-w-screen-xl mx-auto px-4 py-6">
        <div className="flex flex-col lg:flex-row gap-8">
          {/* Left column */}
          <div className="flex-1 min-w-0 space-y-6">

            {/* Hero gallery */}
            <div>
              {photos.length > 0 ? (
                <div className="space-y-2">
                  {/* Main photo */}
                  <div
                    className="relative aspect-[16/9] rounded-2xl overflow-hidden bg-neutral-200 cursor-zoom-in group"
                    onClick={() => setLightboxIndex(0)}
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={photos[0].url}
                      alt={photos[0].caption ?? titleLocale}
                      className="w-full h-full object-cover group-hover:scale-102 transition-transform duration-300"
                    />
                    <div className="absolute bottom-3 right-3 bg-black/50 text-white text-xs px-2 py-1 rounded-lg backdrop-blur-sm">
                      {t('gallery')} · {photos.length}
                    </div>
                  </div>
                  {/* Strip */}
                  {photos.length > 1 && (
                    <div className="flex gap-2 overflow-x-auto pb-1">
                      {photos.slice(1).map((m, i) => (
                        <button
                          key={m.id}
                          onClick={() => setLightboxIndex(i + 1)}
                          className="relative w-24 h-16 rounded-xl overflow-hidden shrink-0 cursor-pointer hover:opacity-90 transition-opacity"
                        >
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img src={m.url} alt={m.caption ?? ''} className="w-full h-full object-cover" />
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              ) : (
                <div className="aspect-[16/9] rounded-2xl bg-gradient-to-br from-neutral-200 to-neutral-300 flex items-center justify-center">
                  <Building size={48} className="text-neutral-400" />
                </div>
              )}

              {/* Media type buttons */}
              {(floorPlans.length > 0 || videos.length > 0 || tours.length > 0) && (
                <div className="flex gap-2 mt-3 flex-wrap">
                  {floorPlans.map(m => (
                    <button
                      key={m.id}
                      onClick={() => window.open(m.url, '_blank', 'noopener,noreferrer')}
                      className="px-3 py-1.5 rounded-xl border border-neutral-200 bg-white text-xs font-medium text-neutral-600 hover:bg-neutral-50 transition-colors cursor-pointer"
                    >
                      {t('floorPlan')}
                    </button>
                  ))}
                  {videos.map(m => (
                    <a key={m.id} href={m.url} target="_blank" rel="noopener noreferrer"
                      className="px-3 py-1.5 rounded-xl border border-neutral-200 bg-white text-xs font-medium text-neutral-600 hover:bg-neutral-50 transition-colors">
                      {t('video')}
                    </a>
                  ))}
                  {tours.map(m => (
                    <a key={m.id} href={m.url} target="_blank" rel="noopener noreferrer"
                      className="px-3 py-1.5 rounded-xl border border-neutral-200 bg-white text-xs font-medium text-neutral-600 hover:bg-neutral-50 transition-colors">
                      {t('tour360')}
                    </a>
                  ))}
                </div>
              )}
            </div>

            {/* Title + quick facts */}
            <div className="bg-white rounded-2xl border border-neutral-100 shadow-sm p-5">
              <h1 className="text-xl font-bold text-neutral-900 mb-1">{titleLocale}</h1>
              {listing.availableFrom && isUpcoming && (
                <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold bg-blue-100 text-blue-700 mb-3">
                  {t('availableFrom', { date: new Date(listing.availableFrom).toLocaleDateString('en-AE', { month: 'long', year: 'numeric' }) })}
                </span>
              )}
              {!isUpcoming && (
                <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold bg-green-100 text-green-700 mb-3">
                  {t('availableNow')}
                </span>
              )}

              <p className="text-xs font-medium text-neutral-500 mb-4">{t('quickFacts')}</p>
              <div className="flex flex-wrap gap-4 text-sm text-neutral-700">
                {listing.bedrooms != null && (
                  <span className="flex items-center gap-1.5">
                    <Bed size={15} className="text-neutral-400" />
                    {listing.bedrooms === 0 ? t('studio') : `${listing.bedrooms} ${listing.bedrooms === 1 ? t('bedrooms') : t('bedroomsPlural')}`}
                  </span>
                )}
                {listing.bathrooms != null && (
                  <span className="flex items-center gap-1.5">
                    <Bath size={15} className="text-neutral-400" />
                    {listing.bathrooms} {listing.bathrooms === 1 ? t('bathrooms') : t('bathroomsPlural')}
                  </span>
                )}
                {listing.sizeSqft != null && (
                  <span className="flex items-center gap-1.5">
                    <Maximize2 size={15} className="text-neutral-400" />
                    {listing.sizeSqft.toLocaleString()} {t('sqft')}
                  </span>
                )}
                {listing.furnishing && (
                  <span className="flex items-center gap-1.5">
                    <Sofa size={15} className="text-neutral-400" />
                    {listing.furnishing === 'UNFURNISHED' ? t('furnishingUnfurnished')
                      : listing.furnishing === 'SEMI_FURNISHED' ? t('furnishingSemi')
                      : t('furnishingFully')}
                  </span>
                )}
                {listing.floor != null && (
                  <span className="flex items-center gap-1.5 text-xs text-neutral-500">
                    {t('floor', { n: listing.floor })}
                  </span>
                )}
                {listing.parkingSpaces != null && listing.parkingSpaces > 0 && (
                  <span className="flex items-center gap-1.5 text-xs text-neutral-500">
                    <Car size={13} className="text-neutral-400" />
                    {t('parking', { n: listing.parkingSpaces })}
                  </span>
                )}
              </div>
            </div>

            {/* Description */}
            {descLocale && (
              <div className="bg-white rounded-2xl border border-neutral-100 shadow-sm p-5">
                <h2 className="text-sm font-semibold text-neutral-900 mb-3">{t('description')}</h2>
                <p className="text-sm text-neutral-600 leading-relaxed whitespace-pre-line">{descDisplay}</p>
                {descLong && (
                  <button
                    onClick={() => setDescExpanded(p => !p)}
                    className="mt-2 text-xs font-semibold text-neutral-900 underline underline-offset-2 cursor-pointer"
                  >
                    {descExpanded ? t('readLess') : t('readMore')}
                  </button>
                )}
              </div>
            )}

            {/* Amenities */}
            {listing.amenities.length > 0 && (
              <div className="bg-white rounded-2xl border border-neutral-100 shadow-sm p-5">
                <h2 className="text-sm font-semibold text-neutral-900 mb-4">{t('amenities')}</h2>
                <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                  {listing.amenities.map((a, i) => {
                    const Icon = amenityIconMap[a.amenity] ?? Star;
                    return (
                      <div key={i} className="flex items-center gap-2 text-sm text-neutral-700">
                        <Icon size={15} className="text-neutral-400 shrink-0" />
                        <span>{a.customLabel ?? t(`amenity.${a.amenity}`)}</span>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Map section */}
            {(listing.lat || listing.lng) && (
              <div className="bg-white rounded-2xl border border-neutral-100 shadow-sm p-5">
                <h2 className="text-sm font-semibold text-neutral-900 mb-3">{t('location')}</h2>
                <div className="flex items-start gap-3 mb-4">
                  <MapPin size={16} className="text-neutral-400 mt-0.5 shrink-0" />
                  <div>
                    <p className="text-sm text-neutral-700">
                      {listing.lat?.toFixed(5)}, {listing.lng?.toFixed(5)}
                    </p>
                    {distance !== null && (
                      <p className="text-xs text-neutral-500 mt-0.5">{t('distanceFromYou', { km: distance.toFixed(1) })}</p>
                    )}
                  </div>
                </div>

                {/* Map placeholder */}
                <div className="h-40 rounded-xl bg-neutral-100 border border-neutral-200 flex items-center justify-center mb-4">
                  <div className="flex flex-col items-center gap-2 text-neutral-400">
                    <MapPin size={24} />
                    <p className="text-xs">{t('mapComingSoon')}</p>
                  </div>
                </div>

                {listing.lat && listing.lng && (
                  <a
                    href={`https://www.google.com/maps/dir/?api=1&destination=${listing.lat},${listing.lng}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-2 px-4 py-2 rounded-xl border border-neutral-200 text-sm font-medium text-neutral-700 hover:bg-neutral-50 transition-colors"
                  >
                    <MapPin size={14} />
                    {t('getDirections')}
                  </a>
                )}
              </div>
            )}

          </div>

          {/* Right column — Price card + wishlist (desktop) */}
          <div className="lg:w-80 shrink-0 space-y-4">
            {/* Price card */}
            <div className="bg-white rounded-2xl border border-neutral-100 shadow-sm p-5 sticky top-20">
              {listing.annualRent && (
                <div className="mb-4">
                  <span className="text-2xl font-bold text-neutral-900">{formatCurrencyCompact(listing.annualRent)}</span>
                  <span className="text-sm text-neutral-400 ml-1">{t('aedPerYear')}</span>
                </div>
              )}

              {/* Chips */}
              <div className="flex flex-wrap gap-2 mb-4">
                {listing.dewaIncluded && (
                  <span className="px-2 py-1 rounded-full bg-green-50 text-green-700 text-xs font-medium border border-green-100">{t('dewaIncluded')}</span>
                )}
                {listing.chillerIncluded && (
                  <span className="px-2 py-1 rounded-full bg-blue-50 text-blue-700 text-xs font-medium border border-blue-100">{t('chillerIncluded')}</span>
                )}
                {listing.chequesAccepted && listing.chequesAccepted > 0 && (
                  <span className="px-2 py-1 rounded-full bg-neutral-100 text-neutral-600 text-xs font-medium">
                    {listing.chequesAccepted} {t('cheques')}
                  </span>
                )}
              </div>

              {/* Details */}
              <div className="space-y-2 text-sm text-neutral-600 border-t border-neutral-100 pt-4 mb-4">
                {listing.securityDeposit && (
                  <div className="flex justify-between">
                    <span className="text-neutral-400">{t('securityDeposit')}</span>
                    <span className="font-medium">{formatCurrency(listing.securityDeposit)}</span>
                  </div>
                )}
                {listing.minLeaseMonths && (
                  <div className="flex justify-between">
                    <span className="text-neutral-400">{t('minLease')}</span>
                    <span className="font-medium">{listing.minLeaseMonths} {t('months')}</span>
                  </div>
                )}
                {listing.utilitiesEstimate && (
                  <div className="flex justify-between">
                    <span className="text-neutral-400">{t('utilitiesEstimate')}</span>
                    <span className="font-medium">{formatCurrencyCompact(listing.utilitiesEstimate)}/mo</span>
                  </div>
                )}
              </div>

              {/* Interest note */}
              <textarea
                value={interestNote}
                onChange={e => setInterestNote(e.target.value.slice(0, 200))}
                placeholder={t('interestNotePlaceholder')}
                rows={3}
                className="w-full border border-neutral-200 rounded-xl px-3 py-2 text-sm text-neutral-700 placeholder:text-neutral-400 resize-none focus:ring-2 focus:ring-neutral-900/20 focus:border-neutral-900 focus:outline-none mb-1"
              />
              <p className="text-[10px] text-neutral-400 text-right mb-4">{200 - interestNote.length} {t('interestNoteMax').split(' ').slice(-2).join(' ')}</p>

              {/* Wishlist CTA */}
              {session ? (
                <button
                  onClick={handleWishlistToggle}
                  disabled={wishlistLoading}
                  className={cn(
                    "w-full py-3 rounded-xl text-sm font-semibold flex items-center justify-center gap-2 transition-all cursor-pointer disabled:opacity-60",
                    wishlisted
                      ? "bg-red-50 border border-red-200 text-red-600 hover:bg-red-100"
                      : "bg-neutral-900 text-white hover:bg-neutral-800"
                  )}
                >
                  {wishlistLoading ? <Loader2 size={15} className="animate-spin" /> : <Heart size={15} fill={wishlisted ? 'currentColor' : 'none'} />}
                  {wishlistLabel}
                </button>
              ) : (
                <div className="space-y-2">
                  <p className="text-xs text-neutral-500 text-center">{t('loginToWishlist')}</p>
                  <Link
                    href={`/auth/login?returnTo=/${locale}/marketplace/${tenantSlug}/${slug}`}
                    className="w-full py-3 rounded-xl text-sm font-semibold bg-neutral-900 text-white hover:bg-neutral-800 transition-colors flex items-center justify-center gap-2 cursor-pointer"
                  >
                    {t('signIn')}
                  </Link>
                </div>
              )}
            </div>

            {/* Building info */}
            <div className="bg-white rounded-2xl border border-neutral-100 shadow-sm p-5">
              <h2 className="text-xs font-semibold text-neutral-500 uppercase tracking-wider mb-3">{t('buildingInfo')}</h2>
              <div className="flex items-start gap-2">
                <Building size={15} className="text-neutral-400 mt-0.5 shrink-0" />
                <div>
                  <p className="text-sm font-medium text-neutral-800">{listing.titleEn}</p>
                  <p className="text-xs text-neutral-400 mt-0.5">{listing.slug}</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Sticky bottom bar (mobile only) */}
      <div className="fixed bottom-0 left-0 right-0 z-30 bg-white border-t border-neutral-100 shadow-lg px-4 py-3 flex items-center gap-3 lg:hidden">
        <div className="flex-1">
          {listing.annualRent && (
            <p className="text-base font-bold text-neutral-900">{formatCurrencyCompact(listing.annualRent)}<span className="text-xs text-neutral-400 font-normal ml-1">{t('aedPerYear')}</span></p>
          )}
        </div>
        {session ? (
          <button
            onClick={handleWishlistToggle}
            disabled={wishlistLoading}
            className={cn(
              "flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold transition-all cursor-pointer disabled:opacity-60",
              wishlisted
                ? "bg-red-50 border border-red-200 text-red-600"
                : "bg-neutral-900 text-white"
            )}
          >
            {wishlistLoading ? <Loader2 size={14} className="animate-spin" /> : <Heart size={14} fill={wishlisted ? 'currentColor' : 'none'} />}
            {wishlisted ? t('wishlistRemove') : t('wishlistAdd')}
          </button>
        ) : (
          <Link
            href={`/auth/login?returnTo=/${locale}/marketplace/${tenantSlug}/${slug}`}
            className="flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-neutral-900 text-white cursor-pointer"
          >
            {t('signIn')}
          </Link>
        )}
      </div>

      {/* Lightbox */}
      {lightboxIndex !== null && photos.length > 0 && (
        <Lightbox
          media={photos}
          index={lightboxIndex}
          onClose={() => setLightboxIndex(null)}
          onPrev={() => setLightboxIndex(i => Math.max(0, (i ?? 0) - 1))}
          onNext={() => setLightboxIndex(i => Math.min(photos.length - 1, (i ?? 0) + 1))}
          t={t}
        />
      )}
    </div>
  );
}
