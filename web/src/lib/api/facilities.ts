import type { PageResponse } from '@/types/listing'
import type {
  AmenityDTO,
  AmenityCreateRequest,
  AmenityUpdateRequest,
  ParkingSpotDTO,
  ParkingSpotCreateRequest,
  ParkingSpotUpdateRequest,
  ParkingSpotBulkCreateRequest,
  BookingRequestDTO,
  BookingDetailDTO,
  BookingCreateRequest,
  DecisionRequest,
  MyFacilitiesDTO,
  BookingResourceType,
  BookingRequestStatus,
} from '@/types/facility'

// Auth is injected by the Next.js middleware for every /api/proxy/* request
// (see src/proxy.ts) — no Authorization header needed here.
const AMENITIES = '/api/proxy/v1/amenities'
const SPOTS = '/api/proxy/v1/parking-spots'
const BOOKINGS = '/api/proxy/v1/bookings'
const FACILITIES = '/api/proxy/v1/facilities'

/** Backend's cap on spotNumbers per bulk-create request (ParkingSpotBulkCreateRequest). */
export const MAX_BULK_SPOT_NUMBERS = 500

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/**
 * Error carrying the HTTP status so callers can branch on 409 (spot held) / 400.
 * `message` is always a safe, displayable string (parsed from the backend's
 * `{error, message, status}` JSON shape, never raw HTML/JSON); the original
 * response text is kept on `body` for debugging.
 */
