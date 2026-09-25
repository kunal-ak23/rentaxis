import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, it, expect, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";
import LedgerTable from "../LedgerTable";
import type { AccountLedger } from "@/lib/api/ledger";

vi.mock("../useNameLookup", () => ({
  useNameLookup: (kind: string) => ({ name: (id: string | null) => (kind === "properties" && id ? "برج ١" : id ?? ""), options: [], loading: false }),
}));

// next-intl's locale-aware Link pulls in next/navigation, which vitest cannot
// resolve outside a Next runtime. Same stand-in the dashboard link tests use.
vi.mock("@/i18n/routing", () => ({
  Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>{children}</a>
  ),
}));

const ledgers: AccountLedger[] = [{
  accountId: "a", accountCode: "166269", accountName: "Rent Receivable - L'Olivier", accountType: "ASSET",
  openingBalance: 0, totalDebit: 64500, totalCredit: 13700, closingBalance: 50800, truncated: false,
  rows: [
    { entryId: "e1", entryNumber: "TCO-26/15", entryDate: "2026-09-11", docType: "TCO", particular: "Advance Rent - L'Olivier / Security Deposit L'Olivier", narration: "", debit: 64500, credit: 0, balance: 64500, propertyId: "p1", unitId: "u1", leaseId: null, renterId: "r1", chequeId: null },
    { entryId: "e2", entryNumber: "PDR-26/75", entryDate: "2026-09-11", docType: "PDR", particular: "PDC Receivable L'Olivier", narration: "Rent - 1st Installment", debit: 0, credit: 13700, balance: 50800, propertyId: null, unitId: "u1", leaseId: null, renterId: "r1", chequeId: null },
  ],
}];

// Auto-cleanup is off (vitest runs without globals), so each render must be
// torn down or the next test sees both trees.
afterEach(cleanup);

