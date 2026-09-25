// Types for the amenities & parking booking feature.
// Field names mirror the backend DTO records exactly — do not rename.

export type BookingResourceType = 'AMENITY' | 'PARKING_SPOT'
export type BookingRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'RELEASED'

/** F14-50: how a booking is charged. Parking is FREE or PER_BOOKING. */
export type BookingFeeType = "FREE" | "PER_BOOKING" | "PER_HOUR"

export interface AmenityDTO {
  feeType?: BookingFeeType
  feeAmount?: number | null
  id: string
  propertyId: string
  nameEn: string
  nameAr: string | null
  description: string | null
  bookable: boolean
  active: boolean
  buildingIds: string[]
  pendingCount: number
  createdAt: string
  updatedAt: string
}

export interface ParkingSpotDTO {
  feeType?: BookingFeeType
  feeAmount?: number | null
  id: string
  propertyId: string
  spotNumber: string
  level: string | null
  covered: boolean
  active: boolean
  buildingIds: string[]
  held: boolean
  pendingCount: number
  createdAt: string
  updatedAt: string
}

export interface BookingRequestDTO {
  /** F14-50: the fee quoted, and the charge raised on approval. */
  feeAmount?: number
  chargeId?: string | null
  id: string
  resourceType: BookingResourceType
  amenityId: string | null
  parkingSpotId: string | null
  resourceName: string
  propertyId: string
  unitId: string
  unitNumber: string | null
  renterUserId: string
  renterName: string | null
  renterEmail: string | null
  renterPhone: string | null
  note: string | null
  preferredDate: string | null
  status: BookingRequestStatus
  adminNote: string | null
  decidedByUserId: string | null
  decidedAt: string | null
  createdAt: string
}

export interface BookingDetailDTO {
  request: BookingRequestDTO
  /** PENDING + APPROVED requests for the same resource, excluding this one. */
  otherRequests: BookingRequestDTO[]
}

export interface RenterAmenityDTO {
  feeType?: BookingFeeType
  feeAmount?: number | null
  id: string
  propertyId: string
  propertyName: string | null
  nameEn: string
  nameAr: string | null
  description: string | null
  bookable: boolean
  pendingCount: number
}

export interface RenterParkingSpotDTO {
  feeType?: BookingFeeType
  feeAmount?: number | null
  id: string
  propertyId: string
  propertyName: string | null
  spotNumber: string
  level: string | null
  covered: boolean
  held: boolean
  pendingCount: number
}

export interface MyFacilitiesDTO {
  amenities: RenterAmenityDTO[]
  parkingSpots: RenterParkingSpotDTO[]
}

export interface AmenityCreateRequest {
  feeType?: BookingFeeType
  feeAmount?: number | null
  propertyId: string
  nameEn: string
  nameAr?: string
  description?: string
  bookable?: boolean
  buildingIds?: string[]
}

/** null/omitted = unchanged; a non-null buildingIds replaces the scope set. */
export interface AmenityUpdateRequest {
  feeType?: BookingFeeType
  feeAmount?: number | null
  nameEn?: string
  nameAr?: string
  description?: string
  bookable?: boolean
  active?: boolean
  buildingIds?: string[]
}

export interface ParkingSpotCreateRequest {
  propertyId: string
  spotNumber: string
  level?: string
  covered?: boolean
  buildingIds?: string[]
}

export interface ParkingSpotUpdateRequest {
  feeType?: BookingFeeType
  feeAmount?: number | null
  spotNumber?: string
  level?: string
  covered?: boolean
  active?: boolean
  buildingIds?: string[]
}

export interface ParkingSpotBulkCreateRequest {
  propertyId: string
  spotNumbers: string[]
  level?: string
  covered?: boolean
  buildingIds?: string[]
}

export interface BookingCreateRequest {
  resourceType: BookingResourceType
  resourceId: string
  unitId: string
  preferredDate?: string
  note?: string
}

export interface DecisionRequest {
  adminNote?: string
}
