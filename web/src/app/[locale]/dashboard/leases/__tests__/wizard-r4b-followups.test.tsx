import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LeaseDetail } from "@/lib/api/leasing";

/**
 * Review of R4-B, M8/M9/M11 on the New Contract wizard:
 * - M8: the regenerate prompt and "Use this schedule" honour the user's
 *   "Separate cheque for one-time charges" choice (they always folded);
 * - M9: on a contract with a rent-free period the prompt compares the saved rent
 *   rows with the generator's own (capped) count, not the raw payment terms;
 * - M11: with no charge types, or no active RENT type, the rent field says why
 *   it cannot take the rent, and the Terms step refuses with that reason.
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
vi.mock("@/components/leases/LeaseLinesGrid", () => ({ default: () => <div data-testid="rows" /> }));
vi.mock("@/components/leases/ChequeGrid", async orig => {
    const m = await orig<typeof import("@/components/leases/ChequeGrid")>();
    return {
        ...m,
        // The grid's own Generate, with "Separate cheque for one-time charges" ticked.
        default: ({ onGenerate }: { onGenerate?: (r: unknown) => void }) => (
            <div data-testid="cheque-grid">
                <button type="button" data-testid="grid-generate-separate" onClick={() => onGenerate?.({
                    installments: 4, firstDueDate: "2026-09-09", distribution: "LAST_LARGER",
                    foldDepositsAndFeesIntoFirst: false, mode: "PDC",
                })} />
            </div>
        ),
    };
});

const RENT_TYPE = { id: "ct-rent", code: "RENT", behaviour: "RENT", vatApplicableDefault: false, active: true };
const chargeTypes = vi.hoisted(() => ({ list: vi.fn() }));
const api = vi.hoisted(() => ({
    createDraft: vi.fn(), updateDraft: vi.fn(), cheques: vi.fn(), generateCheques: vi.fn(),
    dryRunPost: vi.fn(), previewCheques: vi.fn(), rentSchedulePreview: vi.fn(),
}));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: () => chargeTypes.list() },
        leaseApi: { ...m.leaseApi, ...api },
    };
});

import LeaseWizard from "../LeaseWizard";

const UNITS = [
    { id: "u1", unitNumber: "A-101", propertyId: "p1", propertyName: "Tower", propertyType: "RESIDENTIAL", buildingId: null, buildingName: null, status: "VACANT" },
];
const RENTER = { id: "r1", nameEn: "Omar Tenant", nameAr: "عمر", phone: null, email: null };

const LEASE = {
    id: "L1", version: 1, unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Omar Tenant",
    propertyId: "p1", propertyName: "Tower", status: "DRAFT",
    startDate: "2026-09-09", endDate: "2027-09-08", contractDate: "2026-09-01", paymentTerms: 12,
    lines: [{ id: "ln1", seqNo: 1, chargeTypeId: "ct-rent", behaviour: "RENT", grossAmount: 60000, discountAmount: 0,
        netAmount: 60000, vatApplicable: false, narration: null }],
} as unknown as LeaseDetail;
const DRY = { ok: true, errors: [], contractValue: 60000, contractValueInclVat: 60000, chequeTotal: 0,
    depositCarriedForward: 0, journals: { tco: 1, tcoLines: 2, pdr: 0 } };
const MONTHS = [
    { periodStart: "2026-09-09", periodEnd: "2026-09-30", days: 22, amount: 3616.44 },
    { periodStart: "2026-10-01", periodEnd: "2026-10-31", days: 31, amount: 5095.89 },
    { periodStart: "2027-09-01", periodEnd: "2027-09-08", days: 8, amount: 51287.67 },
];
const SUGGESTED = Array.from({ length: 12 }, (_, i) => ({
    seqNo: i + 1, chequeDate: `2027-${String(((8 + i) % 12) + 1).padStart(2, "0")}-09`, amount: 5000, vat: 0,
    narration: `Rent - ${i + 1} Installment`, kind: "RENT",
}));
const cheque = (i: number) => ({ id: `c${i}`, seqNo: i, chequeNumber: `10000${i}`, chequeDate: "2026-09-09",
    payeeBank: "ENBD", amount: 15000, rowKind: "RENT" });

async function toChequesStep(existing: unknown[] = [], lease: LeaseDetail = LEASE) {
    chargeTypes.list.mockResolvedValue([RENT_TYPE]);
    api.createDraft.mockResolvedValue(lease);
    api.cheques.mockResolvedValue(existing);
    api.dryRunPost.mockResolvedValue(DRY);
    api.rentSchedulePreview.mockResolvedValue(MONTHS);
    api.previewCheques.mockResolvedValue(SUGGESTED);
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseWizard open onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
    fireEvent.change(screen.getByLabelText(en.Leasing.unit), { target: { value: "u1" } });
    fireEvent.change(screen.getByLabelText(en.Leasing.renter), { target: { value: "r1" } });
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("wizard-start-date")).toBeInTheDocument());
    fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
    fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60000" } });
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("rows")).toBeInTheDocument());
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("cheque-grid")).toBeInTheDocument());
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

const fee = (i: number) => ({ ...cheque(i), rowKind: "FEE" });

describe("M8: the wizard's regenerate and Use this schedule honour a separate one-time cheque", () => {
    it("regenerates with the choice the user made on the grid's Generate", async () => {
        await toChequesStep([cheque(1), cheque(2), cheque(3), cheque(4)]);
        api.generateCheques.mockResolvedValue({ cheques: [fee(1), cheque(2), cheque(3), cheque(4), cheque(5)], version: 2 });
        fireEvent.click(screen.getByTestId("grid-generate-separate"));
        await waitFor(() => expect(api.generateCheques).toHaveBeenCalledTimes(1));
        await waitFor(() => expect(screen.getByTestId("wizard-regenerate-prompt")).toBeInTheDocument());
        api.generateCheques.mockResolvedValue({ cheques: Array.from({ length: 12 }, (_, i) => cheque(i + 1)), version: 3 });
        fireEvent.click(screen.getByTestId("wizard-regenerate-confirm"));
        await waitFor(() => expect(api.generateCheques).toHaveBeenCalledTimes(2));
        expect(api.generateCheques.mock.calls[1][1]).toMatchObject({ installments: 12, foldDepositsAndFeesIntoFirst: false });
    });

    it("reads a separate one-time cheque off the saved rows before any Generate", async () => {
        await toChequesStep([fee(1), cheque(2), cheque(3)]);
        api.generateCheques.mockResolvedValue({ cheques: [cheque(1)], version: 2 });
        fireEvent.click(screen.getByTestId("wizard-regenerate-confirm"));
        await waitFor(() => expect(api.generateCheques).toHaveBeenCalledTimes(1));
        expect(api.generateCheques.mock.calls[0][1]).toMatchObject({ foldDepositsAndFeesIntoFirst: false });
    });

    it("suggests and applies the schedule on the Review step with the same choice", async () => {
        await toChequesStep();
        api.generateCheques.mockResolvedValue({ cheques: [], version: 2 });
        fireEvent.click(screen.getByTestId("grid-generate-separate"));
        await waitFor(() => expect(api.generateCheques).toHaveBeenCalledTimes(1));
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("wizard-review")).toBeInTheDocument());
        await waitFor(() => expect(api.previewCheques).toHaveBeenCalled());
        expect(api.previewCheques.mock.calls[0][1]).toMatchObject({ foldDepositsAndFeesIntoFirst: false });
        api.generateCheques.mockResolvedValue({ cheques: [cheque(1)], version: 3 });
        fireEvent.click(await screen.findByTestId("review-use-schedule"));
        await waitFor(() => expect(api.generateCheques).toHaveBeenCalledTimes(2));
        expect(api.generateCheques.mock.calls[1][1]).toMatchObject({ foldDepositsAndFeesIntoFirst: false });
    });

    it("still folds by default", async () => {
        await toChequesStep([cheque(1), cheque(2)]);
        api.generateCheques.mockResolvedValue({ cheques: [cheque(1)], version: 2 });
        fireEvent.click(screen.getByTestId("wizard-regenerate-confirm"));
        await waitFor(() => expect(api.generateCheques).toHaveBeenCalledTimes(1));
        expect(api.generateCheques.mock.calls[0][1]).toMatchObject({ foldDepositsAndFeesIntoFirst: true });
    });
});

describe("M9: a rent-free period does not make the regenerate prompt reappear", () => {
    // One free month at the start: 12 monthly anchors, the first moved to 09/10 onto
    // the second — 11 charged months, so the generator makes 11 rent cheques of 12.
    const FREE = { ...LEASE, rentFreePeriods: [{ fromDate: "2026-09-09", toDate: "2026-10-08" }] } as unknown as LeaseDetail;

    it("does not ask when the saved rent rows are the generator's count", async () => {
        await toChequesStep(Array.from({ length: 11 }, (_, i) => cheque(i + 1)), FREE);
        expect(screen.queryByTestId("wizard-regenerate-prompt")).toBeNull();
    });

    it("asks with the generator's count, and regenerates with it", async () => {
        await toChequesStep([cheque(1), cheque(2), cheque(3), cheque(4)], FREE);
        expect(screen.getByTestId("wizard-regenerate-prompt")).toHaveTextContent("now 11, but 4 are saved");
        api.generateCheques.mockResolvedValue({ cheques: Array.from({ length: 11 }, (_, i) => cheque(i + 1)), version: 2 });
        fireEvent.click(screen.getByTestId("wizard-regenerate-confirm"));
        await waitFor(() => expect(api.generateCheques).toHaveBeenCalledTimes(1));
        expect(api.generateCheques.mock.calls[0][1]).toMatchObject({ installments: 11 });
        await waitFor(() => expect(screen.queryByTestId("wizard-regenerate-prompt")).toBeNull());
    });
});

describe("M11: the rent field says why it cannot take the rent", () => {
    async function toTermsStep() {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <LeaseWizard open onClose={() => {}} onCreated={() => {}} />
            </NextIntlClientProvider>,
        );
        fireEvent.change(screen.getByLabelText(en.Leasing.unit), { target: { value: "u1" } });
        fireEvent.change(screen.getByLabelText(en.Leasing.renter), { target: { value: "r1" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("wizard-start-date")).toBeInTheDocument());
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60000" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
    }

    it("with no active RENT charge type", async () => {
        chargeTypes.list.mockResolvedValue([{ ...RENT_TYPE, active: false }, { id: "ct-fee", code: "ADMIN", behaviour: "FEE", active: true }]);
        await toTermsStep();
        expect(await screen.findByTestId("wizard-rent-unavailable")).toHaveTextContent(en.Leasing.rentNoChargeType);
        expect(screen.getByTestId("wizard-error")).toHaveTextContent(en.Leasing.rentNoChargeType);
        expect(api.createDraft).not.toHaveBeenCalled();
    });

    it("when the charge types failed to load", async () => {
        chargeTypes.list.mockRejectedValue(new Error("down"));
        await toTermsStep();
        expect(await screen.findByTestId("wizard-rent-unavailable")).toHaveTextContent(en.Leasing.rentChargeTypesFailed);
        expect(screen.getByTestId("wizard-error")).toHaveTextContent(en.Leasing.rentChargeTypesFailed);
    });

    it("says nothing when a RENT type is there", async () => {
        chargeTypes.list.mockResolvedValue([RENT_TYPE]);
        await toTermsStep();
        await waitFor(() => expect(screen.getByTestId("rows")).toBeInTheDocument());
        expect(screen.queryByTestId("wizard-rent-unavailable")).toBeNull();
    });
});
