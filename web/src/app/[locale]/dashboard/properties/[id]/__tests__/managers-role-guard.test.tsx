import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * UI sweep round 1: PropertyController admits ACCOUNTANT to GET
 * /properties/{id} (PropertyController.java:62-63) but GET
 * /properties/{id}/managers stays SUPER_ADMIN/TENANT_ADMIN/PROPERTY_MANAGER
 * only (PropertyController.java:68-69). The page used to call the managers
 * endpoint unconditionally, so every ACCOUNTANT visit logged a 403. The
 * fetch — and the "Property Manager" card that renders its result — is now
 * gated on a role the backend actually admits.
 */

const role = { current: "TENANT_ADMIN" };
vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "p1" }),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
    usePathname: () => "/en/dashboard/properties/p1",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current, name: "U", tenantId: "t1" } } }) }));

import PropertyDetailPage from "../page";

function mockFetch() {
    const calledUrls: string[] = [];
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        calledUrls.push(url);
        if (url.endsWith("/v1/properties/p1")) {
            return { ok: true, status: 200, json: async () => ({ id: "p1", nameEn: "Belle Vue", emirate: "DUBAI", type: "RESIDENTIAL" }) } as unknown as Response;
        }
        if (url.endsWith("/managers")) {
            return { ok: true, status: 200, json: async () => ([{ id: "m1", name: "Manny Manager", email: "manny@x.com" }]) } as unknown as Response;
        }
        return { ok: true, status: 200, json: async () => ([]) } as unknown as Response;
    }) as unknown as typeof fetch;
    return calledUrls;
}

const renderPage = () =>
    render(<NextIntlClientProvider locale="en" messages={en}><PropertyDetailPage /></NextIntlClientProvider>);

beforeEach(() => { role.current = "TENANT_ADMIN"; });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("property detail — managers request is role-gated (F: managers 403 for ACCOUNTANT)", () => {
    it("ACCOUNTANT: does not request /managers and shows no manager card", async () => {
        role.current = "ACCOUNTANT";
        const calledUrls = mockFetch();
        renderPage();
        expect(await screen.findAllByText("Belle Vue")).not.toHaveLength(0);
        await waitFor(() => expect(calledUrls.some(u => u.endsWith("/v1/properties/p1"))).toBe(true));
        expect(calledUrls.some(u => u.endsWith("/managers"))).toBe(false);
        expect(screen.queryByText("Manny Manager")).not.toBeInTheDocument();
    });

    it("TENANT_ADMIN: requests /managers and shows the manager card", async () => {
        role.current = "TENANT_ADMIN";
        mockFetch();
        renderPage();
        expect(await screen.findByText("Manny Manager")).toBeInTheDocument();
    });

    it("PROPERTY_MANAGER: requests /managers and shows the manager card", async () => {
        role.current = "PROPERTY_MANAGER";
        mockFetch();
        renderPage();
        expect(await screen.findByText("Manny Manager")).toBeInTheDocument();
    });

    it("SUPER_ADMIN: requests /managers and shows the manager card", async () => {
        role.current = "SUPER_ADMIN";
        mockFetch();
        renderPage();
        expect(await screen.findByText("Manny Manager")).toBeInTheDocument();
    });
});

/**
 * Tutorial bug 2026-09-28: the Property Manager card listed the building's security
 * guard. Only PROPERTY_MANAGER assignees belong under that heading.
 */
describe("property detail — the Property Manager card lists property managers only", () => {
    function serveAssignees(rows: unknown[]) {
        global.fetch = vi.fn(async (input: RequestInfo | URL) => {
            const url = String(input);
            if (url.endsWith("/v1/properties/p1")) {
                return { ok: true, status: 200, json: async () => ({ id: "p1", nameEn: "Belle Vue", emirate: "DUBAI", type: "RESIDENTIAL" }) } as unknown as Response;
            }
            if (url.endsWith("/managers")) return { ok: true, status: 200, json: async () => rows } as unknown as Response;
            return { ok: true, status: 200, json: async () => ([]) } as unknown as Response;
        }) as unknown as typeof fetch;
    }

    it("does not show a security guard assigned to the building", async () => {
        serveAssignees([
            { id: "m1", name: "Manny Manager", email: "manny@x.com", role: "PROPERTY_MANAGER" },
            { id: "g1", name: "Gary Guard", email: "gary@x.com", role: "SECURITY_GUARD" },
        ]);
        renderPage();
        expect(await screen.findByText("Manny Manager")).toBeInTheDocument();
        expect(screen.queryByText("Gary Guard")).not.toBeInTheDocument();
    });

    it("says no property manager is assigned when only a guard is", async () => {
        serveAssignees([{ id: "g1", name: "Gary Guard", email: "gary@x.com", role: "SECURITY_GUARD" }]);
        renderPage();
        expect(await screen.findByText(en.MasterData.noManagersAssigned)).toBeInTheDocument();
        expect(screen.queryByText("Gary Guard")).not.toBeInTheDocument();
    });
});
