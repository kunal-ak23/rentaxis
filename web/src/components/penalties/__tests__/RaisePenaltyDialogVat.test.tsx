import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";

const propose = vi.fn();
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, penaltyApi: { ...real.penaltyApi, propose: (b: unknown) => propose(b) } };
});

import RaisePenaltyDialog from "../RaisePenaltyDialog";

/** F14-30: the VAT choice on a raised charge — automatic by reason, or forced either way. */
function renderIn(locale: "en" | "ar") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <RaisePenaltyDialog open leaseId="L1" onClose={() => {}} onRaised={() => {}} />
        </NextIntlClientProvider>,
    );
}

describe("RaisePenaltyDialog VAT", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("says a service recharge carries VAT automatically and sends the user's choice", async () => {
        propose.mockResolvedValue({ id: "p1" });
        renderIn("en");
        fireEvent.change(screen.getByLabelText(/Penalty category|Category/i), { target: { value: "SERVICE_RECHARGE" } });
        expect(screen.getByText("Automatic — charged on a VAT contract")).toBeTruthy();
        fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "200" } });
        fireEvent.change(screen.getByLabelText("VAT (5%)"), { target: { value: "no" } });
        fireEvent.click(screen.getByTestId("raise-penalty-confirm"));
        await waitFor(() => expect(propose).toHaveBeenCalled());
        expect(propose.mock.calls[0][0]).toMatchObject({ reason: "SERVICE_RECHARGE", vatable: false });
    });

    it("titles a recharge by its type, not as a penalty (F15-18)", () => {
        renderIn("en");
        expect(screen.getAllByText("Raise penalty").length).toBeGreaterThan(0);
        fireEvent.change(screen.getByLabelText(/Penalty category|Category/i), { target: { value: "SERVICE_RECHARGE" } });
        expect(screen.queryByText("Raise penalty")).toBeNull();
        expect(screen.getAllByText("Raise Service Recharge").length).toBeGreaterThan(0);
        expect(screen.getByText(/The Service Recharge is proposed, not charged/)).toBeTruthy();
    });

    it("renders the penalty default in Arabic", () => {
        renderIn("ar");
        expect(screen.getByText("تلقائي — لا ضريبة (الغرامة ليست توريدًا)")).toBeTruthy();
    });
});
