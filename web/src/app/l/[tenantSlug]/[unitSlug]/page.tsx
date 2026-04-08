import { notFound } from 'next/navigation'
import type { Metadata } from 'next'
import { MapPin, Bed, Bath, Building, LogIn } from 'lucide-react'
import { fetchPublicListing } from '@/lib/api/listings'

export const revalidate = 3600

// ─── Metadata ─────────────────────────────────────────────────────────────────

export async function generateMetadata({
  params,
}: {
  params: Promise<{ tenantSlug: string; unitSlug: string }>
}): Promise<Metadata> {
  const { tenantSlug, unitSlug } = await params
  let listing = null
  try {
    listing = await fetchPublicListing(tenantSlug, unitSlug)
  } catch {
    // swallow — notFound below handles missing data
  }
  if (!listing) {
    return { title: 'Listing not found', robots: { index: false, follow: false } }
  }

  const title =
    listing.seoTitle ||
    `${listing.title} - ${listing.area ?? ''}, ${listing.emirate ?? ''}`
  const description =
    listing.seoDescription ||
    `${listing.bedrooms != null ? `${listing.bedrooms}BR` : ''} in ${listing.area ?? ''}, ${listing.emirate ?? ''}. ${listing.rentRangeLabel}/year`

  const images: { url: string }[] = listing.ogImageUrl
    ? [{ url: listing.ogImageUrl }]
    : listing.coverPhotoUrl
    ? [{ url: listing.coverPhotoUrl }]
    : []

  return {
    title,
    description,
    keywords: listing.seoKeywords ?? undefined,
    openGraph: {
      title: listing.seoTitle ?? title,
      description: listing.seoDescription ?? description,
      images,
      type: 'website',
      locale: 'en_AE',
      alternateLocale: ['ar_AE'],
    },
    twitter: {
      card: 'summary_large_image',
      title: listing.seoTitle ?? title,
      description: listing.seoDescription ?? description,
    },
    alternates: {
      canonical: `/l/${tenantSlug}/${unitSlug}`,
      languages: {
        en: `/en/l/${tenantSlug}/${unitSlug}`,
        ar: `/ar/l/${tenantSlug}/${unitSlug}`,
      },
    },
    robots: { index: true, follow: true },
  }
}

// ─── Page ──────────────────────────────────────────────────────────────────────

