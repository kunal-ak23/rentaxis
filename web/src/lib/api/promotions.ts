import type { PageResponse } from '@/types/listing'
import { ApiError, throwIfNotOk } from '@/lib/api/facilities'
import type {
  DeleteOutcome,
  PendingAdCountDTO,
  PromoAdDTO,
  PromoAdRequest,
  PromoAdStatsDTO,
  PromoApprovalStatus,
  PromoBusinessDTO,
  PromoBusinessRequest,
  PromoterAccessRequest,
  PromoterDTO,
  PromoterInviteRequest,
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
const PROMOTERS = '/api/proxy/v1/promotions/promoters'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

export { ApiError }

/**
 * Reads a delete's `{ outcome }`. A backend that answers with no body (the
 * delete from before archiving existed) hard-deleted, so that is what it means.
 */
export async function readDeleteOutcome(res: Response): Promise<DeleteOutcome> {
  const body: { outcome?: string } | null = await res.json().catch(() => null)
  return body?.outcome === 'ARCHIVED' ? 'ARCHIVED' : 'DELETED'
}

/**
 * `page` is 0-based, matching Spring Data. The Pagination component is 1-based.
 * Archived businesses are left out unless `archived` asks for them (and then
 * only they are listed).
 */
export async function fetchBusinesses(
  page = 0, size = 10, archived = false,
): Promise<PageResponse<PromoBusinessDTO>> {
  const q = new URLSearchParams({ page: String(page), size: String(size) })
  if (archived) q.set('archived', 'true')
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

/**
 * Always succeeds for the user: a business nothing refers to is deleted;
 * one with ads is archived along with them (history kept).
 */
export async function deleteBusiness(id: string): Promise<DeleteOutcome> {
  const res = await fetch(`${BUSINESSES}/${id}`, { method: 'DELETE' })
  await throwIfNotOk(res)
  return readDeleteOutcome(res)
}

/**
 * Brings an archived business back, paused (an admin switches it on by editing).
 * A name now taken by a live business is a 400 whose message says so.
 */
export async function restoreBusiness(id: string): Promise<void> {
  const res = await fetch(`${BUSINESSES}/${id}/restore`, { method: 'POST' })
  await throwIfNotOk(res)
}

/**
 * `page` is 0-based, matching Spring Data. `approvalStatus` narrows the list to
 * ads awaiting review (PENDING) or sent back (REJECTED); omitted, every ad.
 * Archived ads are left out unless `archived` asks for them (and then only
 * they are listed).
 */
export async function fetchAds(
  page = 0, size = 10, businessId?: string, approvalStatus?: PromoApprovalStatus,
  archived = false,
): Promise<PageResponse<PromoAdDTO>> {
  const q = new URLSearchParams({ page: String(page), size: String(size) })
  if (businessId) q.set('businessId', businessId)
  if (approvalStatus) q.set('approvalStatus', approvalStatus)
  if (archived) q.set('archived', 'true')
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

/** Always succeeds for the user: an ad with view history is archived rather than refused. */
export async function deleteAd(id: string): Promise<DeleteOutcome> {
  const res = await fetch(`${ADS}/${id}`, { method: 'DELETE' })
  await throwIfNotOk(res)
  return readDeleteOutcome(res)
}

/**
 * Brings an archived ad back, paused. An ad whose business is still archived is
 * a 400 ("Restore the business first").
 */
export async function restoreAd(id: string): Promise<void> {
  const res = await fetch(`${ADS}/${id}/restore`, { method: 'POST' })
  await throwIfNotOk(res)
}

/** How many ads are waiting for an admin's decision (the badge on the Ads tab). */
export async function fetchPendingAdCount(): Promise<number> {
  const res = await fetch(`${ADS}/pending-count`)
  await throwIfNotOk(res)
  const data: PendingAdCountDTO = await res.json()
  return data.count
}

/**
 * Approves a PENDING ad. `expectedSubmittedAt` is the ad's `submittedAt` exactly
 * as it was loaded: the server approves only the version the admin looked at, and
 * answers 409 `promo.changed` if the promoter edited the ad since (see
 * {@link isAdChangedError}). Any other status than PENDING is a 400.
 */
export async function approveAd(id: string, expectedSubmittedAt: string | null): Promise<void> {
  const res = await fetch(`${ADS}/${id}/approve`, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ expectedSubmittedAt }),
  })
  await throwIfNotOk(res)
}

/**
 * Rejects a PENDING ad. The reason is required (the promoter reads it) and the
 * server caps it at 500. `expectedSubmittedAt` is as for {@link approveAd}.
 */
export async function rejectAd(
  id: string, reason: string, expectedSubmittedAt: string | null,
): Promise<void> {
  const res = await fetch(`${ADS}/${id}/reject`, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ reason, expectedSubmittedAt }),
  })
  await throwIfNotOk(res)
}

/**
 * True for the 409 `promo.changed` a review answers when the ad was edited after
 * the admin loaded it. Its message ("This ad was changed after you opened it.
 * Reload to review the latest version.") is shown as it comes.
 */
export function isAdChangedError(e: unknown): e is ApiError {
  if (!(e instanceof ApiError) || e.status !== 409) return false
  try {
    const body: { code?: unknown } | null = e.body ? JSON.parse(e.body) : null
    return body?.code === 'promo.changed'
  } catch {
    return false
  }
}

export async function fetchAdStats(id: string): Promise<PromoAdStatsDTO> {
  const res = await fetch(`${ADS}/${id}/stats`)
  await throwIfNotOk(res)
  return res.json()
}

/** Every promoter of the organisation, oldest first. Not paged. */
export async function fetchPromoters(): Promise<PromoterDTO[]> {
  const res = await fetch(PROMOTERS)
  await throwIfNotOk(res)
  return res.json()
}

/**
 * Creates the promoter's login (the usual set-password invite email goes out)
 * and records what they may see. An email that already has a Miftah login is a 400.
 */
export async function invitePromoter(body: PromoterInviteRequest): Promise<void> {
  const res = await fetch(PROMOTERS, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
}

/** Replaces a promoter's access in full: send every business and property they should keep. */
export async function updatePromoterAccess(
  userId: string, body: PromoterAccessRequest,
): Promise<void> {
  const res = await fetch(`${PROMOTERS}/${userId}`, {
    method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body),
  })
  await throwIfNotOk(res)
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
