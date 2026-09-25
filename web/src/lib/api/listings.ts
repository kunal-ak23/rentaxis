import { cache } from 'react'
import type {
  UnitListingSummaryDTO,
  UnitListingDTO,
  UnitListingCreateRequest,
  InterestDTO,
  PageResponse,
  PublicListingDTO,
} from '@/types/listing'

const BASE = '/api/proxy/listings'
const MARKET_BASE = '/api/proxy/marketplace'

// ─── Public (unauthenticated) preview ────────────────────────────────────────

/**
 * Fetches the public (unauthenticated) listing preview from the backend.
 * Called server-side; uses the backend URL directly to bypass the Next.js proxy.
 * Returns null when the listing is not found (404), throws on other errors.
 * Wrapped with React.cache() so generateMetadata and the page component share
 * a single fetch per ISR cache miss.
 */
export const fetchPublicListing = cache(async (
  tenantSlug: string,
  unitSlug: string
): Promise<PublicListingDTO | null> => {
  const backendUrl = process.env.BACKEND_URL || 'http://localhost:8080'
  const url = `${backendUrl}/public/l/${tenantSlug}/${unitSlug}`
  const res = await fetch(url, { next: { revalidate: 3600 } })
  if (res.status === 404) return null
  if (!res.ok) throw new Error(`fetchPublicListing failed: ${res.status}`)
  return res.json() as Promise<PublicListingDTO>
})

// ─── Marketplace (renter-facing) helpers ────────────────────────────────────

export interface MarketplaceListingsParams {
  page?: number
  size?: number
  /** Minimum bedroom count — the backend filters `bedrooms >= minBedrooms`. */
  minBedrooms?: number
  minRent?: number
  maxRent?: number
  furnishing?: string
  availableNow?: boolean
  sort?: string
}

export async function fetchMarketplaceListings(
  tenantSlug: string,
  params: MarketplaceListingsParams,
  token?: string
): Promise<PageResponse<UnitListingSummaryDTO>> {
  const q = new URLSearchParams()
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  if (params.minBedrooms !== undefined) q.set('minBedrooms', String(params.minBedrooms))
  if (params.minRent !== undefined) q.set('minRent', String(params.minRent))
  if (params.maxRent !== undefined) q.set('maxRent', String(params.maxRent))
  if (params.furnishing) q.set('furnishing', params.furnishing)
  if (params.availableNow) q.set('availableNow', 'true')
  if (params.sort) q.set('sort', params.sort)

  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(`${MARKET_BASE}/${tenantSlug}/listings?${q.toString()}`, { headers })
  if (!res.ok) throw new Error(`Failed to fetch marketplace listings: ${res.status}`)
  return res.json()
}

export async function fetchMarketplaceListing(
  tenantSlug: string,
  slug: string,
  token?: string
): Promise<UnitListingDTO> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${MARKET_BASE}/${tenantSlug}/listings/${slug}`, { headers })
  if (!res.ok) throw new Error(`Failed to fetch marketplace listing: ${res.status}`)
  return res.json()
}

export async function addInterest(
  listingId: string,
  note: string | undefined,
  token: string
): Promise<void> {
  const headers: HeadersInit = { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` }
  const res = await fetch(`${MARKET_BASE}/listings/${listingId}/interest`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ note: note || null }),
  })
  if (!res.ok) throw new Error(`Failed to add interest: ${res.status}`)
}

export async function removeInterest(listingId: string, token: string): Promise<void> {
  const headers: HeadersInit = { 'Authorization': `Bearer ${token}` }
  const res = await fetch(`${MARKET_BASE}/listings/${listingId}/interest`, {
    method: 'DELETE',
    headers,
  })
  if (!res.ok) throw new Error(`Failed to remove interest: ${res.status}`)
}

export async function fetchWishlist(token: string): Promise<UnitListingSummaryDTO[]> {
  const headers: HeadersInit = { 'Authorization': `Bearer ${token}` }
  const res = await fetch(`${MARKET_BASE}/me/wishlist`, { headers })
  if (!res.ok) throw new Error(`Failed to fetch wishlist: ${res.status}`)
  return res.json()
}

