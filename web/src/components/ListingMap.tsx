"use client";

interface ListingMapProps {
  lat: number;
  lng: number;
  label?: string;
}

export default function ListingMap({ lat, lng }: ListingMapProps) {
  const apiKey = process.env.NEXT_PUBLIC_GOOGLE_MAPS_API_KEY;
  const src = `https://www.google.com/maps/embed/v1/place?key=${apiKey}&q=${lat},${lng}&zoom=15`;

  return (
    <div className="w-full h-48 rounded-xl overflow-hidden">
      <iframe
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
