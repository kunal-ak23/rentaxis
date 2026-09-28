import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";

/**
 * Break-it R2 silent-mutation sweep: deleting a staff member closed the
 * confirm dialog and, on a refused DELETE, said nothing — the admin believed
 * the record was gone.
 */
vi.mock("next/navigation", () => ({
    useParams: () => ({}),
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() }),
    usePathname: () => "/en/dashboard",
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
    usePathname: () => "/dashboard",
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { id: "u", role: "TENANT_ADMIN", tenantId: "t1" } } }) }));

import StaffManager from "../StaffManager";

let del: () => Response;
beforeEach(() => {
    Object.defineProperty(window, "localStorage", { value: { getItem: () => null, setItem: () => {}, removeItem: () => {} }, writable: true });
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method === "DELETE") return del();
        const url = String(input);
        const body = url.endsWith("/v1/staff")
            ? [{ id: "s1", nameEn: "Sami Staff", nameAr: null, role: "WATCHMAN", active: true, propertyId: null, property: null }]
            : [];
        return new Response(JSON.stringify(body), { status: 200 });
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

async function deleteSami(locale: "en" | "ar") {
    const m = locale === "en" ? en : ar;
    render(<NextIntlClientProvider locale={locale} messages={m}><StaffManager /></NextIntlClientProvider>);
    await screen.findAllByText("Sami Staff");
    fireEvent.click(screen.getAllByLabelText(m.Staff.deleteStaff)[0]);
    const confirm = await screen.findAllByRole("button", { name: /^(Delete|حذف)$/ });
    fireEvent.click(confirm[confirm.length - 1]);
}

describe("StaffManager — a refused delete is shown", () => {
    it("says the staff member was not deleted", async () => {
        del = () => new Response("", { status: 409 });
        await deleteSami("en");
        expect((await screen.findByRole("alert")).textContent).toContain(en.Staff.deleteFailed);
    });

    it("says it in Arabic when the request fails outright", async () => {
        del = () => { throw new TypeError("Failed to fetch"); };
        await deleteSami("ar");
        expect((await screen.findByRole("alert")).textContent).toContain(ar.Staff.deleteFailed);
    });
});
