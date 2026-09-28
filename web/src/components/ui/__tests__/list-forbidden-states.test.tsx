import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
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

import BankAccountsPage from "@/app/[locale]/dashboard/finance/bank-accounts/page";
import UsersManager from "@/components/users/UsersManager";
import StaffManager from "@/components/staff/StaffManager";
import AccountsPage from "@/app/[locale]/dashboard/finance/accounts/page";

/**
 * Break round 1, F8: when the backend refuses a list (403) the page must say
 * access denied — not "No users found" / "No bank accounts" / "No staff" /
 * "No accounts found" next to create buttons the user cannot use.
 */
function stub(forbiddenPath: string) {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes(forbiddenPath)) return new Response(JSON.stringify({ message: "Forbidden" }), { status: 403 });
        return new Response("[]", { status: 200 });
    }) as unknown as typeof fetch;
}

beforeEach(() => {
    Object.defineProperty(window, "localStorage", { value: { getItem: () => null, setItem: () => {}, removeItem: () => {} }, writable: true });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("list pages on a 403 (F8)", () => {
    it("bank accounts", async () => {
        stub("/v1/bank-accounts");
        render(<BankAccountsPage />);
        expect(await screen.findByTestId("page-access-denied")).toBeInTheDocument();
        expect(screen.queryByText(/no bank accounts/i)).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: /add/i })).not.toBeInTheDocument();
    });

    it("users", async () => {
        stub("/admin/users");
        render(<UsersManager />);
        expect(await screen.findByTestId("page-access-denied")).toBeInTheDocument();
        expect(screen.queryByText(/no users/i)).not.toBeInTheDocument();
    });

    it("staff", async () => {
        stub("/v1/staff");
        render(<StaffManager />);
        expect(await screen.findByTestId("page-access-denied")).toBeInTheDocument();
        expect(screen.queryByText(/no staff/i)).not.toBeInTheDocument();
    });

    it("chart of accounts", async () => {
        stub("/v1/finance/accounts");
        render(<AccountsPage />);
        expect(await screen.findByTestId("page-access-denied")).toBeInTheDocument();
        expect(screen.queryByText(/no accounts found/i)).not.toBeInTheDocument();
    });
});
