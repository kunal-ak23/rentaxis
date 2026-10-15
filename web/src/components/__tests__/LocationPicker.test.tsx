import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../messages/en.json";
import ar from "../../../messages/ar.json";

/**
 * Tutorial 26: with no Maps key (or a refused one) the Location tab showed
 * Google's "This page didn't load Google Maps correctly". It now explains, names
 * the place and links to it in Google Maps; the coordinates stay editable below.
 */

const loader = vi.hoisted(() => ({ importLibrary: vi.fn(), setOptions: vi.fn() }));
vi.mock("@googlemaps/js-api-loader", () => loader);

import LocationPicker from "../LocationPicker";

afterEach(() => {
    cleanup();
    vi.unstubAllEnvs();
    vi.clearAllMocks();
});

function renderPicker(locale: "en" | "ar", lat: number | null, lng: number | null) {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <LocationPicker lat={lat} lng={lng} label="Marina Heights A-102" onLocationChange={vi.fn()} />
        </NextIntlClientProvider>,
    );
}

describe("LocationPicker fallback", () => {
    it("without a key: no Google load, the place in words and an open-in-maps link", () => {
        vi.stubEnv("NEXT_PUBLIC_GOOGLE_MAPS_API_KEY", "");
        renderPicker("en", 25.08, 55.14);
        expect(loader.importLibrary).not.toHaveBeenCalled();
        const box = screen.getByTestId("map-fallback");
        expect(box.textContent).toContain(en.Listings.mapUnavailable);
        expect(box.textContent).toContain("Marina Heights A-102");
        expect(box.textContent).toContain("25.08, 55.14");
        const link = screen.getByRole("link", { name: en.Listings.openInMaps });
        expect(link.getAttribute("href")).toBe("https://www.google.com/maps/search/?api=1&query=25.08%2C55.14");
        expect(link.getAttribute("target")).toBe("_blank");
    });

    it("without a pin, searches by the name (Arabic)", () => {
        vi.stubEnv("NEXT_PUBLIC_GOOGLE_MAPS_API_KEY", "");
        renderPicker("ar", null, null);
        expect(screen.getByText(ar.Listings.mapUnavailable)).toBeInTheDocument();
        expect(screen.getByRole("link", { name: ar.Listings.openInMaps }).getAttribute("href"))
            .toContain(encodeURIComponent("Marina Heights A-102"));
    });

    it("with a key Google refuses, falls back too", async () => {
        vi.stubEnv("NEXT_PUBLIC_GOOGLE_MAPS_API_KEY", "bad-key");
        loader.importLibrary.mockReturnValue(new Promise(() => {}));
        renderPicker("en", 25.08, 55.14);
        expect(screen.queryByTestId("map-fallback")).toBeNull();
        await act(async () => {
            (window as Window & { gm_authFailure?: () => void }).gm_authFailure?.();
        });
        expect(screen.getByTestId("map-fallback")).toBeInTheDocument();
    });

    it("when the script fails to load, falls back", async () => {
        vi.stubEnv("NEXT_PUBLIC_GOOGLE_MAPS_API_KEY", "key");
        loader.importLibrary.mockRejectedValue(new Error("network"));
        await act(async () => { renderPicker("en", 25.08, 55.14); });
        expect(screen.getByTestId("map-fallback")).toBeInTheDocument();
    });
});
