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
 * granted ACCOUNTANT, so an accountant was shown links that 403'd on arrival.
 * The ledger pages (chart of accounts, journals, general ledger, tenant
 * ledger, trial balance) are the ones that actually admit the role, and they
 * stay on `canAccessFinance`; Bank Accounts/Staff moved to
 * `canAccessFinanceOps`.
 *
 * <p>Finance-ops audit S1 (P0): VendorController now admits ACCOUNTANT too
 * (an accountant enters the PISR/BPV vouchers that reference vendors), so
 * Vendors moved to its own gate, `canManageVendors`, rather than either of the
 * two above — Bank Accounts and Staff still refuse the role.
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
const path = { current: "/en/dashboard" };

vi.mock("next/navigation", () => ({
    usePathname: () => path.current,
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

import MvpSidebar, { activeNavHref } from "../MvpSidebar";

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
    path.current = "/en/dashboard";
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
            // ImportBatchController is SA/TA/ACCOUNTANT, like the ledger pages.
            "/dashboard/finance/import-batches",
            // OpeningBalanceController is SA/TA/ACCOUNTANT on its own annotation.
            "/dashboard/finance/opening-balances",
            "/dashboard/finance/reconciliation",
            "/dashboard/finance/general-ledger",
            "/dashboard/finance/tenant-ledger",
            "/dashboard/finance/trial-balance",
            "/dashboard/finance/cheques",
            "/dashboard/finance/cheques/collection",
            "/dashboard/finance/cheques/return-replace",
            "/dashboard/finance/cheques/post-dated",
            "/dashboard/settings/account-template",
            "/dashboard/settings/fiscal",
            // VendorController is SA/TA/ACCOUNTANT too (finance-ops audit S1).
            "/dashboard/finance/vendors",
        ]));
    });

    it("offers an accountant NO link whose controller refuses the role", () => {
        const { container } = renderAs("ACCOUNTANT");
        const links = hrefs(container);

        for (const href of [
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
            "/dashboard/finance/import-batches",
            "/dashboard/finance/opening-balances",
            "/dashboard/finance/reconciliation",
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
     *  deciding one is not. Finance → Reports is the other: PropertyReportController
     *  admits PROPERTY_MANAGER read-only, narrowed server-side to assigned
     *  properties with no tenant-wide Unassigned / Total (finance-ops spec §1).
     *  Payables aging likewise (spec §2): PayablesReportController admits a
     *  manager for the property-filtered view of an assigned property only. */
    it("offers a property manager the cheque register, the penalty queue, the property reports and payables aging, but no other finance or staff link", () => {
        const { container } = renderAs("PROPERTY_MANAGER");
        const links = hrefs(container);

        expect(links).toEqual(expect.arrayContaining([
            "/dashboard/finance/cheques",
            "/dashboard/finance/cheques/collection",
            "/dashboard/finance/cheques/return-replace",
            "/dashboard/finance/cheques/post-dated",
            "/dashboard/finance/penalties",
            "/dashboard/finance/reports/property-pl",
            "/dashboard/finance/reports/property-statement",
            "/dashboard/finance/payables/aging",
        ]));
        expect(
            links.filter(h =>
                h.startsWith("/dashboard/finance/") &&
                !h.startsWith("/dashboard/finance/cheques") &&
                !h.startsWith("/dashboard/finance/reports/property-") &&
                h !== "/dashboard/finance/payables/aging" &&
                h !== "/dashboard/finance/penalties",
            ),
        ).toEqual([]);
        // The opening-items grid is PayablesController, which refuses a manager.
        expect(links).not.toContain("/dashboard/finance/payables/opening-items");
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

describe("sidebar renter portal", () => {
    /** Gap #39: `/dashboard/renter-portal/penalties` worked, but nothing linked to it. */
    it("offers a renter the penalties page next to leases and payments", () => {
        const { container } = renderAs("RENTER");
        expect(hrefs(container)).toEqual(expect.arrayContaining([
            "/dashboard/renter-portal",
            "/dashboard/renter-portal/payments",
            "/dashboard/renter-portal/penalties",
        ]));
        expect(screen.getByText(en.Navigation.myPenalties)).toBeInTheDocument();
    });
});

describe("sidebar active item (M-9)", () => {
    const current = (container: HTMLElement) =>
        Array.from(container.querySelectorAll('a[aria-current="page"]')).map(a => a.getAttribute("href"));

    it("lights only My Penalties on the penalties page, not My Leases too", () => {
        path.current = "/ar/dashboard/renter-portal/penalties";
        const { container } = renderAs("RENTER");
        expect(current(container)).toEqual(["/dashboard/renter-portal/penalties"]);
    });

    it("still lights My Leases on the renter home and its own sub-pages", () => {
        path.current = "/en/dashboard/renter-portal";
        expect(current(renderAs("RENTER").container)).toEqual(["/dashboard/renter-portal"]);
    });

    it("matches whole segments and prefers the longest href", () => {
        const hrefs = ["/dashboard/renter-portal", "/dashboard/renter-portal/payments", "/dashboard/tickets"];
        expect(activeNavHref("/en/dashboard/renter-portal/payments/123", hrefs)).toBe("/dashboard/renter-portal/payments");
        expect(activeNavHref("/en/dashboard/renter-portal/leases/9", hrefs)).toBe("/dashboard/renter-portal");
        expect(activeNavHref("/dashboard/tickets-archive", hrefs)).toBeNull();
        expect(activeNavHref("/ar/dashboard/tickets", hrefs)).toBe("/dashboard/tickets");
    });
});
