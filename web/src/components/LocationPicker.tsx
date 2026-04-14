"use client";

import { useEffect, useRef } from "react";
import { getGoogleMapsLoader } from "@/lib/googleMaps";

interface LocationPickerProps {
  lat: number | null;
  lng: number | null;
  onLocationChange: (lat: number, lng: number) => void;
}

export default function LocationPicker({ lat, lng, onLocationChange }: LocationPickerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<google.maps.Map | null>(null);
  const markerRef = useRef<google.maps.Marker | null>(null);

  useEffect(() => {
    getGoogleMapsLoader()
      .importLibrary("maps")
      .then(({ Map }) => {
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

        getGoogleMapsLoader()
          .importLibrary("marker")
          .then(({ Marker }) => {
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
                const m = new Marker({ position: e.latLng, map, draggable: true });
                attachDragEnd(m);
                markerRef.current = m;
              }
              onLocationChange(e.latLng.lat(), e.latLng.lng());
            });
          });
      });

    return () => {
      markerRef.current?.setMap(null);
      markerRef.current = null;
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Sync marker when lat/lng change externally (e.g. from text inputs)
  useEffect(() => {
    if (!mapRef.current || !lat || !lng) return;
    const pos = { lat, lng };
    if (markerRef.current) {
      markerRef.current.setPosition(pos);
    } else {
      getGoogleMapsLoader()
        .importLibrary("marker")
        .then(({ Marker }) => {
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
  }, [lat, lng, onLocationChange]);

  return <div ref={containerRef} className="w-full h-[400px] rounded-xl z-0" />;
}
