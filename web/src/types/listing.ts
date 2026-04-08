export type ListingStatus = 'DRAFT' | 'PUBLISHED' | 'UNLISTED' | 'UPCOMING'
export type Furnishing = 'UNFURNISHED' | 'SEMI_FURNISHED' | 'FULLY_FURNISHED'
export type ViewType = 'SEA' | 'CITY' | 'POOL' | 'GARDEN' | 'STREET' | 'COMMUNITY' | 'OTHER'
export type ListingMediaType = 'PHOTO' | 'FLOOR_PLAN' | 'VIDEO_URL' | 'TOUR_360_URL'
export type InterestStatus = 'ACTIVE' | 'NOTIFIED' | 'WITHDRAWN'
export type ListingAmenity =
  'POOL' | 'GYM' | 'SAUNA' | 'STEAM_ROOM' | 'JACUZZI' | 'KIDS_PLAY_AREA' | 'KIDS_POOL' |
  'BBQ_AREA' | 'GARDEN' | 'ROOFTOP_LOUNGE' | 'SECURITY_24_7' | 'CCTV' | 'CONCIERGE' |
  'INTERCOM' | 'COVERED_PARKING' | 'VISITOR_PARKING' | 'EV_CHARGING' | 'ELEVATOR' |
  'CENTRAL_AC' | 'DISTRICT_COOLING' | 'MAIDS_ROOM' | 'STUDY_ROOM' | 'STORAGE_ROOM' |
  'LAUNDRY_ROOM' | 'BUILT_IN_WARDROBES' | 'BALCONY' | 'PRIVATE_GARDEN' | 'MAID_SERVICE' |
  'PET_FRIENDLY' | 'SMART_HOME' | 'SOLAR_POWER' | 'NEAR_METRO' | 'NEAR_SCHOOL' | 'NEAR_MALL' |
  'SEA_VIEW' | 'OTHER'

export interface UnitListingSummaryDTO {
  id: string; title: string; propertyName: string | null; bedrooms: number | null
  annualRent: number | null; status: ListingStatus; coverPhotoUrl: string | null
  interestsCount: number; updatedAt: string; slug: string
}

export interface UnitListingDTO {
  id: string; unitId: string; status: ListingStatus
  titleEn: string; titleAr: string | null
  descriptionEn: string | null; descriptionAr: string | null
  bedrooms: number | null; bathrooms: number | null; sizeSqft: number | null
  floor: number | null; parkingSpaces: number | null
  furnishing: Furnishing | null; viewType: ViewType | null
  annualRent: number | null; securityDeposit: number | null
  minLeaseMonths: number | null; chequesAccepted: number | null
  dewaIncluded: boolean | null; chillerIncluded: boolean | null
  utilitiesEstimate: number | null; availableFrom: string | null
  slug: string; seoTitle: string | null; seoDescription: string | null
  seoKeywords: string | null; ogImageUrl: string | null
  lat: number | null; lng: number | null
  publishedAt: string | null; createdAt: string; updatedAt: string
  amenities: Array<{ amenity: ListingAmenity; customLabel: string | null }>
  media: UnitListingMediaDTO[]
}

export interface UnitListingMediaDTO {
  id: string; mediaType: ListingMediaType; url: string
  caption: string | null; sortOrder: number; isCover: boolean
}

export interface UnitListingCreateRequest {
  unitId: string; titleEn: string; titleAr?: string
  descriptionEn?: string; descriptionAr?: string
  bedrooms?: number; bathrooms?: number; sizeSqft?: number
  floor?: number; parkingSpaces?: number
  furnishing?: Furnishing; viewType?: ViewType
  annualRent?: number; securityDeposit?: number
  minLeaseMonths?: number; chequesAccepted?: number
  dewaIncluded?: boolean; chillerIncluded?: boolean
  utilitiesEstimate?: number; availableFrom?: string
  seoTitle?: string; seoDescription?: string; seoKeywords?: string; ogImageUrl?: string
  lat?: number; lng?: number
  amenities?: Array<{ amenity: ListingAmenity; customLabel?: string }>
}

export interface InterestDTO {
  id: string; listingId: string; renterUserId: string
  renterName: string | null; renterEmail: string | null; renterPhone: string | null
  note: string | null; status: InterestStatus; createdAt: string
}

export interface PageResponse<T> {
  content: T[]; totalElements: number; totalPages: number
  number: number; size: number
}
