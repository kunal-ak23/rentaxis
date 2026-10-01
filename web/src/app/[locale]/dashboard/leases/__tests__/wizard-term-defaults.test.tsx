import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";
import type { LineRow } from "@/components/leases/leaseMath";
import type { LeaseDetail } from "@/lib/api/leasing";

/**
 * Owner requests (2026-09-29) for the New Contract wizard:
 * - a start date fills the end date with start + 12 months − 1 day, until the
 *   user types an end date of their own;
 * - the number of cheques follows the term's months (12 for the default term),
 *   until the user types a count;
 * - the rent for the full term is asked on the Terms step and is the RENT line on
 *   the Charges step — one value, edited from either step, never duplicated.
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
/** The charges grid reduced to its rows and one edit: the RENT row's amount set to 72,000. */
vi.mock("@/components/leases/LeaseLinesGrid", () => ({
    default: ({ lines, onChange }: { lines: LineRow[]; onChange: (r: LineRow[]) => void }) => (
        <div>
            <pre data-testid="rows">{JSON.stringify(lines.map(l => ({ c: l.chargeTypeId, g: l.grossAmount })))}</pre>
            <button onClick={() => onChange(lines.map(l => (l.chargeTypeId === "ct-rent" ? { ...l, grossAmount: 72000 } : l)))}>rent-72000</button>
            <button onClick={() => onChange([...lines, { key: 99, chargeTypeId: "ct-fee", grossAmount: 1050, discountAmount: 0, narration: "", vatApplicable: false, creditAccountId: null }])}>add-fee</button>
        </div>
    ),
}));
vi.mock("@/components/leases/ChequeGrid", async orig => {
    const m = await orig<typeof import("@/components/leases/ChequeGrid")>();
    return { ...m, default: () => <div data-testid="cheque-grid" /> };
});

const api = vi.hoisted(() => ({ createDraft: vi.fn(), updateDraft: vi.fn(), cheques: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: {
            ...m.chargeTypeApi,
            list: async () => [
                { id: "ct-fee", code: "ADMIN_FEE", behaviour: "FEE", vatApplicableDefault: false, active: true },
                { id: "ct-rent", code: "RENT", behaviour: "RENT", vatApplicableDefault: false, active: true },
            ],
        },
        leaseApi: { ...m.leaseApi, ...api },
    };
});

import LeaseWizard from "../LeaseWizard";

const UNITS = [
    { id: "u1", unitNumber: "A-101", propertyId: "p1", propertyName: "Tower", propertyType: "RESIDENTIAL", buildingId: null, buildingName: null, status: "VACANT" },
];
const RENTER = { id: "r1", nameEn: "Omar Tenant", nameAr: "عمر", phone: null, email: null };

const value = (id: string) => (screen.getByTestId(id) as HTMLInputElement).value;
const rows = () => JSON.parse(screen.getByTestId("rows").textContent || "[]");

