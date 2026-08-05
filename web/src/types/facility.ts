// Types for the amenities & parking booking feature.
// Field names mirror the backend DTO records exactly — do not rename.

export type BookingResourceType = 'AMENITY' | 'PARKING_SPOT'
export type BookingRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'RELEASED'

export interface AmenityDTO {
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
  propertyId: string
  nameEn: string
  nameAr?: string
  description?: string
  bookable?: boolean
  buildingIds?: string[]
}

/** null/omitted = unchanged; a non-null buildingIds replaces the scope set. */
export interface AmenityUpdateRequest {
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
