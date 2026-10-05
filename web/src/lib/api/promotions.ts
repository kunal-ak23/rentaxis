import type { PageResponse } from '@/types/listing'
import { ApiError, throwIfNotOk } from '@/lib/api/facilities'
import type {
  PromoAdDTO,
  PromoAdRequest,
  PromoAdStatsDTO,
  PromoBusinessDTO,
  PromoBusinessRequest,
} from '@/types/promotion'

// Auth is injected by the Next.js middleware for every /api/proxy/* request
// (see src/proxy.ts) — no Authorization header needed here.
//
// ERROR SHAPE, read this before writing UI against these calls. Every
// business-rule failure on this API arrives as a 400, not a mix of 400/409:
// PromotionService throws BusinessRuleViolationException for a duplicate
// business name, a delete blocked by existing ads, a bad window, a ctaUrl off
// the allowlist, an out-of-range priority and a missing tenant header alike,
// and GlobalExceptionHandler maps that type unconditionally to BAD_REQUEST.
// So `err.status` cannot discriminate between them — `err.message` is the only
// signal, and it is server-side ENGLISH.
//
// The practical consequence for the localized Promotions.* error strings:
// use them for checks the client can make BEFORE submitting (https, allowed
// domain, required fields), and render `err.message` for anything the server
// rejects. Do not substring-match the English text to pick a translation; the
// backend treats that wording as an implementation detail and its tests assert
// on it. Machine-readable error codes are tracked as a backend follow-up.
const BUSINESSES = '/api/proxy/v1/promotions/businesses'
const ADS = '/api/proxy/v1/promotions/ads'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

export { ApiError }

/** `page` is 0-based, matching Spring Data. The Pagination component is 1-based. */
export async function fetchBusinesses(
  page = 0, size = 10,
): Promise<PageResponse<PromoBusinessDTO>> {
  const q = new URLSearchParams({ page: String(page), size: String(size) })
  const res = await fetch(`${BUSINESSES}?${q}`)
  await throwIfNotOk(res)
  return res.json()
}

export async function createBusiness(body: PromoBusinessRequest): Promise<PromoBusinessDTO> {
  const res = await fetch(BUSINESSES, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

export async function updateBusiness(
  id: string, body: PromoBusinessRequest,
): Promise<PromoBusinessDTO> {
  const res = await fetch(`${BUSINESSES}/${id}`, {
    method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

export async function deleteBusiness(id: string): Promise<void> {
  const res = await fetch(`${BUSINESSES}/${id}`, { method: 'DELETE' })
  await throwIfNotOk(res)
}

/** `page` is 0-based, matching Spring Data. */
export async function fetchAds(
  page = 0, size = 10, businessId?: string,
): Promise<PageResponse<PromoAdDTO>> {
  const q = new URLSearchParams({ page: String(page), size: String(size) })
  if (businessId) q.set('businessId', businessId)
  const res = await fetch(`${ADS}?${q}`)
  await throwIfNotOk(res)
  return res.json()
}

export async function createAd(body: PromoAdRequest): Promise<PromoAdDTO> {
  const res = await fetch(ADS, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

export async function updateAd(id: string, body: PromoAdRequest): Promise<PromoAdDTO> {
  const res = await fetch(`${ADS}/${id}`, {
    method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
  return res.json()
}

export async function deleteAd(id: string): Promise<void> {
  const res = await fetch(`${ADS}/${id}`, { method: 'DELETE' })
  await throwIfNotOk(res)
}

export async function fetchAdStats(id: string): Promise<PromoAdStatsDTO> {
  const res = await fetch(`${ADS}/${id}/stats`)
  await throwIfNotOk(res)
  return res.json()
}

/** Largest image the upload accepts (PromotionImageService.MAX_BYTES). */
export const PROMO_IMAGE_MAX_BYTES = 2 * 1024 * 1024

/**
 * Uploads an ad's artwork or a business logo into the organisation's private
 * storage (ux6 item 2). Answers the route to save in `backgroundImageUrl` or
 * `logoUrl`; the server decides the type from the bytes (PNG, JPEG, GIF, WebP).
 */
export async function uploadPromoImage(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch('/api/proxy/v1/promotions/images', { method: 'POST', body: form })
  await throwIfNotOk(res)
  const data: { url?: string } = await res.json()
  // 502, not 500 — a 2xx without a URL is a contract mismatch, not a server fault.
  if (!data.url) throw new ApiError(502, 'Upload succeeded but returned no URL')
  return data.url
}
