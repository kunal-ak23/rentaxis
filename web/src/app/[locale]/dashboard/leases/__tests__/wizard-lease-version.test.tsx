import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LineRow } from "@/components/leases/leaseMath";
import type { Cheque, LeaseDetail } from "@/lib/api/leasing";

/**
 * Break-it round 2, review A:
 * - M3: cutting or saving the cheque grid moves the draft's version. The wizard
 *   adopts the version each grid write answers with, so its next save (and its
 *   post) never refuses itself with 409 lease.changed.
 * - M4: after a 409 lease.changed the wizard re-reads the draft. It must reset the
 *   header (dates, payment terms, distribution) as well as the lines — otherwise
 *   the next save writes the stale header back under the new version.
 */

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
vi.mock("@/components/leases/LeaseLinesGrid", () => ({
    default: ({ onChange }: { onChange: (r: LineRow[]) => void }) => (
        <button onClick={() => onChange([{
            key: 0, chargeTypeId: "ct-rent", grossAmount: 60000, discountAmount: 0, narration: "",
            vatApplicable: false, creditAccountId: null,
        }])}>fill-lines</button>
    ),
}));
vi.mock("@/components/leases/ChequeGrid", async orig => {
    const m = await orig<typeof import("@/components/leases/ChequeGrid")>();
    return {
        ...m,
        draftRowsAreValid: () => true,
        default: ({ onGenerate, error }: { onGenerate: (r: unknown) => void; error?: string | null }) => (
            <div>
                <button onClick={() => onGenerate({ installments: 4 })}>gen-cheques</button>
                {error && <p data-testid="cheque-error">{error}</p>}
            </div>
        ),
    };
});

const api = vi.hoisted(() => ({
    createDraft: vi.fn(), updateDraft: vi.fn(), cheques: vi.fn(), generateCheques: vi.fn(),
    saveCheques: vi.fn(), get: vi.fn(), dryRunPost: vi.fn(),
}));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: async () => [
            { id: "ct-rent", code: "RENT", behaviour: "RENT", vatApplicableDefault: false, active: true },
        ] },
        leaseApi: { ...m.leaseApi, ...api },
    };
});

import LeaseWizard from "../LeaseWizard";
import { ApiError } from "@/lib/api/leasing";

const UNITS = [
    { id: "u1", unitNumber: "A-101", propertyId: "p1", propertyName: "Tower", propertyType: "RESIDENTIAL", buildingId: null, buildingName: null, status: "VACANT" },
];
const RENTER = { id: "r1", nameEn: "Omar Tenant", nameAr: "عمر", phone: null, email: null };

const LEASE = {
    id: "L1", version: 1, unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Omar Tenant",
    propertyId: "p1", propertyName: "Tower", status: "DRAFT",
    startDate: "2026-10-01", endDate: "2027-09-30", contractDate: "2026-09-16", agreementDate: null,
    paymentTerms: 4, installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
    firstDueDate: "2026-10-01", gracePeriodDays: null, gracePeriodOverridden: false, ejariNumber: null,
    paymentReferenceNumber: null, rentVatApplicable: false, lines: [],
} as unknown as LeaseDetail;
const CHEQUES = [{ id: "c1", seqNo: 1, amount: 60000, mode: "CHEQUE", debitAccountId: null }] as unknown as Cheque[];
const CHANGED = () => new ApiError(409, "This contract changed since you opened it — review it again",
    JSON.stringify({ error: true, status: 409, code: "lease.changed", message: "This contract changed since you opened it — review it again" }));

async function toChequesStep() {
    api.createDraft.mockResolvedValue(LEASE);
    api.cheques.mockResolvedValue([]);
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseWizard open onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
    fireEvent.change(screen.getByLabelText("Unit"), { target: { value: "u1" } });
    fireEvent.change(screen.getByLabelText("Tenant"), { target: { value: "r1" } });
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("wizard-start-date")).toBeInTheDocument());
    fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-10-01" } });
    fireEvent.change(screen.getByTestId("wizard-end-date"), { target: { value: "2027-09-30" } });
    fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60000" } });
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByText("fill-lines")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fill-lines"));
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByText("gen-cheques")).toBeInTheDocument());
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("lease wizard: the lease version across grid writes (review A M3)", () => {
    it("names the version each grid write answered with on the next one", async () => {
        await toChequesStep();
        api.generateCheques.mockResolvedValue({ cheques: CHEQUES, version: 2 });
        fireEvent.click(screen.getByText("gen-cheques"));
        await waitFor(() => expect(api.generateCheques).toHaveBeenCalled());
        expect(api.generateCheques.mock.calls[0][2]).toBe(1);

        api.saveCheques.mockResolvedValueOnce({ cheques: CHEQUES, version: 3 });
        await waitFor(() => expect(screen.getByTestId("wizard-save-cheques")).not.toBeDisabled());
        fireEvent.click(screen.getByTestId("wizard-save-cheques"));
        await waitFor(() => expect(api.saveCheques).toHaveBeenCalledTimes(1));
        expect(api.saveCheques.mock.calls[0][2]).toBe(2);

        api.saveCheques.mockResolvedValueOnce({ cheques: CHEQUES, version: 4 });
        await waitFor(() => expect(screen.getByTestId("wizard-save-cheques")).not.toBeDisabled());
        fireEvent.click(screen.getByTestId("wizard-save-cheques"));
        await waitFor(() => expect(api.saveCheques).toHaveBeenCalledTimes(2));
        expect(api.saveCheques.mock.calls[1][2]).toBe(3);
        expect(screen.queryByTestId("cheque-error")).toBeNull();
    });
});

describe("lease wizard: a 409 lease.changed reload resets the header too (review A M4)", () => {
    it("shows the saved terms and saves them, with the saved version", async () => {
        await toChequesStep();
        api.generateCheques.mockRejectedValue(CHANGED());
        const fresh = {
            ...LEASE, version: 5, endDate: "2027-06-30", paymentTerms: 2, installmentDistribution: "UNIFORM",
            firstDueDate: "2026-11-01",
            // Its saved RENT line is the Terms step's rent (owner request 2026-09-29).
            lines: [{ id: "ln1", seqNo: 1, chargeTypeId: "ct-rent", behaviour: "RENT", grossAmount: 60000,
                discountAmount: 0, netAmount: 60000, vatApplicable: false, narration: null }],
        } as unknown as LeaseDetail;
        api.get.mockResolvedValue(fresh);
        fireEvent.click(screen.getByText("gen-cheques"));
        expect(await screen.findByTestId("cheque-error")).toHaveTextContent("This contract changed since you opened it");
        await waitFor(() => expect(api.get).toHaveBeenCalledWith("L1"));

        fireEvent.click(screen.getByText("Back"));
        await waitFor(() => expect(screen.getByText("fill-lines")).toBeInTheDocument());
        fireEvent.click(screen.getByText("Back"));
        await waitFor(() => expect(screen.getByTestId("wizard-end-date")).toBeInTheDocument());
        expect((screen.getByTestId("wizard-end-date") as HTMLInputElement).value).toBe("2027-06-30");

        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByText("fill-lines")).toBeInTheDocument());
        fireEvent.click(screen.getByText("fill-lines"));
        api.updateDraft.mockResolvedValue({ ...fresh, version: 6 });
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(api.updateDraft).toHaveBeenCalled());
        expect(api.updateDraft.mock.calls[0][1]).toMatchObject({
            version: 5, endDate: "2027-06-30", paymentTerms: 2, installmentDistribution: "UNIFORM", firstDueDate: "2026-11-01",
        });
    });
});
