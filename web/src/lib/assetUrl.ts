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
  if (url.startsWith(STAFF_LISTING_MEDIA_PREFIX)) {
    return PROXIED_LISTING_MEDIA_PREFIX + url.slice(STAFF_LISTING_MEDIA_PREFIX.length)
  }
  return url
}

const PROMO_IMAGE_ROUTE = '/api/v1/public/promo-images/'
const PROXIED_PROMO_IMAGE_PREFIX = '/api/proxy/v1/promotions/images/'

/**
 * Where an admin page loads a promotion image from (ux6 item 2). An uploaded
 * image is stored as its public route, which serves it only once a live ad or
 * business shows it; the editor previews it before that, so admin pages fetch
 * it through the organisation-scoped admin route instead. An https link to
 * artwork hosted elsewhere is returned untouched.
 */
export function promoImageSrc(url: string | null | undefined): string {
  if (!url) return ''
  if (url.startsWith(PROMO_IMAGE_ROUTE)) {
    return PROXIED_PROMO_IMAGE_PREFIX + url.slice(PROMO_IMAGE_ROUTE.length)
  }
  return url
}
