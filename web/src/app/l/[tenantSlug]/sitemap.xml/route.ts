import { NextResponse } from 'next/server'

// The tenant slug is only known at request time. Forcing this route dynamic
// prevents Next.js from prerendering a placeholder sitemap during the Docker
// build, when the production backend hostname is not resolvable yet.
export const dynamic = 'force-dynamic'

// The public origin of this deployment, as seen by the crawler. Behind the
// reverse proxy (Caddy) the original scheme/host arrive via X-Forwarded-*;
// fall back to the request URL for local dev.
function publicOrigin(req: Request): string {
  const requestUrl = new URL(req.url)
  const host =
    req.headers.get('x-forwarded-host')?.split(',')[0].trim() ||
    req.headers.get('host') ||
    requestUrl.host
  const proto =
    req.headers.get('x-forwarded-proto')?.split(',')[0].trim() ||
    requestUrl.protocol.replace(':', '')
  return `${proto}://${host}`
}

export async function GET(
  req: Request,
  { params }: { params: Promise<{ tenantSlug: string }> }
) {
  const { tenantSlug } = await params
  const backendUrl = process.env.BACKEND_URL || 'http://localhost:8080'
  const url = `${backendUrl}/public/l/${tenantSlug}/sitemap.xml`

  const res = await fetch(url, { next: { revalidate: 3600 } })
  if (!res.ok) {
    return new NextResponse('Not found', { status: 404 })
  }

  // The backend emits root-relative <loc> paths (/l/<tenant>/<slug>), but the
  // sitemap protocol requires fully-qualified URLs — prefix them with the
  // public origin of this request. Already-absolute locs are left untouched.
  const origin = publicOrigin(req)
  const xml = (await res.text()).replaceAll('<loc>/', `<loc>${origin}/`)
  return new NextResponse(xml, {
    headers: {
      'Content-Type': 'application/xml',
      'Cache-Control': 's-maxage=3600',
    },
  })
}
