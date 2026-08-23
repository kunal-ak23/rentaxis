export type PromoCategory =
  | 'DINING' | 'FITNESS' | 'RETAIL' | 'SERVICES' | 'HEALTH' | 'EDUCATION' | 'OTHER'

export type PromoCtaType = 'WEBSITE' | 'COUPON' | 'CALL' | 'WHATSAPP' | 'NONE'

export type PromoPlacement = 'HOME_AND_OFFERS' | 'OFFERS_ONLY'

export const PROMO_CATEGORIES: PromoCategory[] =
  ['DINING', 'FITNESS', 'RETAIL', 'SERVICES', 'HEALTH', 'EDUCATION', 'OTHER']

export const PROMO_CTA_TYPES: PromoCtaType[] =
  ['NONE', 'WEBSITE', 'COUPON', 'CALL', 'WHATSAPP']

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
  tapThroughRate: number
  series: Array<{ day: string; impressions: number; clicks: number }>
}

/** Derived, not stored — the backend keeps `active` and the window separately. */
export type AdStatus = 'LIVE' | 'SCHEDULED' | 'EXPIRED' | 'PAUSED'

export function adStatus(ad: PromoAdDTO, now: Date = new Date()): AdStatus {
  if (!ad.active) return 'PAUSED'
  if (ad.startsAt && new Date(ad.startsAt) > now) return 'SCHEDULED'
  if (ad.endsAt && new Date(ad.endsAt) <= now) return 'EXPIRED'
  return 'LIVE'
}
