import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../messages/ar.json";
import en from "../../../../../messages/en.json";

const unbalanced = vi.fn();
const repair = vi.fn();
vi.mock("@/lib/api/ledger", () => ({
    ledgerApi: { journals: { interPropertyUnbalanced: () => unbalanced(), interPropertyRepair: () => repair() } },
}));

import InterPropertyRepairBanner from "../InterPropertyRepairBanner";

/** F15-11: the admin repair — shown while journals are unbalanced per property, gone after the run. */
function renderIn(locale: "en" | "ar") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <InterPropertyRepairBanner />
        </NextIntlClientProvider>,
    );
}

describe("InterPropertyRepairBanner", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("shows nothing when every journal balances per property", async () => {
        unbalanced.mockResolvedValue([]);
        renderIn("en");
        await waitFor(() => expect(unbalanced).toHaveBeenCalled());
        expect(screen.queryByTestId("ip-repair")).toBeNull();
    });

    it("offers the repair, runs it once confirmed and reports the result", async () => {
        unbalanced.mockResolvedValueOnce(["a", "b"]).mockResolvedValue([]);
        repair.mockResolvedValue({ examined: 2, repaired: [{}, {}] });
        renderIn("en");
        expect(await screen.findByText(/2 journals do not balance per property/)).toBeTruthy();
        fireEvent.click(screen.getByTestId("ip-repair-run"));
        // F15-14: the app's confirm dialog, not window.confirm.
        expect(repair).not.toHaveBeenCalled();
        fireEvent.click(await screen.findByTestId("ip-repair-confirm"));
        expect(await screen.findByTestId("ip-repair-done")).toBeTruthy();
        expect(repair).toHaveBeenCalledTimes(1);
    });

    it("renders in Arabic", async () => {
        unbalanced.mockResolvedValue(["a"]);
        renderIn("ar");
        expect(await screen.findByText("ترحيل قيود المقاصة")).toBeTruthy();
    });
});
