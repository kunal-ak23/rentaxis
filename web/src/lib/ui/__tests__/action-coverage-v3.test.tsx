// src/lib/ui/__tests__/action-coverage-v3.test.tsx — UI PR 3's locations (contract page, lists, Home).
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ACTION_CATALOG, V3_LOCATION, type ActionLocation } from "../actionCatalog";
import type { LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const query = vi.hoisted(() => ({ current: "" }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current), usePathname: () => "/en/dashboard", useRouter: () => ({ push: vi.fn() }), useParams: () => ({ id: "lease-1" }) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "U" } } }) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>, useRouter: () => ({ push: vi.fn() }) }));
for (const [mod, id] of [
    ["@/components/leases/LeaseJournalsTab", "probe-journals"], ["@/components/leases/RecognitionScheduleTab", "probe-recognition"],
    ["@/components/leases/LeasePenaltiesTab", "probe-penalties"], ["@/components/leases/LeaseAddendaPanel", "probe-addenda"],
    ["@/components/leases/LeaseInteractionsPanel", "probe-interactions"], ["@/components/leases/LeaseAssignmentCard", "assignment-card"],
    ["@/components/leases/BadDebtCard", "bad-debt-card"], ["@/components/dashboard/TodayList", "today-list"],
    ["@/components/dashboard/UnitStatusBoard", "unit-board"],
] as const) {
    vi.doMock(mod, () => ({ default: () => <div data-testid={id} /> }));
}
vi.doMock("@/app/[locale]/dashboard/leases/LeaseWizard", () => ({ default: () => null }));
vi.doMock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [], name: () => "", loading: false }) }));

const status = vi.hoisted(() => ({ current: "ACTIVE" as string }));
function lease(id: string, st: LeaseStatus): LeaseDetail {
    return {
        id, unitId: `u-${id}`, renterId: "r1", unitIdentifier: `A-${id}`, renterName: "Tenant", startDate: "2026-01-01", endDate: "2026-12-31", status: st,
        rentAmount: 60000, depositAmount: null, ejariNumber: null, paymentTerms: 4, installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE",
        depositPaymentMethod: "CHEQUE", paymentReferenceNumber: null, propertyId: "p1", propertyName: "Tower", propertyCode: "T",
        hasContract: true, contractNumber: null, displayContractNumber: null, agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01",
        totalDays: 365, gracePeriodDays: 5, firstDueDate: "2026-01-01", renterAcceptedAt: null, renewedFromLeaseId: null, chainId: "c",
        receivableAccountId: null, incomeAccountId: null, postingJournalId: st === "DRAFT" ? null : "j", postedAt: st === "DRAFT" ? null : "2026-01-01T00:00:00Z",
        contractValue: 60000, terminatedOn: null, terminationJournalId: null, terminationNotes: null, lines: [],
    } as LeaseDetail;
}
const ROWS = [{ ...lease("d1", "DRAFT"), hasContract: false }, lease("p1", "PENDING_SIGNATURE"), lease("a1", "ACTIVE")];
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: {
            ...m.leaseApi,
            get: vi.fn(async () => lease("lease-1", status.current as LeaseStatus)),
            cheques: vi.fn(async () => []),
            addenda: vi.fn(async () => []),
            paged: vi.fn(async () => ({ content: ROWS, totalElements: 3, totalPages: 1, number: 0, size: 25 })),
        },
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        settlementApi: { ...m.settlementApi, get: vi.fn(async () => { throw new Error("none"); }) },
        chequeApi: { ...m.chequeApi, statsByLeases: vi.fn(async () => []) },
    };
});

const SUMMARY = { totalProperties: 1, totalUnits: 2, occupiedUnits: 1, vacantUnits: 1, occupancyRate: 50, activeLeases: 1, draftLeases: 1,
    expiringLeases: 0, overdueAmount: 0, collectedAmount: 0, pendingThisMonthAmount: 0, totalRentRevenue: 0, recentActivity: [] };

