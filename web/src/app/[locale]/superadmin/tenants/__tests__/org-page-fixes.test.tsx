import { cleanup, render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import Cookies from "js-cookie";

import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";

vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => "/en/superadmin/tenants",
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { id: "sa", role: "SUPER_ADMIN" } } }) }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));
vi.mock("@/components/ui/FileUpload", () => ({ FileUpload: () => null }));

import TenantsPage from "../page";
import { TenantSwitcher } from "@/components/ui/TenantSwitcher";
import { resetMyOrgsCache } from "@/components/nav/orgStore";

/**
 * Tutorial 04 prep (tutorials/bugs/2026-09-28-04.md): the header switcher did not
 * list an organisation created on this page until a reload; an empty search said
 * "No organisations provisioned yet."; a feature was still called "Lease Renewals".
 */
type Org = { id: string; name: string; status: string; createdAt: string };
let orgs: Org[];
let meTenantsCalls: number;

beforeEach(() => {
    resetMyOrgsCache();
    meTenantsCalls = 0;
    orgs = [{ id: "org-1", name: "Oasis Crest", status: "ACTIVE", createdAt: "2026-01-01T00:00:00Z" }];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (u.includes("/auth/me/tenants")) {
            meTenantsCalls++;
            return new Response(JSON.stringify(orgs.map(o => ({ id: o.id, name: o.name }))), { status: 200 });
        }
        if (u.endsWith("/features")) {
            return new Response(JSON.stringify([
                { feature: "LEASE_RENEWALS", label: "Lease Renewals & Reminders", defaultEnabled: true, enabled: true },
                { feature: "SOMETHING_NEW", label: "Server label", defaultEnabled: false, enabled: false },
            ]), { status: 200 });
        }
        if (init?.method === "POST" && u.endsWith("/admin/tenants")) {
            const body = JSON.parse(String(init.body));
            const created = { id: "org-new", name: body.name, status: "ACTIVE", createdAt: "2026-09-28T00:00:00Z" };
            orgs = [...orgs, created];
            return new Response(JSON.stringify(created), { status: 200 });
        }
        return new Response(JSON.stringify(orgs), { status: 200 });
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); Cookies.remove("active_tenant_id", { path: "/" }); });

function renderWith(locale: "en" | "ar", node: React.ReactNode) {
    return render(<NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>{node}</NextIntlClientProvider>);
}

describe("organisations page fixes", () => {
    it("(a) the switcher lists a newly created organisation without a reload", async () => {
        renderWith("en", <><TenantSwitcher isCollapsed={false} /><TenantsPage /></>);
        await waitFor(() => expect(screen.getByText("Oasis Crest")).toBeInTheDocument());
        await waitFor(() => expect(meTenantsCalls).toBe(1));

        fireEvent.click(screen.getByRole("button", { name: en.SuperAdmin.orgProvision }));
        fireEvent.change(screen.getByTestId("org-name"), { target: { value: "Palm Vista Real Estate" } });
        fireEvent.submit(screen.getByTestId("org-name").closest("form")!);
        await waitFor(() => expect(meTenantsCalls).toBe(2));

        fireEvent.click(screen.getByTestId("org-switcher-button"));
        expect(await screen.findByRole("button", { name: "Palm Vista Real Estate" })).toBeInTheDocument();
    });

    it("(b) a search with no match says so; an empty list says none yet (EN and AR)", async () => {
        renderWith("en", <TenantsPage />);
        await waitFor(() => expect(screen.getByText("Oasis Crest")).toBeInTheDocument());
        fireEvent.change(screen.getByLabelText(en.SuperAdmin.orgSearch), { target: { value: "zzz" } });
        expect(await screen.findByText(en.SuperAdmin.orgNoMatch)).toBeInTheDocument();
        expect(screen.queryByText(en.SuperAdmin.orgEmpty)).toBeNull();
        cleanup();

        orgs = [];
        renderWith("ar", <TenantsPage />);
        expect(await screen.findByText(ar.SuperAdmin.orgEmpty)).toBeInTheDocument();
        expect(ar.SuperAdmin.orgNoMatch).not.toBe(ar.SuperAdmin.orgEmpty);
    });

    it("(c) feature labels use current terms, in the page's language, falling back to the server's", async () => {
        renderWith("ar", <TenantsPage />);
        await waitFor(() => expect(screen.getByText("Oasis Crest")).toBeInTheDocument());
        const row = screen.getByText("Oasis Crest").closest("tr")!;
        fireEvent.click(within(row).getByRole("button", { name: ar.Index.featureToggles }));
        expect(await screen.findByText(ar.SuperAdmin.featureLabels.LEASE_RENEWALS)).toBeInTheDocument();
        expect(screen.getByText("Server label")).toBeInTheDocument();
        expect(screen.queryByText("Lease Renewals & Reminders")).toBeNull();
        expect(en.SuperAdmin.featureLabels.LEASE_RENEWALS).toBe("Contract Renewals & Reminders");
        for (const label of Object.values(en.SuperAdmin.featureLabels)) {
            expect(label).not.toMatch(/lease|renter/i);
        }
    });

    it("(d) the name placeholder is a clearly fictional company", () => {
        expect(en.SuperAdmin.orgNamePlaceholder).not.toMatch(/futtaim/i);
        expect(ar.SuperAdmin.orgNamePlaceholder).not.toMatch(/الفطيم/);
    });
});
