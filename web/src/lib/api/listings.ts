import type {
  UnitListingSummaryDTO,
  UnitListingDTO,
  UnitListingCreateRequest,
  InterestDTO,
  PageResponse,
} from '@/types/listing'

const BASE = '/api/proxy/v1/listings'

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
  if (params.search) q.set('search', params.search)
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
  if (!res.ok) throw new Error(`Failed to fetch listing: ${res.status}`)
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
    body: JSON.stringify(mediaIds),
  })
  if (!res.ok) throw new Error(`Failed to reorder media: ${res.status}`)
}

export async function fetchInterests(
  listingId: string,
  token?: string,
  page = 0
): Promise<PageResponse<InterestDTO>> {
  const headers: HeadersInit = {}
  if (token) headers['Authorization'] = `Bearer ${token}`
  const q = new URLSearchParams({ page: String(page), size: '10' })
  const res = await fetch(`${BASE}/${listingId}/interests?${q}`, { headers })
  if (!res.ok) throw new Error(`Failed to fetch interests: ${res.status}`)
  return res.json()
}
