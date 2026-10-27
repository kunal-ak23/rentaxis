// The Reports hub: every report the role can run, searchable, each card linking to its page.
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const session = vi.hoisted(() => ({ role: "ACCOUNTANT" as string }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: session.role } }, status: "authenticated" }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
import Page from "../page";

afterEach(() => { cleanup(); session.role = "ACCOUNTANT"; });

const cards = () => [...document.querySelectorAll("[data-testid^='report-card-']")].map(e => e.getAttribute("data-testid"));

describe("Reports hub", () => {
    it("shows an accountant the tenant ledger with its three formats", () => {
        render(<Page />);
        expect(screen.getByRole("heading", { level: 1, name: "Reports" })).toBeInTheDocument();
        const card = screen.getByTestId("report-card-tenant-ledger");
        expect(card).toHaveTextContent("Tenant Ledger");
        expect(card).toHaveTextContent("Receivable accounts by tenant, with cheque columns, in PACT's layout.");
        for (const f of ["Excel", "PDF", "CSV"]) expect(within(card).getByText(f)).toBeInTheDocument();
        expect(within(card).getByRole("link")).toHaveAttribute("href", "/dashboard/finance/tenant-ledger");
        expect(within(card).queryByText("Your assigned properties only")).toBeNull();
        // Both sections, in catalog order.
        expect(screen.getByRole("heading", { name: "Registers & ledgers" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "Final reports" })).toBeInTheDocument();
        expect(cards()).toEqual([
            "report-card-general-ledger", "report-card-tenant-ledger", "report-card-trial-balance",
            "report-card-balance-sheet", "report-card-company-pl", "report-card-property-pl",
            "report-card-property-statement", "report-card-vat-return", "report-card-payables-aging",
        ]);
        // The manager scope note is for managers: an accountant sees the full portfolio on the
        // reports a manager can also run (balance sheet, property P&L/statement, payables aging).
        for (const id of ["report-card-balance-sheet", "report-card-property-pl", "report-card-property-statement", "report-card-payables-aging"]) {
            expect(within(screen.getByTestId(id)).queryByText("Your assigned properties only")).toBeNull();
        }
        expect(screen.queryByText("Your assigned properties only")).toBeNull();
    });

    it("shows a property manager only scoped reports, each noted", () => {
        session.role = "PROPERTY_MANAGER";
        render(<Page />);
        expect(screen.queryByTestId("report-card-tenant-ledger")).toBeNull();
        expect(cards()).toEqual(["report-card-balance-sheet", "report-card-property-pl", "report-card-property-statement", "report-card-payables-aging"]);
        for (const id of cards()) expect(screen.getByTestId(id!)).toHaveTextContent("Your assigned properties only");
        // No register is open to a manager, so the section is not shown empty.
        expect(screen.queryByRole("heading", { name: "Registers & ledgers" })).toBeNull();
    });

    it("searches titles and descriptions", () => {
        render(<Page />);
        const search = screen.getByRole("searchbox", { name: "Search reports" });
        fireEvent.change(search, { target: { value: "cheque" } });
        expect(cards()).toEqual(["report-card-tenant-ledger"]);
        fireEvent.change(search, { target: { value: "BALANCE" } });
        expect(cards()).toEqual(["report-card-general-ledger", "report-card-trial-balance", "report-card-balance-sheet"]);
        fireEvent.change(search, { target: { value: "zzz" } });
        expect(cards()).toEqual([]);
        expect(screen.getByText("No report matches “zzz”.")).toBeInTheDocument();
    });

    it("refuses a renter", () => {
        session.role = "RENTER";
        render(<Page />);
        expect(screen.getByTestId("page-access-denied")).toBeInTheDocument();
        expect(cards()).toEqual([]);
        expect(screen.queryByRole("searchbox")).toBeNull();
    });
});
