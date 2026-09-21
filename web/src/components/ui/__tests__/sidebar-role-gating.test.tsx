import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";

/**
 * Which sidebar links each role is offered.
 *
 * <p>The finance group used to be one gate — `canAccessFinance`, which admits
 * ACCOUNTANT. But Vendors, Bank Accounts and Staff sit behind
 * VendorController / BankAccountController / StaffController, none of which
 * grant ACCOUNTANT. An accountant was therefore shown links that 403 on
 * arrival. The ledger pages (chart of accounts, journals, general ledger,
 * tenant ledger, trial balance) are the ones that actually admit the role,
 * and they stay on `canAccessFinance`; Vendors/Bank Accounts/Staff moved to
 * `canAccessFinanceOps`.
 *
 * <p>The cheque register replaced Payments and moved to its own gate,
 * `canManageCheques` — `ChequeController`'s STAFF group admits SA/TA/
 * ACCOUNTANT/PROPERTY_MANAGER, unlike the old Payments link which sat behind
 * `canAccessFinanceOps` (SA/TA only) because `PaymentScheduleController`
 * refused both. An accountant and a property manager now both see the
 * register; only cancelling a cheque (a separate, narrower permission) stays
 * off PROPERTY_MANAGER's plate.
 */

const role = { current: "ACCOUNTANT" };

vi.mock("next/navigation", () => ({
    usePathname: () => "/en/dashboard",
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({
        data: { user: { role: role.current, name: "User", tenantId: "tenant-1" } },
    }),
}));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: () => true, features: {}, loading: false }),
}));
vi.mock("framer-motion", () => ({
    motion: new Proxy({}, {
        get: () => ({ children, ...rest }: { children?: React.ReactNode }) => <div {...rest}>{children}</div>,
    }),
    AnimatePresence: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));
vi.mock("next/image", () => ({
    default: ({ alt }: { alt: string }) => <img alt={alt} />,
}));

import MvpSidebar from "../MvpSidebar";

function renderAs(userRole: string) {
    role.current = userRole;
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <MvpSidebar />
        </NextIntlClientProvider>,
    );
}

function hrefs(container: HTMLElement): string[] {
    return Array.from(container.querySelectorAll("a")).map(a => a.getAttribute("href") ?? "");
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    Object.defineProperty(window, "localStorage", {
        value: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
        writable: true,
    });
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("sidebar finance gating", () => {
    it("offers an accountant the ledger pages and the cheque register", () => {
        const { container } = renderAs("ACCOUNTANT");
        const links = hrefs(container);

        expect(links).toEqual(expect.arrayContaining([
            "/dashboard/finance/accounts",
            "/dashboard/finance/journals",
            // VoucherController is SA/TA/ACCOUNTANT, like the ledger pages —
            // not SA/TA like Vendors and Bank Accounts below.
            "/dashboard/finance/vouchers",
            "/dashboard/finance/general-ledger",
            "/dashboard/finance/tenant-ledger",
            "/dashboard/finance/trial-balance",
            "/dashboard/finance/cheques",
            "/dashboard/finance/cheques/collection",
            "/dashboard/finance/cheques/return-replace",
            "/dashboard/finance/cheques/post-dated",
            "/dashboard/settings/account-template",
            "/dashboard/settings/fiscal",
        ]));
    });

    it("offers an accountant NO link whose controller refuses the role", () => {
        const { container } = renderAs("ACCOUNTANT");
        const links = hrefs(container);

        for (const href of [
            "/dashboard/finance/vendors",
            "/dashboard/finance/bank-accounts",
            "/dashboard/staff",
            "/dashboard/settings/gateway",
        ]) {
            expect(links, `${href} 403s for ACCOUNTANT and must not be offered`).not.toContain(href);
        }
    });

    it("still offers a tenant admin the operational pages", () => {
        const { container } = renderAs("TENANT_ADMIN");
        const links = hrefs(container);

        expect(links).toEqual(expect.arrayContaining([
            "/dashboard/finance/accounts",
            "/dashboard/finance/vouchers",
            "/dashboard/finance/cheques",
            "/dashboard/finance/vendors",
            "/dashboard/finance/bank-accounts",
            "/dashboard/staff",
        ]));
    });

    /** ChequeController's STAFF group admits PROPERTY_MANAGER, unlike the old
     *  Payments link — pinned by walkthrough 13 for the OTHER finance pages,
     *  which still refuse a property manager outright. The penalty queue is
     *  the one deliberate exception: PenaltyAssessmentController#list/#propose
     *  admits PROPERTY_MANAGER too (canProposePenalties) — spotting that a
     *  renter should be fined is part of running a building, even though
     *  deciding one is not. */
    it("offers a property manager the cheque register and the penalty queue, but no other finance or staff link", () => {
        const { container } = renderAs("PROPERTY_MANAGER");
        const links = hrefs(container);

        expect(links).toEqual(expect.arrayContaining([
            "/dashboard/finance/cheques",
            "/dashboard/finance/cheques/collection",
            "/dashboard/finance/cheques/return-replace",
            "/dashboard/finance/cheques/post-dated",
            "/dashboard/finance/penalties",
        ]));
        expect(
            links.filter(h =>
                h.startsWith("/dashboard/finance/") &&
                !h.startsWith("/dashboard/finance/cheques") &&
                h !== "/dashboard/finance/penalties",
            ),
        ).toEqual([]);
        expect(links).not.toContain("/dashboard/staff");
    });

    it("renders the group heading only when the group has items", () => {
        const { container } = renderAs("SECURITY_GUARD");
        expect(hrefs(container).some(h => h.startsWith("/dashboard/finance/"))).toBe(false);
        cleanup();
        renderAs("ACCOUNTANT");
        expect(screen.getByText(en.Ledger.journals)).toBeInTheDocument();
    });
});