async function toTerms(locale: "en" | "ar" = "en") {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <LeaseWizard open onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
    const m = locale === "en" ? en : ar;
    fireEvent.change(screen.getByLabelText(m.Leasing.unit), { target: { value: "u1" } });
    fireEvent.change(screen.getByLabelText(m.Leasing.renter), { target: { value: "r1" } });
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("wizard-start-date")).toBeInTheDocument());
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("wizard: end date and number of cheques follow the start date", () => {
    it("fills a 12-month term (start + 12 months − 1 day) and 12 cheques", async () => {
        await toTerms();
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        expect(value("wizard-end-date")).toBe("2027-09-08");
        expect(value("wizard-payment-terms")).toBe("12");
    });

    it("re-derives both from a new start date while they are still automatic", async () => {
        await toTerms();
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2027-02-01" } });
        expect(value("wizard-end-date")).toBe("2028-01-31");
        expect(value("wizard-payment-terms")).toBe("12");
    });

    it("never overwrites an end date the user typed; the count follows it (whole months, the generator's count)", async () => {
        await toTerms();
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        fireEvent.change(screen.getByTestId("wizard-end-date"), { target: { value: "2027-03-20" } });
        // Review of R4-B I1: 6 months and 12 days is 6 cheques — a 7th would share a date.
        expect(value("wizard-payment-terms")).toBe("6");
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-10-01" } });
        expect(value("wizard-end-date")).toBe("2027-03-20");
    });

    it("keeps a count the user typed", async () => {
        await toTerms();
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        fireEvent.change(screen.getByTestId("wizard-payment-terms"), { target: { value: "6" } });
        fireEvent.change(screen.getByTestId("wizard-end-date"), { target: { value: "2027-03-08" } });
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-10-01" } });
        expect(value("wizard-payment-terms")).toBe("6");
    });

    it("refuses more cheques than months in the term", async () => {
        await toTerms();
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60000" } });
        fireEvent.change(screen.getByTestId("wizard-payment-terms"), { target: { value: "13" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(screen.getByTestId("wizard-error").textContent).toContain("at most 12");
    });
});

describe("wizard: at most one cheque a month (review of R4-B I1)", () => {
    it("refuses a 7th cheque on a term of 6 months and 12 days", async () => {
        await toTerms();
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        fireEvent.change(screen.getByTestId("wizard-end-date"), { target: { value: "2027-03-20" } });
        fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60000" } });
        fireEvent.change(screen.getByTestId("wizard-payment-terms"), { target: { value: "7" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(screen.getByTestId("wizard-error").textContent).toContain("at most 6");
    });

    it("counts from a first due date after the start: the default follows it and 12 is refused", async () => {
        await toTerms();
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        expect(value("wizard-payment-terms")).toBe("12");
        fireEvent.change(screen.getByTestId("wizard-first-due-date"), { target: { value: "2026-09-15" } });
        expect(value("wizard-payment-terms")).toBe("11");
        fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60000" } });
        fireEvent.change(screen.getByTestId("wizard-payment-terms"), { target: { value: "12" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(screen.getByTestId("wizard-error").textContent).toContain("at most 11");
    });
});

describe("wizard: rent for the full term", () => {
    it("is required on the Terms step", async () => {
        await toTerms();
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        expect(screen.getByTestId("wizard-error").textContent?.trim()).toBe(en.Leasing.errRentRequired);
        expect(screen.getByTestId("wizard-start-date")).toBeInTheDocument();
    });

    it("is the RENT line on the Charges step, kept in sync both ways and never duplicated by Back/Next", async () => {
        await toTerms();
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60,000" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("rows")).toBeInTheDocument());
        expect(rows()).toEqual([{ c: "ct-rent", g: 60000 }]);

        // Edited on step 3 → step 2 shows it.
        fireEvent.click(screen.getByText("rent-72000"));
        fireEvent.click(screen.getByText("add-fee"));
        fireEvent.click(screen.getByText(en.Leasing.back));
        await waitFor(() => expect(screen.getByTestId("wizard-rent")).toBeInTheDocument());
        expect(value("wizard-rent")).toBe("72000");

        // Edited on step 2 → the same one RENT line on step 3, the fee kept.
        fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "65000" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("rows")).toBeInTheDocument());
        expect(rows()).toEqual([{ c: "ct-rent", g: 65000 }, { c: "ct-fee", g: 1050 }]);
    });

    it("a saved draft round-trips: its RENT line is the Terms step's rent", async () => {
        const saved = {
            id: "L1", version: 1, unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Omar Tenant",
            propertyId: "p1", propertyName: "Tower", status: "DRAFT",
            startDate: "2026-09-09", endDate: "2027-09-08", contractDate: "2026-09-01", agreementDate: null,
            paymentTerms: 12, installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
            firstDueDate: null, gracePeriodDays: null, gracePeriodOverridden: false, ejariNumber: null,
            paymentReferenceNumber: null, rentVatApplicable: false,
            lines: [{ id: "ln1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", behaviour: "RENT",
                grossAmount: 60000, discountAmount: 0, netAmount: 60000, vatApplicable: false, narration: null }],
        } as unknown as LeaseDetail;
        api.createDraft.mockResolvedValue(saved);
        api.updateDraft.mockResolvedValue(saved);
        api.cheques.mockResolvedValue([]);
        await toTerms();
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-09-09" } });
        fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60000" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("rows")).toBeInTheDocument());
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("cheque-grid")).toBeInTheDocument());
        expect(api.createDraft.mock.calls[0][0].lines).toEqual([
            expect.objectContaining({ chargeTypeId: "ct-rent", grossAmount: 60000 }),
        ]);

        fireEvent.click(screen.getByText(en.Leasing.back));
        await waitFor(() => expect(screen.getByTestId("rows")).toBeInTheDocument());
        expect(rows()).toEqual([{ c: "ct-rent", g: 60000 }]);
        fireEvent.click(screen.getByText(en.Leasing.back));
        await waitFor(() => expect(screen.getByTestId("wizard-rent")).toBeInTheDocument());
        expect(value("wizard-rent")).toBe("60000");
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("rows")).toBeInTheDocument());
        expect(rows()).toEqual([{ c: "ct-rent", g: 60000 }]);
    });

    it("reads in Arabic", async () => {
        await toTerms("ar");
        expect(screen.getByLabelText(ar.Leasing.rentFullTerm)).toHaveAttribute("inputmode", "decimal");
    });
});
