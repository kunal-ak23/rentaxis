import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

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
import { resetMyOrgsCache } from "@/components/nav/orgStore";

/**
 * Break-it R4 brand4 F2: provisioning an organisation with a taken name showed
 * "This action conflicts with existing related records (idx_landlord_org_slug_unique)."
 * The server now answers a coded org.nameTaken, shown in the user's language.
 */
beforeEach(() => {
    resetMyOrgsCache();
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (init?.method === "POST" && u.endsWith("/admin/tenants")) {
            return new Response(JSON.stringify({
                error: true, status: 409, code: "org.nameTaken",
                message: "An organisation with this name already exists. Choose a different name.",
            }), { status: 409 });
        }
        if (u.includes("/auth/me/tenants")) return new Response("[]", { status: 200 });
        return new Response(JSON.stringify([{ id: "org-1", name: "Oasis Crest", status: "ACTIVE", createdAt: "2026-01-01T00:00:00Z" }]), { status: 200 });
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("provisioning an organisation with a taken name", () => {
    it.each([["en", en], ["ar", ar]] as const)("says the name is taken (%s)", async (locale, messages) => {
        render(<NextIntlClientProvider locale={locale} messages={messages}><TenantsPage /></NextIntlClientProvider>);
        await waitFor(() => expect(screen.getByText("Oasis Crest")).toBeInTheDocument());
        fireEvent.click(screen.getByRole("button", { name: messages.SuperAdmin.orgProvision }));
        fireEvent.change(screen.getByTestId("org-name"), { target: { value: "oasis crest!" } });
        fireEvent.submit(screen.getByTestId("org-name").closest("form")!);
        expect(await screen.findByText(messages.SuperAdmin.orgNameTaken)).toBeInTheDocument();
        expect(document.body.textContent).not.toContain("idx_");
    });
});