export class ApiError extends Error {
  constructor(public status: number, message: string, public body?: string) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * Extracts a safe, displayable message from a response body. The backend's
 * error shape is `{error, message, status}`; a proxy failure (e.g. a 502)
 * can instead return raw HTML, so JSON parsing is attempted and any failure
 * (or a parsed value without a string `message`) falls back to a generic,
 * synthesized message — the raw text is never surfaced to callers directly.
 */
function parseErrorMessage(text: string, status: number): string {
  if (text) {
    try {
      const parsed: unknown = JSON.parse(text)
      if (parsed && typeof parsed === 'object' && typeof (parsed as { message?: unknown }).message === 'string') {
        return (parsed as { message: string }).message
      }
    } catch {
      // Not JSON (e.g. a proxy 502 HTML page) — fall through to the generic message.
    }
  }
  return `Request failed (status ${status})`
}

async function handle<T>(res: Response, action: string): Promise<T> {
  const text = await res.text().catch(() => '')
  if (!res.ok) {
    throw new ApiError(res.status, parseErrorMessage(text, res.status), text)
  }
  // 204 No Content (or any other empty-bodied success response) has nothing to parse.
  if (res.status === 204 || !text) {
    return undefined as T
  }
  return JSON.parse(text) as T
}

async function handleVoid(res: Response, action: string): Promise<void> {
  const text = await res.text().catch(() => '')
  if (!res.ok) {
    throw new ApiError(res.status, parseErrorMessage(text, res.status), text)
  }
}

// ─── Amenities (admin) ───────────────────────────────────────────────────────

export async function fetchAmenities(
  propertyId: string,
  page = 0,
  size = 10
): Promise<PageResponse<AmenityDTO>> {
  const q = new URLSearchParams({ propertyId, page: String(page), size: String(size) })
  return handle(await fetch(`${AMENITIES}?${q}`), 'fetchAmenities')
}

export async function createAmenity(body: AmenityCreateRequest): Promise<AmenityDTO> {
  return handle(
    await fetch(AMENITIES, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'createAmenity'
  )
}

export async function updateAmenity(id: string, body: AmenityUpdateRequest): Promise<AmenityDTO> {
  return handle(
    await fetch(`${AMENITIES}/${id}`, { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'updateAmenity'
  )
}

export async function deactivateAmenity(id: string): Promise<void> {
  return handleVoid(await fetch(`${AMENITIES}/${id}`, { method: 'DELETE' }), 'deactivateAmenity')
}

// ─── Parking spots (admin) ───────────────────────────────────────────────────

export async function fetchParkingSpots(
  propertyId: string,
  page = 0,
  size = 10
): Promise<PageResponse<ParkingSpotDTO>> {
  const q = new URLSearchParams({ propertyId, page: String(page), size: String(size) })
  return handle(await fetch(`${SPOTS}?${q}`), 'fetchParkingSpots')
}

export async function createParkingSpot(body: ParkingSpotCreateRequest): Promise<ParkingSpotDTO> {
  return handle(
    await fetch(SPOTS, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'createParkingSpot'
  )
}

export async function bulkCreateParkingSpots(
  body: ParkingSpotBulkCreateRequest
): Promise<ParkingSpotDTO[]> {
  return handle(
    await fetch(`${SPOTS}/bulk`, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'bulkCreateParkingSpots'
  )
}

export async function updateParkingSpot(
  id: string,
  body: ParkingSpotUpdateRequest
): Promise<ParkingSpotDTO> {
  return handle(
    await fetch(`${SPOTS}/${id}`, { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'updateParkingSpot'
  )
}

export async function deactivateParkingSpot(id: string): Promise<void> {
  return handleVoid(await fetch(`${SPOTS}/${id}`, { method: 'DELETE' }), 'deactivateParkingSpot')
}

// ─── Bookings (admin inbox) ──────────────────────────────────────────────────

export interface BookingFilters {
  propertyId?: string
  status?: BookingRequestStatus
  resourceType?: BookingResourceType
  page?: number
  size?: number
}

export async function fetchBookings(
  filters: BookingFilters
): Promise<PageResponse<BookingRequestDTO>> {
  const q = new URLSearchParams()
  if (filters.propertyId) q.set('propertyId', filters.propertyId)
  if (filters.status) q.set('status', filters.status)
  if (filters.resourceType) q.set('resourceType', filters.resourceType)
  if (filters.page !== undefined) q.set('page', String(filters.page))
  if (filters.size !== undefined) q.set('size', String(filters.size))
  return handle(await fetch(`${BOOKINGS}?${q}`), 'fetchBookings')
}

export async function fetchBooking(id: string): Promise<BookingDetailDTO> {
  return handle(await fetch(`${BOOKINGS}/${id}`), 'fetchBooking')
}

/** Throws ApiError with status 409 when the parking spot is already APPROVED elsewhere. */
export async function approveBooking(id: string, body: DecisionRequest): Promise<BookingRequestDTO> {
  return handle(
    await fetch(`${BOOKINGS}/${id}/approve`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(body),
    }),
    'approveBooking'
  )
}

export async function rejectBooking(id: string, body: DecisionRequest): Promise<BookingRequestDTO> {
  return handle(
    await fetch(`${BOOKINGS}/${id}/reject`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(body),
    }),
    'rejectBooking'
  )
}

/** Admin OR the owning renter; APPROVED parking only. */
export async function releaseBooking(id: string): Promise<BookingRequestDTO> {
  return handle(await fetch(`${BOOKINGS}/${id}/release`, { method: 'POST' }), 'releaseBooking')
}

// ─── Renter side ─────────────────────────────────────────────────────────────

export async function fetchMyFacilities(): Promise<MyFacilitiesDTO> {
  return handle(await fetch(`${FACILITIES}/my`), 'fetchMyFacilities')
}

/** Idempotent: an existing PENDING request by the caller for the same resource is returned. */
export async function createBooking(body: BookingCreateRequest): Promise<BookingRequestDTO> {
  return handle(
    await fetch(BOOKINGS, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'createBooking'
  )
}

export async function fetchMyBookings(): Promise<BookingRequestDTO[]> {
  return handle(await fetch(`${BOOKINGS}/my`), 'fetchMyBookings')
}

export async function cancelBooking(id: string): Promise<BookingRequestDTO> {
  return handle(await fetch(`${BOOKINGS}/${id}/cancel`, { method: 'POST' }), 'cancelBooking')
}

// ─── Bulk spot-number entry parser ───────────────────────────────────────────

/**
 * Parses comma/semicolon/newline-separated spot numbers (comma for typed
 * entry, semicolon/newline for spreadsheet-paste input) with numeric range
 * expansion.
 *   "B1-05, B1-06"  -> ["B1-05", "B1-06"]        (prefixes differ around the dash → literal)
 *   "P10-P20"       -> ["P10", "P11", ..., "P20"] (same prefix both sides → expanded)
 *   "10-12"         -> ["10", "11", "12"]
 *   "A1\nA2;A3"     -> ["A1", "A2", "A3"]
 * Zero-padding of the start bound is preserved ("P08-P10" -> P08, P09, P10).
 * A range that would expand to more than MAX_BULK_SPOT_NUMBERS entries —
 * the backend's spotNumbers cap — is kept literal to guard against typos.
 * Duplicates are removed; order of first appearance is kept.
 */
export function parseSpotNumbers(input: string): string[] {
  const out: string[] = []
  for (const raw of input.split(/[,;\n]/)) {
    const entry = raw.trim()
    if (!entry) continue
    const m = entry.match(/^(.*?)(\d+)\s*-\s*(.*?)(\d+)$/)
    if (m && m[1] === m[3]) {
      const prefix = m[1]
      const start = parseInt(m[2], 10)
      const end = parseInt(m[4], 10)
      const width = m[2].length
      if (end >= start && end - start < MAX_BULK_SPOT_NUMBERS) {
        for (let n = start; n <= end; n++) {
          out.push(`${prefix}${String(n).padStart(width, '0')}`)
        }
        continue
      }
    }
    out.push(entry)
  }
  return Array.from(new Set(out))
}
