import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LineRow } from "@/components/leases/leaseMath";

/**
 * #54: the header's "Rent carries VAT" flag was stored and ignored — a RENT
 * line's VAT box started from the charge type's catalogue default, so a
 * commercial lease with the flag ticked charged 0 VAT until the operator also
 * ticked the line. The flag now drives the RENT lines' VAT box; an explicit
 * per-line toggle afterwards still wins for that line, including through later
 * header changes (review M-3).
 */

vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/components/ui/SearchableSelect", () => ({
    SearchableSelect: ({ options, value, onChange, placeholder }: {
        options: { value: string; label: string }[]; value: string; onChange: (v: string) => void; placeholder?: string;
    }) => (
        <select aria-label={placeholder} value={value} onChange={e => onChange(e.target.value)}>
            <option value="" />
            {options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
        </select>
    ),
}));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: {
            ...m.chargeTypeApi,
            list: async () => [
                { id: "ct-rent", code: "RENT", behaviour: "RENT", vatApplicableDefault: false, active: true },
                { id: "ct-fee", code: "ADMIN_FEE", behaviour: "FEE", vatApplicableDefault: true, active: true },
            ],
        },
    };
});

/**
 * The grid stubbed down to what the wizard hands it: its rows (rendered as
 * JSON) and an onChange that behaves like the real grid's `pickType` with no
 * `rentVat` prop — the row takes the catalogue default — or its VAT checkbox,
 * which marks the row `vatTouched` as the real one does.
 */
vi.mock("@/components/leases/LeaseLinesGrid", () => ({
    default: ({ lines, onChange }: { lines: LineRow[]; onChange: (r: LineRow[]) => void }) => (
        <div>
            <pre data-testid="rows">{JSON.stringify(lines.map(l => ({ c: l.chargeTypeId, v: l.vatApplicable })))}</pre>
            <button onClick={() => onChange(lines.map(l => ({ ...l, chargeTypeId: "ct-rent", vatApplicable: false })))}>pick-rent</button>
            <button onClick={() => onChange(lines.map(l => ({ ...l, chargeTypeId: "ct-fee", vatApplicable: true })))}>pick-fee</button>
            <button onClick={() => onChange(lines.map(l => ({ ...l, vatApplicable: !l.vatApplicable, vatTouched: true })))}>toggle-vat</button>
        </div>
    ),
}));

import LeaseWizard from "../LeaseWizard";

const UNITS = [
    { id: "u1", unitNumber: "A-101", status: "VACANT", property: { id: "p1", nameEn: "Tower", type: "RESIDENTIAL" } },
];
const RENTER = { id: "r1", nameEn: "Omar Tenant", nameAr: "عمر" };

const rows = () => JSON.parse(screen.getByTestId("rows").textContent || "[]");

async function toLinesStep(rentVat: boolean) {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseWizard open units={UNITS as never} renters={[RENTER] as never} onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
    fireEvent.change(screen.getByLabelText("Unit"), { target: { value: "u1" } });
    fireEvent.change(screen.getByLabelText("Tenant"), { target: { value: "r1" } });
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("wizard-start-date")).toBeInTheDocument());
    fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-10-01" } });
    fireEvent.change(screen.getByTestId("wizard-end-date"), { target: { value: "2027-09-30" } });
    const box = screen.getByTestId("wizard-rent-vat") as HTMLInputElement;
    if (box.checked !== rentVat) fireEvent.click(box);
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("rows")).toBeInTheDocument());
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("lease wizard: header rent-VAT flag drives RENT lines (#54)", () => {
    it("a line set to a RENT charge takes the header flag, not the catalogue default", async () => {
        await toLinesStep(true);
        fireEvent.click(screen.getByText("pick-rent"));
        expect(rows()).toEqual([{ c: "ct-rent", v: true }]);
    });

    it("leaves a non-RENT charge on its catalogue default", async () => {
        await toLinesStep(false);
        fireEvent.click(screen.getByText("pick-fee"));
        expect(rows()).toEqual([{ c: "ct-fee", v: true }]);
    });

    it("an explicit per-line toggle afterwards wins for that line", async () => {
        await toLinesStep(true);
        fireEvent.click(screen.getByText("pick-rent"));
        fireEvent.click(screen.getByText("toggle-vat"));
        expect(rows()).toEqual([{ c: "ct-rent", v: false }]);
    });

    it("changing the header flag re-applies it to the RENT lines", async () => {
        await toLinesStep(false);
        fireEvent.click(screen.getByText("pick-rent"));
        expect(rows()).toEqual([{ c: "ct-rent", v: false }]);

        fireEvent.click(screen.getByText("Back"));
        await waitFor(() => expect(screen.getByTestId("wizard-rent-vat")).toBeInTheDocument());
        fireEvent.click(screen.getByTestId("wizard-rent-vat"));
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("rows")).toBeInTheDocument());
        expect(rows()).toEqual([{ c: "ct-rent", v: true }]);
    });

    it("re-ticking the header does not clobber a line the operator set by hand (M-3)", async () => {
        await toLinesStep(true);
        fireEvent.click(screen.getByText("pick-rent"));
        fireEvent.click(screen.getByText("toggle-vat"));
        expect(rows()).toEqual([{ c: "ct-rent", v: false }]);

        fireEvent.click(screen.getByText("Back"));
        await waitFor(() => expect(screen.getByTestId("wizard-rent-vat")).toBeInTheDocument());
        fireEvent.click(screen.getByTestId("wizard-rent-vat")); // off
        fireEvent.click(screen.getByTestId("wizard-rent-vat")); // on again
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("rows")).toBeInTheDocument());
        expect(rows()).toEqual([{ c: "ct-rent", v: false }]);

        // Picking the charge again hands the row back to the header.
        fireEvent.click(screen.getByText("pick-fee"));
        fireEvent.click(screen.getByText("pick-rent"));
        expect(rows()).toEqual([{ c: "ct-rent", v: true }]);
    });
});