async function renderLease(st: LeaseStatus, q = ""): Promise<HTMLElement> {
    status.current = st;
    query.current = q;
    const { default: Page } = await import("@/app/[locale]/dashboard/leases/[id]/page");
    const { container } = render(<Page />);
    await screen.findByTestId("lease-actions");
    return container;
}

/** One container per location; several renders are stacked for the contract header (a draft, an active and an ended contract). */
async function renderLocation(loc: ActionLocation): Promise<HTMLElement[]> {
    global.fetch = vi.fn(async (u: RequestInfo | URL) => ({ ok: true, status: 200,
        json: async () => (String(u).includes("/dashboard/summary") ? SUMMARY : []) })) as unknown as typeof fetch;
    window.history.replaceState(null, "", "/en/dashboard");
    if (loc === "lease.actions") {
        const out: HTMLElement[] = [];
        for (const st of ["DRAFT", "ACTIVE", "TERMINATED"] as LeaseStatus[]) {
            out.push((await renderLease(st)).querySelector('[data-testid="lease-actions"]')!.cloneNode(true) as HTMLElement);
            cleanup();
        }
        return out;
    }
    if (loc === "lease.drawer.assignment" || loc === "lease.drawer.writeOff") {
        await renderLease("ACTIVE");
        fireEvent.click(screen.getByTestId(loc === "lease.drawer.assignment" ? "lease-assignment" : "lease-write-off"));
        return [document.body];
    }
    if (loc.startsWith("lease.section.")) {
        const section = loc.split(".")[2];
        // The old tab name for every section that had one; the new ?section= for the rest.
        const q = section === "cheques" ? "tab=payments" : section === "attachments" ? "tab=documents"
            : section === "addenda" ? "tab=documents&section=addenda" : `tab=${section}`;
        return [await renderLease("ACTIVE", q)];
    }
    if (loc.startsWith("leases.")) {
        const { default: Page } = await import("@/app/[locale]/dashboard/leases/page");
        const { container } = render(<Page />);
        await screen.findByTestId("lease-row-a1");
        if (loc === "leases.row") {
            fireEvent.click(screen.getByTestId("lease-view-cards"));
            await screen.findByTestId("lease-card-menu-a1");
            const cards = container.cloneNode(true) as HTMLElement;
            fireEvent.click(screen.getByTestId("lease-view-table"));
            await screen.findByTestId("lease-row-a1");
            return [container, cards];
        }
        return [container];
    }
    if (loc.startsWith("properties.")) {
        const { default: Page } = await import("@/app/[locale]/dashboard/properties/page");
        const { container } = render(<Page />);
        await screen.findByTestId("properties-add-property");
        return [container];
    }
    const { default: Page } = await import("@/app/[locale]/dashboard/page");
    const { container } = render(<Page />);
    await screen.findByTestId("recent-activity");
    return [container];
}

const found = (roots: HTMLElement[], probe: string) => {
    const ids = probe.includes("ROW") ? ROWS.map(r => probe.replace("ROW", r.id)) : [probe];
    return roots.some(root => ids.some(id => root.querySelector(`[data-testid="${id}"]`)));
};

afterEach(() => cleanup());

describe("action coverage v3 — the contract page, the lists and Home keep every action", () => {
    const byLocation = new Map<ActionLocation, typeof ACTION_CATALOG>();
    for (const e of ACTION_CATALOG) if (V3_LOCATION(e.location)) byLocation.set(e.location, [...(byLocation.get(e.location) ?? []), e]);

    it("draws every PR 3 location", () => {
        expect(byLocation.size).toBe(17);
    });

    it.each([...byLocation.keys()])("%s", async loc => {
        const roots = await renderLocation(loc);
        const missing = byLocation.get(loc)!.filter(e => !found(roots, e.probe));
        expect(missing.map(e => `${e.id} (was: ${e.was})`)).toEqual([]);
    });
});
