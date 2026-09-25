// src/app/[locale]/dashboard/settings/__tests__/settings-page.test.tsx
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const role = { current: "TENANT_ADMIN" };
const query = { current: "" };
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current } } }) }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/settings/FinesSettings", () => ({ default: (p: { embedded?: boolean }) => <div data-testid="probe-fines" data-embedded={String(p.embedded)} /> }));
vi.mock("@/components/settings/RentSettings", () => ({ default: (p: { hideOnlinePaymentToggle?: boolean }) => <div data-testid="probe-rent" data-hide={String(p.hideOnlinePaymentToggle)} /> }));
vi.mock("@/components/settings/GatewaySettings", () => ({ default: () => <div data-testid="probe-gateway" /> }));
vi.mock("@/components/settings/OnlinePaymentSwitch", () => ({ default: () => <div data-testid="probe-online-switch" /> }));
vi.mock("@/components/settings/OrganisationSection", () => ({ default: () => <div data-testid="probe-organisation" /> }));
vi.mock("@/components/staff/StaffManager", () => ({ default: () => <div data-testid="probe-staff" /> }));
vi.mock("@/components/users/UsersManager", () => ({ default: () => <div data-testid="probe-users" /> }));
import SettingsPage from "../page";

beforeEach(() => { role.current = "TENANT_ADMIN"; query.current = ""; });
afterEach(cleanup);

describe("Settings page", () => {
    it.each([
        ["", ["probe-organisation"]],
        ["section=organisation", ["probe-organisation"]],
        ["section=users", ["probe-users", "probe-staff"]],
        ["section=rent", ["probe-fines", "probe-rent"]],
        ["section=payments", ["probe-gateway", "probe-online-switch"]],
        ["section=nonsense", ["probe-organisation"]],
    ])("?%s renders %j", (q, probes) => {
        query.current = q;
        render(<SettingsPage />);
        for (const p of probes) expect(screen.getByTestId(p)).toBeInTheDocument();
    });

    it("hides the online-payment switch inside Rent & fines (it lives in Payments)", () => {
        query.current = "section=rent";
        render(<SettingsPage />);
        expect(screen.getByTestId("probe-rent")).toHaveAttribute("data-hide", "true");
    });

    it("lists the four sections as links", () => {
        render(<SettingsPage />);
        expect(screen.getByTestId("settings-nav-rent")).toHaveAttribute("href", "/dashboard/settings?section=rent");
        expect(screen.getByTestId("settings-nav-organisation")).toHaveAttribute("aria-current", "page");
    });

    it("shows a no-access panel to a role with no section", () => {
        role.current = "ACCOUNTANT";
        render(<SettingsPage />);
        expect(screen.getByTestId("settings-no-access")).toBeInTheDocument();
        expect(screen.queryByTestId("probe-organisation")).toBeNull();
    });
});
