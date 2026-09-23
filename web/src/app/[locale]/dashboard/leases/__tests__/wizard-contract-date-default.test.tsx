import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
// Real SearchableSelect needs a pointer-events-heavy dropdown interaction
// that is irrelevant here — swap it for a plain <select> so the test can
// drive unit/renter selection directly and reach the Terms step.
vi.mock("@/components/ui/SearchableSelect", () => ({
    SearchableSelect: ({
        options,
        value,
        onChange,
        placeholder,
    }: {
        options: { value: string; label: string }[];
        value: string;
        onChange: (v: string) => void;
        placeholder?: string;
    }) => (
        <select aria-label={placeholder} value={value} onChange={e => onChange(e.target.value)}>
            <option value="" />
            {options.map(o => (
                <option key={o.value} value={o.value}>
                    {o.label}
                </option>
            ))}
        </select>
    ),
}));

import LeaseWizard from "../LeaseWizard";

const UNIT = { id: "u1", unitNumber: "A-101", status: "VACANT" };
const RENTER = { id: "r1", nameEn: "Omar Tenant", nameAr: "عمر" };

function renderWizard() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseWizard open units={[UNIT]} renters={[RENTER]} onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
}

async function goToTermsStep() {
    fireEvent.change(screen.getByLabelText("Unit"), { target: { value: "u1" } });
    fireEvent.change(screen.getByLabelText("Renter"), { target: { value: "r1" } });
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
});
