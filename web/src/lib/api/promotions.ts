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
const BUSINESSES = '/api/proxy/v1/promotions/businesses'
const ADS = '/api/proxy/v1/promotions/ads'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

export { ApiError }

export async function fetchBusinesses(
  page: number, size: number,
): Promise<PageResponse<PromoBusinessDTO>> {
  const res = await fetch(`${BUSINESSES}?page=${page}&size=${size}`)
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

export async function fetchAds(
  page: number, size: number, businessId?: string,
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

/** Reuses the shared asset endpoint — image-only, 2 MB cap, enforced server-side. */
export async function uploadPromoImage(file: File, folder: string): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  form.append('folder', folder)
  const res = await fetch(`/api/proxy/v1/assets/upload?folder=${encodeURIComponent(folder)}`, {
    method: 'POST', body: form,
  })
  await throwIfNotOk(res)
  const data: { url?: string } = await res.json()
  if (!data.url) throw new ApiError(500, 'Upload succeeded but returned no URL')
  return data.url
}
