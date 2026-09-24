import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { BookingRequestDTO, MyFacilitiesDTO } from "@/types/facility";

vi.mock("next-intl", () => {
    // Stable identity: the page's load effect lists `t` in its deps, so a
    // fresh function per render would refire the initial fetches forever.
    const translate = (key: string, vars?: Record<string, string | number>) => {
        if (!vars) return key;
        let out = key;
        for (const [k, v] of Object.entries(vars)) out = out.replaceAll(`{${k}}`, String(v));
        return out;
    };
    return {
        useTranslations: () => translate,
        useLocale: () => "en",
    };
});

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "RENTER" } }, status: "authenticated" }),
}));

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

const api = vi.hoisted(() => ({
    fetchMyFacilities: vi.fn(),
    fetchMyBookings: vi.fn(),
    createBooking: vi.fn(),
    cancelBooking: vi.fn(),
    releaseBooking: vi.fn(),
}));

// Keep the real ApiError/throwIfNotOk — the page branches on `instanceof ApiError`.
vi.mock("@/lib/api/facilities", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@/lib/api/facilities")>();
    return { ...actual, ...api };
});

import { ApiError } from "@/lib/api/facilities";
import RenterFacilitiesPage from "../page";

const activeLease = {
    id: "lease-1",
    unitId: "unit-1",
    unitIdentifier: "A-204",
    propertyId: "prop-1",
    propertyName: "Sample Vista",
    status: "ACTIVE",
};

function makeBooking(overrides: Partial<BookingRequestDTO>): BookingRequestDTO {
    return {
        id: "b1",
        resourceType: "PARKING_SPOT",
        amenityId: null,
        parkingSpotId: "spot-1",
        resourceName: "P-101",
        propertyId: "prop-1",
        unitId: "unit-1",
        unitNumber: "A-204",
        renterUserId: "user-1",
        renterName: "Ahmed Khan",
        renterEmail: null,
        renterPhone: null,
        note: null,
        preferredDate: null,
        status: "PENDING",
        adminNote: null,
        decidedByUserId: null,
        decidedAt: null,
        createdAt: "2026-08-01T09:00:00Z",
        ...overrides,
    };
}

function facilitiesWithSpot(held: boolean): MyFacilitiesDTO {
    return {
        amenities: [],
        parkingSpots: [
            {
                id: "spot-1",
                propertyId: "prop-1",
                propertyName: "Sample Vista",
                spotNumber: "P-101",
                level: null,
                covered: false,
                held,
                pendingCount: 0,
            },
        ],
    };
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({
        ok: true,
        json: async () => [activeLease],
    }) as unknown as Response) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    // resetAllMocks (not restoreAllMocks): the module-level vi.fn() mocks keep
    // their call counts and once-queues across tests otherwise.
    vi.resetAllMocks();
});

describe("RenterFacilitiesPage stale-state resync", () => {
    it("resyncs both lists and shows the localized message when cancel hits a 400", async () => {
        api.fetchMyFacilities.mockResolvedValue(facilitiesWithSpot(false));
        // First load: the renter still sees their PENDING request; after the
        // 400-triggered resync the admin's REJECTED decision comes back.
        api.fetchMyBookings
            .mockResolvedValueOnce([makeBooking({ status: "PENDING" })])
            .mockResolvedValue([makeBooking({ status: "REJECTED" })]);
        api.cancelBooking.mockRejectedValue(new ApiError(400, "Booking is not pending"));

        render(<RenterFacilitiesPage />);

        await waitFor(() => expect(screen.getByText("statusPENDING")).toBeInTheDocument());

        fireEvent.click(screen.getByRole("button", { name: "cancelRequest" }));
        await waitFor(() => expect(screen.getByText("cancelRequestConfirm")).toBeInTheDocument());
        // Two "cancelRequest" buttons now exist (row + dialog confirm); the
        // dialog renders last in the DOM, so its confirm is the final match.
        const confirmButtons = screen.getAllByRole("button", { name: "cancelRequest" });
        fireEvent.click(confirmButtons[confirmButtons.length - 1]);

        // Localized "changed state" copy, not the raw backend English string.
        await waitFor(() => expect(screen.getByText("requestChanged")).toBeInTheDocument());
        expect(screen.queryByText("Booking is not pending")).not.toBeInTheDocument();
        // Both lists were re-fetched and the row now reflects the server truth
        // (REJECTED, no Cancel button left to retry with).
        await waitFor(() => expect(screen.getByText("statusREJECTED")).toBeInTheDocument());
        expect(api.fetchMyBookings).toHaveBeenCalledTimes(2);
        expect(api.fetchMyFacilities).toHaveBeenCalledTimes(2);
        // Scoped to the requests table: the ConfirmDialog's confirm button
        // (same accessible name) lingers in the DOM during its exit animation.
        const tbody = screen.getByRole("table").querySelector("tbody")!;
        expect(within(tbody).queryByRole("button", { name: "cancelRequest" })).not.toBeInTheDocument();
    });

    it("surfaces the backend message without a resync on non-400/409 cancel failures", async () => {
        api.fetchMyFacilities.mockResolvedValue(facilitiesWithSpot(false));
        api.fetchMyBookings.mockResolvedValue([makeBooking({ status: "PENDING" })]);
        api.cancelBooking.mockRejectedValue(new ApiError(500, "boom"));

        render(<RenterFacilitiesPage />);
        await waitFor(() => expect(screen.getByText("statusPENDING")).toBeInTheDocument());

        fireEvent.click(screen.getByRole("button", { name: "cancelRequest" }));
        await waitFor(() => expect(screen.getByText("cancelRequestConfirm")).toBeInTheDocument());
        const confirmButtons = screen.getAllByRole("button", { name: "cancelRequest" });
        fireEvent.click(confirmButtons[confirmButtons.length - 1]);

        await waitFor(() => expect(screen.getByText("boom")).toBeInTheDocument());
        expect(api.fetchMyBookings).toHaveBeenCalledTimes(1);
        expect(api.fetchMyFacilities).toHaveBeenCalledTimes(1);
    });

    it("resyncs the cards after a 409 spot conflict so the stale Available badge flips to Held", async () => {
        // First load: spot looks Available; the resync after the 409 reveals
        // it was approved to someone else meanwhile.
        api.fetchMyFacilities
            .mockResolvedValueOnce(facilitiesWithSpot(false))
            .mockResolvedValue(facilitiesWithSpot(true));
        api.fetchMyBookings.mockResolvedValue([]);
        api.createBooking.mockRejectedValue(
            new ApiError(409, "Parking spot is already assigned"),
        );

        render(<RenterFacilitiesPage />);

        await waitFor(() => expect(screen.getByText("available")).toBeInTheDocument());

        fireEvent.click(screen.getByRole("button", { name: "request" }));
        await waitFor(() => expect(screen.getByRole("button", { name: "submitRequest" })).toBeInTheDocument());
        fireEvent.click(screen.getByRole("button", { name: "submitRequest" }));

        // Dialog stays open with the localized conflict copy…
        await waitFor(() => expect(screen.getByText("spotConflict")).toBeInTheDocument());
        // …and the card behind it has been resynced to the server truth.
        expect(api.fetchMyFacilities).toHaveBeenCalledTimes(2);
        await waitFor(() => expect(screen.getByText("held")).toBeInTheDocument());
        expect(screen.queryByText("available")).not.toBeInTheDocument();
        // The Request button is now disabled — no more guaranteed-409 retries.
        expect(screen.getByRole("button", { name: "request" })).toBeDisabled();
    });
});
