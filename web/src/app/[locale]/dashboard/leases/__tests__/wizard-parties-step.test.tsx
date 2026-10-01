import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";
import type { LineRow } from "@/components/leases/leaseMath";
import type { LeaseDetail } from "@/lib/api/leasing";
import { businessTodayIso } from "@/lib/businessDate";

/**
 * Demo feedback 2026-09-29, contract wizard step 1 (Parties):
 * - a Property and a Building filter narrow the unit picker; clearing them lists
 *   every vacant unit again, and a filter change drops a unit it would hide;
 * - the Agreement date field is gone (owner ruling: only the Contract date). The
 *   draft is saved with no agreement date and the server defaults it.
 *
 * Replaces the #45 "contract date follows the agreement date" tests: there is no
 * agreement date to follow any more, so the contract date defaults to the business
 * date and is the operator's to change.
 */

vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));

// The unit picker as a plain <select> over the units the filter would let through,
// recording the scope it was handed — the real one sends it to /units/search.
const pickerScope = vi.hoisted(() => ({ propertyId: undefined as string | undefined, buildingId: undefined as string | undefined }));
vi.mock("@/components/pickers/UnitPicker", () => ({
    UnitPicker: ({ value, onChange, placeholder, propertyId, buildingId }: {
        value: string; onChange: (id: string, u: unknown) => void; placeholder?: string; propertyId?: string; buildingId?: string;
    }) => {
        pickerScope.propertyId = propertyId;
        pickerScope.buildingId = buildingId;
        const shown = UNITS.filter(u => (!propertyId || u.propertyId === propertyId) && (!buildingId || u.buildingId === buildingId));
        return (
            <select aria-label={placeholder} data-testid="unit-select" value={value}
                onChange={e => onChange(e.target.value, UNITS.find(u => u.id === e.target.value) ?? null)}>
                <option value="" />
                {shown.map(u => <option key={u.id} value={u.id}>{u.unitNumber}</option>)}
            </select>
        );
    },
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
        }] as LineRow[])}>fill-lines</button>
    ),
}));
const api = vi.hoisted(() => ({ createDraft: vi.fn(), cheques: vi.fn() }));
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
    { id: "u1", unitNumber: "A-101", propertyId: "p1", propertyName: "Desert Rose Gardens", propertyType: "RESIDENTIAL", buildingId: "b1", buildingName: "Tower A", status: "VACANT" },
    { id: "u2", unitNumber: "B-201", propertyId: "p1", propertyName: "Desert Rose Gardens", propertyType: "RESIDENTIAL", buildingId: "b2", buildingName: "Tower B", status: "VACANT" },
    { id: "u3", unitNumber: "C-301", propertyId: "p2", propertyName: "Palm Ridge", propertyType: "RESIDENTIAL", buildingId: "b3", buildingName: "Main", status: "VACANT" },
];
const RENTER = { id: "r1", nameEn: "Omar Tenant", nameAr: "عمر", phone: null, email: null };

