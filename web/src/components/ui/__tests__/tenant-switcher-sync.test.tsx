import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Cookies from "js-cookie";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next/navigation", () => ({ usePathname: () => "/en/dashboard/renters", useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }) }));
const session = { current: { user: { id: "sa", role: "SUPER_ADMIN", tenantId: undefined as string | undefined } } };
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: session.current }) }));

import { TenantSwitcher } from "../TenantSwitcher";
import { resetMyOrgsCache } from "@/components/nav/orgStore";
import { getPageOrg, resetPageOrg } from "@/lib/session/orgSync";

const ORGS = [{ id: "brk1", name: "Sweep brk1" }, { id: "org2", name: "BRK-AUTH-Org2" }];
const realLocation = window.location;
let storageWrites: [string, string][];

beforeEach(() => {
    resetMyOrgsCache();
    resetPageOrg();
    session.current = { user: { id: "sa", role: "SUPER_ADMIN", tenantId: undefined } };
    Object.defineProperty(window, "location", { configurable: true, value: { ...realLocation, href: "http://localhost:3000/en/dashboard/renters" } });
    storageWrites = [];
    vi.spyOn(Storage.prototype, "setItem").mockImplementation((k: string, v: string) => { storageWrites.push([k, v]); });
    window.fetch = vi.fn(async () => new Response(JSON.stringify(ORGS), { status: 200 })) as unknown as typeof fetch;
});
afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    Cookies.remove("active_tenant_id", { path: "/" });
    Object.defineProperty(window, "location", { configurable: true, value: realLocation });
});

async function pick(name: string) {
    fireEvent.click(await screen.findByTestId("org-switcher-button"));
    fireEvent.click(await screen.findByRole("button", { name }));
}

describe("TenantSwitcher — cross-tab announcement (F3)", () => {
    it("tells the other tabs when this tab switches organisation", async () => {
        Cookies.set("active_tenant_id", "brk1", { path: "/" });
        render(<TenantSwitcher isCollapsed={false} />);
        await waitFor(() => expect(screen.getByTestId("org-switcher-button")).toHaveAttribute("title", "Sweep brk1"));
        await pick("BRK-AUTH-Org2");
        expect(Cookies.get("active_tenant_id")).toBe("org2");
        const announced = storageWrites.filter(([k]) => k === "rentaxis-org-change").map(([, v]) => JSON.parse(v).orgId);
        expect(announced).toEqual(["org2"]);
        // This tab's own expectation follows its own switch.
        expect(getPageOrg()).toBe("org2");
    });
});
