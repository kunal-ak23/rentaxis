import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * The two document pages' role gate and their session handling.
 *
 * `VoucherController` carries ONE class-level
 * `@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")`
 * covering every handler, so a PROPERTY_MANAGER gets an access-denied panel
 * here rather than a form whose first request 403s — and no voucher link at all
 * in the sidebar.
 *
 * The session test is the plan 3 trap (commit a2bbd01f) moved one level up:
 * rendering before NextAuth answers would mount the form, throw it away and
 * mount a second one, losing whatever had been typed into the first.
 */

let role: string | null = null;

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: role ? { user: { role } } : null }) }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams("") }));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>
            {children}
        </a>
    ),
}));

const mounted = vi.hoisted(() => ({ count: 0 }));
vi.mock("@/components/finance/VoucherForm", () => ({
    __esModule: true,
    default: ({ type }: { type: string }) => {
        mounted.count += 1;
        return <div data-testid="voucher-form-stub">{type}</div>;
    },
}));

import PurchaseInvoicePage from "../purchase-invoice/page";
import PaymentVoucherPage from "../payment/page";

function renderPage(Page: () => React.JSX.Element) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <Page />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    role = null;
    mounted.count = 0;
});
afterEach(cleanup);

describe("voucher document pages", () => {
    it.each([
        ["SUPER_ADMIN", true],
        ["TENANT_ADMIN", true],
        ["ACCOUNTANT", true],
        ["PROPERTY_MANAGER", false],
        ["TENANT_USER", false],
        ["RENTER", false],
    ])("shows the purchase-invoice form to %s: %s", async (r, allowed) => {
        role = r;
        renderPage(PurchaseInvoicePage);
        if (allowed) {
            expect(await screen.findByTestId("voucher-form-stub")).toHaveTextContent("PISR");
            expect(screen.queryByTestId("voucher-access-denied")).not.toBeInTheDocument();
        } else {
            expect(await screen.findByTestId("voucher-access-denied")).toHaveTextContent(
                en.Ledger.accessDeniedTitle,
            );
            expect(screen.queryByTestId("voucher-form-stub")).not.toBeInTheDocument();
        }
    });

    it("renders the payment voucher as a BPV", async () => {
        role = "ACCOUNTANT";
        renderPage(PaymentVoucherPage);
        expect(await screen.findByTestId("voucher-form-stub")).toHaveTextContent("BPV");
    });

    it("waits for the session and mounts the form exactly once", async () => {
        // NextAuth has not answered yet.
        renderPage(PurchaseInvoicePage);
        expect(screen.getByTestId("voucher-page-loading")).toBeInTheDocument();
        expect(mounted.count).toBe(0);
        expect(screen.queryByTestId("voucher-access-denied")).not.toBeInTheDocument();

        cleanup();
        role = "ACCOUNTANT";
        renderPage(PurchaseInvoicePage);
        await waitFor(() => expect(mounted.count).toBe(1));
    });
});