beforeEach(() => {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as unknown as Response;
        if (url.endsWith("/api/proxy/v1/properties")) {
            return ok([{ id: "p1", nameEn: "Desert Rose Gardens", nameAr: "حدائق وردة الصحراء" }, { id: "p2", nameEn: "Palm Ridge" }]);
        }
        if (url.endsWith("/v1/buildings/property/p1")) return ok([{ id: "b1", nameEn: "Tower A" }, { id: "b2", nameEn: "Tower B", nameAr: "البرج ب" }]);
        if (url.endsWith("/v1/buildings/property/p2")) return ok([{ id: "b3", nameEn: "Main" }]);
        return ok([]);
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

function renderWizard(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <LeaseWizard open onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
}
const unitOptions = () => Array.from(screen.getByTestId("unit-select").querySelectorAll("option")).map(o => o.textContent).filter(Boolean);

describe("contract wizard — Parties step building filter", () => {
    it("narrows the unit list to the chosen property and building; clearing shows all", async () => {
        renderWizard();
        expect(unitOptions()).toEqual(["A-101", "B-201", "C-301"]);
        expect(screen.getByTestId("wizard-filter-building")).toBeDisabled();

        await waitFor(() => expect(screen.getByRole("option", { name: "Desert Rose Gardens" })).toBeInTheDocument());
        fireEvent.change(screen.getByTestId("wizard-filter-property"), { target: { value: "p1" } });
        expect(pickerScope.propertyId).toBe("p1");
        expect(unitOptions()).toEqual(["A-101", "B-201"]);

        await waitFor(() => expect(screen.getByTestId("wizard-filter-building")).not.toBeDisabled());
        fireEvent.change(screen.getByTestId("wizard-filter-building"), { target: { value: "b2" } });
        expect(pickerScope).toEqual({ propertyId: "p1", buildingId: "b2" });
        expect(unitOptions()).toEqual(["B-201"]);

        fireEvent.change(screen.getByTestId("wizard-filter-building"), { target: { value: "" } });
        expect(pickerScope.buildingId).toBeUndefined();
        expect(unitOptions()).toEqual(["A-101", "B-201"]);

        fireEvent.change(screen.getByTestId("wizard-filter-property"), { target: { value: "" } });
        expect(pickerScope).toEqual({ propertyId: undefined, buildingId: undefined });
        expect(unitOptions()).toEqual(["A-101", "B-201", "C-301"]);
    });

    it("drops a picked unit that the new filter would hide", async () => {
        renderWizard();
        fireEvent.change(screen.getByLabelText("Unit"), { target: { value: "u1" } });
        expect(screen.getByTestId("wizard-unit-property")).toBeInTheDocument();
        await waitFor(() => expect(screen.getByRole("option", { name: "Desert Rose Gardens" })).toBeInTheDocument());
        fireEvent.change(screen.getByTestId("wizard-filter-property"), { target: { value: "p1" } });
        await waitFor(() => expect(screen.getByTestId("wizard-filter-building")).not.toBeDisabled());
        // u1 is in Tower A: still listed under p1, dropped under Tower B.
        expect(screen.getByLabelText("Unit")).toHaveValue("u1");
        fireEvent.change(screen.getByTestId("wizard-filter-building"), { target: { value: "b2" } });
        expect(screen.getByLabelText("Unit")).toHaveValue("");
        expect(screen.queryByTestId("wizard-unit-property")).not.toBeInTheDocument();
    });

    it("labels the filters in Arabic", async () => {
        renderWizard("ar");
        expect(screen.getByRole("option", { name: "كل العقارات" })).toBeInTheDocument();
        expect(screen.getByRole("option", { name: "كل المباني" })).toBeInTheDocument();
        fireEvent.change(await screen.findByTestId("wizard-filter-property"), { target: { value: "p1" } });
        expect(await screen.findByRole("option", { name: "البرج ب" })).toBeInTheDocument();
    });
});

describe("contract wizard — no agreement date (owner ruling 2026-09-29)", () => {
    it("has no Agreement date field; the contract date defaults to the business date", async () => {
        renderWizard();
        expect(screen.queryByTestId("wizard-agreement-date")).not.toBeInTheDocument();
        expect(screen.queryByText("Agreement Date")).not.toBeInTheDocument();
        fireEvent.change(screen.getByLabelText("Unit"), { target: { value: "u1" } });
        fireEvent.change(screen.getByLabelText("Tenant"), { target: { value: "r1" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("wizard-contract-date")).toBeInTheDocument());
        expect(screen.getByTestId("wizard-contract-date")).toHaveValue(businessTodayIso());
        fireEvent.change(screen.getByTestId("wizard-contract-date"), { target: { value: "2026-01-15" } });
        expect(screen.getByTestId("wizard-contract-date")).toHaveValue("2026-01-15");
    });

    it("saves the draft with the contract date and no agreement date", async () => {
        api.createDraft.mockResolvedValue({
            id: "L1", version: 1, unitId: "u1", renterId: "r1", status: "DRAFT", lines: [],
            startDate: "2026-10-01", endDate: "2027-09-30", contractDate: "2026-09-20", agreementDate: "2026-09-20",
        } as unknown as LeaseDetail);
        api.cheques.mockResolvedValue([]);
        renderWizard();
        fireEvent.change(screen.getByLabelText("Unit"), { target: { value: "u1" } });
        fireEvent.change(screen.getByLabelText("Tenant"), { target: { value: "r1" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByTestId("wizard-start-date")).toBeInTheDocument());
        fireEvent.change(screen.getByTestId("wizard-contract-date"), { target: { value: "2026-09-20" } });
        fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-10-01" } });
        fireEvent.change(screen.getByTestId("wizard-end-date"), { target: { value: "2027-09-30" } });
        fireEvent.change(screen.getByTestId("wizard-rent"), { target: { value: "60000" } });
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(screen.getByText("fill-lines")).toBeInTheDocument());
        fireEvent.click(screen.getByText("fill-lines"));
        fireEvent.click(screen.getByTestId("wizard-next"));
        await waitFor(() => expect(api.createDraft).toHaveBeenCalled());
        expect(api.createDraft.mock.calls[0][0]).toMatchObject({ contractDate: "2026-09-20", agreementDate: null });
    });
});
