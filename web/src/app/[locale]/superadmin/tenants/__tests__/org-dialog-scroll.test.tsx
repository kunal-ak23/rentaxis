import { cleanup, render, screen, fireEvent, waitFor, within } from "@testing-library/react";
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
vi.mock("@/components/ui/FileUpload", () => ({ FileUpload: () => <div style={{ height: 300 }} /> }));

import TenantsPage from "../page";
import { resetMyOrgsCache } from "@/components/nav/orgStore";

/**
 * Break-it R4 ops4 F2: the Create/Edit Organisation card had no height cap and no
 * scroll; at 1366x768 and 1280x720 the Create button sat below the screen. jsdom
 * does no layout, so this pins the structure that makes it reachable: the card is
 * capped at the viewport, the fields scroll inside it, and the buttons live in a
 * footer outside the scrolling part — in both directions.
 */
beforeEach(() => {
    resetMyOrgsCache();
    global.fetch = vi.fn(async (url: unknown) => {
        if (String(url).includes("/auth/me/tenants")) return new Response("[]", { status: 200 });
        return new Response(JSON.stringify([{ id: "org-1", name: "Oasis Crest", status: "ACTIVE", createdAt: "2026-01-01T00:00:00Z" }]), { status: 200 });
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("organisation dialog layout", () => {
    it.each([["en", en], ["ar", ar]] as const)("scrolls its fields and keeps the buttons in a fixed footer (%s)", async (locale, messages) => {
        render(<NextIntlClientProvider locale={locale} messages={messages}><TenantsPage /></NextIntlClientProvider>);
        await waitFor(() => expect(screen.getByText("Oasis Crest")).toBeInTheDocument());
        fireEvent.click(screen.getByRole("button", { name: messages.SuperAdmin.orgProvision }));

        const dialog = screen.getByRole("dialog");
        expect(dialog.className).toMatch(/max-h-\[calc\(100dvh/);
        expect(dialog.className).toContain("flex-col");

        const body = screen.getByTestId("org-dialog-body");
        expect(body.className).toContain("overflow-y-auto");
        expect(body.className).toContain("min-h-0");
        expect(within(body).getByTestId("org-name")).toBeInTheDocument();

        const footer = screen.getByTestId("org-dialog-footer");
        expect(body.contains(footer)).toBe(false);
        expect(footer.className).toContain("shrink-0");
        expect(within(footer).getByRole("button", { name: messages.SuperAdmin.orgCreate })).toHaveAttribute("type", "submit");
    });
});
