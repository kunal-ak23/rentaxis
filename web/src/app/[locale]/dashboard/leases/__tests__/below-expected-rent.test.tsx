import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../../messages/ar.json";
import en from "../../../../../../messages/en.json";

vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("@/components/cheques/ChequeScanner", () => ({ default: () => null }));
vi.mock("@/components/cheques/BulkChequeUploadFlow", () => ({ default: () => null }));

import LeaseWizard from "../LeaseWizard";

/**
 * A unit carries an asking rent. Letting below it is a normal commercial
 * decision — a discount for a long tenancy, a quick fill, a difficult unit —
 * so this warns and never blocks. It exists so the discount is deliberate
 * rather than a typo nobody notices until the first cheque.
 *
 * Unit.expectedRent and the wizard's rent field are both per month. If that
 * ever stops being true the comparison silently becomes nonsense, which is
 * what the "equal" and "above" cases below are really guarding.
 */

const PROPERTY = { id: "p1", nameEn: "Marina Tower", type: "RESIDENTIAL" };

const UNIT_WITH_ASKING_RENT = {
    id: "u1", unitNumber: "101", status: "VACANT",
    expectedRent: 5000, property: PROPERTY,
};
const UNIT_WITHOUT = {
    id: "u2", unitNumber: "202", status: "VACANT",
    expectedRent: 0, property: PROPERTY,
};
const UNIT_NULL = {
    id: "u3", unitNumber: "303", status: "VACANT",
    expectedRent: null, property: PROPERTY,
};
const UNIT_ABSENT = { id: "u4", unitNumber: "404", status: "VACANT", property: PROPERTY };
// Not reachable through JSON, but reachable through anything that coerces a
// bad value on the way in. Every comparison against NaN is false, so without
// an explicit guard this warns and renders "NaN" at the operator.
const UNIT_NAN = {
    id: "u5", unitNumber: "505", status: "VACANT",
    expectedRent: Number.NaN, property: PROPERTY,
};
const RENTER = { id: "r1", nameEn: "Sara Haddad", nameAr: "سارة حداد", email: "sara@example.com" };

type WizardUnit = typeof UNIT_WITH_ASKING_RENT | typeof UNIT_NULL | typeof UNIT_ABSENT;

function renderWizard(units: WizardUnit[], locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <LeaseWizard open units={units} renters={[RENTER]} onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
}

/**
 * Drives the real SearchableSelect: open the combobox, click the option. The
 * rows carry role="option", so they are not reachable as buttons.
 */
function pick(comboboxIndex: number, optionLabel: string) {
    fireEvent.click(screen.getAllByRole("combobox")[comboboxIndex]);
    const option = screen.getAllByRole("option").find(o => o.textContent?.includes(optionLabel));
    expect(option, `no option matching ${optionLabel}`).toBeTruthy();
    fireEvent.click(option!);
}

/** Field renders an unassociated <label>, so reach the input through the wrapper. */
function inputUnder(labelText: string): HTMLInputElement {
    const label = screen.getByText(labelText);
    const input = label.parentElement!.querySelector("input");
    expect(input, `no input under ${labelText}`).toBeTruthy();
    return input as HTMLInputElement;
}

function clickNext(locale: "en" | "ar" = "en") {
    const messages = locale === "ar" ? ar : en;
    const next = screen.getAllByRole("button").find(b => b.textContent?.trim() === messages.LeaseWizard.next);
    fireEvent.click(next!);
}

/** Selects a unit + renter, advances to Terms, and enters a monthly rent. */
async function enterRent(units: WizardUnit[], unitNumber: string, rent: number, locale: "en" | "ar" = "en") {
    renderWizard(units, locale);
    pick(0, unitNumber);
    pick(1, RENTER.nameEn);
    clickNext(locale);

    const messages = locale === "ar" ? ar : en;
    await waitFor(() => expect(screen.getByText(messages.LeaseWizard.monthlyRentRequired)).toBeInTheDocument());
    fireEvent.change(inputUnder(messages.LeaseWizard.monthlyRentRequired), { target: { value: String(rent) } });
}

const warning = () => screen.queryByTestId("below-expected-rent");

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({}) })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("lease wizard: rent below the unit's expected rent", () => {
    it("warns, naming the expected rent, what was entered, and the gap", async () => {
        await enterRent([UNIT_WITH_ASKING_RENT], "101", 4000);

        await waitFor(() => expect(warning()).toBeInTheDocument());
        const text = warning()!.textContent ?? "";
        expect(text).toContain("5,000");           // expected
        expect(text).toContain("4,000");           // entered
        expect(text).toContain("1,000");           // the shortfall, so it needn't be worked out
    });

    it("stays silent when the rent matches the expected rent exactly", async () => {
        await enterRent([UNIT_WITH_ASKING_RENT], "101", 5000);
        expect(warning()).toBeNull();
    });

    it("stays silent when the rent is above the expected rent", async () => {
        // Guards the comparison direction, and would fail loudly if the two
        // figures were ever put on different periods.
        await enterRent([UNIT_WITH_ASKING_RENT], "101", 9000);
        expect(warning()).toBeNull();
    });

    it("stays silent while the rent field is still empty", async () => {
        await enterRent([UNIT_WITH_ASKING_RENT], "101", 0);
        expect(warning()).toBeNull();
    });

    it("stays silent for a unit with no usable asking rent", async () => {
        // Most units carry 0 or null here; a warning on those would be noise
        // on every lease and the real ones would stop being read.
        for (const [unit, number] of [
            [UNIT_WITHOUT, "202"],
            [UNIT_NULL, "303"],
            [UNIT_ABSENT, "404"],
            [UNIT_NAN, "505"],
        ] as const) {
            await enterRent([unit], number, 4000);
            expect(warning(), `unit ${number}`).toBeNull();
            cleanup();
        }
    });

    it("does not block the lease — Next still advances past Terms", async () => {
        await enterRent([UNIT_WITH_ASKING_RENT], "101", 4000);
        await waitFor(() => expect(warning()).toBeInTheDocument());

        fireEvent.change(inputUnder(en.LeaseWizard.startDateRequired), { target: { value: "2026-09-11" } });
        fireEvent.change(inputUnder(en.LeaseWizard.endDateRequired), { target: { value: "2027-09-10" } });
        clickNext();

        // The stepper labels are split across text nodes ("3. Charges & VAT"),
        // so the step counter is the reliable read of where the wizard is.
        await waitFor(() => expect(screen.getByText("Step 3 of 5")).toBeInTheDocument());
        // The warning belongs to Terms and should not follow the user forward.
        expect(warning()).toBeNull();
    });

    it("renders the warning in Arabic", async () => {
        await enterRent([UNIT_WITH_ASKING_RENT], "101", 4000, "ar");

        await waitFor(() => expect(warning()).toBeInTheDocument());
        // The catalogs must not fall back to the English string.
        expect(warning()!.textContent).not.toContain("Expected rent");
    });
});
