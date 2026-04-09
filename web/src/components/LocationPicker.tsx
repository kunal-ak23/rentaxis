"use client";

import { useEffect, useRef } from "react";
import L from "leaflet";
import "leaflet/dist/leaflet.css";

// Fix default marker icon (webpack breaks the default icon paths)
const defaultIcon = L.icon({
  iconUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png",
  iconRetinaUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png",
  shadowUrl: "https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png",
  iconSize: [25, 41],
  iconAnchor: [12, 41],
  popupAnchor: [1, -34],
  shadowSize: [41, 41],
});

interface LocationPickerProps {
  lat: number | null;
  lng: number | null;
  onLocationChange: (lat: number, lng: number) => void;
}

export default function LocationPicker({ lat, lng, onLocationChange }: LocationPickerProps) {
  const mapRef = useRef<L.Map | null>(null);
  const markerRef = useRef<L.Marker | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    // Default center: Dubai
    const center: [number, number] = lat && lng ? [lat, lng] : [25.2048, 55.2708];
    const zoom = lat && lng ? 15 : 11;

    const map = L.map(containerRef.current).setView(center, zoom);
    mapRef.current = map;

    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      maxZoom: 19,
    }).addTo(map);

    // Place marker if we have coords
    if (lat && lng) {
      markerRef.current = L.marker([lat, lng], { icon: defaultIcon, draggable: true }).addTo(map);
      markerRef.current.on("dragend", () => {
        const pos = markerRef.current!.getLatLng();
        onLocationChange(pos.lat, pos.lng);
      });
    }

    // Click to place/move marker
    map.on("click", (e: L.LeafletMouseEvent) => {
      const { lat: clickLat, lng: clickLng } = e.latlng;
      if (markerRef.current) {
        markerRef.current.setLatLng([clickLat, clickLng]);
      } else {
        markerRef.current = L.marker([clickLat, clickLng], { icon: defaultIcon, draggable: true }).addTo(map);
        markerRef.current.on("dragend", () => {
          const pos = markerRef.current!.getLatLng();
          onLocationChange(pos.lat, pos.lng);
        });
      }
      onLocationChange(clickLat, clickLng);
    });

    return () => {
      map.remove();
      mapRef.current = null;
      markerRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Sync marker position when lat/lng change externally (e.g. from text inputs)
  useEffect(() => {
    if (!mapRef.current) return;
    if (lat && lng) {
      if (markerRef.current) {
        markerRef.current.setLatLng([lat, lng]);
      } else {
        markerRef.current = L.marker([lat, lng], { icon: defaultIcon, draggable: true }).addTo(mapRef.current);
        markerRef.current.on("dragend", () => {
          const pos = markerRef.current!.getLatLng();
          onLocationChange(pos.lat, pos.lng);
        });
      }
      mapRef.current.setView([lat, lng], mapRef.current.getZoom());
    }
  }, [lat, lng, onLocationChange]);

  return <div ref={containerRef} className="w-full h-[400px] rounded-xl z-0" />;
}
