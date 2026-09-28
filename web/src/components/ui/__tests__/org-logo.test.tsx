import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Cookies from "js-cookie";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next/navigation", () => ({ usePathname: () => "/en/dashboard", useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }) }));
const session = { current: { user: { id: "ta", role: "TENANT_ADMIN", tenantId: "org1" as string | undefined } } };
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: session.current }) }));

import { TenantSwitcher } from "../TenantSwitcher";
import { safeLogoSrc } from "../OrgAvatar";
import { resetMyOrgsCache } from "@/components/nav/orgStore";
import { resetPageOrg } from "@/lib/session/orgSync";

/**
 * Organisation branding (2026-09-28): the header's organisation control shows the
 * organisation's own logo when one is set, in the same 32 px box as the initials,
 * and falls back to the initials when there is none or it cannot load. Super
 * admin Global View is unchanged.
 */
const LOGO = "/api/v1/assets/serve/assets/oasis-logo.png";
let orgs: Array<{ id: string; name: string; logoUrl?: string | null }>;

beforeEach(() => {
    resetMyOrgsCache();
    resetPageOrg();
    session.current = { user: { id: "ta", role: "TENANT_ADMIN", tenantId: "org1" } };
    orgs = [{ id: "org1", name: "Oasis Crest", logoUrl: LOGO }, { id: "org2", name: "Blue Harbour", logoUrl: null }];
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
        expect(within(b).getByTestId("org-avatar-logo")).toHaveClass("h-8", "w-8");
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

    it("a logo URL that is not ours, https or an inline image is not shown", () => {
        expect(safeLogoSrc("javascript:alert(1)")).toBeNull();
        expect(safeLogoSrc("http://169.254.169.254/logo.png")).toBeNull();
        expect(safeLogoSrc("//evil.example/logo.png")).toBeNull();
        expect(safeLogoSrc("data:image/svg+xml;base64,PHN2Zz4=")).toBeNull();
        expect(safeLogoSrc("  ")).toBeNull();
        expect(safeLogoSrc("https://acct.blob.core.windows.net/shared/assets/l.png")).toBe("https://acct.blob.core.windows.net/shared/assets/l.png");
        expect(safeLogoSrc("/api/v1/assets/serve/assets/l.png")).toBe("/api/v1/assets/serve/assets/l.png");
        // A private folder goes through the signed-in proxy.
        expect(safeLogoSrc("/api/v1/assets/serve/tenant-x/l.png")).toBe("/api/proxy/v1/assets/serve/tenant-x/l.png");
    });
});
