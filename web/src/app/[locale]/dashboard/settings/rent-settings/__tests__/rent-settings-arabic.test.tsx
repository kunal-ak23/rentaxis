import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * #81: the rent settings page kept its description, the property picker, the
 * field hints, the online-payment copy and the fine-override units in English
 * under /ar.
 */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: null }) }));

import RentSettingsPage from "../page";

beforeEach(() => {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        const json = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as unknown as Response;
        if (url.includes("/v1/properties")) return json([{ property: { id: "p1", nameEn: "Belle Vue", nameAr: "بيل فيو" } }]);
        if (url.includes("/v1/rent-settings/")) {
            return json({
                propertyId: "p1", dueDayOfMonth: 1, gracePeriodDays: 5, penaltyType: "FIXED_PER_DAY", penaltyAmount: 50,
                onlinePaymentEnabled: true, fineBounceAmount: 700, fineSignatureMismatchAmount: null,
                fineAccountClosedAmount: null, fineGraceDays: null, finePerDayRate: null,
            });
        }
        return { ok: false, status: 404, json: async () => ({}) } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("rent settings in Arabic", () => {
    it("the picker, the form and the fine overrides carry no English", async () => {
        const { container } = render(
            <NextIntlClientProvider locale="ar" messages={ar}><RentSettingsPage /></NextIntlClientProvider>,
        );
        await screen.findByText("بيل فيو");
        expect(screen.getByText(ar.OnlinePayments.rentSettingsDesc)).toBeInTheDocument();
        expect(screen.getByText(ar.OnlinePayments.selectPropertyPlaceholder)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container))).toEqual([]);

        fireEvent.change(screen.getAllByRole("combobox")[0], { target: { value: "p1" } });
        await screen.findByText(ar.OnlinePayments.dueDayHint);
        fireEvent.click(screen.getByText(ar.Fines.overrideSection));

        expect(screen.getByText("تجاوز واحد مفعّل")).toBeInTheDocument();
        expect(screen.getByText(ar.OnlinePayments.onlineEnabledHint)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container))).toEqual([]);
    });
});
