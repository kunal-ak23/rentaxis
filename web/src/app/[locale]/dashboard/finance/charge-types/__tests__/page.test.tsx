import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";
import type { ChargeType } from "@/lib/api/leasing";

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));

const api = vi.hoisted(() => ({ list: vi.fn(), update: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chargeTypeApi: { ...m.chargeTypeApi, list: api.list, update: api.update } };
});

import ChargeTypesPage from "../page";

const row = (over: Partial<ChargeType>): ChargeType => ({
    id: over.code ?? "x", code: "X", nameEn: "X", nameAr: null, role: "OTHER_INCOME", behaviour: "FEE",
    vatApplicableDefault: false, active: true, displayOrder: 1, recognition: "RENT_LIKE", ...over,
});

const CATALOGUE = [
    row({ id: "rent", code: "RENT", nameEn: "Rent", nameAr: "الإيجار", role: "ADVANCE_RENT", behaviour: "RENT" }),
    row({ id: "admin", code: "ADMIN_FEE", nameEn: "Admin Fee", role: "ADMIN_FEE", recognition: "ONE_OFF" }),
    row({ id: "cool", code: "COOLING", nameEn: "Cooling Charges", nameAr: "رسوم التبريد", role: "COOLING_CHARGES" }),
];

function renderPage(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <ChargeTypesPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => api.list.mockResolvedValue(CATALOGUE));
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

/** F14-18 / spec §4c: each fee says how it is earned and whether a renewal copies it. */
describe("Charge types settings", () => {
    it("offers a choice only for fees, and says what a renewal does", async () => {
        renderPage();
        await screen.findByTestId("charge-types-table");
        expect(screen.queryByTestId("charge-type-recognition-RENT")).not.toBeInTheDocument();
        expect((screen.getByTestId("charge-type-recognition-ADMIN_FEE") as HTMLSelectElement).value).toBe("ONE_OFF");
        expect(screen.getByTestId("charge-type-ADMIN_FEE")).toHaveTextContent("Not copied (one-off)");
        expect(screen.getByTestId("charge-type-COOLING")).toHaveTextContent("Copied");
    });

    it("saves a changed recognition with the rest of the row", async () => {
        api.update.mockImplementation(async (_id: string, body: ChargeType) => body);
        renderPage();
        const select = await screen.findByTestId("charge-type-recognition-COOLING");
        fireEvent.change(select, { target: { value: "PASS_THROUGH" } });
        await waitFor(() => expect(api.update).toHaveBeenCalledWith("cool",
            expect.objectContaining({ code: "COOLING", role: "COOLING_CHARGES", recognition: "PASS_THROUGH" })));
        await waitFor(() => expect((screen.getByTestId("charge-type-recognition-COOLING") as HTMLSelectElement).value)
            .toBe("PASS_THROUGH"));
    });

    it("explains in Arabic why a type used by a posted lease keeps its rule (#99)", async () => {
        const { ApiError } = await import("@/lib/api/facilities");
        api.update.mockRejectedValue(new ApiError(400, "COOLING is on 2 lease(s) already past draft",
            JSON.stringify({ code: "chargeType.ruleInUse", args: { code: "COOLING", count: 2 }, message: "COOLING is on 2 lease(s)" })));
        renderPage("ar");
        const select = await screen.findByTestId("charge-type-recognition-COOLING");
        fireEvent.change(select, { target: { value: "PASS_THROUGH" } });
        const alert = await screen.findByRole("alert");
        expect(alert).toHaveTextContent("يُستخدم COOLING في 2");
    });

    it("reads in Arabic", async () => {
        renderPage("ar");
        await screen.findByTestId("charge-types-table");
        expect(screen.getByTestId("charge-type-COOLING")).toHaveTextContent("رسوم التبريد");
        expect(screen.getByTestId("charge-type-COOLING")).toHaveTextContent("تُنسخ");
    });
});
