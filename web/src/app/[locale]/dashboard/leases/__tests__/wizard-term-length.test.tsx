import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";

// Break round 1: the wizard accepted a ~973-year contract (end date 2999).
// Over 50 years is blocked at the Terms step with the backend's message; over
// 5 years asks "This contract runs N years — continue?" before moving on.

vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
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

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chargeTypeApi: { ...m.chargeTypeApi, list: async () => [
        { id: "ct-rent", code: "RENT", behaviour: "RENT", vatApplicableDefault: false, active: true },
    ] } };
});

import LeaseWizard from "../LeaseWizard";

const UNITS = [
    { id: "u1", unitNumber: "A-101", propertyId: "p1", propertyName: "Tower", propertyType: "RESIDENTIAL", buildingId: null, buildingName: null, status: "VACANT" },
];
const RENTER = { id: "r1", nameEn: "Omar Tenant", nameAr: "عمر", phone: null, email: null };

async function toTerms(start: string, end: string) {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseWizard open onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
    fireEvent.change(screen.getByLabelText("Unit"), { target: { value: "u1" } });
    fireEvent.change(screen.getByLabelText("Tenant"), { target: { value: "r1" } });
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("wizard-start-date")).toBeInTheDocument());
    fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: start } });
    fireEvent.change(screen.getByTestId("wizard-end-date"), { target: { value: end } });
    fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60000" } });
}

const onTermsStep = () => screen.queryByTestId("wizard-start-date") !== null;

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("lease wizard term length", () => {
    it("blocks a term over 50 years with a clear message", async () => {
        await toTerms("2026-06-01", "2999-06-01");
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(screen.getByTestId("wizard-error").textContent).toContain("at most 50 years");
        expect(screen.queryByTestId("wizard-long-term-confirm")).toBeNull();
        expect(onTermsStep()).toBe(true);
    });

    it("asks to confirm a term over 5 years and moves on only after Continue", async () => {
        await toTerms("2026-06-01", "2032-05-31");
        fireEvent.click(screen.getByTestId("wizard-next"));
        const confirm = screen.getByTestId("wizard-long-term-confirm");
        expect(confirm.textContent).toContain("This contract runs 6 years — continue?");
        expect(onTermsStep()).toBe(true);

        fireEvent.click(screen.getByTestId("wizard-long-term-continue"));
        await waitFor(() => expect(onTermsStep()).toBe(false));
    });

    it("a confirmed term is not asked about again after Back", async () => {
        await toTerms("2026-06-01", "2032-05-31");
        fireEvent.click(screen.getByTestId("wizard-next"));
        fireEvent.click(screen.getByTestId("wizard-long-term-continue"));
        await waitFor(() => expect(onTermsStep()).toBe(false));
        fireEvent.click(screen.getByText("Back"));
        await waitFor(() => expect(onTermsStep()).toBe(true));
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(screen.queryByTestId("wizard-long-term-confirm")).toBeNull();
        await waitFor(() => expect(onTermsStep()).toBe(false));
    });

    it("changing a date after the prompt asks again", async () => {
        await toTerms("2026-06-01", "2032-05-31");
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(screen.getByTestId("wizard-long-term-confirm")).toBeInTheDocument();
        fireEvent.change(screen.getByTestId("wizard-end-date"), { target: { value: "2027-05-31" } });
        fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60000" } });
        expect(screen.queryByTestId("wizard-long-term-confirm")).toBeNull();
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(onTermsStep()).toBe(false));
    });

    it("a normal one-year term moves on without a prompt", async () => {
        await toTerms("2026-06-01", "2027-05-31");
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(screen.queryByTestId("wizard-long-term-confirm")).toBeNull();
        await waitFor(() => expect(onTermsStep()).toBe(false));
    });

    it("exactly 5 years needs no confirmation", async () => {
        await toTerms("2026-06-01", "2031-05-31");
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(screen.queryByTestId("wizard-long-term-confirm")).toBeNull();
        await waitFor(() => expect(onTermsStep()).toBe(false));
    });
});

// Break-it R4 money4 F2: the contract date is the posting date of the contract;
// the server refuses one more than a year ahead, so the Terms step does too.
describe("lease wizard contract date (R4 money4 F2)", () => {
    it("caps the contract date input and blocks 2099 with a clear message", async () => {
        await toTerms("2026-06-01", "2027-05-31");
        const input = screen.getByTestId("wizard-contract-date") as HTMLInputElement;
        expect(input.max).toMatch(/^\d{4}-\d{2}-\d{2}$/);
        fireEvent.change(input, { target: { value: "2099-12-31" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(screen.getByTestId("wizard-error").textContent).toContain("at most one year from today");
        expect(onTermsStep()).toBe(true);
    });
});