export async function fetchListings(
  params: {
    page?: number
    size?: number
    status?: string
    search?: string
    sort?: string
  },
  token?: string
): Promise<PageResponse<UnitListingSummaryDTO>> {
  const q = new URLSearchParams()
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  if (params.status) q.set('status', params.status)
  // The backend's title-search query param is named `q`.
  if (params.search) q.set('q', params.search)
  if (params.sort) q.set('sort', params.sort)

  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(`${BASE}?${q.toString()}`, { headers })
  if (!res.ok) throw new Error(`Failed to fetch listings: ${res.status}`)
  return res.json()
}

export async function fetchListing(id: string, token?: string): Promise<UnitListingDTO> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}/${id}`, { headers })
  if (!res.ok) {
    // The status travels with the error so a caller can tell "no such listing"
    // (404) from "could not load it right now".
    throw Object.assign(new Error(`Failed to fetch listing: ${res.status}`), { status: res.status })
  }
  return res.json()
}

export async function createListing(
  body: UnitListingCreateRequest,
  token?: string
): Promise<UnitListingDTO> {
  const headers: HeadersInit = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(BASE, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const err = await res.text()
    throw new Error(err || `Failed to create listing: ${res.status}`)
  }
  return res.json()
}

export async function updateListing(
  id: string,
  body: Partial<UnitListingCreateRequest>,
  token?: string
): Promise<UnitListingDTO> {
  const headers: HeadersInit = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}/${id}`, {
    method: 'PUT',
    headers,
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const err = await res.text()
    throw new Error(err || `Failed to update listing: ${res.status}`)
  }
  return res.json()
}

export async function publishListing(id: string, token?: string): Promise<void> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}/${id}/publish`, { method: 'POST', headers })
  if (!res.ok) throw new Error(`Failed to publish listing: ${res.status}`)
}

export async function unlistListing(id: string, token?: string): Promise<void> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}/${id}/unlist`, { method: 'POST', headers })
  if (!res.ok) throw new Error(`Failed to unlist listing: ${res.status}`)
}

export async function deleteListing(id: string, token?: string): Promise<void> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}/${id}`, { method: 'DELETE', headers })
  if (!res.ok) throw new Error(`Failed to delete listing: ${res.status}`)
}

export async function uploadMedia(
  listingId: string,
  formData: FormData,
  token?: string
): Promise<{ id: string; url: string }> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}/${listingId}/media`, {
    method: 'POST',
    headers,
    body: formData,
  })
  if (!res.ok) throw new Error(`Failed to upload media: ${res.status}`)
  return res.json()
}

export async function deleteMedia(
  listingId: string,
  mediaId: string,
  token?: string
): Promise<void> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}/${listingId}/media/${mediaId}`, {
    method: 'DELETE',
    headers,
  })
  if (!res.ok) throw new Error(`Failed to delete media: ${res.status}`)
}

export async function reorderMedia(
  listingId: string,
  mediaIds: string[],
  token?: string
): Promise<void> {
  const headers: HeadersInit = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}/${listingId}/media/reorder`, {
    method: 'PUT',
    headers,
    // Backend deserializes into ReorderRequest(List<UUID> mediaIds) — the body
    // must be an object wrapping the array, not the bare array.
    body: JSON.stringify({ mediaIds }),
  })
  if (!res.ok) throw new Error(`Failed to reorder media: ${res.status}`)
}

export async function fetchInterests(
  listingId: string,
  token?: string,
  page = 0,
  size = 10
): Promise<PageResponse<InterestDTO>> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const q = new URLSearchParams({ page: String(page), size: String(size) })
  const res = await fetch(`${BASE}/${listingId}/interests?${q}`, { headers })
  if (!res.ok) throw new Error(`Failed to fetch interests: ${res.status}`)
  return res.json()
}

/** F14-51: the enquiry becomes a draft lease (renter record created if new, no login). Returns the draft. */
export async function createLeaseFromInterest(listingId: string, interestId: string, token?: string): Promise<{ id: string }> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}/${listingId}/interests/${interestId}/lease`, { method: 'POST', headers })
  if (!res.ok) {
    let message = `Failed to create the lease: ${res.status}`
    try { const b = await res.json(); if (b?.message) message = b.message } catch { /* keep the status */ }
    throw new Error(message)
  }
  return res.json()
}

/** F14-51: publish the listing again when the unit becomes vacant (opt-in). */
export async function setRepublishWhenVacant(listingId: string, on: boolean, token?: string): Promise<void> {
  const headers: HeadersInit = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE}/${listingId}/republish-when-vacant`, { method: 'PUT', headers, body: JSON.stringify({ on }) })
  if (!res.ok) throw new Error(`Failed to save: ${res.status}`)
}
