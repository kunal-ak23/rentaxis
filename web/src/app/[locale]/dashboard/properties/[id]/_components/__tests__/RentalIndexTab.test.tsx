import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));

const h = vi.hoisted(() => ({ role: "TENANT_ADMIN", flag: true }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: h.role } }, status: "authenticated" }) }));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: (f: string) => f === "RENTAL_INDEX" && h.flag, tenantSlug: "x" }),
}));
const api = vi.hoisted(() => ({
    list: vi.fn(), parse: vi.fn(), fromPdf: vi.fn(), manual: vi.fn(), revoke: vi.fn(),
    documentUrl: (id: string) => `/api/proxy/v1/rental-index/rates/${id}/document`,
}));
vi.mock("@/lib/api/rentalIndex", async (orig) => {
    const real = await orig<typeof import("@/lib/api/rentalIndex")>();
    return { ...real, rentalIndexApi: api };
});

import { RentalIndexTab } from "../RentalIndexTab";
import { rentalIndexTabVisible } from "@/components/rentalIndex/useRentalIndexEnabled";

const rate = (o: Record<string, unknown> = {}) => ({
    id: "r1", propertyId: "p1", buildingId: "b1", buildingName: "Tower A", dldPropertyType: "APARTMENT",
    category: "1 bed rooms+hall", unitType: "BHK1", indexMin: 60000, indexMax: 80000, average: 70000,
    areaName: null, source: "DLD_PDF", sourceUnitId: null, hasDocument: true, dldPercent: 5, dldIssuedAt: null,
    checkedOn: "2026-11-01", expiresOn: "2027-02-01", expired: false, revoked: false, createdBy: "u1",
    createdByName: "Sam Admin", createdAt: "2026-11-01T00:00:00Z", revokedBy: null, revokedAt: null, ...o,
});
const buildings = [{ id: "b1", name: "Tower A" }];

beforeEach(() => {
    h.role = "TENANT_ADMIN"; h.flag = true;
    [api.list, api.parse, api.fromPdf, api.manual, api.revoke].forEach(f => f.mockReset());
});
afterEach(cleanup);

describe("rentalIndexTabVisible", () => {
    it("needs the flag and a Dubai property", () => {
        expect(rentalIndexTabVisible(true, "DUBAI")).toBe(true);
        expect(rentalIndexTabVisible(false, "DUBAI")).toBe(false);
        expect(rentalIndexTabVisible(true, "ABU_DHABI")).toBe(false);
        expect(rentalIndexTabVisible(true, undefined)).toBe(false);
    });
});

