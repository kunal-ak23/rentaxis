import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { PropertyPnl } from "@/lib/api/propertyReports";

/**
 * R1 review of PR #373 (P3-1): toggling "By tower" off and back on quickly —
 * nothing stops the Apply button, unlike the By tower / By property toggle —
 * could leave an older, slower `pnlByBuilding` read on screen after a newer
 * one had already landed.
 */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("next-intl", async () => {
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const messages = (await import("../../../../../../../../messages/en.json")).default;
    const cache = new Map<string, ReturnType<typeof createTranslator>>();
    return {
        useTranslations: (namespace: string) => {
            if (!cache.has(namespace)) cache.set(namespace, createTranslator({ locale: "en", messages, namespace: namespace as never }));
            return cache.get(namespace)!;
        },
        useLocale: () => "en",
    };
});
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ options: [{ id: "p1", label: "Sweep House" }], name: () => "Sweep House", loading: false }),
}));
vi.mock("@/hooks/useBuildings", () => ({
    useBuildings: () => ({ buildings: [{ id: "b1", nameEn: "Tower A" }], loading: false, hasBuildings: true, label: (b: { nameEn: string }) => b.nameEn }),
}));

const basePnl: PropertyPnl = {
    from: "2026-01-01", to: "2026-01-31", compare: "NONE", priorFrom: null, priorTo: null, scoped: false,
    columns: [{ key: "p1", propertyId: "p1", kind: "PROPERTY", name: "Sweep House", nameAr: null }],
    groups: [],
    income: {}, expenses: {},
    noi: { p1: { amount: 1, prior: null, delta: null, deltaPct: null } },
    allocation: null, check: null, dataQuality: { lineAccountPropertyMismatches: 0 },
};

let resolveSlow: (v: PropertyPnl) => void = () => {};
const slow = new Promise<PropertyPnl>(r => { resolveSlow = r; });
const byBuildingCalls = { count: 0 };

vi.mock("@/lib/api/propertyReports", async orig => {
    const m = await orig<typeof import("@/lib/api/propertyReports")>();
    return {
        ...m,
        propertyReportsApi: {
            ...m.propertyReportsApi,
            pnl: vi.fn(async () => basePnl),
            pnlByBuilding: vi.fn(async () => {
                byBuildingCalls.count += 1;
                // First call (the first "By tower" toggle-on) hangs; every
                // call after resolves at once, with a different NOI.
                if (byBuildingCalls.count === 1) return slow;
                return {
                    ...basePnl,
                    columns: [{ key: "b1", propertyId: null, kind: "BUILDING", name: "Tower A", nameAr: null }],
                    noi: { b1: { amount: 777, prior: null, delta: null, deltaPct: null } },
                };
            }),
        },
    };
});

import PropertyPlPage from "../page";

afterEach(() => {
    cleanup();
});

describe("Property P&L by tower — P3-1: no stale response wins", () => {
    it("keeps the newer by-tower read on screen when an older, slower one lands after it", async () => {
        render(<PropertyPlPage />);
        const applyButton = () => screen.getByRole("button", { name: /Apply/i });
        await waitFor(() => expect(applyButton()).not.toBeDisabled());

        fireEvent.click(screen.getByTestId("property-multiselect").querySelector("summary")!);
        fireEvent.click(screen.getByRole("checkbox", { name: /Sweep House/ }));
        fireEvent.click(applyButton());
        await waitFor(() => expect(applyButton()).not.toBeDisabled());

        const towerToggle = await screen.findByTestId("pl-view-tower");
        fireEvent.click(towerToggle); // 1st loadBuilding: hangs
        const propertyToggle = screen.getByTestId("pl-view-property");
        fireEvent.click(propertyToggle); // byTower false: no fetch, hides the table
        fireEvent.click(towerToggle); // byTower true again: 2nd loadBuilding, resolves fast

        await screen.findByText("777.00");

        // Now let the slow, stale first read land.
        resolveSlow({
            ...basePnl,
            columns: [{ key: "b1", propertyId: null, kind: "BUILDING", name: "Tower A", nameAr: null }],
            noi: { b1: { amount: 1, prior: null, delta: null, deltaPct: null } },
        });
        await act(async () => { await new Promise(r => setTimeout(r, 20)); });
        expect(screen.getByText("777.00")).toBeTruthy();
        expect(byBuildingCalls.count).toBe(2);
    });
});
