import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../messages/ar.json";

/** Break-it R2 pass 3: the staff delete confirm (title, question, button) was English under /ar. */
vi.mock("next/navigation", () => ({
    useParams: () => ({}),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() }),
    usePathname: () => "/ar/dashboard",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
    usePathname: () => "/dashboard",
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { id: "u", role: "TENANT_ADMIN", tenantId: "t1" } } }) }));

import StaffManager from "../StaffManager";

beforeEach(() => {
    Object.defineProperty(window, "localStorage", { value: { getItem: () => null, setItem: () => {}, removeItem: () => {} }, writable: true });
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const body = String(input).endsWith("/v1/staff")
            ? [{ id: "s1", nameEn: "Sami Staff", nameAr: null, role: "WATCHMAN", active: true, propertyId: null, property: null }]
            : [];
        return new Response(JSON.stringify(body), { status: 200 });
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("StaffManager — delete confirm in Arabic", () => {
    it("titles, asks and confirms in Arabic", async () => {
        render(<NextIntlClientProvider locale="ar" messages={ar}><StaffManager /></NextIntlClientProvider>);
        await screen.findAllByText("Sami Staff");
        fireEvent.click(screen.getAllByLabelText(ar.Staff.deleteStaff)[0]);
        expect(await screen.findByText(ar.Staff.deleteTitle)).toBeInTheDocument();
        expect(screen.getByText(ar.Staff.confirmDelete)).toBeInTheDocument();
        expect(screen.getByRole("button", { name: ar.Staff.deleteConfirmButton })).toBeInTheDocument();
        expect(screen.queryByText("Delete Staff Member")).toBeNull();
        expect(screen.queryByRole("button", { name: "Delete" })).toBeNull();
    });
});
