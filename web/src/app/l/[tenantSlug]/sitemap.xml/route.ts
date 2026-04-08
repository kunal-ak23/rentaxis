import { NextResponse } from 'next/server'

export async function GET(
  _req: Request,
  { params }: { params: Promise<{ tenantSlug: string }> }
) {
  const { tenantSlug } = await params
  const backendUrl = process.env.BACKEND_URL || 'http://localhost:8080'
  const url = `${backendUrl}/public/l/${tenantSlug}/sitemap.xml`

  const res = await fetch(url, { next: { revalidate: 3600 } })
  if (!res.ok) {
    return new NextResponse('Not found', { status: 404 })
  }

  const xml = await res.text()
  return new NextResponse(xml, {
    headers: {
      'Content-Type': 'application/xml',
      'Cache-Control': 's-maxage=3600',
    },
  })
}
