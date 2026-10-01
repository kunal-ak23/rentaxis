import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LineRow } from "@/components/leases/leaseMath";
import type { Cheque, LeaseDetail } from "@/lib/api/leasing";

/**
 * Restored capabilities (UX gap audit 2026-10-01), on the wizard harness of
 * wizard-lease-version.test.tsx:
 * - the Cheques step's "Scan cheques" (lost in the v2 rebuild) saves the grid,
 *   opens the scan on the saved rows and reads the draft back afterwards;
 * - the Terms step's below-asking-rent warning (#254).
 *
 * The harness was written for break-it round 2, review A:
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

vi.mock("@/components/cheques/BulkChequeUploadFlow", async orig => {
    const m = await orig<typeof import("@/components/cheques/BulkChequeUploadFlow")>();
    return {
        ...m,
        default: ({ leaseId, rows, onSuccess }: { leaseId: string; rows: Cheque[]; onSuccess: () => void }) => (
            <div data-testid="scan-flow" data-lease={leaseId} data-rows={rows.length}>
                <button onClick={onSuccess}>scan-done</button>
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
    vi.unstubAllGlobals();
});

const PDC = [
    { id: "c1", seqNo: 1, amount: 30000, mode: "PDC", status: "DRAFT", debitAccountId: null },
    { id: "c2", seqNo: 2, amount: 30000, mode: "PDC", status: "DRAFT", debitAccountId: null },
] as unknown as Cheque[];

describe("lease wizard: Scan cheques on the Cheques step (restored)", () => {
    it("saves the grid, opens the scan on the saved rows, then reads the draft back", async () => {
        await toChequesStep();
        expect(screen.queryByTestId("wizard-scan-cheques")).toBeNull();
        api.generateCheques.mockResolvedValue({ cheques: PDC, version: 2 });
        fireEvent.click(screen.getByText("gen-cheques"));
        const scan = await screen.findByTestId("wizard-scan-cheques");
        expect(scan).toHaveTextContent("Scan cheques");

        api.saveCheques.mockResolvedValueOnce({ cheques: PDC, version: 3 });
        await waitFor(() => expect(scan).not.toBeDisabled());
        fireEvent.click(scan);
        const flow = await screen.findByTestId("scan-flow");
        expect(api.saveCheques).toHaveBeenCalledTimes(1);
        expect(api.saveCheques.mock.calls[0][2]).toBe(2);
        expect(flow.dataset.lease).toBe("L1");
        expect(flow.dataset.rows).toBe("2");

        api.get.mockResolvedValue({ ...LEASE, version: 4 });
        api.cheques.mockResolvedValue(PDC);
        fireEvent.click(screen.getByText("scan-done"));
        await waitFor(() => expect(api.get).toHaveBeenCalledWith("L1"));
        expect(screen.queryByTestId("scan-flow")).toBeNull();
    });

    it("does not open the scan when the save is refused", async () => {
        await toChequesStep();
        api.generateCheques.mockResolvedValue({ cheques: PDC, version: 2 });
        fireEvent.click(screen.getByText("gen-cheques"));
        const scan = await screen.findByTestId("wizard-scan-cheques");
        api.saveCheques.mockRejectedValueOnce(new ApiError(400, "Row 1: a cheque needs its date"));
        await waitFor(() => expect(scan).not.toBeDisabled());
        fireEvent.click(scan);
        await waitFor(() => expect(api.saveCheques).toHaveBeenCalled());
        expect(await screen.findByTestId("cheque-error")).toBeInTheDocument();
        expect(screen.queryByTestId("scan-flow")).toBeNull();
    });
});

describe("lease wizard: below the unit's asking rent (#254, restored)", () => {
    it("warns, without blocking, when the rent works out below the asking rent a year", async () => {
        const fetchMock = vi.fn(async (url: string) => ({
            ok: true,
            json: async () => (String(url).includes("/units/paged") ? { content: [{ id: "u1", expectedRent: 80000 }] } : []),
        }));
        vi.stubGlobal("fetch", fetchMock);
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
        const warn = await screen.findByTestId("below-expected-rent");
        expect(warn).toHaveTextContent("This unit asks 80,000.00 a year");
        expect(warn).toHaveTextContent("20,000.00 below");
        expect(fetchMock.mock.calls.some(c => String(c[0]).startsWith("/api/proxy/v1/units/paged?q=A-101"))).toBe(true);

        fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "80000" } });
        await waitFor(() => expect(screen.queryByTestId("below-expected-rent")).toBeNull());
    });
});