describe("RentalIndexTab", () => {
    it("renders nothing and calls nothing when the flag is off", () => {
        h.flag = false;
        const { container } = render(<RentalIndexTab propertyId="p1" buildings={buildings} emirate="DUBAI" />);
        expect(container).toBeEmptyDOMElement();
        expect(api.list).not.toHaveBeenCalled();
    });

    it("renders nothing for a non-Dubai property", () => {
        const { container } = render(<RentalIndexTab propertyId="p1" buildings={buildings} emirate="ABU_DHABI" />);
        expect(container).toBeEmptyDOMElement();
        expect(api.list).not.toHaveBeenCalled();
    });

    it("lists ranges with status badges", async () => {
        api.list.mockResolvedValue([
            rate(),
            rate({ id: "r2", expired: true, unitType: "STUDIO" }),
            rate({ id: "r3", revoked: true, source: "MANUAL", hasDocument: false }),
        ]);
        render(<RentalIndexTab propertyId="p1" buildings={buildings} emirate="DUBAI" />);
        expect(await screen.findByText("Active")).toBeInTheDocument();
        expect(screen.getByText("Expired")).toBeInTheDocument();
        expect(screen.getByText("Revoked")).toBeInTheDocument();
        expect(api.list).toHaveBeenCalledWith("p1");
        expect(screen.getAllByText("Tower A").length).toBeGreaterThan(0);
        expect(screen.getAllByRole("link", { name: /PDF/ })).toHaveLength(2);
    });

    it("hides write actions from a viewer", async () => {
        h.role = "ACCOUNTANT";
        api.list.mockResolvedValue([rate()]);
        render(<RentalIndexTab propertyId="p1" buildings={buildings} emirate="DUBAI" />);
        await screen.findByText("Active");
        expect(screen.queryByRole("button", { name: "Add from DLD result" })).toBeNull();
        expect(screen.queryByRole("button", { name: "Revoke" })).toBeNull();
    });

    it("upload flow parses, then posts to fromPdf with the chosen unit type", async () => {
        api.list.mockResolvedValue([]);
        api.parse.mockResolvedValue({
            dldPercent: 5, increase: 3500, rentAfter: 73500, premiseNo: "123456", contractEnd: "2026-12-31",
            currentRent: 70000, indexMin: 60000, indexMax: 80000, average: 70000, category: "2 bed rooms+hall",
            building: "Tower A", area: "Area", dldPropertyType: "Apartment", issuedAt: null, checkedOn: "2026-11-25",
            proposedUnitType: "BHK2", premiseMatches: true, endMatches: false, rentMatches: null,
        });
        api.fromPdf.mockResolvedValue(rate({ unitType: "BHK3" }));
        render(<RentalIndexTab propertyId="p1" buildings={buildings} emirate="DUBAI" />);
        fireEvent.click(await screen.findByRole("button", { name: "Add from DLD result" }));
        const file = new File(["%PDF"], "r.pdf", { type: "application/pdf" });
        fireEvent.change(screen.getByTestId("ri-file"), { target: { files: [file] } });
        await waitFor(() => expect(api.parse).toHaveBeenCalled());
        expect(await screen.findByTestId("ri-check-premise")).toHaveTextContent("✓");
        expect(screen.getByTestId("ri-check-end")).toHaveTextContent("✗");
        const sel = screen.getByTestId("ri-unit-type") as HTMLSelectElement;
        expect(sel.value).toBe("BHK2");
        fireEvent.change(sel, { target: { value: "BHK3" } });
        fireEvent.change(screen.getByTestId("ri-building"), { target: { value: "b1" } });
        fireEvent.click(screen.getByRole("button", { name: "Save range" }));
        await waitFor(() => expect(api.fromPdf).toHaveBeenCalled());
        const [f, args] = api.fromPdf.mock.calls[0];
        expect(f).toBe(file);
        expect(args).toMatchObject({ propertyId: "p1", buildingId: "b1", unitType: "BHK3" });
        await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
    });

    it("revokes after a confirm", async () => {
        api.list.mockResolvedValue([rate()]);
        api.revoke.mockResolvedValue(rate({ revoked: true }));
        render(<RentalIndexTab propertyId="p1" buildings={buildings} emirate="DUBAI" />);
        fireEvent.click(await screen.findByRole("button", { name: "Revoke" }));
        expect(api.revoke).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole("button", { name: "Revoke range" }));
        await waitFor(() => expect(api.revoke).toHaveBeenCalledWith("r1"));
    });

    it("defaults the checked-on date to the business (Dubai) day, not the UTC one (final review F4)", async () => {
        // 21:30 UTC on the 25th is already the 26th in Dubai.
        vi.useFakeTimers({ toFake: ["Date"] });
        vi.setSystemTime(new Date("2026-11-25T21:30:00Z"));
        try {
            api.list.mockResolvedValue([]);
            api.manual.mockResolvedValue(rate({ source: "MANUAL" }));
            render(<RentalIndexTab propertyId="p1" buildings={buildings} emirate="DUBAI" />);
            fireEvent.click(await screen.findByRole("button", { name: "Enter by hand" }));
            expect((screen.getByLabelText("Checked on") as HTMLInputElement).value).toBe("2026-11-26");
            fireEvent.change(screen.getByTestId("ri-min"), { target: { value: "60000" } });
            fireEvent.change(screen.getByTestId("ri-max"), { target: { value: "80000" } });
            fireEvent.click(screen.getByRole("button", { name: "Save range" }));
            await waitFor(() => expect(api.manual).toHaveBeenCalled());
            expect(api.manual.mock.calls[0][0].checkedOn).toBe("2026-11-26");
        } finally {
            vi.useRealTimers();
        }
    });

    it("refuses a bad hand-entered range before calling the server", async () => {
        api.list.mockResolvedValue([]);
        render(<RentalIndexTab propertyId="p1" buildings={buildings} emirate="DUBAI" />);
        fireEvent.click(await screen.findByRole("button", { name: "Enter by hand" }));
        fireEvent.change(screen.getByTestId("ri-min"), { target: { value: "90000" } });
        fireEvent.change(screen.getByTestId("ri-max"), { target: { value: "50000" } });
        fireEvent.click(screen.getByRole("button", { name: "Save range" }));
        expect(await screen.findByText("Enter a range: the lowest above zero, then a highest at least as large.")).toBeInTheDocument();
        expect(api.manual).not.toHaveBeenCalled();
    });
});
