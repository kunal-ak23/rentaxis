import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { JournalEntry } from "@/lib/api/ledger";

/**
 * A journal line carries unit and renter ids; the detail page names them with
 * one bounded /names call for this entry's ids (scale P1-6), never by loading
 * every unit and renter of the tenant.
 */

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "j1", locale: "en" }),
    useSearchParams: () => new URLSearchParams(""),
}));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ name: () => "", options: [], loading: false }),
}));
const lookup = vi.hoisted(() => ({
    searchUnits: vi.fn(async () => []),
    searchRenters: vi.fn(async () => []),
    unitNames: vi.fn(async (ids: string[]) => ({ rows: ids.map(id => ({ id, unitNumber: `Unit-${id}`, propertyId: null, propertyName: null, propertyType: null, buildingId: null, buildingName: null, status: null })), failedIds: [] })),
    renterNames: vi.fn(async (ids: string[]) => ({ rows: ids.map(id => ({ id, nameEn: `Renter-${id}`, nameAr: null, phone: null, email: null })), failedIds: [] })),
}));
vi.mock("@/lib/api/lookup", () => ({ lookupApi: lookup }));
const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, journals: { ...m.ledgerApi.journals, get: api.get } } };
});

import JournalDetailPage from "../[id]/page";

const line = (lineNo: number, unitId: string | null, renterId: string | null) => ({
    lineNo, accountId: `a${lineNo}`, accountCode: `51010${lineNo}`, accountName: "Maintenance",
    debit: lineNo === 1 ? 1000 : 0, credit: lineNo === 1 ? 0 : 1000, narration: null,
    propertyId: null, unitId, leaseId: null, renterId, chequeId: null,
});

const entry = {
    id: "j1", entryNumber: "JV/2026/0001", docType: "JV", entryDate: "2026-09-20", narration: "Accrual", status: "POSTED",
    propertyId: null, unitId: null, leaseId: null, renterId: null, sourceType: "MANUAL", sourceId: null,
    reversalOfId: null, reversedById: null, importBatchId: null, postedBy: null, postedAt: "2026-09-20T09:00:00Z", total: 1000,
    lines: [line(1, "u1", "r1"), line(2, "u1", null)],
} as unknown as JournalEntry;

afterEach(cleanup);

describe("journal detail — line names", () => {
    it("names each line's unit and tenant from this entry's ids only", async () => {
        api.get.mockResolvedValue(entry);
        render(<NextIntlClientProvider locale="en" messages={en}><JournalDetailPage /></NextIntlClientProvider>);
        await waitFor(() => expect(screen.getAllByText("Unit-u1")).toHaveLength(2));
        await waitFor(() => expect(screen.getByText("Renter-r1")).toBeInTheDocument());
        expect(lookup.unitNames).toHaveBeenCalledTimes(1);
        expect(lookup.unitNames).toHaveBeenCalledWith(["u1"]);
        expect(lookup.renterNames).toHaveBeenCalledWith(["r1"]);
    });
});
