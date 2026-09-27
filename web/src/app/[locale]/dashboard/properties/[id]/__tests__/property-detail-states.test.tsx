import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * Break round 1, F5: a property that does not exist, belongs to another
 * organisation, or is outside a property manager's scope answered 404/403 and
 * the page kept its loading skeleton forever. It must resolve to not-found,
 * access-denied, or an error with a retry.
 */

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
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "U", tenantId: "t1" } } }) }));

import PropertyDetailPage from "../page";

function mockPropertyStatus(statuses: number[]) {
    let call = 0;
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/v1/properties/p1")) {
            const status = statuses[Math.min(call++, statuses.length - 1)];
            const body = status === 200 ? { id: "p1", nameEn: "Belle Vue", emirate: "DUBAI", type: "RESIDENTIAL" } : { message: "nope" };
            return { ok: status === 200, status, json: async () => body } as unknown as Response;
        }
        return { ok: false, status: 404, json: async () => ({}) } as unknown as Response;
    }) as unknown as typeof fetch;
}

const renderPage = () =>
    render(<NextIntlClientProvider locale="en" messages={en}><PropertyDetailPage /></NextIntlClientProvider>);

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("property detail — unresolvable ids (F5)", () => {
    it("shows not-found with a way back for a 404", async () => {
        mockPropertyStatus([404]);
        renderPage();
        expect(await screen.findByTestId("page-not-found")).toBeInTheDocument();
        expect(screen.getByText(/property not found/i)).toBeInTheDocument();
        expect(screen.getByRole("link", { name: /back to properties/i })).toHaveAttribute("href", "/dashboard/properties");
    });

    it("shows access denied for a 403", async () => {
        mockPropertyStatus([403]);
        renderPage();
        expect(await screen.findByTestId("page-access-denied")).toBeInTheDocument();
    });

    it("shows an error with a retry for a 500, and the retry loads the property", async () => {
        mockPropertyStatus([500, 200]);
        renderPage();
        expect(await screen.findByTestId("page-load-failed")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: /retry/i }));
        await waitFor(() => expect(screen.queryByTestId("page-load-failed")).not.toBeInTheDocument());
        expect((await screen.findAllByText("Belle Vue")).length).toBeGreaterThan(0);
    });

    it("shows an error with a retry when the request itself fails", async () => {
        global.fetch = vi.fn(async () => { throw new TypeError("network"); }) as unknown as typeof fetch;
        renderPage();
        expect(await screen.findByTestId("page-load-failed")).toBeInTheDocument();
    });
});
