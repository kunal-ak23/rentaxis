import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";
import type { LineRow } from "@/components/leases/leaseMath";
import type { LeaseDetail } from "@/lib/api/leasing";

/**
 * Owner requests (2026-09-29): the Review step shows the monthly rent and the
 * cheques, or — when none were added — the schedule the generator would create,
 * with "Use this schedule"; and a count changed after cheques were generated asks
 * before regenerating. The figures come from the backend previews (mocked here);
 * ContractReviewPreviewIT pins them to ProrationEngine and the generator.
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
    return { ...m, default: () => <div data-testid="cheque-grid" /> };
});

const api = vi.hoisted(() => ({
    createDraft: vi.fn(), updateDraft: vi.fn(), cheques: vi.fn(), generateCheques: vi.fn(),
    dryRunPost: vi.fn(), previewCheques: vi.fn(), rentSchedulePreview: vi.fn(),
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

async function toChequesStep(existing: unknown[] = []) {
    api.createDraft.mockResolvedValue(LEASE);
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

describe("wizard Review: monthly rent and cheques", () => {
    it("shows the monthly rent with a total equal to the rent, and the suggested schedule when no cheques were added", async () => {
        await toChequesStep();
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("wizard-review")).toBeInTheDocument());

        await waitFor(() => expect(screen.getAllByTestId("review-month")).toHaveLength(3));
        expect(screen.getByTestId("review-monthly-total")).toHaveTextContent("60,000.00");

        await waitFor(() => expect(screen.getAllByTestId("review-suggested-row")).toHaveLength(12));
        expect(screen.getByTestId("review-suggested-total")).toHaveTextContent("60,000.00");
        expect(screen.getByTestId("review-suggested")).toHaveTextContent(en.Leasing.reviewFirstDueDefaulted);
        // Asked of the generator with the Terms step's count, the start date as first due date.
        expect(api.previewCheques).toHaveBeenCalledWith("L1", {
            installments: 12, firstDueDate: "2026-09-09", distribution: "LAST_LARGER", foldDepositsAndFeesIntoFirst: true,
        });

        api.generateCheques.mockResolvedValue({ cheques: [cheque(1)], version: 2 });
        fireEvent.click(screen.getByTestId("review-use-schedule"));
        await waitFor(() => expect(api.generateCheques).toHaveBeenCalledWith("L1", api.previewCheques.mock.calls[0][1], 1));
        await waitFor(() => expect(screen.getByTestId("review-cheques")).toBeInTheDocument());
    });

    it("lists the cheques added instead of a suggestion", async () => {
        await toChequesStep(Array.from({ length: 12 }, (_, i) => cheque(i + 1)));
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("review-cheques")).toBeInTheDocument());
        expect(screen.getAllByTestId("review-cheque")).toHaveLength(12);
        expect(screen.getByTestId("review-cheques-total")).toHaveTextContent("180,000.00");
        expect(screen.queryByTestId("review-suggested")).toBeNull();
        expect(api.previewCheques).not.toHaveBeenCalled();
    });
});

describe("wizard Cheques: a changed count asks before regenerating", () => {
    it("asks, and regenerates only on confirm", async () => {
        await toChequesStep([cheque(1), cheque(2), cheque(3), cheque(4)]);
        expect(screen.getByTestId("wizard-regenerate-prompt")).toHaveTextContent("now 12, but 4 are saved");
        expect(api.generateCheques).not.toHaveBeenCalled();
        api.generateCheques.mockResolvedValue({ cheques: Array.from({ length: 12 }, (_, i) => cheque(i + 1)), version: 2 });
        fireEvent.click(screen.getByTestId("wizard-regenerate-confirm"));
        await waitFor(() => expect(api.generateCheques).toHaveBeenCalledTimes(1));
        expect(api.generateCheques.mock.calls[0][1]).toMatchObject({ installments: 12 });
        await waitFor(() => expect(screen.queryByTestId("wizard-regenerate-prompt")).toBeNull());
    });

    it("keeps the cheques when told to", async () => {
        await toChequesStep([cheque(1), cheque(2), cheque(3), cheque(4)]);
        fireEvent.click(screen.getByTestId("wizard-regenerate-keep"));
        expect(screen.queryByTestId("wizard-regenerate-prompt")).toBeNull();
        expect(api.generateCheques).not.toHaveBeenCalled();
    });
});
