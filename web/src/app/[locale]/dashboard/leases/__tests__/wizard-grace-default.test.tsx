import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LineRow } from "@/components/leases/leaseMath";

/**
 * Gap #65: the wizard started the grace field at 0 and always sent it, and an
 * explicit 0 beats the property's collection policy on the server — so a
 * building's "5 days" never reached a lease drafted here. The field now starts
 * empty with the property's default as its placeholder, and empty is sent as
 * null, which the server resolves to the property's value.
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

const createDraft = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: {
            ...m.chargeTypeApi,
            list: async () => [{ id: "ct-rent", code: "RENT", behaviour: "RENT", vatApplicableDefault: false, active: true }],
        },
        leaseApi: {
            ...m.leaseApi,
            createDraft: (body: unknown) => {
                createDraft(body);
                return Promise.resolve({ id: "l1", lines: [] });
            },
            cheques: async () => [],
        },
    };
});

/** The grid reduced to one button that fills in a valid rent line. */
vi.mock("@/components/leases/LeaseLinesGrid", () => ({
    default: ({ lines, onChange }: { lines: LineRow[]; onChange: (r: LineRow[]) => void }) => (
        <button onClick={() => onChange(lines.map(l => ({ ...l, chargeTypeId: "ct-rent", grossAmount: 51000 })))}>fill-rent</button>
    ),
}));

import LeaseWizard from "../LeaseWizard";

const UNITS = [
    { id: "u1", unitNumber: "A-101", status: "VACANT", property: { id: "p1", nameEn: "Tower", type: "RESIDENTIAL" } },
];
const RENTER = { id: "r1", nameEn: "Omar Tenant", nameAr: "عمر" };

beforeEach(() => {
    global.fetch = vi.fn(async (url: unknown) => {
        if (String(url).includes("/rent-settings/p1")) {
            return { ok: true, status: 200, json: async () => ({ gracePeriodDays: 5 }) } as Response;
        }
        return { ok: false, status: 404, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

async function toTermsStep() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseWizard open units={UNITS as never} renters={[RENTER] as never} onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
    fireEvent.change(screen.getByLabelText("Unit"), { target: { value: "u1" } });
    fireEvent.change(screen.getByLabelText("Renter"), { target: { value: "r1" } });
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByTestId("wizard-start-date")).toBeInTheDocument());
    fireEvent.change(screen.getByTestId("wizard-start-date"), { target: { value: "2026-10-01" } });
    fireEvent.change(screen.getByTestId("wizard-end-date"), { target: { value: "2027-09-30" } });
}

async function saveCharges() {
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(screen.getByText("fill-rent")).toBeInTheDocument());
    fireEvent.click(screen.getByText("fill-rent"));
    fireEvent.click(screen.getByTestId("wizard-next"));
    await waitFor(() => expect(createDraft).toHaveBeenCalledTimes(1));
    return createDraft.mock.calls[0][0] as { gracePeriodDays: number | null };
}

describe("lease wizard: grace inherits the property's default (#65)", () => {
    it("starts empty with the property's default as placeholder, and sends null", async () => {
        await toTermsStep();
        const input = screen.getByTestId("grace-days-input") as HTMLInputElement;
        expect(input.value).toBe("");
        await waitFor(() => expect(input.placeholder).toBe("Property default: 5 days"));
        expect(screen.queryByTestId("grace-use-property-default")).toBeNull();

        const body = await saveCharges();
        expect(body.gracePeriodDays).toBeNull();
    });

    it("sends a typed 0 as the lease's own grace", async () => {
        await toTermsStep();
        fireEvent.change(screen.getByTestId("grace-days-input"), { target: { value: "0" } });

        const body = await saveCharges();
        expect(body.gracePeriodDays).toBe(0);
    });

    it("'Use property default' clears an override back to null", async () => {
        await toTermsStep();
        fireEvent.change(screen.getByTestId("grace-days-input"), { target: { value: "7" } });
        fireEvent.click(screen.getByTestId("grace-use-property-default"));
        expect((screen.getByTestId("grace-days-input") as HTMLInputElement).value).toBe("");

        const body = await saveCharges();
        expect(body.gracePeriodDays).toBeNull();
    });
});