describe("LedgerTable", () => {
  it("renders account band, rows, sub total and report total in PACT layout", () => {
    render(<NextIntlClientProvider locale="en" messages={en}><LedgerTable ledgers={ledgers} /></NextIntlClientProvider>);
    // The band isolates the code and the name, so it is read as one cell.
    const band = screen.getByText("166269").closest("td")!;
    expect(band.textContent).toMatch(/Account Code :: 166269/);
    expect(band.textContent).toMatch(/Name :: Rent Receivable - L'Olivier/);
    expect(screen.getByText("TCO-26/15")).toHaveAttribute("href", expect.stringContaining("/dashboard/finance/journals/e1"));
    // Debit cell of row 1, the sub total's debit and the report total's debit.
    expect(screen.getAllByText("64,500.00")).toHaveLength(3);
    // Row 2's running balance, the sub total's closing balance and the report total.
    expect(screen.getAllByText("50,800.00 Dr")).toHaveLength(3);
    expect(screen.getByText("Sub Total")).toBeInTheDocument();
    expect(screen.getByText("Report Total")).toBeInTheDocument();
  });

  it("renders an opening balance row only when the opening balance is non-zero", () => {
    const { unmount } = render(
      <NextIntlClientProvider locale="en" messages={en}><LedgerTable ledgers={ledgers} /></NextIntlClientProvider>,
    );
    expect(screen.queryByText("Opening Balance")).not.toBeInTheDocument();
    unmount();

    const opened = [{ ...ledgers[0], openingBalance: -2500 }];
    render(<NextIntlClientProvider locale="en" messages={en}><LedgerTable ledgers={opened} /></NextIntlClientProvider>);
    expect(screen.getByText("Opening Balance")).toBeInTheDocument();
    expect(screen.getByText("2,500.00 Cr")).toBeInTheDocument();
  });

  it("drops the unit and tenant columns when showTenantColumns is false", () => {
    render(
      <NextIntlClientProvider locale="en" messages={en}>
        <LedgerTable ledgers={ledgers} showTenantColumns={false} />
      </NextIntlClientProvider>,
    );
    expect(screen.queryByRole("columnheader", { name: "Unit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Tenant" })).not.toBeInTheDocument();
    // Every row still spans the header's column count.
    expect(screen.getAllByRole("columnheader")).toHaveLength(7);
  });

  it("orders the tenant columns Unit · Tower · Tenant, Narration last", () => {
    render(<NextIntlClientProvider locale="en" messages={en}><LedgerTable ledgers={ledgers} /></NextIntlClientProvider>);
    const headers = screen.getAllByRole("columnheader").map(h => h.textContent);
    expect(headers).toEqual([en.Ledger.docDate, en.Ledger.docNo, en.Ledger.particular, en.Ledger.debit, en.Ledger.credit, en.Ledger.balance,
      en.Ledger.unit, en.Ledger.tower, en.Ledger.tenant, en.Ledger.narration]);
    expect(screen.getByText("برج ١")).toBeInTheDocument();
  });

  it("opens every account with its balance brought forward in a period report, even at zero", () => {
    const two: AccountLedger[] = [ledgers[0], { ...ledgers[0], accountId: "b", accountCode: "125620", openingBalance: -51000,
      rows: [{ ...ledgers[0].rows[0], entryId: "e9", debit: 5241.67, credit: 0, balance: -45758.33 }], totalDebit: 5241.67, totalCredit: 0, closingBalance: -45758.33 }];
    render(<NextIntlClientProvider locale="en" messages={en}><LedgerTable ledgers={two} broughtForward /></NextIntlClientProvider>);
    expect(screen.getByTestId("ledger-bf-a")).toHaveTextContent(`${en.Ledger.broughtForward}0.00`);
    expect(screen.getByTestId("ledger-bf-b")).toHaveTextContent("51,000.00 Cr");
    expect(screen.getByTestId("ledger-subtotal-b")).toHaveTextContent("5,241.67");
    expect(screen.getByTestId("ledger-subtotal-b")).toHaveTextContent("45,758.33 Cr");
    // Accounts in code order: 125620 before 166269.
    const bands = screen.getAllByText(/^(125620|166269)$/).map(b => b.textContent);
    expect(bands).toEqual(["125620", "166269"]);
    // Report total = Σ sub-totals: 50,800 Dr + 45,758.33 Cr = 5,041.67 Dr.
    expect(screen.getByTestId("ledger-report-total")).toHaveTextContent("5,041.67 Dr");
  });

  it("sums a sub-total from its rows, not from the server's account totals", () => {
    const off = [{ ...ledgers[0], totalDebit: 1, totalCredit: 1, closingBalance: 1 }];
    render(<NextIntlClientProvider locale="en" messages={en}><LedgerTable ledgers={off} /></NextIntlClientProvider>);
    expect(screen.getByTestId("ledger-subtotal-a")).toHaveTextContent("64,500.0013,700.0050,800.00 Dr");
  });

  it("names the tenant in the sub-band with PACT's literal 'Tenant Name : ' label", () => {
    render(
      <NextIntlClientProvider locale="en" messages={en}>
        <LedgerTable ledgers={ledgers} subBand="Prabhjot Singh" />
      </NextIntlClientProvider>,
    );
    // "Tenant : …" would read as the column header; PACT prints the full label.
    expect(screen.getByText("Tenant Name : Prabhjot Singh")).toBeInTheDocument();
  });

  it("omits the sub-band when no name is passed", () => {
    render(<NextIntlClientProvider locale="en" messages={en}><LedgerTable ledgers={ledgers} /></NextIntlClientProvider>);
    expect(screen.queryByText(/Tenant Name :/)).not.toBeInTheDocument();
  });

  it("warns when the backend truncated the rows", () => {
    const truncated = [{ ...ledgers[0], truncated: true }];
    render(<NextIntlClientProvider locale="en" messages={en}><LedgerTable ledgers={truncated} /></NextIntlClientProvider>);
    expect(screen.getByText(/Showing the first 2 rows/)).toBeInTheDocument();
  });

  it("labels the account band in Arabic, the code and name isolated", () => {
    const { container } = render(<NextIntlClientProvider locale="ar" messages={ar}><LedgerTable ledgers={ledgers} /></NextIntlClientProvider>);
    const band = screen.getByText("166269").closest("td")!;
    expect(band.textContent).toContain(`${ar.Ledger.accountCodeLabel} :: 166269`);
    expect(band.textContent).toContain(`${ar.Ledger.accountNameLabel} :: `);
    expect(screen.getByText("166269").tagName).toBe("BDI");
    // The narration is journal data (#81): stored English stays as written.
    const data = ledgers.flatMap(l => [l.accountName, ...l.rows.flatMap(r =>
      [r.particular, r.narration, r.entryNumber, r.unitId ?? "", r.renterId ?? ""])]);
    // "Dr"/"Cr" are the balance suffix fmtBalance appends in both locales.
    expect(leftoverLatinWords(visibleText(container), [...data, "Dr", "Cr"])).toEqual([]);
  });
});
