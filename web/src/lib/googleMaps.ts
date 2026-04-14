import { Loader } from "@googlemaps/js-api-loader";

let loader: Loader | null = null;

export function getGoogleMapsLoader(): Loader {
  if (!loader) {
    loader = new Loader({
      apiKey: process.env.NEXT_PUBLIC_GOOGLE_MAPS_API_KEY!,
      version: "weekly",
    });
  }
  return loader;
}
