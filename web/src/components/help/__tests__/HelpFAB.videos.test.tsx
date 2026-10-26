import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { UserRole } from "@/lib/rbac";

/**
 * The help button offers the page's video tutorials (HELP_PAGE_MAP videos), only
 * published ones made for the viewer's role, and only a tour the role can take.
 */

const state: { role: UserRole; path: string } = { role: "PROPERTY_MANAGER", path: "/dashboard/collections" };

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: state.role } } }) }));
vi.mock("@/i18n/routing", () => ({
    usePathname: () => state.path,
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("@/components/tour/TourProvider", () => ({ useTour: () => ({ startTour: vi.fn() }) }));

import HelpFAB from "../HelpFAB";

afterEach(cleanup);

const openMenu = () => fireEvent.click(screen.getByRole("button"));
const videoLinks = () => within(screen.getByTestId("help-fab-videos")).getAllByRole("link").map(a => a.getAttribute("href"));

describe("HelpFAB page videos", () => {
    it("lists the page's videos the role may watch, in order, linked to the player", () => {
        state.role = "PROPERTY_MANAGER";
        state.path = "/dashboard/collections";
        render(<HelpFAB />);
        openMenu();
        // 40 (returned cheques in the books) is for admins and accountants only.
        expect(videoLinks()).toEqual(["/dashboard/help/videos?play=15", "/dashboard/help/videos?play=35"]);
    });

    it("gives an accountant the same page with the books video too", () => {
        state.role = "ACCOUNTANT";
        state.path = "/dashboard/collections";
        render(<HelpFAB />);
        openMenu();
        expect(videoLinks()).toEqual(["/dashboard/help/videos?play=35", "/dashboard/help/videos?play=40"]);
    });

    it("gives a security guard the gate article and the gate video on the gate desk", () => {
        state.role = "SECURITY_GUARD";
        state.path = "/dashboard/gatepass/gate";
        render(<HelpFAB />);
        openMenu();
        expect(screen.getByRole("link", { name: /View Page Help/i })).toHaveAttribute("href", "/dashboard/help/operations--gate-passes");
        expect(videoLinks()).toEqual(["/dashboard/help/videos?play=25"]);
    });

    it("offers the contract-list tour to a role it is written for, and no tour to one it is not", () => {
        state.role = "ACCOUNTANT";
        state.path = "/dashboard/leases";
        render(<HelpFAB />);
        openMenu();
        expect(screen.getByRole("button", { name: /Take a Tour/i })).toBeInTheDocument();
        cleanup();

        state.role = "ACCOUNTANT";
        state.path = "/superadmin/tenants"; // the super-admin tour, for System Admins only
        render(<HelpFAB />);
        openMenu();
        expect(screen.queryByRole("button", { name: /Take a Tour/i })).not.toBeInTheDocument();
    });
});
