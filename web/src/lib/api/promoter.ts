import type { PageResponse } from '@/types/listing'
import { ApiError, throwIfNotOk } from '@/lib/api/facilities'
import { PROMO_IMAGE_MAX_BYTES } from '@/lib/api/promotions'
import type {
  PromoAdDTO,
  PromoAdRequest,
  PromoBusinessDTO,
  PromoBusinessRequest,
  PromoterMeDTO,
  PromoterPropertyDTO,
} from '@/types/promotion'

// The promoter portal's API: /api/v1/promoter/**, reached through the Next.js
// proxy like every other call (auth is injected by src/proxy.ts). A PROMOTER is
// refused every other route on the backend, so nothing here may fall back to
// the admin /promotions endpoints.
//
// Everything is scoped server-side to the promoter's own access: a business or
// ad they were not given answers 404 (never 403), so the UI treats a 404 as
// "not found", not as a permissions problem.
//
// Error shape: same as lib/api/promotions.ts — business-rule failures are 400s
// whose English `message` is the only discriminator; render it as is.
const BASE = '/api/proxy/v1/promoter'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

export { ApiError, PROMO_IMAGE_MAX_BYTES }

/** Whether the promoter may create businesses, and the organisation they advertise to. */
export async function fetchPromoterMe(): Promise<PromoterMeDTO> {
  const res = await fetch(`${BASE}/me`)
  await throwIfNotOk(res)
  return res.json()
}

/** The properties the promoter may target — by name only. Nothing else about them. */
export async function fetchPromoterProperties(): Promise<PromoterPropertyDTO[]> {
  const res = await fetch(`${BASE}/properties`)
  await throwIfNotOk(res)
  return res.json()
}

/**
 * The businesses the promoter was granted. The contract names the shape of a
 * business but not whether the list is paged, so both a bare array and a Spring
 * page are accepted.
 */
export async function fetchPromoterBusinesses(): Promise<PromoBusinessDTO[]> {
  const res = await fetch(`${BASE}/businesses`)
  await throwIfNotOk(res)
  const data: PromoBusinessDTO[] | PageResponse<PromoBusinessDTO> = await res.json()
  return Array.isArray(data) ? data : data.content
}

/** Needs `canCreateBusinesses`; the business is granted to the creator automatically. */
export async function createPromoterBusiness(body: PromoBusinessRequest): Promise<PromoBusinessDTO> {
  const res = await fetch(`${BASE}/businesses`, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

/** Needs `canCreateBusinesses`. Every live ad of the business goes back to PENDING. */
export async function updatePromoterBusiness(
  id: string, body: PromoBusinessRequest,
): Promise<PromoBusinessDTO> {
  const res = await fetch(`${BASE}/businesses/${id}`, {
    method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

/** `page` is 0-based, matching Spring Data. Ads of the granted businesses, oldest first. */
export async function fetchPromoterAds(
  page = 0, size = 10, businessId?: string,
): Promise<PageResponse<PromoAdDTO>> {
  const q = new URLSearchParams({ page: String(page), size: String(size) })
  if (businessId) q.set('businessId', businessId)
  const res = await fetch(`${BASE}/ads?${q}`)
  await throwIfNotOk(res)
  return res.json()
}

/**
 * The ad is saved PENDING. `propertyIds` must be non-empty and within the
 * promoter's own properties; priority and placement are not the promoter's to
 * set and are not sent.
 */
export async function createPromoterAd(body: PromoAdRequest): Promise<PromoAdDTO> {
  const res = await fetch(`${BASE}/ads`, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

/** Full replace, like the admin PUT; the ad goes back to PENDING. */
export async function updatePromoterAd(id: string, body: PromoAdRequest): Promise<PromoAdDTO> {
  const res = await fetch(`${BASE}/ads/${id}`, {
    method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

/** Refused (400) once the ad has view history, as for an admin. */
export async function deletePromoterAd(id: string): Promise<void> {
  const res = await fetch(`${BASE}/ads/${id}`, { method: 'DELETE' })
  await throwIfNotOk(res)
}

/** Same upload as the admin's (PNG, JPEG, GIF, WebP up to 2 MB), into the organisation's storage. */
export async function uploadPromoterImage(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch(`${BASE}/images`, { method: 'POST', body: form })
  await throwIfNotOk(res)
  const data: { url?: string } = await res.json()
  if (!data.url) throw new ApiError(502, 'Upload succeeded but returned no URL')
  return data.url
}
