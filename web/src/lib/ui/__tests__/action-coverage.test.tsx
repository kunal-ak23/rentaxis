// src/lib/ui/__tests__/action-coverage.test.tsx
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ACTION_CATALOG, type ActionLocation } from "../actionCatalog";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const query = { current: "" };
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current), usePathname: () => "/en/dashboard", useRouter: () => ({ push: vi.fn() }), useParams: () => ({}) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "U" } } }), signOut: vi.fn() }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>, useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/hooks/useTenantFeatures", () => ({ useTenantFeatures: () => ({ isEnabled: () => true, tenantSlug: "acme", features: {}, loading: false }) }));
vi.mock("@/components/ui/GlobalSearch", () => ({ default: () => null }));
for (const [mod, id] of [
    ["@/components/settings/FinesSettings", "probe-fines"], ["@/components/settings/RentSettings", "probe-rent"],
    ["@/components/settings/GatewaySettings", "probe-gateway"], ["@/components/settings/OnlinePaymentSwitch", "probe-online-switch"],
    ["@/components/settings/OrganisationSection", "probe-organisation"], ["@/components/staff/StaffManager", "probe-staff"],
    ["@/components/users/UsersManager", "probe-users"],
] as const) {
    vi.doMock(mod, () => ({ default: () => <div data-testid={id} /> }));
}

async function renderLocation(loc: ActionLocation): Promise<HTMLElement> {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
    if (loc.startsWith("settings.")) {
        query.current = `section=${loc.split(".")[1]}`;
        const { default: Page } = await import("@/app/[locale]/dashboard/settings/page");
        return render(<Page />).container;
    }
    if (loc === "operations.staff") {
        const { default: Page } = await import("@/app/[locale]/dashboard/staff/page");
        return render(<Page />).container;
    }
    const { TopHeader } = await import("@/components/ui/TopHeader");
    const { NavShellProvider } = await import("@/components/nav/NavShellContext");
    return render(<NavShellProvider><TopHeader /></NavShellProvider>).container;
}

afterEach(cleanup);

describe("action coverage — every inventoried action is still reachable", () => {
    const byLocation = new Map<ActionLocation, typeof ACTION_CATALOG>();
    for (const e of ACTION_CATALOG) byLocation.set(e.location, [...(byLocation.get(e.location) ?? []), e]);

    it.each([...byLocation.keys()])("%s", async loc => {
        const container = await renderLocation(loc);
        const missing = byLocation.get(loc)!.filter(e => !container.querySelector(`[data-testid="${e.probe}"]`));
        expect(missing.map(e => `${e.id} (was: ${e.was})`)).toEqual([]);
    });

    it("has unique ids", () => {
        const ids = ACTION_CATALOG.map(e => e.id);
        expect(new Set(ids).size).toBe(ids.length);
    });
});
