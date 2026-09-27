import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
// The server pickers reduced to a plain <select> over a fixed page of results,
// handing onChange the option object as the real pickers do.
vi.mock("@/components/pickers/UnitPicker", () => ({
    UnitPicker: ({ value, onChange, placeholder }: {
        value: string; onChange: (id: string, u: unknown) => void; placeholder?: string;
    }) => (
        <select aria-label={placeholder} value={value} onChange={e => onChange(e.target.value, UNITS.find(u => u.id === e.target.value) ?? null)}>
            <option value="" />
            {UNITS.map(u => <option key={u.id} value={u.id}>{u.unitNumber}</option>)}
        </select>
    ),
}));
vi.mock("@/components/pickers/RenterPicker", () => ({
    RenterPicker: ({ value, onChange, placeholder }: {
        value: string; onChange: (id: string, r: unknown) => void; placeholder?: string;
    }) => (
        <select aria-label={placeholder} value={value} onChange={e => onChange(e.target.value, e.target.value === RENTER.id ? RENTER : null)}>
            <option value="" />
            <option value={RENTER.id}>{RENTER.nameEn}</option>
        </select>
    ),
}));

import LeaseWizard from "../LeaseWizard";

const UNITS = [
    { id: "u1", unitNumber: "A-101", propertyId: "p1", propertyName: "Tower", propertyType: "RESIDENTIAL", buildingId: null, buildingName: null, status: "VACANT" },
];
const RENTER = { id: "r1", nameEn: "Omar Tenant", nameAr: "عمر", phone: null, email: null };

function renderWizard() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseWizard open onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
}

async function goToTermsStep() {
    fireEvent.change(screen.getByLabelText("Unit"), { target: { value: "u1" } });
    fireEvent.change(screen.getByLabelText("Tenant"), { target: { value: "r1" } });
    fireEvent.change(screen.getByTestId("wizard-agreement-date"), { target: { value: "2025-11-20" } });
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("wizard-contract-date")).toBeInTheDocument());
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("lease wizard contract date default (#45)", () => {
    it("defaults the contract date to the agreement date when the operator has not edited it", async () => {
        renderWizard();
        await goToTermsStep();
        expect(screen.getByTestId("wizard-contract-date")).toHaveValue("2025-11-20");
    });

    it("keeps the contract date editable and stops following the agreement date once touched", async () => {
        renderWizard();
        await goToTermsStep();

        fireEvent.change(screen.getByTestId("wizard-contract-date"), { target: { value: "2026-01-15" } });
        expect(screen.getByTestId("wizard-contract-date")).toHaveValue("2026-01-15");

        // Go back and change the agreement date again — the now-touched contract
        // date must not be silently overwritten.
        fireEvent.click(screen.getByText("Back"));
        fireEvent.change(screen.getByTestId("wizard-agreement-date"), { target: { value: "2025-12-01" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("wizard-contract-date")).toBeInTheDocument());
        expect(screen.getByTestId("wizard-contract-date")).toHaveValue("2026-01-15");
    });

    it("falls back to today, not blank, when the agreement date is cleared (m3)", async () => {
        renderWizard();
        await goToTermsStep();
        fireEvent.click(screen.getByText("Back"));
        fireEvent.change(screen.getByTestId("wizard-agreement-date"), { target: { value: "" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("wizard-contract-date")).toBeInTheDocument());
        const today = new Date();
        const pad = (n: number) => String(n).padStart(2, "0");
        expect(screen.getByTestId("wizard-contract-date"))
            .toHaveValue(`${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`);
    });
});