export default async function PublicListingPage({
  params,
}: {
  params: Promise<{ tenantSlug: string; unitSlug: string }>
}) {
  const { tenantSlug, unitSlug } = await params

  let listing = null
  try {
    listing = await fetchPublicListing(tenantSlug, unitSlug)
  } catch {
    notFound()
  }
  if (!listing) notFound()

  // ─── JSON-LD ────────────────────────────────────────────────────────────────
  const jsonLd = {
    '@context': 'https://schema.org',
    '@type': 'Apartment',
    name: listing.seoTitle ?? listing.title,
    description: listing.seoDescription ?? undefined,
    image: listing.coverPhotoUrl ?? undefined,
    numberOfBedrooms: listing.bedrooms ?? undefined,
    numberOfBathroomsTotal: listing.bathrooms ?? undefined,
    address: {
      '@type': 'PostalAddress',
      addressLocality: listing.area ?? undefined,
      addressRegion: listing.emirate ?? undefined,
      addressCountry: 'AE',
    },
    ...(listing.approxLat != null && listing.approxLng != null
      ? {
          geo: {
            '@type': 'GeoCoordinates',
            latitude: listing.approxLat,
            longitude: listing.approxLng,
          },
        }
      : {}),
    offers: {
      '@type': 'Offer',
      priceCurrency: 'AED',
      price: listing.rentRangeLabel,
      availability: 'https://schema.org/InStock',
    },
  }

  // ─── Derived display values ──────────────────────────────────────────────────
  const furnishingLabel =
    listing.furnishing === 'UNFURNISHED'
      ? 'Unfurnished'
      : listing.furnishing === 'SEMI_FURNISHED'
      ? 'Semi-Furnished'
      : listing.furnishing === 'FULLY_FURNISHED'
      ? 'Fully Furnished'
      : null

  const truncatedDesc = listing.seoDescription
    ? listing.seoDescription.length > 200
      ? listing.seoDescription.slice(0, 200) + '\u2026'
      : listing.seoDescription
    : null

  const callbackUrl = encodeURIComponent(`/marketplace/${tenantSlug}/${unitSlug}`)

  return (
    <>
      {/* JSON-LD structured data */}
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
      />

      <div className="min-h-screen bg-neutral-50">
        {/* Hero image */}
        {listing.coverPhotoUrl ? (
          /* eslint-disable-next-line @next/next/no-img-element */
          <img
            src={listing.coverPhotoUrl}
            alt={listing.title}
            className="w-full max-h-64 object-cover"
          />
        ) : (
          <div className="w-full max-h-64 h-56 bg-gradient-to-br from-blue-100 to-indigo-200 flex items-center justify-center">
            <Building size={48} className="text-indigo-400" />
          </div>
        )}

        <div className="max-w-2xl mx-auto px-4 py-6 space-y-5">
          {/* Title */}
          <h1 className="text-xl font-bold text-neutral-900">{listing.title}</h1>

          {/* Rent badge */}
          <div className="inline-flex items-center gap-2 px-4 py-2 rounded-full bg-indigo-600 text-white font-semibold text-sm shadow-sm">
            {listing.rentRangeLabel}
          </div>

          {/* Quick facts bar */}
          <div className="flex flex-wrap gap-4 text-sm text-neutral-700">
            {listing.bedrooms != null && (
              <span className="flex items-center gap-1.5">
                <Bed size={15} className="text-neutral-400" />
                {listing.bedrooms === 0
                  ? 'Studio'
                  : `${listing.bedrooms} ${listing.bedrooms === 1 ? 'bed' : 'beds'}`}
              </span>
            )}
            {listing.bathrooms != null && (
              <span className="flex items-center gap-1.5">
                <Bath size={15} className="text-neutral-400" />
                {listing.bathrooms} {listing.bathrooms === 1 ? 'bath' : 'baths'}
              </span>
            )}
            {furnishingLabel && (
              <span className="flex items-center gap-1.5 text-neutral-600">
                {furnishingLabel}
              </span>
            )}
          </div>

          {/* Availability chip */}
          {listing.availableNow ? (
            <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-semibold bg-green-100 text-green-700">
              {listing.availableLabel}
            </span>
          ) : (
            <span className="inline-flex items-center px-3 py-1 rounded-full text-xs font-semibold bg-blue-100 text-blue-700">
              {listing.availableLabel}
            </span>
          )}

          {/* Location */}
          <div className="flex items-start gap-2 text-sm text-neutral-600">
            <MapPin size={16} className="text-neutral-400 mt-0.5 shrink-0" />
            <div>
              {listing.buildingName && (
                <p className="font-medium text-neutral-800">{listing.buildingName}</p>
              )}
              <p>
                {[listing.area, listing.emirate].filter(Boolean).join(', ')}
              </p>
            </div>
          </div>

          {/* Map placeholder */}
          <div className="h-36 rounded-xl bg-neutral-200 border border-neutral-200 flex items-center justify-center text-neutral-500 text-sm gap-2">
            <MapPin size={18} className="text-neutral-400" />
            {listing.area && listing.emirate
              ? `${listing.area}, ${listing.emirate}`
              : listing.area ?? listing.emirate ?? 'UAE'}
          </div>

          {/* Truncated description */}
          {truncatedDesc && (
            <p className="text-sm text-neutral-600 leading-relaxed">{truncatedDesc}</p>
          )}

          {/* Login CTA card */}
          {listing.loginRequired && (
            <div className="rounded-2xl bg-gradient-to-br from-blue-600 to-indigo-700 p-6 text-white shadow-lg">
              <p className="text-base font-semibold mb-1">
                See full details, photos, and save this listing
              </p>
              <p className="text-blue-200 text-sm mb-4">
                Create a free account or sign in to view all photos, exact location, pricing details, and save to your wishlist.
              </p>
              <a
                href={`/auth/signin?callbackUrl=${callbackUrl}`}
                className="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-white text-indigo-700 font-semibold text-sm hover:bg-blue-50 transition-colors shadow"
              >
                <LogIn size={16} />
                Login to Continue
              </a>
            </div>
          )}
        </div>
      </div>
    </>
  )
}
