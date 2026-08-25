import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import ListingMap from "../ListingMap";

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

describe("ListingMap", () => {
  it("shows a usable Google Maps link when the embed key is absent", () => {
    vi.stubEnv("NEXT_PUBLIC_GOOGLE_MAPS_API_KEY", "");

    render(<ListingMap lat={25.2048} lng={55.2708} label="Downtown Dubai" />);

    expect(screen.queryByTitle(/map of/i)).toBeNull();
    expect(screen.getByRole("link", { name: /view downtown dubai on google maps/i }))
      .toHaveAttribute("href", "https://www.google.com/maps/search/?api=1&query=25.2048%2C55.2708");
  });

  it("renders the embedded map when a key is configured", () => {
    vi.stubEnv("NEXT_PUBLIC_GOOGLE_MAPS_API_KEY", "configured-key");

    render(<ListingMap lat={25.2048} lng={55.2708} label="Downtown Dubai" />);

    expect(screen.getByTitle("Map of Downtown Dubai")).toHaveAttribute(
      "src",
      "https://www.google.com/maps/embed/v1/place?key=configured-key&q=25.2048,55.2708&zoom=15",
    );
  });
});
