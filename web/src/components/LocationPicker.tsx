"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { ExternalLink, MapPin } from "lucide-react";
import { importLibrary } from "@googlemaps/js-api-loader";
import { ensureGoogleMapsConfigured } from "@/lib/googleMaps";

interface LocationPickerProps {
  lat: number | null;
  lng: number | null;
  onLocationChange: (lat: number, lng: number) => void;
  /** What the place is called, shown when the map cannot load (e.g. the listing title). */
  label?: string;
}

type GoogleAuthWindow = Window & { gm_authFailure?: () => void };

/** Google Maps is usable only with a key; without one it renders its own error page. */
const hasMapsKey = () => Boolean(process.env.NEXT_PUBLIC_GOOGLE_MAPS_API_KEY?.trim());

export default function LocationPicker({ lat, lng, onLocationChange, label }: LocationPickerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<google.maps.Map | null>(null);
  const markerRef = useRef<google.maps.Marker | null>(null);
  const [failed, setFailed] = useState(() => !hasMapsKey());

  useEffect(() => {
    if (!hasMapsKey()) return;
    // Google calls this global when the key is refused (wrong key, referrer not
    // allowed, billing off) — the "didn't load Google Maps correctly" page.
    const w = window as GoogleAuthWindow;
    const previous = w.gm_authFailure;
    w.gm_authFailure = () => {
      setFailed(true);
      previous?.();
    };
    ensureGoogleMapsConfigured();

    Promise.all([
      importLibrary("maps"),
      importLibrary("marker"),
    ]).then(([{ Map }, { Marker }]) => {
      if (!containerRef.current || mapRef.current) return;

      const center = { lat: lat ?? 25.2048, lng: lng ?? 55.2708 };
      const map = new Map(containerRef.current, {
        center,
        zoom: lat && lng ? 15 : 11,
        mapTypeControl: false,
        streetViewControl: false,
        fullscreenControl: false,
      });
      mapRef.current = map;

      const attachDragEnd = (m: google.maps.Marker) => {
        m.addListener("dragend", () => {
          const pos = m.getPosition();
          if (pos) onLocationChange(pos.lat(), pos.lng());
        });
      };

      if (lat && lng) {
        const m = new Marker({ position: center, map, draggable: true });
        attachDragEnd(m);
        markerRef.current = m;
      }

      map.addListener("click", (e: google.maps.MapMouseEvent) => {
        if (!e.latLng) return;
        if (markerRef.current) {
          markerRef.current.setPosition(e.latLng);
        } else {
          importLibrary("marker").then(({ Marker: M }) => {
            if (!mapRef.current) return;
            const m = new M({ position: e.latLng!, map: mapRef.current, draggable: true });
            attachDragEnd(m);
            markerRef.current = m;
          });
        }
        onLocationChange(e.latLng.lat(), e.latLng.lng());
      });
    }).catch(() => setFailed(true));

    return () => {
      w.gm_authFailure = previous;
      markerRef.current?.setMap(null);
      markerRef.current = null;
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Sync marker when lat/lng change externally (e.g. from text inputs)
  useEffect(() => {
    if (failed || !mapRef.current || !lat || !lng) return;
    const pos = { lat, lng };
    if (markerRef.current) {
      markerRef.current.setPosition(pos);
    } else {
      ensureGoogleMapsConfigured();
      importLibrary("marker").then(({ Marker }) => {
        if (!mapRef.current) return;
        const m = new Marker({ position: pos, map: mapRef.current, draggable: true });
        m.addListener("dragend", () => {
          const p = m.getPosition();
          if (p) onLocationChange(p.lat(), p.lng());
        });
        markerRef.current = m;
      });
    }
    mapRef.current.setCenter(pos);
  }, [lat, lng, onLocationChange, failed]);

  if (failed) {
    return <MapFallback lat={lat} lng={lng} label={label} />;
  }
  return <div ref={containerRef} className="w-full h-[400px] rounded-xl z-0" />;
}

/**
 * Shown instead of the map when it cannot load (no key on this server, or the
 * key refused): the place in words and a link that opens it in Google Maps. The
 * coordinates can still be typed in the fields under the map.
 */
function MapFallback({ lat, lng, label }: { lat: number | null; lng: number | null; label?: string }) {
  const t = useTranslations("Listings");
  const hasPin = lat != null && lng != null;
  const query = hasPin ? `${lat},${lng}` : (label ?? "").trim();
  const href = query
    ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`
    : null;
  return (
    <div
      role="status"
      data-testid="map-fallback"
      className="flex min-h-48 w-full flex-col items-center justify-center gap-3 rounded-xl bg-input/40 px-6 py-8 text-center"
    >
      <MapPin size={28} className="text-primary" aria-hidden="true" />
      <p className="text-sm font-medium text-foreground">{t("mapUnavailable")}</p>
      {(label?.trim() || hasPin) && (
        <p className="text-xs text-muted">
          {label?.trim()}
          {label?.trim() && hasPin ? " · " : ""}
          {hasPin && <span dir="ltr">{`${lat}, ${lng}`}</span>}
        </p>
      )}
      <p className="text-xs text-muted">{t("mapUnavailableHint")}</p>
      {href && (
        <a
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-1.5 text-xs font-semibold text-foreground hover:bg-input"
        >
          {t("openInMaps")}
          <ExternalLink size={12} aria-hidden="true" />
        </a>
      )}
    </div>
  );
}
