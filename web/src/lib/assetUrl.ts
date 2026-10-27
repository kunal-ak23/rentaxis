/**
 * Where a stored file's URL has to be fetched from (issue #300).
 *
 * <p>`/api/v1/assets/serve/**` used to be open to anyone, so a private document —
 * a ticket photo, a settlement deduction scan, a lease PDF — could be dropped
 * straight into an `<img src>` and the browser, carrying no session at all, would
 * get it. The backend now requires a caller for everything outside the public
 * `assets/` folder, so those same URLs have to go through `/api/proxy`, where the
 * middleware attaches the signed-in user's identity headers.
 *
 * Public assets deliberately do NOT go through the proxy: an organisation's logo
 * is rendered on the login page and on public listing pages, where there is no
 * session to attach and the proxy would answer 401.
 *
 * Anything that is not a local serve URL — an Azure blob URL, a `data:` URI, an
 * absolute link — is returned untouched.
 */

const LOCAL_SERVE_PREFIX = '/api/v1/assets/serve/'
const PROXIED_SERVE_PREFIX = '/api/proxy/v1/assets/serve/'
const PUBLIC_FOLDER = 'assets/'

export function assetSrc(url: string | null | undefined): string {
  if (!url) return ''
  if (!url.startsWith(LOCAL_SERVE_PREFIX)) return url
  const key = url.slice(LOCAL_SERVE_PREFIX.length)
  if (key.toLowerCase().startsWith(PUBLIC_FOLDER)) return url
  return PROXIED_SERVE_PREFIX + key
}

const STAFF_LISTING_MEDIA_PREFIX = '/api/listings/'
const PROXIED_LISTING_MEDIA_PREFIX = '/api/proxy/listings/'

/**
 * Where a listing photo or floor plan is fetched from (bug 26/27).
 *
 * The API names a stored file by a backend route, never by its private blob URL:
 * staff DTOs carry `/api/listings/{id}/media/{mediaId}/file`, which needs the
 * signed-in user's identity, so it goes through `/api/proxy`; the marketplace and
 * public pages carry `/api/v1/public/listing-media/{mediaId}`, which needs no
 * session and is fetched as it is. An external link is returned untouched.
 */
export function listingMediaSrc(url: string | null | undefined): string {
  if (!url) return ''
  // The API may name it absolutely (PR #401 R1: installed apps need that); the
  // staff route still has to go through this site's proxy, so only its path counts.
  const path = backendRoutePath(url, STAFF_LISTING_MEDIA_PREFIX)
  if (path) {
    return PROXIED_LISTING_MEDIA_PREFIX + path.slice(STAFF_LISTING_MEDIA_PREFIX.length)
  }
  return url
}

/**
 * The path of `url` when it is one of the backend's own routes under `prefix` —
 * given as a path, or as an absolute URL whose path is that route.
 */
function backendRoutePath(url: string, prefix: string): string | null {
  if (url.startsWith(prefix)) return url
  if (!/^https?:\/\//i.test(url)) return null
  try {
    const u = new URL(url)
    return u.pathname.startsWith(prefix) ? u.pathname + u.search : null
  } catch {
    return null
  }
}

const PROMO_IMAGE_ROUTE = '/api/v1/public/promo-images/'
const PROXIED_PROMO_IMAGE_PREFIX = '/api/proxy/v1/promotions/images/'
const PROXIED_PROMOTER_IMAGE_PREFIX = '/api/proxy/v1/promoter/images/'

/** Which authenticated route previews an uploaded promotion image: the admin's or a promoter's. */
export type PromoImageScope = 'admin' | 'promoter'

/**
 * Where an admin page loads a promotion image from (ux6 item 2). An uploaded
 * image is stored as its public route, which serves it only once a live ad or
 * business shows it; the editor previews it before that, so admin pages fetch
 * it through the organisation-scoped admin route instead. An https link to
 * artwork hosted elsewhere is returned untouched.
 */
export function promoImageSrc(
  url: string | null | undefined,
  scope: PromoImageScope = 'admin',
): string {
  if (!url) return ''
  const path = backendRoutePath(url, PROMO_IMAGE_ROUTE)
  if (path) {
    // A promoter is refused every /promotions route, so its previews load
    // through the promoter's own image route (same files, access-checked).
    const prefix = scope === 'promoter' ? PROXIED_PROMOTER_IMAGE_PREFIX : PROXIED_PROMO_IMAGE_PREFIX
    return prefix + path.slice(PROMO_IMAGE_ROUTE.length)
  }
  return url
}

/**
 * Where to show an uploaded attachment (a ticket photo, a settlement deduction
 * scan) from. A file kept on the server's own disk has a local serve URL, which
 * {@link assetSrc} already routes; a file in Azure storage has a bare blob URL in
 * the organisation's private container, which answers 403 to the browser — the
 * listing-photo problem of bug 26/27 — so it is shown through the attachment's
 * own authenticated download route instead.
 */
export function storedFileSrc(fileUrl: string | null | undefined, downloadPath: string): string {
  if (!fileUrl) return ''
  if (fileUrl.startsWith(LOCAL_SERVE_PREFIX) || fileUrl.startsWith('data:')) return assetSrc(fileUrl)
  return downloadPath
}
