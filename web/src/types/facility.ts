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
  /** True when an approved booking covers today; an attached bay is never held. */
  held: boolean
  /** Attached bays belong to one unit and are never bookable. Optional for older fixtures/servers. */
  attachedUnitId?: string | null
  attachedUnitNumber?: string | null
  /** EN name of the attached unit's building; null if the unit has none. */
  attachedBuildingName?: string | null
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
  /** Parking range end (LocalDate); amenity time slot (LocalTime "HH:mm:ss"). Sent by the server, optional here for older fixtures. */
  preferredEndDate?: string | null
  preferredStartTime?: string | null
  preferredEndTime?: string | null
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

/** An approved parking booking not yet ended; ISO yyyy-MM-dd, `to: null` = open-ended, `from: null` = since before records. */
export interface BookedRange {
  from: string | null
  to: string | null
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
  /** Approved bookings not yet ended, so the renter can pick free dates. Optional for older servers. */
  bookedRanges?: BookedRange[]
}

/** A bay attached to the renter's current unit: included with the unit, no booking or fee. */
export interface AllocatedParkingDTO {
  id: string
  propertyId: string
  propertyName: string | null
  unitId: string
  unitNumber: string | null
  spotNumber: string
  level: string | null
  covered: boolean
  photoUrls: string[]
}

export interface MyFacilitiesDTO {
  amenities: RenterAmenityDTO[]
  parkingSpots: RenterParkingSpotDTO[]
  /** Optional for older servers/fixtures; treat a missing list as empty. */
  allocatedParking?: AllocatedParkingDTO[]
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
  /** Create and attach in one transaction; omit for a bookable bay. */
  attachedUnitId?: string
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
  /** Create and attach in one transaction; omit for a bookable bay. */
  attachedUnitId?: string
}

export interface BookingCreateRequest {
  resourceType: BookingResourceType
  resourceId: string
  unitId: string
  preferredDate?: string
  /** Parking only: last day of the booking (inclusive); omitted = open-ended. */
  preferredEndDate?: string
  note?: string
}

export interface DecisionRequest {
  adminNote?: string
}
