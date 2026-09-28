import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Cookies from "js-cookie";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next/navigation", () => ({ usePathname: () => "/en/dashboard", useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }) }));
const session = { current: { user: { id: "ta", role: "TENANT_ADMIN", tenantId: "org1" as string | undefined } } };
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: session.current }) }));

import { TenantSwitcher } from "../TenantSwitcher";
import { orgLogoSrc, resetMyOrgsCache } from "@/components/nav/orgStore";
import { resetPageOrg } from "@/lib/session/orgSync";

/**
 * Organisation branding (2026-09-28): the header's organisation control shows the
 * organisation's own logo when one is set, in the same 32 px box as the initials,
 * and falls back to the initials when there is none or it cannot load. Super
 * admin Global View is unchanged.
 */
// Streamed by the app (tenant containers are private), versioned for the cache.
const LOGO = "/api/proxy/v1/org/branding/logo?v=3f2a9c1d0b7e";
let orgs: Array<{ id: string; name: string; logoVersion?: string | null }>;

beforeEach(() => {
    resetMyOrgsCache();
    resetPageOrg();
    session.current = { user: { id: "ta", role: "TENANT_ADMIN", tenantId: "org1" } };
    orgs = [{ id: "org1", name: "Oasis Crest", logoVersion: "3f2a9c1d0b7e" }, { id: "org2", name: "Blue Harbour", logoVersion: null }];
    window.fetch = vi.fn(async () => new Response(JSON.stringify(orgs), { status: 200 })) as unknown as typeof fetch;
    Cookies.set("active_tenant_id", "org1", { path: "/" });
});
afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    Cookies.remove("active_tenant_id", { path: "/" });
});

async function button() {
    const b = screen.getByTestId("org-switcher-button");
    await waitFor(() => expect(b).toHaveAttribute("title", expect.not.stringMatching(/^—$/)));
    return b;
}

describe("header organisation logo", () => {
    it("shows the organisation's logo, sized like the initials, with its name as alt text", async () => {
        render(<TenantSwitcher isCollapsed={false} responsive />);
        const b = await button();
        const img = await within(b).findByRole("img", { name: "Oasis Crest" });
        expect(img).toHaveAttribute("src", LOGO);
        expect(img).toHaveAttribute("width", "32");
        expect(img).toHaveAttribute("height", "32");
        expect(within(b).getByTestId("org-avatar-logo")).toHaveClass("h-8", "w-8", "rounded-full");
        expect(within(b).queryByText("OC")).toBeNull();
    });

    it("falls back to the initials when the organisation has no logo", async () => {
        session.current = { user: { id: "ta2", role: "TENANT_ADMIN", tenantId: "org2" } };
        Cookies.set("active_tenant_id", "org2", { path: "/" });
        render(<TenantSwitcher isCollapsed={false} responsive />);
        const b = await button();
        await waitFor(() => expect(b).toHaveAttribute("title", "Blue Harbour"));
        expect(within(b).getByTestId("org-avatar-initials")).toHaveTextContent("BH");
        expect(within(b).queryByRole("img")).toBeNull();
    });

    it("falls back to the initials, in the same box, when the logo cannot load", async () => {
        render(<TenantSwitcher isCollapsed={false} responsive />);
        const b = await button();
        fireEvent.error(await within(b).findByRole("img", { name: "Oasis Crest" }));
        expect(within(b).queryByRole("img")).toBeNull();
        expect(within(b).getByTestId("org-avatar-initials")).toHaveTextContent("OC");
        expect(within(b).getByTestId("org-avatar-initials")).toHaveClass("h-8", "w-8");
    });

    it("the collapsed switcher shows the logo too", async () => {
        render(<TenantSwitcher isCollapsed />);
        const b = await button();
        expect(await within(b).findByRole("img", { name: "Oasis Crest" })).toBeInTheDocument();
    });

    it("super admin Global View keeps its initials and shows no organisation's logo", async () => {
        session.current = { user: { id: "sa", role: "SUPER_ADMIN", tenantId: undefined } };
        Cookies.remove("active_tenant_id", { path: "/" });
        render(<TenantSwitcher isCollapsed={false} responsive />);
        await waitFor(() => expect(window.fetch).toHaveBeenCalled());
        await new Promise(r => setTimeout(r, 0));
        const b = screen.getByTestId("org-switcher-button");
        expect(b).toHaveAttribute("title", "Global View");
        expect(within(b).getByTestId("org-avatar-initials")).toHaveTextContent("GV");
        expect(within(b).queryByRole("img")).toBeNull();
    });

    it("the logo is always the app's own streaming route, never a storage URL", () => {
        expect(orgLogoSrc({ logoVersion: null })).toBeNull();
        expect(orgLogoSrc(null)).toBeNull();
        expect(orgLogoSrc({ logoVersion: "a b&c" })).toBe("/api/proxy/v1/org/branding/logo?v=a%20b%26c");
    });
});
