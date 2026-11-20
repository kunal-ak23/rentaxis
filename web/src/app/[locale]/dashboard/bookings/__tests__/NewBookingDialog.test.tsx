import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRouter } from "@/test/fetchRouter";

const translate = vi.hoisted(() => (key: string, vars?: Record<string, string | number>) =>
    vars ? `${key}:${JSON.stringify(vars)}` : key);
vi.mock("next-intl", () => ({ useTranslations: () => translate, useLocale: () => "en" }));

import { NewBookingDialog } from "../_components/NewBookingDialog";

const PROPERTY = { id: "p-1", nameEn: "Tower One", nameAr: null };
let api: ReturnType<typeof fetchRouter>;

beforeEach(() => {
    api = fetchRouter();
    api.on("GET", "/v1/units/property/p-1", { body: [{ id: "u-1", unitNumber: "101" }, { id: "u-2", unitNumber: "102" }] });
    api.on("GET", "/v1/leases/property/p-1", {
        body: [
            { id: "l-1", unitId: "u-1", renterId: "r-1", renterName: "Sara", status: "ACTIVE" },
            { id: "l-0", unitId: "u-2", renterId: "r-0", renterName: "Old", status: "TERMINATED" },
        ],
    });
    api.on("GET", "/v1/renters/r-1", { body: { id: "r-1", userId: "user-1", nameEn: "Sara" } });
    api.on("GET", "/v1/amenities", {
        body: { content: [{ id: "a-1", propertyId: "p-1", nameEn: "Banquet Hall", nameAr: null, active: true, bookable: true }], totalElements: 1, number: 0, size: 200, totalPages: 1 },
    });
    api.on("GET", "/v1/bookings/quote", { body: { feeType: "PER_DAY", unitAmount: 1000, net: 1000, vat: 50, total: 1050 } });
    api.on("POST", "/v1/bookings/on-behalf", { status: 201, body: { id: "b-1", status: "APPROVED" } });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("NewBookingDialog", () => {
    it("books for the unit's live-lease renter, shows the server quote, and closes on 201", async () => {
        const onClose = vi.fn();
        const onCreated = vi.fn();
        render(<NewBookingDialog open properties={[PROPERTY]} defaultPropertyId="p-1" onClose={onClose} onCreated={onCreated} />);

        await screen.findByRole("option", { name: "101" });
        fireEvent.change(screen.getByTestId("onbehalf-unit"), { target: { value: "u-1" } });
        await waitFor(() => expect(screen.getByTestId("onbehalf-renter").textContent).toContain("Sara"));

        fireEvent.change(screen.getByTestId("onbehalf-type"), { target: { value: "AMENITY" } });
        await screen.findByRole("option", { name: "Banquet Hall" });
        fireEvent.change(screen.getByTestId("onbehalf-resource"), { target: { value: "a-1" } });
        fireEvent.change(screen.getByTestId("onbehalf-start"), { target: { value: "2026-12-05" } });

        await waitFor(() => expect(screen.getByTestId("onbehalf-quote").textContent).toContain("1050"));
        expect(api.callsTo("GET", "/v1/bookings/quote")[0].url).toContain("resourceId=a-1");

        fireEvent.click(screen.getByTestId("onbehalf-submit"));
        await waitFor(() => expect(onClose).toHaveBeenCalled());
        expect(api.callsTo("POST", "/v1/bookings/on-behalf")[0].body).toEqual({
            booking: { resourceType: "AMENITY", resourceId: "a-1", unitId: "u-1", preferredDate: "2026-12-05" },
            renterUserId: "user-1",
        });
        expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ id: "b-1" }));
    });

    it("stays open and shows the server's message when the booking is refused", async () => {
        api.on("POST", "/v1/bookings/on-behalf", { status: 409, body: { message: "Parking spot is already booked" } });
        const onClose = vi.fn();
        render(<NewBookingDialog open properties={[PROPERTY]} defaultPropertyId="p-1" onClose={onClose} onCreated={vi.fn()} />);
        await screen.findByRole("option", { name: "101" });
        fireEvent.change(screen.getByTestId("onbehalf-unit"), { target: { value: "u-1" } });
        await waitFor(() => expect(screen.getByTestId("onbehalf-renter").textContent).toContain("Sara"));
        fireEvent.change(screen.getByTestId("onbehalf-type"), { target: { value: "AMENITY" } });
        await screen.findByRole("option", { name: "Banquet Hall" });
        fireEvent.change(screen.getByTestId("onbehalf-resource"), { target: { value: "a-1" } });
        fireEvent.change(screen.getByTestId("onbehalf-start"), { target: { value: "2026-12-05" } });
        fireEvent.click(screen.getByTestId("onbehalf-submit"));
        expect((await screen.findByTestId("onbehalf-error")).textContent).toContain("already booked");
        expect(onClose).not.toHaveBeenCalled();
    });

    /** Final review I1 and m4: a per-month bay needs an end date; the quote waits for it and carries the renter. */
    it("requires an end date for a per-month bay and quotes only once both dates are set", async () => {
        api.on("GET", "/v1/parking-spots", {
            body: { content: [{ id: "s-1", propertyId: "p-1", spotNumber: "P-12", level: null, active: true, attachedUnitId: null, feeType: "PER_MONTH", feeAmount: 300 }], totalElements: 1, number: 0, size: 200, totalPages: 1 },
        });
        api.on("GET", "/v1/bookings/quote", { body: { feeType: "PER_MONTH", unitAmount: 300, net: 600, vat: 0, total: 600 } });
        const onClose = vi.fn();
        render(<NewBookingDialog open properties={[PROPERTY]} defaultPropertyId="p-1" onClose={onClose} onCreated={vi.fn()} />);
        await screen.findByRole("option", { name: "101" });
        fireEvent.change(screen.getByTestId("onbehalf-unit"), { target: { value: "u-1" } });
        await waitFor(() => expect(screen.getByTestId("onbehalf-renter").textContent).toContain("Sara"));
        fireEvent.change(screen.getByTestId("onbehalf-type"), { target: { value: "PARKING_SPOT" } });
        await screen.findByRole("option", { name: "P-12" });
        fireEvent.change(screen.getByTestId("onbehalf-resource"), { target: { value: "s-1" } });
        fireEvent.change(screen.getByTestId("onbehalf-start"), { target: { value: "2026-12-01" } });

        expect(await screen.findByTestId("onbehalf-needs-end")).toBeInTheDocument();
        expect(screen.getByTestId("onbehalf-end")).toBeRequired();
        expect(screen.queryByTestId("onbehalf-quote")).not.toBeInTheDocument();
        expect(api.callsTo("GET", "/v1/bookings/quote")).toHaveLength(0);
        expect((screen.getByTestId("onbehalf-submit") as HTMLButtonElement).disabled).toBe(true);

        fireEvent.change(screen.getByTestId("onbehalf-end"), { target: { value: "2027-01-31" } });
        await waitFor(() => expect(screen.getByTestId("onbehalf-quote").textContent).toContain("600"));
        const url = api.callsTo("GET", "/v1/bookings/quote")[0].url;
        expect(url).toContain("end=2027-01-31");
        expect(url).toContain("unitId=u-1");
        expect(url).toContain("renterUserId=user-1");

        fireEvent.click(screen.getByTestId("onbehalf-submit"));
        await waitFor(() => expect(onClose).toHaveBeenCalled());
        expect(api.callsTo("POST", "/v1/bookings/on-behalf")[0].body).toEqual({
            booking: { resourceType: "PARKING_SPOT", resourceId: "s-1", unitId: "u-1", preferredDate: "2026-12-01", preferredEndDate: "2027-01-31" },
            renterUserId: "user-1",
        });
    });

    it("a unit with no live contract has no renter and cannot be submitted", async () => {
        render(<NewBookingDialog open properties={[PROPERTY]} defaultPropertyId="p-1" onClose={vi.fn()} onCreated={vi.fn()} />);
        await screen.findByRole("option", { name: "102" });
        fireEvent.change(screen.getByTestId("onbehalf-unit"), { target: { value: "u-2" } });
        await waitFor(() => expect(screen.getByTestId("onbehalf-renter").textContent).toContain("onBehalfNoRenter"));
        expect((screen.getByTestId("onbehalf-submit") as HTMLButtonElement).disabled).toBe(true);
    });
});
