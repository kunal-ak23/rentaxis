export type PromoCategory =
  | 'DINING' | 'FITNESS' | 'RETAIL' | 'SERVICES' | 'HEALTH' | 'EDUCATION' | 'OTHER'

export type PromoCtaType = 'WEBSITE' | 'COUPON' | 'CALL' | 'WHATSAPP' | 'NONE'

export type PromoPlacement = 'HOME_AND_OFFERS' | 'OFFERS_ONLY'

export const PROMO_CATEGORIES: PromoCategory[] =
  ['DINING', 'FITNESS', 'RETAIL', 'SERVICES', 'HEALTH', 'EDUCATION', 'OTHER']

export const PROMO_CTA_TYPES: PromoCtaType[] =
  ['NONE', 'WEBSITE', 'COUPON', 'CALL', 'WHATSAPP']

export const PROMO_PLACEMENTS: PromoPlacement[] = ['HOME_AND_OFFERS', 'OFFERS_ONLY']

export interface PromoBusinessDTO {
  id: string
  nameEn: string
  nameAr: string | null
  logoUrl: string | null
  category: PromoCategory
  phoneE164: string | null
  whatsappE164: string | null
  allowedDomains: string[]
  active: boolean
  adCount: number
  createdAt: string
  updatedAt: string
}

export interface PromoBusinessRequest {
  nameEn: string
  nameAr?: string | null
  logoUrl?: string | null
  category?: PromoCategory
  phoneE164?: string | null
  whatsappE164?: string | null
  allowedDomains?: string[]
  active?: boolean
}

export interface PromoAdDTO {
  id: string
  businessId: string
  businessNameEn: string
  titleEn: string | null
  titleAr: string | null
  subtitleEn: string | null
  subtitleAr: string | null
  backgroundImageUrl: string | null
  accentColor: string | null
  ctaType: PromoCtaType
  ctaLabelEn: string | null
  ctaLabelAr: string | null
  ctaUrl: string | null
  couponCode: string | null
  couponTermsEn: string | null
  couponTermsAr: string | null
  startsAt: string | null
  endsAt: string | null
  priority: number
  placement: PromoPlacement
  active: boolean
  propertyIds: string[]
  impressions: number
  clicks: number
  createdAt: string
  updatedAt: string
}

export interface PromoAdRequest {
  businessId: string
  titleEn?: string | null
  titleAr?: string | null
  subtitleEn?: string | null
  subtitleAr?: string | null
  backgroundImageUrl?: string | null
  accentColor?: string | null
  ctaType?: PromoCtaType
  ctaLabelEn?: string | null
  ctaLabelAr?: string | null
  ctaUrl?: string | null
  couponCode?: string | null
  couponTermsEn?: string | null
  couponTermsAr?: string | null
  startsAt?: string | null
  endsAt?: string | null
  priority?: number
  placement?: PromoPlacement
  propertyIds?: string[]
  active?: boolean
}

export interface PromoAdStatsDTO {
  adId: string
  impressions: number
  clicks: number
  /**
   * Distinct clickers over distinct viewers, clamped to 1 — NOT
   * `clicks / impressions`. Impressions are deduped per renter-day but clicks
   * are not, so the naive ratio is taps-per-renter-day and can exceed 1: three
   * renters, one of whom taps ten times, renders as "333%". Only ever read this
   * field; never derive a rate from the counters on `PromoAdDTO`.
   */
  tapThroughRate: number
  series: Array<{ day: string; impressions: number; clicks: number }>
}

/** Derived, not stored — the backend keeps `active` and the window separately. */
export type AdStatus = 'LIVE' | 'SCHEDULED' | 'EXPIRED' | 'PAUSED'

/**
 * Mirrors the server's eligibility rule in `PromoAdRepository.findEligible`,
 * including its half-open window: `startsAt <= now` and `endsAt > now`.
 *
 * `businessActive` is part of that rule and is NOT on `PromoAdDTO`, so callers
 * must pass it. Without it an admin who deactivates a business — which this
 * app's own copy tells them to do instead of deleting it — would see all of
 * that business's ads still reading "Live" while the renter feed serves none
 * of them, with nothing anywhere saying they had just silenced five campaigns.
 */
export function adStatus(
  ad: PromoAdDTO,
  now: Date = new Date(),
  businessActive = true,
): AdStatus {
  if (!ad.active || !businessActive) return 'PAUSED'
  if (ad.startsAt && new Date(ad.startsAt) > now) return 'SCHEDULED'
  if (ad.endsAt && new Date(ad.endsAt) <= now) return 'EXPIRED'
  return 'LIVE'
}

/**
 * PUT is a FULL REPLACE on both resources: `PromotionService.applyAd` and
 * `applyBusiness` recompute every column from the request, so an omitted field
 * is reset to its null-means-default, not left alone. Building a body by hand
 * to toggle one flag — `updateAd(id, { businessId, active: false })` — type
 * checks and then wipes the title, artwork, CTA, coupon and window, resets
 * priority to 1 and placement to HOME_AND_OFFERS. Start from these instead.
 */
export function adToRequest(ad: PromoAdDTO): PromoAdRequest {
  return {
    businessId: ad.businessId,
    titleEn: ad.titleEn,
    titleAr: ad.titleAr,
    subtitleEn: ad.subtitleEn,
    subtitleAr: ad.subtitleAr,
    backgroundImageUrl: ad.backgroundImageUrl,
    accentColor: ad.accentColor,
    ctaType: ad.ctaType,
    ctaLabelEn: ad.ctaLabelEn,
    ctaLabelAr: ad.ctaLabelAr,
    ctaUrl: ad.ctaUrl,
    couponCode: ad.couponCode,
    couponTermsEn: ad.couponTermsEn,
    couponTermsAr: ad.couponTermsAr,
    startsAt: ad.startsAt,
    endsAt: ad.endsAt,
    priority: ad.priority,
    placement: ad.placement,
    propertyIds: ad.propertyIds,
    active: ad.active,
  }
}

/** See {@link adToRequest} — same full-replace caveat. */
export function businessToRequest(b: PromoBusinessDTO): PromoBusinessRequest {
  return {
    nameEn: b.nameEn,
    nameAr: b.nameAr,
    logoUrl: b.logoUrl,
    category: b.category,
    phoneE164: b.phoneE164,
    whatsappE164: b.whatsappE164,
    allowedDomains: b.allowedDomains,
    active: b.active,
  }
}
