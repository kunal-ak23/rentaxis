import { setOptions } from "@googlemaps/js-api-loader";

let configured = false;

export function ensureGoogleMapsConfigured(): void {
  if (configured) return;
  setOptions({
    key: process.env.NEXT_PUBLIC_GOOGLE_MAPS_API_KEY!,
    v: "weekly",
  });
  configured = true;
}
