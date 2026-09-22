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
