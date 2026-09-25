import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { LeaseDetail } from "@/lib/api/leasing";

const api = vi.hoisted(() => ({ setRentFreePeriods: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, setRentFreePeriods: api.setRentFreePeriods } };
});

import RentFreePeriodsCard, { computedConcession } from "../RentFreePeriodsCard";

const lease = (over: Partial<LeaseDetail> = {}) => ({
    id: "l1", status: "DRAFT", startDate: "2026-06-01", endDate: "2027-05-31",
    lines: [{ id: "r", seqNo: 1, chargeTypeId: "ct", chargeTypeCode: "RENT", chargeTypeName: "Rent", behaviour: "RENT",
        creditAccountId: null, creditAccountCode: null, creditAccountName: null, grossAmount: 72000, discountAmount: 0,
        netAmount: 72000, narration: null, vatApplicable: false, periodStart: "2026-06-01", periodEnd: "2027-05-31",
        addendumId: null }],
    rentFreePeriods: [],
    ...over,
}) as unknown as LeaseDetail;

function renderCard(l: LeaseDetail, editable: boolean, locale: "en" | "ar" = "en", onSaved = vi.fn()) {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <RentFreePeriodsCard lease={l} editable={editable} onSaved={onSaved} />
        </NextIntlClientProvider>,
    );
    return onSaved;
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

/** Spec §4b: the rent-free control on a draft lease. */
describe("RentFreePeriodsCard", () => {
    it("computes the concession the server will: headline × free days ÷ term days", () => {
        expect(computedConcession(72000, 30, 365)).toBe(5917.81);
    });

    it("adds a period, previews the payable rent, and sends the periods", async () => {
        api.setRentFreePeriods.mockResolvedValue(lease());
        const onSaved = renderCard(lease(), true);
        fireEvent.click(screen.getByTestId("rent-free-add"));
        fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-06-30" } });
        expect(screen.getByTestId("rent-free-summary")).toHaveTextContent("5,917.81");
        expect(screen.getByTestId("rent-free-summary")).toHaveTextContent("66,082.19");
        fireEvent.change(screen.getByTestId("rent-free-override-0"), { target: { value: "6000" } });
        expect(screen.getByTestId("rent-free-summary")).toHaveTextContent("66,000.00");
        fireEvent.click(screen.getByTestId("rent-free-save"));
        await waitFor(() => expect(api.setRentFreePeriods).toHaveBeenCalledWith("l1", [
            { fromDate: "2026-06-01", toDate: "2026-06-30", concessionOverride: 6000, note: null },
        ]));
        await waitFor(() => expect(onSaved).toHaveBeenCalled());
    });

    it("shows the periods read-only on a posted lease, and nothing when there are none", () => {
        renderCard(lease({ status: "ACTIVE", rentFreePeriods: [
            { id: "p", fromDate: "2026-06-01", toDate: "2026-06-30", concession: 6000, days: 30, note: "First month" },
        ] }), false);
        expect(screen.getByTestId("rent-free-row")).toHaveTextContent("30 days");
        expect(screen.getByTestId("rent-free-row")).toHaveTextContent("6,000.00");
        expect(screen.queryByTestId("rent-free-add")).not.toBeInTheDocument();
        cleanup();
        renderCard(lease({ status: "ACTIVE" }), false);
        expect(screen.queryByTestId("rent-free-card")).not.toBeInTheDocument();
    });

    it("reads in Arabic, with Western-digit amounts isolated LTR (PR #358 R1)", () => {
        renderCard(lease(), true, "ar");
        expect(screen.getByText("فترات الإعفاء من الإيجار")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("rent-free-add"));
        fireEvent.change(screen.getByLabelText("إلى"), { target: { value: "2026-06-30" } });
        const amounts = Array.from(screen.getByTestId("rent-free-summary").querySelectorAll("bdi[dir='ltr']"))
            .map(b => b.textContent);
        expect(amounts).toEqual(["72,000.00", "5,917.81", "66,082.19"]);
    });
});
