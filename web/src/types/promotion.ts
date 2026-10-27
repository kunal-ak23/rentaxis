export type PromoCategory =
  | 'DINING' | 'FITNESS' | 'RETAIL' | 'SERVICES' | 'HEALTH' | 'EDUCATION' | 'OTHER'

export type PromoCtaType = 'WEBSITE' | 'COUPON' | 'CALL' | 'WHATSAPP' | 'NONE'

export type PromoPlacement = 'HOME_AND_OFFERS' | 'OFFERS_ONLY'

/**
 * Where an ad stands with the organisation's admins. Admin-written ads are
 * APPROVED on create; a promoter's ad is PENDING after every write of theirs
 * (create, edit, or an edit of its business) until an admin approves it.
 */
export type PromoApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/**
 * Where one value of a coupon ad's redirect link comes from. The renter-data
 * sources (name, phone, email, property, unit) are filled in by the app for the
 * tenant who taps, after they confirm what is shared; STATIC is the ad's own text.
 */
export type PromoRedirectParamSource =
  | 'COUPON_CODE' | 'RENTER_NAME' | 'RENTER_PHONE' | 'RENTER_EMAIL'
  | 'PROPERTY_NAME' | 'UNIT_NUMBER' | 'STATIC'

export const PROMO_REDIRECT_SOURCES: PromoRedirectParamSource[] = [
  'COUPON_CODE', 'RENTER_NAME', 'RENTER_PHONE', 'RENTER_EMAIL',
  'PROPERTY_NAME', 'UNIT_NUMBER', 'STATIC',
]

/** One `name=value` pair appended to a coupon ad's redirect link. */
export interface PromoRedirectParam {
  /** `^[A-Za-z0-9_.\-]{1,40}$`, unique within the ad. */
  name: string
  source: PromoRedirectParamSource
  /** Required (trimmed, up to 200) only for STATIC; ignored and cleared for every other source. */
  value?: string | null
}

/** Mirrors the server's limits on a coupon ad's redirect link (PromotionService). */
export const REDIRECT_URL_MAX = 2048
export const REDIRECT_PARAMS_MAX = 10
export const REDIRECT_PARAM_NAME = /^[A-Za-z0-9_.-]{1,40}$/
export const REDIRECT_PARAM_VALUE_MAX = 200

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
  /**
   * COUPON ads only: the link opened after the code is copied, and the
   * parameters added to it. Absent from an older backend, null/empty when unused.
   */
  redirectUrl?: string | null
  redirectParams?: PromoRedirectParam[] | null
  /** PENDING / APPROVED / REJECTED. Only APPROVED ads can reach renters. */
  approvalStatus: PromoApprovalStatus
  /** The admin's reason when REJECTED; null otherwise. */
  rejectionReason: string | null
  /** When a promoter last sent the ad for approval; null for admin-only ads. */
  submittedAt: string | null
  reviewedAt: string | null
  /** The promoter who last wrote the ad; null for an ad only admins have written. */
  submittedByName: string | null
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
  /** COUPON ads only; the server clears both for any other ctaType. */
  redirectUrl?: string | null
  redirectParams?: PromoRedirectParam[]
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
 * What an ad's status pill says. Approval comes first: an ad awaiting approval
 * or rejected is not showing to renters whatever its window says, so the pill
 * says that rather than "Live" / "Scheduled". Only an APPROVED ad falls
 * through to the window and the active switch.
 */
export type AdDisplayStatus = 'PENDING' | 'REJECTED' | AdStatus

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

/** The pill an ad's row shows: approval first, then {@link adStatus}. See {@link AdDisplayStatus}. */
export function adDisplayStatus(
  ad: PromoAdDTO,
  now: Date = new Date(),
  businessActive = true,
): AdDisplayStatus {
  if (ad.approvalStatus === 'PENDING') return 'PENDING'
  if (ad.approvalStatus === 'REJECTED') return 'REJECTED'
  return adStatus(ad, now, businessActive)
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
    redirectUrl: ad.redirectUrl ?? null,
    redirectParams: ad.redirectParams ?? [],
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

// ---------------------------------------------------------------------------
// Promoters (an outside advertiser's login). Admin side: Promotions → Promoters.
// Promoter side: the /dashboard/promoter portal.
// ---------------------------------------------------------------------------

/** One promoter and what an admin has granted them (`GET /promotions/promoters`). */
export interface PromoterDTO {
  userId: string
  name: string
  email: string
  phoneNumber: string | null
  /** The login's status; `INACTIVE` once an admin has deactivated it from Users. */
  status: string
  canCreateBusinesses: boolean
  businessIds: string[]
  propertyIds: string[]
}

/** `POST /promotions/promoters` — creates the login (set-password invite) and its access. */
export interface PromoterInviteRequest {
  name: string
  email: string
  phoneNumber?: string | null
  canCreateBusinesses: boolean
  businessIds: string[]
  propertyIds: string[]
}

/** `PUT /promotions/promoters/{userId}` — replaces the access in full. */
export interface PromoterAccessRequest {
  canCreateBusinesses: boolean
  businessIds: string[]
  propertyIds: string[]
}

/** `GET /promoter/me`. */
export interface PromoterMeDTO {
  canCreateBusinesses: boolean
  organisationName: string
}

/** `GET /promoter/properties` — only the properties the promoter was given. */
export interface PromoterPropertyDTO {
  id: string
  nameEn: string
  nameAr: string | null
}

/** `GET /promotions/ads/pending-count`. */
export interface PendingAdCountDTO {
  count: number
}
