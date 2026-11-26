import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LeaseDetail } from "@/lib/api/leasing";

const push = vi.hoisted(() => vi.fn());
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push }),
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
    dryRunPost: vi.fn(), previewCheques: vi.fn(), rentSchedulePreview: vi.fn(), post: vi.fn(), incomeFrom: vi.fn(),
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

// Back-dated: the term began in September, the contract is dated November (QA-22).
const LEASE = {
    id: "L1", version: 1, unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Omar Tenant",
    propertyId: "p1", propertyName: "Tower", status: "DRAFT", displayContractNumber: null,
    startDate: "2026-09-09", endDate: "2027-09-08", contractDate: "2026-11-26", paymentTerms: 12,
    lines: [{ id: "ln1", seqNo: 1, chargeTypeId: "ct-rent", behaviour: "RENT", grossAmount: 60000, discountAmount: 0,
        netAmount: 60000, vatApplicable: false, narration: null }],
} as unknown as LeaseDetail;
const DRY = { ok: true, errors: [], contractValue: 60000, contractValueInclVat: 60000, chequeTotal: 0,
    depositCarriedForward: 0, journals: { tco: 1, tcoLines: 2, pdr: 0 }, notices: [] };
const OPTIONS = {
    backdated: true, earliest: "2026-11-01", latest: "2026-11-01", defaultFrom: "2026-11-01",
    incomeFrom: "2026-11-01", catchUpOn: "2026-11-26", foldedMonths: 2, foldedAmount: 8712.33, monthsNamed: ["09/2026", "10/2026"],
};

async function toReviewStep(onCreated = vi.fn(), onClose = vi.fn()) {
    api.createDraft.mockResolvedValue(LEASE);
    api.cheques.mockResolvedValue([]);
    api.dryRunPost.mockResolvedValue(DRY);
    api.rentSchedulePreview.mockResolvedValue([]);
    api.previewCheques.mockResolvedValue([]);
    api.incomeFrom.mockResolvedValue(OPTIONS);
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseWizard open onClose={onClose} onCreated={onCreated} />
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
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("wizard-review")).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId("wizard-post")).not.toBeDisabled());
    return { onCreated, onClose };
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

/**
 * QA-22 (prod hand test 2026-11-26): the wizard's Post Contract posted at once. It skipped
 * the confirm step and the back-dated "income from" choice the contract page asks, so a
 * back-dated contract booked income into past, closed months. It now opens the same dialog.
 */
describe("wizard Post Contract opens the post dialog", () => {
    it("asks before posting, and posts a back-dated contract with one entry this month", async () => {
        const { onCreated, onClose } = await toReviewStep();
        fireEvent.click(screen.getByTestId("wizard-post"));

        expect(api.post).not.toHaveBeenCalled();
        expect(await screen.findByTestId("post-income-from-fold")).toBeInTheDocument();
        expect(screen.getByTestId("post-income-from-one")).toBeChecked();

        api.post.mockResolvedValue({ lease: { id: "L1" }, tcoEntryNumber: "TCO-26/9", notices: [] });
        await waitFor(() => expect(screen.getByTestId("post-lease-confirm")).not.toBeDisabled());
        fireEvent.click(screen.getByTestId("post-lease-confirm"));
        await waitFor(() => expect(api.post).toHaveBeenCalledWith("L1", 1, "2026-11-01"));
        await waitFor(() => expect(push).toHaveBeenCalledWith("/dashboard/leases/L1?posted=TCO-26%2F9"));
        expect(onCreated).toHaveBeenCalled();
        expect(onClose).toHaveBeenCalled();
    });

    it("posts nothing when the dialog is cancelled", async () => {
        await toReviewStep();
        fireEvent.click(screen.getByTestId("wizard-post"));
        await screen.findByTestId("post-dry-run-ok");
        fireEvent.click(screen.getByText(en.Leasing.cancel));
        await waitFor(() => expect(screen.queryByTestId("post-dry-run")).toBeNull());
        expect(api.post).not.toHaveBeenCalled();
        expect(screen.getByTestId("wizard-review")).toBeInTheDocument();
    });
});
