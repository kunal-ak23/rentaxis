import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { MyFacilitiesDTO } from "@/types/facility";

// Real English messages here (unlike page.test.tsx's key-echo mock), so the dates
// and the section copy are asserted as the renter reads them.
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
vi.mock("@/lib/api/facilities", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@/lib/api/facilities")>();
    return { ...actual, ...api };
});

import RenterFacilitiesPage from "../page";

const lease = { id: "l1", unitId: "unit-1", unitIdentifier: "A-204", propertyId: "prop-1", propertyName: "Belle Vue", status: "ACTIVE" };

const facilities: MyFacilitiesDTO = {
    amenities: [],
    allocatedParking: [
        { id: "a1", propertyId: "prop-1", propertyName: "Belle Vue", unitId: "unit-1", unitNumber: "A-204",
            spotNumber: "B1-07", level: "B1", covered: true, photoUrls: [] },
    ],
    parkingSpots: [
        { id: "spot-1", propertyId: "prop-1", propertyName: "Belle Vue", spotNumber: "P-101", level: null, covered: false,
            held: false, pendingCount: 0,
            bookedRanges: [
                { from: "2027-03-01", to: "2027-03-31" },
                { from: "2027-06-15", to: null },
                { from: null, to: "2027-02-10" },
            ] },
    ],
};

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}><RenterFacilitiesPage /></NextIntlClientProvider>,
    );
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [lease] }) as unknown as Response) as unknown as typeof fetch;
    api.fetchMyBookings.mockResolvedValue([]);
    api.createBooking.mockResolvedValue({});
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });

describe("renter facilities: allocated parking", () => {
    it("lists an attached bay in its own section, with no request button and no fee", async () => {
        api.fetchMyFacilities.mockResolvedValue(facilities);
        renderPage();
        const section = await screen.findByTestId("allocated-parking");
        expect(within(section).getByText(en.Facilities.allocatedParkingSection)).toBeInTheDocument();
        expect(within(section).getByText(en.Facilities.allocatedParkingNote)).toBeInTheDocument();
        const card = within(section).getByTestId("allocated-spot-a1");
        expect(card).toHaveTextContent("B1-07");
        expect(card).toHaveTextContent("B1");
        expect(card).toHaveTextContent(en.Facilities.covered);
        expect(card).toHaveTextContent("A-204");
        expect(within(section).queryByRole("button")).toBeNull();
        expect(card).not.toHaveTextContent(/AED/);
        // Above the bookable list.
        const bookable = screen.getByTestId("request-spot-spot-1");
        expect(section.compareDocumentPosition(bookable) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it("renders no allocated section when there are none (older server: field missing)", async () => {
        api.fetchMyFacilities.mockResolvedValue({ ...facilities, allocatedParking: undefined });
        renderPage();
        await screen.findByTestId("request-spot-spot-1");
        expect(screen.queryByTestId("allocated-parking")).toBeNull();
    });

    it("an allocated bay alone is not the 'no facilities' empty state", async () => {
        api.fetchMyFacilities.mockResolvedValue({ ...facilities, parkingSpots: [] });
        renderPage();
        await screen.findByTestId("allocated-parking");
        expect(screen.queryByText(en.Facilities.noFacilities)).toBeNull();
    });
});

describe("renter facilities: booked ranges and end date", () => {
    // Each date is wrapped in FSI (U+2068) ... PDI (U+2069) so it keeps its order inside Arabic text.
    const iso = (d: string) => `\u2068${d}\u2069`;

    it("shows each booked range under the bookable spot, dd/mm/yyyy, each date isolated", async () => {
        api.fetchMyFacilities.mockResolvedValue(facilities);
        renderPage();
        expect(await screen.findByTestId("spot-booked-spot-1-0")).toHaveTextContent(`Booked: ${iso("01/03/2027")} – ${iso("31/03/2027")}`);
        expect(screen.getByTestId("spot-booked-spot-1-1")).toHaveTextContent(`Booked: from ${iso("15/06/2027")}`);
        expect(screen.getByTestId("spot-booked-spot-1-2")).toHaveTextContent(`Booked: until ${iso("10/02/2027")}`);
    });

    it("a held bay with booked ranges keeps Request enabled (it is only taken for those dates)", async () => {
        api.fetchMyFacilities.mockResolvedValue({
            ...facilities,
            parkingSpots: [{ ...facilities.parkingSpots[0], held: true }],
        });
        renderPage();
        const request = await screen.findByTestId("request-spot-spot-1");
        expect(screen.getByTestId("spot-booked-spot-1-0")).toBeInTheDocument();
        expect(request).toBeEnabled();
    });

    async function openDialog() {
        api.fetchMyFacilities.mockResolvedValue(facilities);
        renderPage();
        fireEvent.click(await screen.findByTestId("request-spot-spot-1"));
        return screen.findByTestId("request-end-date");
    }

    it("refuses an end date before the start date, without calling the API", async () => {
        const end = await openDialog();
        fireEvent.change(screen.getByTestId("request-start-date"), { target: { value: "2099-05-10" } });
        fireEvent.change(end, { target: { value: "2099-05-01" } });
        fireEvent.click(screen.getByText(en.Facilities.submitRequest));
        expect(await screen.findByText(en.Facilities.endBeforeStart)).toBeInTheDocument();
        expect(api.createBooking).not.toHaveBeenCalled();
    });

    it("sends preferredEndDate for a parking request", async () => {
        const end = await openDialog();
        fireEvent.change(screen.getByTestId("request-start-date"), { target: { value: "2099-05-10" } });
        fireEvent.change(end, { target: { value: "2099-05-20" } });
        fireEvent.click(screen.getByText(en.Facilities.submitRequest));
        await waitFor(() => expect(api.createBooking).toHaveBeenCalledTimes(1));
        expect(api.createBooking.mock.calls[0][0]).toMatchObject({
            resourceType: "PARKING_SPOT", resourceId: "spot-1", preferredDate: "2099-05-10", preferredEndDate: "2099-05-20",
        });
    });

    it("omits preferredEndDate when it is left blank", async () => {
        await openDialog();
        fireEvent.click(screen.getByText(en.Facilities.submitRequest));
        await waitFor(() => expect(api.createBooking).toHaveBeenCalledTimes(1));
        expect(api.createBooking.mock.calls[0][0].preferredEndDate).toBeUndefined();
    });
});
