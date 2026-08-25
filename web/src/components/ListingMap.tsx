"use client";

import { ExternalLink, MapPin } from "lucide-react";

interface ListingMapProps {
  lat: number;
  lng: number;
  label?: string;
}

export default function ListingMap({ lat, lng, label }: ListingMapProps) {
  const apiKey = process.env.NEXT_PUBLIC_GOOGLE_MAPS_API_KEY;
  const directionsUrl = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${lat},${lng}`)}`;

  if (!apiKey?.trim()) {
    return (
      <a
        href={directionsUrl}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={`View ${label || "property location"} on Google Maps`}
        className="flex h-48 w-full flex-col items-center justify-center gap-3 rounded-xl border border-neutral-200 bg-neutral-100 px-6 text-center text-neutral-600 transition-colors hover:bg-neutral-200"
      >
        <MapPin size={28} className="text-indigo-500" aria-hidden="true" />
        <span className="text-sm font-medium">View location on Google Maps</span>
        <ExternalLink size={15} aria-hidden="true" />
      </a>
    );
  }

  const src = `https://www.google.com/maps/embed/v1/place?key=${apiKey}&q=${lat},${lng}&zoom=15`;

  return (
    <div className="w-full h-48 rounded-xl overflow-hidden">
      <iframe
        title={label ? `Map of ${label}` : "Property location map"}
        src={src}
        width="100%"
        height="100%"
        loading="lazy"
        className="border-0"
        allowFullScreen
        referrerPolicy="no-referrer-when-downgrade"
      />
    </div>
  );
}
