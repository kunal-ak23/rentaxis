import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import { OverviewCounts } from "../_components/OverviewCounts";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function mockSummary(body: unknown) {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => body }) as unknown as Response) as unknown as typeof fetch;
}
const renderCounts = () => render(
    <NextIntlClientProvider locale="en" messages={en}>
        <OverviewCounts units={[]} propertyId="p1" showParking />
    </NextIntlClientProvider>,
);

describe("Overview parking: attached bays", () => {
    it("shows the attached count beside total, assigned and free", async () => {
        mockSummary({ total: 10, assigned: 2, free: 5, inactive: 0, attached: 3 });
        renderCounts();
        expect(await screen.findByTestId("overview-parking-attached")).toHaveTextContent("3");
        expect(screen.getByTestId("overview-parking-free")).toHaveTextContent("5");
    });

    it("reads a summary without the field (older server) as zero", async () => {
        mockSummary({ total: 4, assigned: 1, free: 3, inactive: 0 });
        renderCounts();
        expect(await screen.findByTestId("overview-parking-attached")).toHaveTextContent("0");
    });
});
