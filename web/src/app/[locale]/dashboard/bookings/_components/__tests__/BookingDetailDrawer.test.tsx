import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { BookingDetailDTO, BookingRequestDTO } from "@/types/facility";

vi.mock("next-intl", () => {
  // Stable identity: the drawer's `load` useCallback depends on `t`, so a
  // fresh function per render would refire the load effect forever.
  const translate = Object.assign((key: string) => key, {
    // serverText looks a coded refusal up under Common.errors.<code>.
    has: (key: string) => key.startsWith("errors."),
  });
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

describe("BookingDetailDrawer — break-it R3 ops3 F7/F9", () => {
  const amenityPending = makeRequest({
    id: "req-1", resourceType: "AMENITY", amenityId: "am-1", parkingSpotId: null, resourceName: "PoolFee",
    preferredDate: "2026-10-12",
  });
  const coded = (status: number, code: string, message: string) =>
    new ApiError(status, message, JSON.stringify({ error: message, message, code }));

  it("a lost race on an amenity says the request was already decided and shows its real status", async () => {
    const onChanged = vi.fn();
    api.approveBooking.mockRejectedValue(coded(409, "booking.decisionInProgress",
      "A decision on this booking is already in progress, please retry"));
    api.fetchBooking
      .mockResolvedValueOnce({ request: amenityPending, otherRequests: [] })
      // The winner has not committed at the first re-read…
      .mockResolvedValueOnce({ request: amenityPending, otherRequests: [] })
      // …and has at the next one.
      .mockResolvedValue({ request: { ...amenityPending, status: "APPROVED" }, otherRequests: [] });

    render(<BookingDetailDrawer bookingId="req-1" onClose={() => {}} onChanged={onChanged} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "approve" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "approve" }));

    await waitFor(() => expect(screen.getByText("alreadyDecided")).toBeInTheDocument(), { timeout: 3000 });
    expect(screen.getByText("statusAPPROVED")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "approve" })).toBeNull();
    expect(screen.queryByText("spotConflict")).toBeNull();
    expect(onChanged).toHaveBeenCalledWith(expect.objectContaining({ status: "APPROVED" }));
  });

  it("a 400 'not pending' re-reads the booking too", async () => {
    api.rejectBooking.mockRejectedValue(coded(400, "booking.notPending", "Booking is not pending"));
    api.fetchBooking
      .mockResolvedValueOnce({ request: amenityPending, otherRequests: [] })
      .mockResolvedValue({ request: { ...amenityPending, status: "CANCELLED" }, otherRequests: [] });

    render(<BookingDetailDrawer bookingId="req-1" onClose={() => {}} onChanged={() => {}} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "reject" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "reject" }));

    await waitFor(() => expect(screen.getByText("alreadyDecided")).toBeInTheDocument());
    expect(screen.getByText("statusCANCELLED")).toBeInTheDocument();
  });

  it("an amenity clash shows the clash, never the parking 'another tenant' text", async () => {
    api.approveBooking.mockRejectedValue(coded(409, "booking.renterAlreadyBooked",
      "The renter already has an approved booking of this amenity at that time."));
    api.fetchBooking.mockResolvedValue({ request: amenityPending, otherRequests: [] });

    render(<BookingDetailDrawer bookingId="req-1" onClose={() => {}} onChanged={() => {}} />);
    await waitFor(() => expect(screen.getByRole("button", { name: "approve" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "approve" }));

    await waitFor(() => expect(screen.getByText("errors.booking.renterAlreadyBooked")).toBeInTheDocument());
    expect(screen.queryByText("spotConflict")).toBeNull();
  });

  it("other requests list the booked date, not the day they were requested", async () => {
    api.fetchBooking.mockResolvedValue({
      request: amenityPending,
      otherRequests: [makeRequest({ id: "req-9", resourceType: "AMENITY", status: "APPROVED",
        preferredDate: "2026-10-12", preferredStartTime: "10:00:00", preferredEndTime: "12:30:00",
        createdAt: "2026-09-01T09:00:00Z" })],
    });

    render(<BookingDetailDrawer bookingId="req-1" onClose={() => {}} onChanged={() => {}} />);
    await waitFor(() => expect(screen.getByText(/bookedFor 12\/10\/2026 · 10:00–12:30/)).toBeInTheDocument());
    expect(screen.queryByText(/01\/09\/2026/)).toBeNull();
  });
});
