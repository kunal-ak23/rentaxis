import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import * as fs from "node:fs";
import * as path from "node:path";

import en from "../../../../../../messages/en.json";

vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("@/components/cheques/ChequeScanner", () => ({ default: () => null }));
vi.mock("@/components/cheques/BulkChequeUploadFlow", () => ({ default: () => null }));
import LeaseWizard from "../LeaseWizard";

/**
 * NumberInput's own tests prove the component behaves. They say nothing about
 * whether the forms use it — reverting a call site to a bare
 * <input type="number"> leaves every one of them green. This covers the wizard,
 * which is where the complaint came from.
 */

const UNIT = {
    id: "u1", unitNumber: "101", status: "VACANT",
    property: { id: "p1", nameEn: "Marina Tower", type: "RESIDENTIAL" },
};
const RENTER = { id: "r1", nameEn: "Sara Haddad", nameAr: "سارة حداد", email: "sara@example.com" };

function renderWizard() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseWizard open units={[UNIT]} renters={[RENTER]} onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
}

/** SearchableSelect rows carry role="option", so they are not buttons. */
function pick(comboboxIndex: number, optionLabel: string) {
    fireEvent.click(screen.getAllByRole("combobox")[comboboxIndex]);
    const option = screen.getAllByRole("option").find(o => o.textContent?.includes(optionLabel));
    fireEvent.click(option!);
}

/** Step 1 will not advance without both parties chosen. */
async function openTermsStep() {
    renderWizard();
    pick(0, UNIT.unitNumber);
    pick(1, RENTER.nameEn);
    clickNext();
    await waitFor(() => expect(screen.getByText(en.LeaseWizard.monthlyRentRequired)).toBeInTheDocument());
}

/** Field renders an unassociated <label>, so reach the input via the wrapper. */
function inputUnder(labelText: string): HTMLInputElement {
    const label = screen.getByText(labelText);
    return label.parentElement!.querySelector("input") as HTMLInputElement;
}

function clickNext() {
    const next = screen.getAllByRole("button").find(b => b.textContent?.trim() === en.LeaseWizard.next);
    fireEvent.click(next!);
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({}) })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("lease wizard number fields", () => {
    it("opens the amount fields empty instead of showing a 0 to type around", async () => {
        await openTermsStep();

        expect(inputUnder(en.LeaseWizard.monthlyRentRequired).value).toBe("");
        expect(inputUnder(en.LeaseWizard.securityDepositAed).value).toBe("");
    });

    it("still shows a real default where one is meant to be seen", async () => {
        // Installments default to 4. "Starts empty" must mean "starts empty at
        // zero", not "never shows its initial value".
        await openTermsStep();
        fireEvent.change(inputUnder(en.LeaseWizard.startDateRequired), { target: { value: "2026-09-11" } });
        fireEvent.change(inputUnder(en.LeaseWizard.endDateRequired), { target: { value: "2027-09-10" } });
        fireEvent.change(inputUnder(en.LeaseWizard.monthlyRentRequired), { target: { value: "5000" } });
        clickNext();
        clickNext();

        await waitFor(() => expect(screen.getByText(en.LeaseWizard.installmentsRequired)).toBeInTheDocument());
        expect(inputUnder(en.LeaseWizard.installmentsRequired).value).toBe("4");
    });
});

/**
 * The sweep covered every form binding a number, not just the wizard. A file
 * check is the only thing that notices a call site quietly reverting, since
 * rendering each of these forms in a test would cost far more than it proves.
 */
describe("the number-input sweep", () => {
    const FORMS = [
        "app/[locale]/dashboard/leases/LeaseWizard.tsx",
        "app/[locale]/dashboard/leases/LeaseMetadataEditor.tsx",
        "app/[locale]/dashboard/leases/PaymentScheduleEditor.tsx",
        "app/[locale]/dashboard/leases/[id]/settlement/page.tsx",
        "app/[locale]/dashboard/settings/rent-settings/page.tsx",
        "app/[locale]/dashboard/settings/fines/page.tsx",
        "app/[locale]/dashboard/properties/page.tsx",
        "app/[locale]/dashboard/finance/transactions/page.tsx",
        "app/[locale]/dashboard/staff/page.tsx",
    ];
    const SRC = path.join(__dirname, "..", "..", "..", "..", "..");

    it.each(FORMS)("%s binds no number straight to a raw input", file => {
        const text = fs.readFileSync(path.join(SRC, file), "utf8");
        // A raw type="number" is fine when it holds a string — those already
        // render empty. What must not come back is one parsing its own event
        // into a number, which is exactly the binding that displayed the 0.
        const raw = text.split("<input").slice(1)
            .map(chunk => chunk.slice(0, chunk.indexOf("/>")))
            .filter(attrs => attrs.includes('type="number"'))
            .filter(attrs => /Number\(|parseFloat\(|parseInt\(/.test(attrs));
        expect(raw, `raw numeric inputs left in ${file}`).toEqual([]);
    });
});
