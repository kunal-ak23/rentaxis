import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { BookingDetailDTO, BookingRequestDTO } from "@/types/facility";

vi.mock("next-intl", () => {
  // Stable identity: the drawer's `load` useCallback depends on `t`, so a
  // fresh function per render would refire the load effect forever.
  const translate = (key: string) => key;
  return {
    useTranslations: () => translate,
    useLocale: () => "en",
  };
});

const api = vi.hoisted(() => ({
  fetchBooking: vi.fn(),
  approveBooking: vi.fn(),
  rejectBooking: vi.fn(),
  releaseBooking: vi.fn(),
}));

// Keep the real ApiError class — the drawer branches on `instanceof ApiError`.
vi.mock("@/lib/api/facilities", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/facilities")>();
  return { ...actual, ...api };
});

import { ApiError } from "@/lib/api/facilities";
import { BookingDetailDrawer } from "../BookingDetailDrawer";

function makeRequest(overrides: Partial<BookingRequestDTO>): BookingRequestDTO {
  return {
    id: "req-1",
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

const competitorPending = makeRequest({
  id: "req-2",
  renterUserId: "user-2",
  renterName: "Sara Ali",
  unitNumber: "B-101",
});

const staleDetail: BookingDetailDTO = {
  request: makeRequest({}),
  otherRequests: [competitorPending],
};

/** What the server says after the competitor won the race: their request is APPROVED now. */
const refreshedDetail: BookingDetailDTO = {
  request: makeRequest({}),
  otherRequests: [{ ...competitorPending, status: "APPROVED" }],
};

beforeEach(() => {
  api.fetchBooking.mockResolvedValue(staleDetail);
});

afterEach(() => {
  cleanup();
  // resetAllMocks (not restoreAllMocks): the module-level vi.fn() mocks keep
  // their call counts and once-queues across tests otherwise.
  vi.resetAllMocks();
});

describe("BookingDetailDrawer 409 handling", () => {
  it("re-fetches the detail on a 409 so otherRequests shows the winner's APPROVED badge", async () => {
    api.approveBooking.mockRejectedValue(
      new ApiError(409, "Parking spot is already assigned to another renter"),
    );
    // First load returns the stale detail; the post-409 re-fetch returns the
    // refreshed one with the competitor APPROVED.
    api.fetchBooking
      .mockResolvedValueOnce(staleDetail)
      .mockResolvedValue(refreshedDetail);

    render(
      <BookingDetailDrawer bookingId="req-1" onClose={() => {}} onChanged={() => {}} />,
    );

    // Initial render: competitor is (stale) PENDING.
    await waitFor(() => expect(screen.getByText("Sara Ali")).toBeInTheDocument());
    const otherRow = () => screen.getByText("Sara Ali").closest("div[class*='px-4']") as HTMLElement;
    expect(within(otherRow()).getByText("statusPENDING")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "approve" }));

    // The conflict message shows AND the detail was re-fetched — the
    // competitor's badge flips to APPROVED instead of staying stale.
    await waitFor(() => expect(screen.getByText("spotConflict")).toBeInTheDocument());
    await waitFor(() =>
      expect(within(otherRow()).getByText("statusAPPROVED")).toBeInTheDocument(),
    );
    expect(api.fetchBooking).toHaveBeenCalledTimes(2);
  });

  it("keeps the conflict message when the post-409 re-fetch itself fails", async () => {
    api.approveBooking.mockRejectedValue(new ApiError(409, "conflict"));
    api.fetchBooking
      .mockResolvedValueOnce(staleDetail)
      .mockRejectedValue(new ApiError(500, "boom"));

    render(
      <BookingDetailDrawer bookingId="req-1" onClose={() => {}} onChanged={() => {}} />,
    );
    await waitFor(() => expect(screen.getByText("Sara Ali")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "approve" }));

    await waitFor(() => expect(screen.getByText("spotConflict")).toBeInTheDocument());
    // Stale detail is kept rather than blanking the drawer.
    expect(screen.getByText("Sara Ali")).toBeInTheDocument();
  });

  it("does not re-fetch on a plain 400", async () => {
    api.approveBooking.mockRejectedValue(new ApiError(400, "Booking is not pending"));

    render(
      <BookingDetailDrawer bookingId="req-1" onClose={() => {}} onChanged={() => {}} />,
    );
    await waitFor(() => expect(screen.getByText("Sara Ali")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "approve" }));

    await waitFor(() => expect(screen.getByText("actionError")).toBeInTheDocument());
    expect(api.fetchBooking).toHaveBeenCalledTimes(1);
  });
});
