import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
import RentalIndexPanel from "../RentalIndexPanel";
import { fetchRouter } from "@/test/fetchRouter";
import type { LeaseDetail, RenewalPreview } from "@/lib/api/leasing";

const LEASE = { id: "l1", unitId: "u1", propertyId: "p1", unitIdentifier: "101", endDate: "2027-09-30", rentAmount: 120000 } as unknown as LeaseDetail;
const OK = {
    baseRent: 120000, newRent: 126000, indexStatus: "OK", indexMin: 90000, indexMax: 110000, indexAverage: 100000,
    indexGapPercent: 16, indexAllowedPercent: 5, indexCapRent: 126000, indexAboveCap: false, indexCheckedOn: "2026-11-01",
    noticeDeadline: "2027-07-02", noticePassed: false,
} as unknown as RenewalPreview;

beforeEach(() => {
    const api = fetchRouter();
    api.on("GET", "/units/paged", { body: { content: [{ id: "u1", type: "BHK1", dewaPremiseNumber: "123456789" }] } });
});
afterEach(cleanup);

describe("RentalIndexPanel", () => {
    it("shows the allowed percentage and highest new rent", async () => {
        render(<RentalIndexPanel lease={LEASE} preview={OK} onAttached={() => {}} />);
        const panel = screen.getByTestId("rental-index-panel");
        expect(panel).toHaveTextContent("5%");
        expect(panel).toHaveTextContent("126,000");
        expect(await screen.findByTestId("check-on-dld")).toBeInTheDocument();
        await waitFor(() => expect(screen.getByRole("button", { name: "Copy for DLD" })).toBeEnabled());
    });

    it("warns when the notice deadline has passed", () => {
        render(<RentalIndexPanel lease={LEASE} preview={{ ...OK, noticePassed: true }} onAttached={() => {}} />);
        expect(screen.getByTestId("rental-index-notice-passed")).toBeInTheDocument();
    });

    it("explains a status with no cap", () => {
        render(<RentalIndexPanel lease={LEASE} preview={{ ...OK, indexStatus: "NOT_DUBAI", indexCapRent: null } as RenewalPreview} onAttached={() => {}} />);
        expect(screen.getByTestId("rental-index-panel")).toHaveTextContent("Dubai properties only");
        expect(screen.queryByTestId("check-on-dld")).toBeNull();
    });
});
